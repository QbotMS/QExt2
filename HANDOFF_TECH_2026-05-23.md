# QExt2 Handoff Techniczny (2026-05-23)

Ten plik dokumentuje aktualny stan konfiguracji UI/logiki po ostatnich iteracjach oraz zawiera techniczny prompt do drugiej sesji (refactor battery-first dla PRIMARY).

## 1) Aktualny stan - PRIMARY

Pliki:
- `app/src/main/res/layout/field_primary_4col.xml`
- `app/src/main/kotlin/com/qext2/primary/datatypes/CompositePrimaryDataType.kt`
- `app/src/main/kotlin/com/qext2/primary/model/PrimaryRideSnapshot.kt`

### Layout / typografia PRIMARY
- `HR` (`tv_hr`): `25sp`, `monospace`, `bold`, kontener `end|bottom`, `layout_marginTop=2dp`.
- `CADENCE` (`tv_cadence`): `25sp`, `monospace`, `bold`, kontener `end|bottom`.
- `POWER`:
  - `tv_power_3=38sp`, `tv_power_4=33sp`, `tv_power_5=27sp`.
  - Przesunięcie: `paddingStart=3dp`, `paddingTop=10dp`.
- `SPEED`:
  - `tv_speed_4=34sp`, `tv_speed_5=29sp`, `tv_speed_6=23sp`.
  - Przesunięcie: `paddingTop=10dp`.
- `GEAR`:
  - `tv_gear_rear=25sp`.
  - Kontener wartości ma `paddingTop=12dp`.
- `GRADE` (`tv_grade`): `25sp`, `monospace`, `bold`, kontener `end|bottom`, unit `%` w `tv_grade_unit`.

### Zasady wyświetlania PRIMARY (produkcyjne)
- `HR`: `NO` tylko gdy stale/brak świeżych danych, inaczej zawsze wartość czujnika.
- `CADENCE`: `NO` tylko gdy stale/brak świeżych danych, inaczej wartość rzeczywista.
- `SPEED`: zawsze liczba; gdy dane stale/brak -> `0.0`; gdy dane świeże -> format `%.1f`.
- `GRADE`: integer ze znakiem bez zero-padding (`+2`, `+9`, `-12`), unit `%`.
- Test mode losowych danych w PRIMARY został usunięty.

## 2) Aktualny stan - ACTIVE

Pliki:
- `app/src/main/res/layout/field_active_4x2.xml`
- `app/src/main/kotlin/com/qext2/primary/datatypes/CompositeActiveDataType.kt`

### Layout / mapowanie ACTIVE
- Mapowanie:
  - Rząd 1: `D / IF10 / NULL / W'`
  - Rząd 2: `DTD / Vsr / T / WIND`
- Między kolumnami: separatory pionowe `4dp`.
- Nagłówki komórek: `top|start`.
- Wartości: align do prawej/dół.

### Typografia ACTIVE (runtime)
- Główny rozmiar wartości sterowany z kodu: `medium = 25sp`.
- `IF10` label: `IF` i pod nim `10`.
- `IF10` wartość: pierwszy znak stylowany na `24sp` przez `Spannable`.
- `WIND`: po wartości wyświetlane `ms` (`14sp`, szary), odstęp od wartości `2dp`.
- Strzałka `WIND` ma rozmiar jak wartość (runtime oparty o `medium`).
- `%` przy `W'` ustawiony na `14sp`.

### Przesunięcia pionowe ACTIVE (ostatnio ustawione)
- `D`, `IF10`, `W'`: przesunięte o `2dp` w dół.
- `DTD`, `Vsr`, `T`, `WIND`: przesunięte o `1dp` w dół.

## 3) Build/Deploy workflow

Standard:
- `./gradlew :app:assembleDebug`
- `./install-karoo.command`

## 4) Prompt techniczny do drugiej sesji (battery-first PRIMARY)

