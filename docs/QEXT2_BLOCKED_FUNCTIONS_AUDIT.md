# QEXT2 Blocked Functions Audit (SDK-first)

| funkcja | status | SDK source found | SDK field/path | snapshot path | readiness/reason | local model allowed now |
|---|---|---|---|---|---|---|
| TSS | WORKING (SDK-only) | YES | `DataType.Type.TRAINING_STRESS_SCORE` / `DataType.Field.TRAINING_STRESS_SCORE` | `RideDataAggregator -> StatsRideSnapshot.tssValue` | `sdk_training_stress_score`; brak danych: `sdk_field_not_available` | NO |
| ETA | WORKING (local model) | NO | brak bezposredniego pola ETA w subskrypcjach | `snapshot.etaTimestamp` + `etaModelReady` | `eta_no_route`, `eta_model_not_ready`, `local_eta_prediction`; source=`local_model` | YES |
| KCAL | WORKING (SDK-only) | YES | `DataType.Type.CALORIES` / `DataType.Field.CALORIES` | `RideDataAggregator -> StatsRideSnapshot.caloriesKcal` | `sdk_calories`; brak danych: `sdk_field_not_available` | NO |
| CARB | WORKING (local model) | NO | brak pola SDK CARB | `snapshot.carbsGPerH` + `carbModelReady` | `carb_model_not_ready`, `carb_invalid`, `local_carb_estimate`; source=`local_model` | YES |
| CARB_BALANCE | WORKING (local model) | NO | brak pola SDK | `snapshot.carbBalanceG` + `carbModelReady` | `carb_model_not_ready`, `local_carb_balance`; source=`local_model` | YES |
| FLUID | WORKING (local model) | NO | brak pola SDK | `snapshot.fluidLPerH` + `fluidModelReady` | `fluid_model_not_ready`, `fluid_invalid`, `local_fluid_estimate`; source=`local_model` | YES |
| WPRIME / W' | WORKING (local model, policy only) | NO | brak pola SDK | `snapshot.wBalancePercent` + `wPrimeModelReady` | `wprime_no_cp_or_wprime`, `wprime_invalid`, `local_wprime_balance`; source=`local_model` | YES |
| RSRV / reserve | WORKING (local model) | NO | brak pola SDK | `snapshot.rideReservePercent` + `rsrvModelReady` | `rsrv_model_not_ready`, `rsrv_invalid`, `local_reserve_estimate`; source=`local_model` | YES |
| GEAR | WORKING/NO_DATA runtime | YES | `DataType.Type.SHIFTING_GEARS` (+ pola front/rear) | `RideDataAggregator -> core FieldOutput GEAR` | `missing_gear` przy braku streamu | NO |
| route progress | PARTIAL | YES | `OnNavigationState.NavigatingRoute.routeDistance` | nav state refs + logs | `NAV/GRACE/MISSING` | NO |
| distance left | WORKING | YES | `DataType.Type.DISTANCE_TO_DESTINATION` | `distanceToDestinationMetersRef` | `hasRoute` gating | NO |
| time to destination | BLOCKED | NO | brak bezposredniego SDK TTD | lokalny ETA calc | `sdk_field_not_available` | PENDING |
| battery pct | WORKING | YES | Android runtime `ACTION_BATTERY_CHANGED` | `batteryPctRef -> StatsRideSnapshot.batteryPctCurrent` | `battery_source_not_connected` | NO |
| BAT_DRAIN | WORKING | YES (runtime source) | `batteryPctCurrent` + runtime trend | `StatsRideSnapshot.batteryDrain*` | `battery_drain_not_ready` / OK | NO |
| BAT_LEFT | WORKING | YES (runtime source) | `batteryPctCurrent` + drain estimate | `StatsRideSnapshot.batteryTimeLeftSec` | `battery_estimate_not_ready` / OK | NO |
| ascent done | WORKING | YES | `DataType.Type.ELEVATION_GAIN` | `StatsRideSnapshot.ascentDoneM` | `route_not_loaded`, `route_climb_model_not_ready`, `flat_route_or_zero_ascent` | NO |
| ascent left | WORKING | YES | `DataType.Type.ELEVATION_REMAINING` / `ASCENT_REMAINING` | `StatsRideSnapshot.ascentLeftM` | `route_not_loaded`, `route_climb_model_not_ready`, `flat_route_or_zero_ascent` | NO |

## Uwagi

- Pola bez SDK source, ale z gotowym lokalnym modelem (ETA/WPRIME/RSRV) sa odblokowane przez `local_model` policy z readiness.
- Pola bez SDK source i bez lokalnego modelu: pozostaja `WAIT`.
- MVP LIVE path bez zmian.

## 2026-07-20 - ENDURANCE tryb-komunikat WYLACZONY
PACING ENDURANCE ON (ClimbPacingProducer, Priority 2) nie trafia juz na ekran.
Powod: nic nie wnosil; roznica zachowania climbing vs endurance i tak nie jest zaimplementowana (tylko napis).
Realizacja: `if (!isClimbing) return null` po aktualizacji stanu trybu; CLIMBING ON dziala normalnie.
Odwracalne: usun ten warunek i przywroc `val title = if (isClimbing) ... else "PACING ENDURANCE ON"`.
