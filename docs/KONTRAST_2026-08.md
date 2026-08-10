# Kontrast w sloncu — prace 2026-08 (galaz fix/kontrast-2026-08)

Cel: poprawa czytelnosci pol PRIMARY i komunikatow ACTIVE przy jasnosci ekranu 16-24%
w pelnym sloncu. Prace podzielone na atomowe commity — kazdy do cofniecia osobno.

## Powrot do stanu sprzed calosci prac
- tag punktu wyjscia: `pre-kontrast-2026-08` (= commit d421d83, HEAD main z 2026-08-04)
- pelny powrot: `git checkout main` (galaz robocza nie zostala zmergowana)
- po ewentualnym merge: `git revert <zakres>` albo `git reset --hard pre-kontrast-2026-08` (destrukcyjne!)
- najszybszy powrot NA KAROO: reinstalacja poprzedniego APK (zachowac plik!)

---

## Commit 1: usuniecie martwego kodu legacy (bez zmian zachowania)

### Co usunieto i dlaczego
`PrimaryRideSnapshot.kt` zawieral blok oznaczony w kodzie jako
`legacy_live_snapshot_mapping / TODO remove after full migration` — stary silnik kolorow
pol PRIMARY, w calosci martwy: `computeColors()` nie byla wolana z zadnego miejsca
(zweryfikowano grep po app/src/main i app/src/test). Zywe kolory licza:
- kadencja/predkosc/bieg: `FieldComputers.kt` (pl.qbot.karoo.core) przez `LabRideStateRepository`
- tetno: `HrStrainAdvisor.kt` (strefa HR + drift, histereza 30 s)
- moc: `pacingPowerColor()` w `RideDataAggregator` (cyfra) + `PacingEngine.assessPower` (tlo)
- nachylenie: `PrimaryRideSnapshot.gradeBackground()` + `contrastText()` (te zostaja — zywe)

Martwy blok wprowadzal w blad przy analizie (regualy inne niz zywe) — usuniety PRZED
zmianami kontrastowymi, zeby dalsze prace bazowaly wylacznie na prawdzie.

### Usuniete symbole (PrimaryRideSnapshot.kt, -8254 znakow)
- `computeColors()` + adnotacja `@JvmStatic`
- `powerColor()`, `cadenceColor()`, `speedColor()`, `gradeColor()`, `gearColor()` (prywatne)
- `powerColorHysteresis()`, `gearColorHysteresis()`, `resetLegacyState()`
- stale: `SPEED_ZERO_THRESHOLD`, `GEAR_HYSTERESIS_MS`, `POWER_HYSTERESIS_MS`
- zmienne stanu: `lastGearColor`, `gearColorSinceMs`, `gearInitialized`,
  `lastPowerColor`, `powerColorSinceMs`, `powerInitialized`

### Zmiany towarzyszace
- `RideDataAggregator.kt`: usuniete wywolanie `PrimaryRideSnapshot.resetLegacyState()`
  (zerowalo wylacznie stan martwych funkcji — no-op)
- skasowane 4 nietrackowane pliki `.bak.lthr` (QExt2PrimaryExtension, AthleteDataStore,
  RideDataAggregator, PrimaryRideSnapshot)

### Co ZOSTALO w PrimaryRideSnapshot.kt (zywe)
- `gradeBackground()` — 11-stopniowa skala tla nachylenia
- `contrastText()` — dobor koloru cyfr do tla
- gettery `*Display` + stale `*_STALE_MS` (fallback gdy FieldOutput value pusty)

### Powrot z samego commitu 1
`git revert <hash-commitu-1>` — przywraca martwy kod bez wplywu na cokolwiek innego.

### Weryfikacja
- grep `computeColors|resetLegacyState` po app/src/main + app/src/test: 0 trafien
- bilans nawiosow klamrowych pliku: rowny
- kompilacja: GitHub Actions (build.yml) po pushu galezi

---

## Commit 2: komunikaty ACTIVE — pasek waznosci + ciemny panel tresci

### Problem
Komunikaty (np. przypomnienia o jedzeniu/piciu z FuelReminderProducer) rysowaly
CIEMNY tekst na kolorowym tle calego panelu:
- INFO: tekst #111827 na niebieskim #3B82F6 — sila sygnalu ~23% (najgorszy element QExt2)
- WARNING: tekst #111827 na bursztynie #FBBF24 — ~57%
- CRITICAL: bialy tekst na ciemnej czerwieni #DC2626 — ~83%
Wielowyrazowy ciemny tekst na jasnym tle rozjezdza sie przy drganiach (rozproszenie
swiatla w oku zalewa cienkie ciemne kreski liter) — obserwacja potwierdzona w terenie.

### Rozwiazanie
Rozdzial rol: WAZNOSC niesie jasny pasek naglowka (widoczny katem oka),
TRESC zawsze bialym tekstem na nieprzezroczystym ciemnym panelu (czytelnosc).
- pasek naglowka (nowy element msg_title_bar), tytul ciemny #0B0F1A na pasku:
  - INFO:     #60A5FA (jasny blekit, ~60% luminancji; stary #3B82F6 mial ~48%)
  - WARNING:  #FBBF24 (bursztyn, bez zmian, ~75%)
  - CRITICAL: #FF5252 (jasna czerwien, ~52%; spojna z paleta pol)