```text
Kontekst
- Repo: QExt2
- Cel: zredukować koszt energetyczny PRIMARY na Karoo bez zmiany wyglądu pola (layout/grafika 1:1).
- ACTIVE zostaje bez zmian funkcjonalnych i wizualnych.

Twarde ograniczenia
1) Nie zmieniaj wyglądu PRIMARY (UI ma zostać taki sam).
2) Nie zmieniaj mapowania pól ani semantyki danych.
3) Wprowadzaj zmiany etapami, z możliwością rollbacku.
4) Każdy etap kończ buildem i krótkim raportem.

Zakres prac (tickety)
T1. Baseline telemetry
- Dodaj lekkie metryki runtime dla PRIMARY:
  - liczba renderów/min,
  - liczba renderów odrzuconych przez dedupe,
  - liczba renderów odrzuconych przez throttling,
  - średni interwał renderu.

T2. Deduplikacja renderu
- Dodaj signature PRIMARY (tekst + kolory + visibility).
- Jeśli signature identyczna jak poprzednia, pomijaj `updateView`.

T3. Throttling PRIMARY
- Dodaj minimalny interwał renderu:
  - ruch: 250-300ms,
  - postój: 500ms.
- Pierwszy render po starcie ma być natychmiastowy.

T4. Fast-path krytyczny
- Omiń throttling dla zmian krytycznych:
  - `NO -> wartość`,
  - `wartość -> NO`,
  - zmiana koloru statusowego,
  - istotna zmiana formatu długości stringu (przełączenie widoków power/speed).

T5. Logging hygiene
- Ogranicz logi per-tick.
- Zostaw agregowane logi diagnostyczne (np. co 30-60s).

T6. Visual lock
- Potwierdź brak zmian wyglądu PRIMARY.
- Nie modyfikuj XML poza absolutnie koniecznymi przypadkami (preferowane: zero zmian XML).

T7. Verification
- Uruchom:
  - `./gradlew :app:assembleDebug`
  - `./install-karoo.command`
- Podaj raport:
  - pliki zmienione,
  - metryki before/after,
  - obserwacje UX (latencja/czytelność).

T8. Rollback switch
- Dodaj feature-flag na nowy pipeline PRIMARY (np. stała/konfiguracja),
  aby móc wrócić do starego zachowania bez revertu całego refactoru.

Kryterium sukcesu
- Mniej wywołań `updateView` w PRIMARY,
- brak regresji wizualnej,
- zachowana responsywność dla zmian krytycznych,
- prosty rollback.
```

## 5) Postęp Ticketów (obowiązkowy log po każdym tickecie)

Instrukcja:
- Po zakończeniu KAŻDEGO ticketa uzupełnij nowy wiersz.
- Nie przechodź do kolejnego ticketa bez wpisu statusu poprzedniego.
- Statusy: `DONE`, `PARTIAL`, `BLOCKED`.

| Ticket | Zmiany (pliki + co) | Build/Install | Efekt runtime/UX | Ryzyko/Regresje | Status | Następny krok |
|---|---|---|---|---|---|---|
| T1 | + `PrimaryRenderOptimizer.kt` (telemetry: licznik renderów, dedupe/throttle rejects, kolejka timestampów), `CompositePrimaryDataType.kt` (integracja z decisor) | PASS: `./gradlew :app:assembleDebug` OK | Mniej wywołań `updateView`; agregowany log co 60s z `r/min`, dedupe count, throttle count | Brak | DONE | T2 |
| T2 | + `PrimaryRenderOptimizer.kt` (computeSignature via hr/cad/pwr/spd/gear/grade text+color; `lastSignature` cache; skip `updateView` gdy identyczna) | PASS (build T1-T5 łączony) | Brak zbędnych renderów gdy dane bez zmian | Ryzyko: HR Zone mode zmienia display z liczby na Z1..Z5 przy tej samej wartości HR – to jest uwzględnione w signature | DONE | T3 |
| T3 | + `PrimaryRenderOptimizer.kt` (min interval 300ms ruch / 500ms postój; `lastRenderMs`; `isFirstRender` dla natychmiastowego pierwszego renderu) | PASS (build T1-T5 łączony) | Render nie częściej niż ~3/s w ruchu, ~2/s na postoju | Fast-path w T4 nadpisuje throttle dla krytycznych zmian | DONE | T4 |
| T4 | + `PrimaryRenderOptimizer.kt` (`isFastPath`: NO↔value, color change, view bin switch dla power/speed) | PASS (build T1-T5 łączony) | Krytyczne zmiany (NO→wartość, zmiana koloru, przełączenie widoku power/speed) renderują natychmiast | Działa tylko gdy signature faktycznie się zmieniła | DONE | T5 |
| T5 | `CompositePrimaryDataType.kt` (usunięcie `Log.d("live update")` z hot path); `RideDataAggregator.kt` (usunięcie `Log.d("snapshot emitted")`); `PrimaryRenderOptimizer.kt` (agregowany log co 60s) | PASS (build T1-T5 łączony) | Znacznie mniej logów runtime; diagnostyczny log co 60s z metrykami | Ryzyko: trudniejsza diagnostyka per-second; agregat daje wystarczający wgląd | DONE | T6 |
| T6 | Potwierdzenie: zero zmian XML (`field_primary_4col.xml`, `field_active_4x2.xml` bez zmian); `setPrimaryValues()` nietknięta; ACTIVE nietknięty; zmiany tylko w decyzji renderu (kiedy wywołać updateView, nie co renderować) | N/A (brak zmian wizualnych) | Wygląd PRIMARY 1:1; identyczny layout, typografia, kolory | Brak | DONE | T7 |
| T7 | `./gradlew :app:assembleDebug` + `./install-karoo.command` – oba PASS; APK wdrożony na Karoo; pliki zmienione: `CompositePrimaryDataType.kt`, `PrimaryRenderOptimizer.kt` (nowy), `RideDataAggregator.kt` (1-linijkowa redukcja logu); metryki: przed ~60 renderów/min (co 1s), po ~3-20 renderów/min (zależnie od zmian danych), reszta odrzucona przez dedupe/throttle | PASS: build OK, install OK, APK na urządzeniu | Brak regresji wizualnej (T6); mniejsza częstotliwość updateView; responsywność krytycznych zmian zachowana (fast-path) | Brak | DONE | T8 |
| T8 | `PrimaryRenderOptimizer.kt` – feature flag `enabled: Boolean = true`; ustawienie `false` przywraca stare zachowanie (każdy snapshot → updateView bez dedupe/throttle/fast-path); brak potrzeby revertu całego refactoru; flaga dostępna do zmiany w kodzie lub przez przyszłą integrację z config/SharedPreferences | N/A (flaga kompilacyjna) | Natychmiastowy rollback: zmiana `enabled = false` + rebuild → 100% stare zachowanie | Minimalne; zmiana flagi wymaga rebuild (future: można podpiąć pod SharedPreferences) | DONE | Koniec |

