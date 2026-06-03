# QExt2 — Audyt funkcjonalny (2026-06-03)
Cel: weryfikacja działania w terenie (wielodniowy bikepacking, Tuscany Trail).

## POTWIERDZONE OK ✅
- Brak `!!` force-unwrapów w całym main/ (zero crashy z NPE)
- Wszystkie debug flagi `false` w produkcji (`QExt2DebugConfig`)
- Power null: `if (v != null)` guard w handlerze streamu SDK
- W-balance: `if (powerFresh)` guard + `coerceIn(0, wPrime)` — zakres bezpieczny
- TSS dla RSRV: od bazowego FTP=245, nie adjFTP — kalibracja spójna z danymi
- `decouplingPercent()` dla RSRV: rolling window 3600s (60 min), first/second-half split OK
- P0 reset dnia po dacie: zainstalowany, sprawdzony (RideDataAggregator ~286-295) ✅
- fetchConsumerId: czyszczony przed re-fetchem (brak stackowania konsumentów)
- WeatherClient: `withTimeoutOrNull(15s)` + catch — graceful degradation offline
- Fetch: `waitForConnection=true` czeka na sieć zamiast natychmiastowego faila
- adjFtp (todayFactor×FTP): tylko display IF + carbs/fluid — NIE wpływa na TSS/RSRV
- `todayFactor`: poprawnie zaciągany z `mcp_server.py /ride-readiness`, dziś = 0.993 ✅
- rsrvModelReady: gate `isMoving && reserve∈[0,100]` — route NIE wymagane ✅
- effectiveTss = dailyTssBase + sessionTss: budżet 390 dzienny, wieloetapowość OK ✅

## 🔴 KRYTYCZNE — naprawione/do naprawy PRZED WYJAZDEM
### [NAPRAWIONE ✅] RSRV wzór — liniowy TSS, budżet 390 (2026-06-03)
### [NAPRAWIONE ✅] Reset dnia po dacie kalendarzowej (P0) (2026-06-03)

### [DO NAPRAWY] Bug 1: TSS akumuluje przy dropout czujnika mocy
Plik: StatsCalculator.kt
`val activeSample = movingAdvanced && hasPower`
Gdy power meter się rozłączy, SDK przestaje wysyłać → powerRef trzyma ostatnią
wartość (np. 150W), hasPower=true → NP/TSS/energy rosną przy powerFresh=false.
10-min dropout → RSRV spada ~3-5% bez powodu. Realna sytuacja w terenie.
FIX: `val activeSample = movingAdvanced && hasPower && powerFresh`
(wBalance ma swój `if (powerFresh)` guard osobno — OK, nie ruszać)

### [DO NAPRAWY] Bug 2: HrDecouplingBuffer za mały dla długich etapów
Plik: HrDecouplingBuffer.kt
MAX_SAMPLES=2400 @ 1Hz = 40 min buffera. HrStrainAdvisor wymaga próbek z 8-18 min
jazdy (elapsedSec 480-1080) jako baseline. Wypadają z bufora po ~58 min → przez
resztę etapu (4-7h) kolor HR pokazuje tylko strefę, bez komponentu dryfu HR.
NIE dotyczy RSRV (RSRV używa StatsCalculator.decouplingPercent() — OK).
FIX: MAX_SAMPLES = 7200 (120 min, ~115KB pamięci — akceptowalne)
Docelowo: persystować baseline window raz (snapshot przy 18 min) — post-trip.

## 🟡 WAŻNE — post-trip

### Bug 3: Static state w PrimaryRideSnapshot.companion object
`lastGearColor`, `powerColorSinceMs`, `gearInitialized` etc. to mutable `var`
w companion object — globalny stan persystuje między jazdami. Przez ~30s na starcie
nowej jazdy kolor biegu/mocy może pokazywać stan z poprzedniej.
FIX: dodać `resetLegacyState()` do companion i wywołać z aggregator.startStreaming.

### Bug 4: Brak ostrzeżenia o świeżości danych API
`fetchTimestamp` zapisany w AthleteDataStore ale nigdzie nie ma strażnika max-age.
Przy braku łączności przez 2+ dni stare todayFactor/FTP używane bez ostrzeżenia.
FIX: w SetupActivity pokazać "dane z: X h temu"; ewentualnie badge readiness.

### Bug 5: Brak retry fetcha /ride-readiness
Jeden strzał przy starcie, `waitForConnection=true` pomaga, ale jeśli fetch
zakończy się błędem (serwer niedostępny, timeout) — brak ponowienia.
FIX: retry raz z ~60s opóźnieniem jeśli pierwsze nie zwróciło statusCode==200.

## 🔵 NISKIE — znany backlog (nie blokują terenu)
- wPrimeKj default 3.75 kJ vs realne 21 — tylko świeża instalacja, persystowane po fetch
- maxHr default 180 vs realne 184 — po fetchu = 184, minor
- RSRV: budżet 390 zaszyty → docelowo ζ×TL z QBota (backlog z RSRV_MODEL doc)
- RSRV: dekupling jako mnożnik, nie płaskie odjęcie (backlog z RSRV_MODEL doc)
- Martwy RsrvDisplayPolicy.decide (route-gated, testowany ale nie używany live)
- Martwy intensityFactor/ifSafe w rideReservePercent (compiler warning)
- TODO remove w PrimaryRideSnapshot (legacy mapping migration)
- Duplikat /ride-readiness w qbot_api.py bez todayFactor (QBot-side)
- bodyWeightKg 102.0 z serwera — do weryfikacji (wpływa na carbs/fluid heat factor)
- HrDecouplingBuffer: docelowo persystować baseline (snapshot przy 18 min)

## Pliki referencyjne
StatsCalculator.kt — `update()` activeSample (Bug 1), `decouplingPercent()` (RSRV ok),
  `tssValue()` (FTP=245), `rideReservePercent()` (naprawione)
HrDecouplingBuffer.kt — MAX_SAMPLES (Bug 2)
HrStrainAdvisor.kt — baseline window 480-1080s, activation ramp 15-30 min
RideDataAggregator.kt — effectiveTss ~829, P0 reset ~286-295, rsrvModelReady ~885
QExt2PrimaryExtension.kt — fetchAthleteData, fetchConsumerId lifecycle
AthleteDataStore.kt — persystencja, defaults, KEY_RESERVE_BASE_DATE (P0)
PrimaryRideSnapshot.kt — companion static state (Bug 3), TODO migration