- panel tresci: #0D1424 pelne krycie (bylo #CC111827 = 80% przezroczystosci,
  dane pola przebijaly pod komunikatem); linie tresci biale (line2 bylo #D1D5DB)

### Zmienione pliki
- app/src/main/res/layout/field_active_4x2.xml — nowa struktura nakladki:
  pionowy LinearLayout = pasek naglowka (msg_title_bar) + kontener tresci (weight=1);
  tlo nakladki #CC111827 -> #FF0D1424
- app/src/main/kotlin/com/qext2/primary/active/ActiveMessageRenderer.kt —
  severityColors() -> severityBarColor() (kolor tylko dla paska); panel i kolory
  tekstu stale; reszta logiki bind() (widocznosc, line2, logi) bez zmian

### Co NIE zmienione
- logika kolejki/priorytetow/wygasania komunikatow (ActiveMessageManager)
- BpActiveStaticDataType: dzieli layout, ale nie dotyka nakladki (visibility=gone)
- tresci i severity komunikatow u producentow

### Powrot z samego commitu 2
`git revert <hash-commitu-2>` — przywraca stary wyglad (kolorowy caly panel).

---

## Commit 3: PRIMARY — odchoinkowienie (koniec wielu alarmow z jednej przyczyny)

### Problem
Na podjezdzie jedno zdarzenie (za mocna jazda na za duzym przelozeniu) zapalalo
naraz do 6 kolorowych pol: moc (cyfra czerwona), tetno (czerwone), kadencja
(czerwona), bieg (czerwony), predkosc (bursztyn — falszywka, porownanie ze
srednia moving nie zna nachylenia), nachylenie (czerwone tlo — to akurat OK,
wskaznik). Kadencja i bieg to DUPLIKAT (ten sam model OptimalCadenceModel,
progi -15 i -10 rpm — zapalaly sie sekwencyjnie). Moc miala DWA niezalezne
silniki (cyfra: pacingPowerColor; tlo: PacingEngine.assessPower — inne wzory,
mogly sie nie zgadzac), a tlo z kryciem 25% bylo niewidoczne w sloncu.
Kadencja/bieg/moc bez histerezy — migotanie na progach.

### Zmiany

**SPEED (FieldComputers.speed):** przy |nachyleniu| > 3% porownanie ze srednia
zawieszone — kolor NEUTRAL, reason climb/descent_reference_suspended.
Na plaskim bez zmian.

**CADENCE (FieldComputers.cadence):** gdy doradca biegu ma komplet danych
(bieg+moc+LTP), kadencja pokazuje wartosc bez koloru (reason
signal_delegated_to_gear) — sygnal niesie pole GEAR, bo tam jest akcja
(zrzuc/wrzuc). Fallback bez czujnika biegow: stara logika kolorow.

**GEAR (FieldComputers.gear):** histereza koloru — kandydat musi utrzymac sie
GEAR_COLOR_HOLD_SEC=4 s zanim kolor sie zmieni (koniec migotania na progu
+-5 rpm). Czas z probek (state.tSec) — deterministyczne w testach replay;
cofniecie czasu = reset stanu (nowy przejazd).

**POWER (RideDataAggregator):** pacingPowerColor -> unifiedPowerColors —
JEDEN silnik (sufit pacingowy z W'bal/RSRV/trybu/driftu HR) daje cyfre I tlo:
- ponizej celu: biala cyfra, brak tla
- cel (85-100% sufitu): zielona cyfra #4ADE80, brak tla
- swiezo nad sufitem (<3 s): czerwona cyfra #FF5252, brak tla (szybki sygnal)
- ALARM (nad sufitem >= POWER_ALARM_HOLD_MS=3 s): PELNE czerwone tlo #FF5252
  + cyfra z contrastText (wzorzec pola nachylenia — jasna plama widoczna w sloncu)
- wyjscie z alarmu tez wymaga 3 s ponizej sufitu (zero migotania)
- stan alarmu zerowany na starcie streamingu (resetUnifiedPowerState)
Usuniete tlo z assessPower (krycie 0x40 => ~2% sily sygnalu przy 16% jasnosci).

### Bilans na podjezdzie (scenariusz uzytkownika)
Przed: moc + tetno + kadencja + bieg + predkosc + nachylenie kolorowe naraz.
Po: nachylenie (wskaznik, zawsze) + bieg (jedna akcja: zrzuc) + ew. moc
(gdy realnie nad sufitem) + tetno (gdy realnie >=85% maxHR lub drift).
Predkosc i kadencja milkna. Z szesciu rownoczesnych sygnalow zostaja
maksymalnie cztery, kazdy o INNEJ przyczynie.

### Nie ruszone
- pole nachylenia (dziala dobrze — decyzja uzytkownika)
- HrStrainAdvisor (ma wlasna histereze 30 s, sygnal merytorycznie poprawny)
- PacingEngine: komunikaty pacingu na podjazd bez zmian; fun assessPower
  zostala OSIEROCONA (zero wywolan) — kandydat do usuniecia w przyszlym
  sprzataniu, nie ruszana w tym commicie dla czystosci powrotu

### Powrot z samego commitu 3
`git revert <hash-commitu-3>` — przywraca stare reguly wszystkich czterech pol.