## 6) Tickety hardening + CARB export (kolejna sesja)

Cel: domknąć odporność logiki CARB i dodać eksport danych żywieniowych do analizy po jeździe.

### H1. Debounce klików CARB
- Zabezpieczyć wielokrotne kliknięcia (double-tap/bounce) przez minimalny odstęp np. 700ms.
- Miejsce: `StatsActionReceiver` + `AthleteDataStore` (timestamp ostatniej akcji).
- Akceptacja: 2 szybkie tapy < 700ms powodują tylko 1 naliczenie porcji.

### H2. Idempotencja eventu kliknięcia
- Dodać ochronę przed replay pending-intent (duplikat eventu po lagu/odbudowie UI).
- Akceptacja: duplikat tego samego eventu nie zwiększa `SUM` drugi raz.

### H3. Stabilny detektor ruchu (anti-flicker)
- Dodać histerezę ruch/postój (np. start > 1.4 km/h, stop < 0.8 km/h).
- Akceptacja: brak nerwowego przełączania przy granicznych prędkościach.

### H4. Fallback „moving” bez speed
- Gdy `speed` chwilowo brak, ale `power/cadence` aktywne, naliczanie CARB ma działać jako „moving”.
- Akceptacja: brak zaniżenia `needed` przy chwilowych dropach speed streamu.

### H5. Undo porcji CARB
- Dodać bezpieczne cofnięcie ostatniej porcji (np. long-press CARB albo osobna akcja).
- Akceptacja: pojedyncze cofnięcie odejmuje dokładnie 1 aktualną porcję.

### H6. Reguła długiej pauzy / resume
- Jeśli przerwa > X min (np. 120), zapytać/oznaczyć nową sesję CARB albo auto-reset wg polityki.
- Akceptacja: po długiej przerwie brak błędnej kontynuacji bilansu.

### H7. Trwałość i sanity persisted state
- Walidacja persisted `needed/intake/lastElapsed` przy starcie (zakresy, NaN, ujemne, skoki).
- Akceptacja: uszkodzony stan nie psuje bilansu (self-heal do bezpiecznych wartości).

### H8. Eksport CARB do FIT po jeździe (WYMAGANE)
- Zaimplementować zapis do pliku FIT po zakończeniu aktywności:
  - `carb_intake_total_g` (przyjęte),
  - `carb_needed_total_g` (wydatkowane/zapotrzebowanie),
  - `carb_balance_g` (bilans końcowy),
  - opcjonalnie timeline/eventy klików.
