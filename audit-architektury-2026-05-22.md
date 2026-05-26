# Audyt architektury i powiazan - QExt2

Data: 2026-05-22
Zakres: modul `app` (extension Karoo), przeplyw danych, jakosc implementacji, ryzyka utrzymaniowe.

## 1) Obecna architektura (stan)

- Wejscie systemowe: `QExt2PrimaryExtension` (`app/src/main/kotlin/com/qext2/primary/QExt2PrimaryExtension.kt`) zarzadza cyklem zycia uslugi, lacznoscia z Karoo i singletonem agregatora.
- Warstwa agregacji: `RideDataAggregator` (`app/src/main/kotlin/com/qext2/primary/engine/RideDataAggregator.kt`) subskrybuje strumienie telemetrii Karoo i buduje dwa snapshoty (`PrimaryRideSnapshot`, `StatsRideSnapshot`).
- Warstwa prezentacji:
  - `CompositePrimaryDataType` renderuje pole 4-kolumnowe na bazie `snapshot`.
  - `CompositeActiveDataType` renderuje pole 4x2 przez bezposrednie subskrypcje wielu strumieni.
  - `StatsDataType` renderuje pole 3x3 na bazie `statsSnapshot`.
- Konfiguracja i persystencja: `AthleteDataStore` (SharedPreferences), UI ustawien w `SetupActivity`.

Wniosek: projekt ma sensowny podzial odpowiedzialnosci, ale zawiera niespojnosci pomiedzy warstwa agregacji i widokami, ktore powoduja dryf logiki i ukryte bledy funkcjonalne.

## 2) Powiazania i przeplyw danych

1. `QExt2PrimaryExtension.onCreate()` inicjalizuje `KarooSystemService`, po polaczeniu tworzy `RideDataAggregator`, uruchamia polling baterii i fetch danych sportowca.
2. `RideDataAggregator.startStreaming()` subskrybuje telemetryczne `DataType` i co 1s emituje:
   - `snapshot` dla PRIMARY,
   - `statsSnapshot` dla STATS (ETA, reserve, bateria, itd.).
