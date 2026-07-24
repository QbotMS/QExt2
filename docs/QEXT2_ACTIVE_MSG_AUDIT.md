# QEXT2 Active Message Audit

## Architecture

ACTIVE MSG to osobny data type `qext2-active` (`CompositeActiveDataType`) renderowany w `field_active_4x2.xml`.
Nie zalezy od `StatsRideSnapshot` — uzywa wlasnych stanow sensorowych, route/climb i kolejki komunikatow.

## Components

| component | file/path | status | input source | readiness | reason/source support | tests | gaps | next action |
|---|---|---|---|---|---|---|---|---|
| Core model | `ActiveMessage.kt` | WORKING | id, title, line1, line2, severity, priority, resumePolicy, createdAtMs, expiresAtMs | N/A (data class) | N/A | — | — | — |
| Message manager | `ActiveMessageManager.kt` | WORKING | `show()`, `getCurrent()`, `hideExpired()` | thread-safe priority queue | yes (expiry, resume, suspend) | 13 tests (`ActiveMessageManagerTest.kt`) | — | — |
| Renderer | `ActiveMessageRenderer.kt` | WORKING | `RemoteViews` + ActiveMessage -> R.id.message_overlay | wizualna tylko | severity colors (INFO/WARNING/CRITICAL) | tested via CompositeActiveDataType smoke | renderuje tylko gdy message != null; brak -> GONE | — |
| Climb producer | `ClimbAnnouncementProducer.kt` | WORKING | `ClimbState` (hasRoute, distanceToClimbM, climbElevationM, avgGradePercent) | route + climb SDK | pre-climb / active / finish phases | 4 tests (`ClimbAnnouncementProducerTest.kt`) | — | — |
| Sensor producer | `SensorMessageProducer.kt` | WORKING | `SensorState` (speed, cadence, hr, power, freshness, route, elapsed) | sensor SDK | power/HR/sensors/route missing warnings | 7 tests (`SensorMessageProducerTest.kt`) | — | — |
| Climb resolver | `ActiveClimbResolver.kt` | WORKING | `RideDataAggregator` climb list + current distance | SDK navigation climbs | real clims only (fake mode has separate path) | 3 tests (`ActiveClimbResolverTest.kt`) | — | — |
| Beep cooldown | `BeepCooldownTracker.kt` | WORKING | per-severity cooldown timestamps | runtime state | success=10s, error=2s cooldown | 3 tests (`BeepCooldownTrackerTest.kt`) | — | — |
| No-SDK climb log gate | `NoSdkClimbLogGate.kt` | WORKING | per-route-key anti-spam | route key from aggregator | once per route key | 2 tests (`NoSdkClimbLogGateTest.kt`) | — | — |
| Data type integration | `CompositeActiveDataType.kt` | WORKING | sensor streams + route data + timer tick | Karoo SDK streams + aggregator | beep dispatch + render debounce | demo/scenario debug modes | STATS_ADV logi nie obejmuja ACTIVE | — |

## Summary

| status | count | components |
|---|---|---|
| WORKING | 9 | wszystkie |
| PARTIAL | 0 | — |
| UNKNOWN | 0 | — |
| DISABLED | 0 | — |

## Key facts

- ACTIVE MSG to osobny `DataTypeImpl` (`qext2-active`), niezalezny od `qext2-primary` i `qext2-stats`.
- Renderuje sie w `field_active_4x2.xml` przez `ActiveMessageRenderer.bind()`.
- Nie dotyka `RideState`, `StatsRideSnapshot` ani `FieldComputers` — uzywa wlasnych stanow.
- Przy braku komunikatu (static/no-motion): `message_overlay` = GONE, brak crasha, brak INVALID.
- Ma pelna obsluge kolejki: priorytety, wygasanie, wznawianie, interrupt.
- Beep wysylany przez `KarooSystemService.dispatch(PlayBeepPattern(...))` z cooldown trackerem.
- Nie ma wlasnych pol `WAIT/NO_DATA/NO_MODEL` — to system komunikatow dynamicznych, nie statyczne pole danych.
- Debug modes (`DEBUG_ACTIVE_DEMO`, `DEBUG_ACTIVE_SCENARIO`) sa `false` — prod-ready.

## Test coverage

| test class | tests | status |
|---|---|---|
| `ActiveMessageManagerTest` | 13 | PASS |
| `ClimbAnnouncementProducerTest` | 4 | PASS |
| `SensorMessageProducerTest` | 7 | PASS |
| `ActiveClimbResolverTest` | 3 | PASS |
| `BeepCooldownTrackerTest` | 3 | PASS |
| `NoSdkClimbLogGateTest` | 2 | PASS |
| **Total** | **32** | all PASS |

## Weather data usage (audit 2026-05-24)

- ACTIVE MSG does NOT use weather data to produce messages (obsolete — now has `WeatherMessageProducer`).
- No hardcoded weather defaults produce fake ACTIVE messages.
- Weather message production: **WORKING** (via `WeatherMessageProducer`, requires `OPENWEATHER_API_KEY` in `local.properties` + location + weather freshness).

## 2026-07-20 - ENDURANCE tryb-komunikat WYLACZONY
PACING ENDURANCE ON (ClimbPacingProducer, Priority 2) nie trafia juz na ekran.
Powod: nic nie wnosil; roznica zachowania climbing vs endurance i tak nie jest zaimplementowana (tylko napis).
Realizacja: `if (!isClimbing) return null` po aktualizacji stanu trybu; CLIMBING ON dziala normalnie.
Odwracalne: usun ten warunek i przywroc `val title = if (isClimbing) ... else "PACING ENDURANCE ON"`.
