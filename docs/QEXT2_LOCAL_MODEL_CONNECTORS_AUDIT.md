# QEXT2 Local Model Connectors Audit

| field | model source | required inputs | runtime connector path | snapshot fields | readiness flags | source/reason | tests covering it | static/no-motion behavior | connector status | gaps | next action |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ETA | `EtaCalculator` in aggregator | hasRoute, distanceToDest, speedKmh, elapsed | SDK `OnNavigationState` + `DISTANCE` + `SPEED` + `ELAPSED_TIME` -> aggregator -> `etaMs` | `etaTimestamp`, `hasRoute`, `etaModelReady` | `etaModelReady` = hasRoute && etaMs > 0 | `local_eta_prediction` / `eta_no_route` / `eta_model_not_ready` | `StatsAdvancedFieldPolicyTest.eta*` (4 tests inc. `etaDoesNotDependOnDeadlineOrSunset`) | WAIT/NO_MODEL (brak trasy + brak ruchu) | **COMPLETE** | DEADLINE/SUN/sunset NOT ETA input; computed separately | — |
| WPRIME/W' | `StatsCalculator.wBalancePercent` | CP capacity (`wPrimeKj`), LTP (`ltpWatts`), power stream | `QEXT_READINESS_URL` (REST endpoint, nie QBot) -> `AthleteDataStore` -> aggregator `setWPrimeParams` -> `wBalancePercent` | `wBalancePercent`, `wPrimeModelReady` | `wPrimeModelReady` = wBalance >= 0 (CP i W' ustawione z serwera); StatsCalculator defaults 0/0 -> model blokowany | `local_wprime_balance` / `wprime_no_cp_or_wprime` | `StatsAdvancedFieldPolicyTest.wprime*` (5 tests) | WAIT/NO_MODEL (brak CP/W' z serwera) | **COMPLETE** | endpoint moze byc niedostepny (ngrok-free.dev) | — |
| RSRV | `StatsCalculator.rideReservePercent` | TSS, IF, decoupling%, elapsedSec, todayFactor, sleep refresh | aggregator obliczenia lokalne + `StatsCalculator` -> `rideReservePercent` | `rideReservePercent`, `rsrvModelReady` | `rsrvModelReady` = hasActivity && reserve >= 0 | `local_reserve_estimate` / `rsrv_model_not_ready` | `StatsAdvancedFieldPolicyTest.rsrv*` (2 tests) | WAIT/NO_MODEL (brak aktywnosci) | **COMPLETE** | — | — |
| CARB | `StatsCalculator.carbsGPerH` | NP (intensity), elapsed, VI, temperature, bodyWeight | aggregator `statsCalc.carbsGPerH(adjIf, elapsedSec, vi, temperatureRef, bodyWeightKg)` | `carbsGPerH`, `carbModelReady` | `carbModelReady` = NP > 0 && elapsed > 60s | `local_carb_estimate` / `carb_model_not_ready` | `StatsAdvancedFieldPolicyTest.carb*` (3 tests) | WAIT/NO_MODEL (NP=0, <60s) | **COMPLETE** | — | — |
| CARB_BALANCE | aggregator `carbIntakeTotal - carbNeededTotal` | CARB model, carb intake (manual button/store) | `AthleteDataStore` (carb intake button) + `StatsCalculator.carbsGPerH` (rate) -> `carbBalanceG` | `carbBalanceG`, `carbIntakeTotalG`, `carbNeededTotalG`, `carbModelReady` | shared `carbModelReady` | `local_carb_balance` / `carb_model_not_ready` | `StatsAdvancedFieldPolicyTest.carbBalance*` (2 tests) | WAIT/NO_MODEL (NP=0, <60s; intake=0 OK) | **COMPLETE** | — | — |
| FLUID | `StatsCalculator.fluidLPerH` | IF (intensity), temperature, humidity, bodyWeight | aggregator `statsCalc.fluidLPerH(adjIf, temperatureRef)` | `fluidLPerH`, `fluidModelReady`, `hasActivity` | `fluidModelReady` = elapsed > 60s && hasActivity | `local_fluid_estimate` / `fluid_model_not_ready` | `StatsAdvancedFieldPolicyTest.fluid*` (3 tests) | WAIT/NO_MODEL (brak aktywnosci) | **COMPLETE** | — | — |

## Summary

| connector status | count | fields |
|---|---|---|
| COMPLETE | 6 | ETA, RSRV, CARB, CARB_BALANCE, FLUID, WPRIME/W' |
| PARTIAL | 0 | — |
| MISSING | 0 | — |
| DEFAULT_ONLY | 0 | — |

## Notes

- Wszystkie local_model maja: readiness flag, source=`local_model`, reason przy braku, testy JVM, guardy NaN/Infinity/ujemne.
- WPRIME/W' jest teraz COMPLETE po dodaniu UI pola w slocie wczesniej zajmowanym przez permanentnie martwe `deadline` (SUN).
- Temperature source dla CARB/FLUID pochodzi z SDK `TEMPERATURE` streamu; humidity z AthleteData (serwer zewnetrzny).
- Carb intake pochodzi z przycisku w UI STATS 3x3 (reczny input), przechowywany w `AthleteDataStore`.
- Zadne z pol local_model nie jest DEFAULT_ONLY - wszystkie maja pelna sciezke connectora.