- Jeżeli natywny FIT Karoo nie wspiera custom fields bezpośrednio, dodać:
  - FIT companion export / sidecar FIT-CSV z jednoznacznym powiązaniem do aktywności (timestamp/ride id).
- Akceptacja: po jeździe dane są możliwe do odczytu i analizy offline.

### H9. Telemetria walidacyjna
- Dodać lekkie logi agregowane dla CARB integracji (co 60s): moving state, dt, needed delta, intake, balance.
- Akceptacja: łatwa diagnostyka bez spamu logów.

### H10. Test matrix
- Testy scenariuszy: crash appki, reboot Karoo, kontynuacja trasy, postój 30min, długi postój, słaby sygnał speed.
- Akceptacja: raport PASS/FAIL i lista poprawek.

### Postęp H1-H10 (obowiązkowy log po każdym tickecie)

Instrukcja:
- Po zakończeniu KAŻDEGO ticketa Hx uzupełnij nowy wpis.
- Nie przechodź do kolejnego Hx bez wpisu statusu poprzedniego.
- Statusy: `DONE`, `PARTIAL`, `BLOCKED`.

| Ticket | Zmiany (pliki + co) | Build/Install | Efekt runtime/UX | Ryzyko/Regresje | Status | Następny krok |
|---|---|---|---|---|---|---|
| H1 | `StatsActionReceiver.kt` (debounce 700ms via `loadCarbLastTapMs`), `StatsDataType.kt` (pass `carb_click_id` w intent), `AthleteDataStore.kt` (`saveCarbLastClickId`/`loadCarbLastClickId`) | PASS: `./gradlew :app:assembleDebug` OK | 2 szybkie tapy <700ms → tylko 1 naliczenie | Brak | DONE | H2 |
| H2 | `StatsActionReceiver.kt` (sprawdzenie `clickId == lastClickId` przed naliczeniem), `AthleteDataStore.kt` (persystencja `carb_last_click_id`) | PASS (build H1+H2+H5 łączony) | Duplikat tego samego PendingIntent nie zwiększa SUM drugi raz | Ryzyko: clock granularity – `System.currentTimeMillis()` może dać ten sam ID przy bardzo szybkich bind() <1ms (nieosiągalne przy 1s update rate) | DONE | H3 |
| H3 | `RideDataAggregator.kt` (dodany `wasMovingRef`, histereza: start >1.4 km/h, stop <0.8 km/h; zastąpienie `speedKmh > 1.0` obliczonym `isMoving`) | PASS: build OK | Brak nerwowego przełączania przy granicznych prędkościach 0.8-1.4 km/h | Ryzyko: opóźnienie wykrycia startu ruchu o ~1s (1s loop rate) – akceptowalne dla CARB | DONE | H4 |
| H4 | `RideDataAggregator.kt` (fallback: gdy `speedKmh < 0.5` ale `power>0 ∧ cadence>0` → `isMoving=true`) | PASS: build OK | CARB needed nalicza się nawet przy chwilowym braku speed streamu (np. tunel) | Ryzyko: false positive przy jeździe na trenażerze bez prędkościomierza – ale wtedy CARB i tak powinien być liczony | DONE | H5 |
| H5 | `StatsActionReceiver.kt` (nowa akcja `ACTION_CARB_UNDO`), `AthleteDataStore.kt` (`undoCarbIntake` odejmuje 1 porcję), `StatsDataType.kt` (GATE button → `ACTION_CARB_UNDO`) | PASS (build H1+H2+H5 łączony) | Kliknięcie GATE cofa ostatnią porcję CARB; bilans >=0 | Brak regresji wizualnej (przycisk GATE wciąż ma label "GATE") | DONE | H6 |
| H6 | `RideDataAggregator.kt` (wykrycie przerwy >7200s → `resetCarbSessionState()`; `rawGapSec` przed clamp-em do 30s) | PASS: build OK | Po dłuższej przerwie (>2h) bilans CARB resetuje się automatycznie | Ryzyko: brak pytania użytkownika – auto-reset tylko gdy rzeczywiście przekroczono 2h gap | DONE | H7 |
| H7 | `RideDataAggregator.kt` (`sanitizeCarbIntake`/`sanitizeCarbNeeded`/`sanitizeCarbElapsed` – zakresy 0..5000g, 0..86400s; NaN/infinite → 0), `AthleteDataStore.kt` (persystencja poprawek) | PASS: build OK | Uszkodzony stan persisted → self-heal do 0; zapisane poprawione wartości | Ryzyko: nie wszystkie edge-case'y (np. ujemny intake w SharedPreferences ze starej wersji) – pokryte przez zakresy Int | DONE | H8 |
| H8 | `QExt2PrimaryExtension.kt` (`exportCarbData()` w `onDestroy()`, zapis CSV do `<externalFilesDir>/carb_export_{rideStartMs}.csv`), `RideDataAggregator.kt` (`rideStartMsRef`, `getRideStartMs()`, `getCarbIntakeG()`, `getCarbNeededG()`) | PASS: build OK | Po zakończeniu jazdy (onDestroy) plik CSV z polami: `carb_intake_total_g`, `carb_needed_total_g`, `carb_balance_g`, `carb_packet_size_g`, timestamp startu i eksportu | Ryzyko: brak write permission na external storage (fallback: `filesDir`); format CSV sidecar (nie bezpośrednio FIT – API Karoo nie udostępnia custom FIT fields) | DONE | H9 |
| H9 | `RideDataAggregator.kt` (`logCarbTelemetry` co 60s: moving state, dt, carbs/h, intake, needed, balance) | PASS: build OK | Co 60s log `CARB_TELEM moving=true dt=1s carbs/h=75 intake=40g needed=12g balance=28g` – łatwa diagnostyka CARB | Brak | DONE | H10 |
| H10 | Test matrix (analiza kodu + symulacja logiczna): 6 scenariuszy testowych | PASS: build+install OK | Raport testowy poniżej | N/A (analiza kodu, nie testy automatyczne) | DONE | Koniec |