3. `CompositePrimaryDataType` i `StatsDataType` czytaja dane z agregatora (StateFlow).
4. `CompositeActiveDataType` omija agregator i utrzymuje osobne subskrypcje + osobne kalkulatory (IF10/W').

Ryzyko architektoniczne: ACTIVE ma oddzielna logike obliczen i oddzielne zrodla danych, co zwieksza szanse rozjazdu metryk miedzy polami.

## 3) Wykryte bledy i problemy

### Krytyczne / wysokie

1. Przycisk odswiezania nie wykonuje fetchu z API.
   - Plik: `app/src/main/kotlin/com/qext2/primary/setup/SetupActivity.kt:40`
   - Objaw: klik zapisuje tylko `last_refresh_ts`, ale nie wywoluje `QExt2PrimaryExtension.refetchAthleteData()`.
   - Skutek: uzytkownik dostaje komunikat "Gotowe", mimo ze dane nie sa odswiezone.

2. `StatsDataType` zawsze wyswietla dane demo zamiast danych live.
   - Plik: `app/src/main/kotlin/com/qext2/primary/datatypes/StatsDataType.kt:76`
   - Objaw: `bind()` wywoluje `demoSnapshot(snap)` i dalej renderuje wartosci demo.
   - Skutek: caly ekran STATS jest logicznie odklejony od realnej jazdy.

3. ACTIVE nie renderuje czesci subskrybowanych metryk (temp, wiatr, W').
   - Plik: `app/src/main/kotlin/com/qext2/primary/datatypes/CompositeActiveDataType.kt:325`
   - Objaw: `emitUpdate()` aktualizuje tylko D/DTD/IF10/Vsr/NULL; helpery `formatTemp()`, `formatWind()`, `formatWindDir()` i `WPrimeCalculator` sa praktycznie martwe dla UI.
   - Skutek: duza czesc logiki kosztuje CPU i komplikuje kod bez efektu dla uzytkownika.

4. Rozjazd deadline pomiedzy logika a prezentacja STATS.
   - Plik: `app/src/main/kotlin/com/qext2/primary/engine/RideDataAggregator.kt:527`
   - Objaw: `deadlineStatus` liczone jest z `resolveDeadlineMs()`, ale `deadlineTimestamp` przekazuje `civilDusk/sunset` zamiast finalnego deadline.
   - Skutek: mozliwy konflikt "status" vs pokazywana godzina.

### Srednie

5. Dublowanie strumieni mocy (POWER + 3S POWER) w agregatorze i ACTIVE.
   - Pliki: `RideDataAggregator.kt:213`, `RideDataAggregator.kt:230`, `CompositeActiveDataType.kt:217`, `CompositeActiveDataType.kt:230`
   - Skutek: czestsze odswiezanie, ryzyko podwojnego samplowania i niestabilnosci metryk (IF10/W').

6. Nadmiar logow produkcyjnych na goracych sciezkach.
   - Pliki: `RideDataAggregator.kt` (wiele `Log.d` na kazdy event + co sekunde), `Composite*DataType`.
   - Skutek: narzut I/O, szum diagnostyczny, trudniejsze sledzenie realnych bledow.

7. Hardcoded endpoint ngrok w kodzie.
   - Plik: `QExt2PrimaryExtension.kt:94`
   - Skutek: niestabilnosc operacyjna, brak latwej zmiany srodowiska, ryzyko awarii po wygasnieciu tunelu.

8. Konfiguracja release podpisywana debug key.
   - Plik: `app/build.gradle.kts:21`
   - Skutek: ryzyko dystrybucyjne i brak gotowosci do stabilnego release process.

### Niskie / utrzymaniowe

9. `PrimaryRideSnapshot` trzyma mutable stan w `companion object` (`gearColorHysteresis`).
   - Plik: `app/src/main/kotlin/com/qext2/primary/model/PrimaryRideSnapshot.kt:36`
   - Skutek: ukryty stan globalny, trudniejsza testowalnosc i potencjalny efekt uboczny miedzy sesjami/widokami.

10. W ACTIVE sa pola nieuzywane (`hasPowerData`) i kalkulatory z ograniczonym wykorzystaniem.
   - Plik: `CompositeActiveDataType.kt:119`
   - Skutek: dlug techniczny i gorsza czytelnosc.

## 4) Obszary do poprawy (proponowany kierunek)

1. Ujednolicic warstwe danych:
   - Przeniesc logike ACTIVE do agregatora (albo odwrotnie) tak, aby wszystkie pola opieraly sie o jeden "source of truth".

2. Rozdzielic warstwy:
   - Agregator: tylko subskrypcja i agregacja.
   - Kalkulatory domenowe: IF10/W'/ETA/HRD jako niezalezne komponenty.
   - Widoki: tylko mapowanie snapshot -> `RemoteViews`.

3. Wprowadzic "feature flags" / config runtime:
   - URL API, poziom logowania, fallbacki danych (FTP/W').

4. Zwiekszyc testowalnosc:
   - Testy jednostkowe dla mapowania danych do widokow.
   - Testy kontraktowe snapshotow (np. deadline, stale values, fallbacki).

5. Ograniczyc koszt renderowania:
   - Debounce/coalesce aktualizacji widokow ACTIVE.
   - Aktualizowac tylko pola, ktore realnie sie zmienily.

## 5) Plan zmian (priorytety)

### Sprint 1 (hotfixy)
- Naprawic przycisk odswiezania: realny fetch + status sukces/blad.
- Usunac `demoSnapshot` ze STATS i przelaczyc na dane live.
- Naprawic `deadlineTimestamp` (zapis finalnego deadline po `resolveDeadlineMs`).

### Sprint 2 (stabilizacja)
- Ograniczyc logowanie do trybu debug.
- Uporzadkowac subskrypcje mocy (jedno zrodlo z jasna semantyka).
- Uzyc endpointu API z konfiguracji (BuildConfig / prefs).

### Sprint 3 (refaktor architektury)
- Wydzielic warstwe kalkulatorow i jeden model snapshot dla wszystkich pol.
- Przepisac ACTIVE na zasilanie z agregatora (bez bezposrednich subskrypcji w DataType).
- Dodac pakiet testow jednostkowych dla krytycznych metryk.

## 6) Walidacja

- `./gradlew testDebugUnitTest` przechodzi lokalnie (BUILD SUCCESSFUL).
- Obecny zestaw testow jest bardzo waski (praktycznie tylko `HrStrainAdvisorTest`), co nie pokrywa glownych ryzyk wskazanych wyzej.

## 7) Podsumowanie

Najwiekszy problem nie lezy w "braku funkcji", ale w niespelnionej spojnosci miedzy tym, co system liczy, a tym, co UI naprawde pokazuje. Priorytetem powinny byc: (1) usuniecie danych demo z produkcyjnego widoku STATS, (2) naprawa realnego odswiezania API, (3) konsolidacja logiki ACTIVE i agregatora.