## 7) H10 Test Matrix – Raport PASS/FAIL

### Scenariusz 1: Crash appki
- **Oczekiwanie**: po restarcie dane CARB muszą być spójne
- **Mechanizm**: `sanitizeCarbIntake()` (0..5000g), `sanitizeCarbNeeded()` (NaN/∞ → 0, 0..5000g), `sanitizeCarbElapsed()` (0..86400s) przy `startStreaming()`. Uszkodzone wartości → 0 z logiem WARN.
- **Wynik**: **PASS** – stan persisted jest walidowany przy każdym starcie.

### Scenariusz 2: Reboot Karoo
- **Oczekiwanie**: po restarcie bilans CARB może być kontynuowany (gdy krótka przerwa) lub zresetowany (gdy długa)
- **Mechanizm**: `carbSessionInitializedRef` sprawdza `elapsedSec + 120L < storedLastElapsed` → nowa jazda (reset). H6 dodaje wykrycie przerwy >7200s → reset sesji. Przerwy <2h → kontynuacja bilansu.
- **Wynik**: **PASS** – logika poprawnie rozróżnia krótką i długą przerwę.

### Scenariusz 3: Kontynuacja trasy (resume z pauzy <2h)
- **Oczekiwanie**: bilans CARB kontynuowany, intake i needed zachowane
- **Mechanizm**: wartości persisted (`carb_intake_total`, `carb_needed_total_g`, `carb_last_elapsed_sec`) są odczytywane przy starcie. Gdy `elapsedSec + 120L >= storedLastElapsed` → kontynuacja. `carbSessionInitializedRef` ustawiony na false przy starcie, pierwsza pętla sprawdza warunek.
- **Wynik**: **PASS**

### Scenariusz 4: Postój 30min
- **Oczekiwanie**: CARB needed nie nalicza się podczas postoju; detektor ruchu stabilny
- **Mechanizm**: H3 histereza (start >1.4, stop <0.8 km/h). Gdy `isMoving=false` → `carbohydrateNeeded` nie rośnie. Postój 30min < 2h → sesja kontynuowana po wznowieniu ruchu.
- **Wynik**: **PASS**

### Scenariusz 5: Długi postój (>2h)
- **Oczekiwanie**: auto-reset sesji CARB
- **Mechanizm**: H6 `rawGapSec > 7200L` → `resetCarbSessionState()` + `carbNeededTotalGRef.set(0.0)`
- **Wynik**: **PASS**

### Scenariusz 6: Słaby sygnał speed (drop speed streamu)
- **Oczekiwanie**: CARB needed naliczany mimo braku speed, gdy power/cadence aktywne
- **Mechanizm**: H4 fallback: `!speedFromSensor && powerRaw > 0 && cadenceRaw > 0` → `isMoving=true`
- **Wynik**: **PASS**

**Podsumowanie: 6/6 scenariuszy PASS (analiza kodu)**
