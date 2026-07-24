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

## 2026-07-24 - KOMUNIKAT W' (zastapil "ZA MOCNO") + zamrozenie W'

### Komunikat ACTIVE
Stary `ZA MOCNO / W' X%` USUNIETY. Jeden komunikat `UWAGA!` z czterema obliczami
(`ClimbPacingProducer.wPrimeMessage`). Os podzialu to **pale / odbudowuje**, NIE "powyzej/ponizej 0%"
(W'bal jest z definicji przyciete do [0, W'max] - nie ma ujemnego W').

Wyzwalacz: `W'bal < 55%` (bez warunku "za mocno" - odpala tez przy spokojnej jezdzie).
Moc: srednia **3 s** (`snapshot.power3s`). CP: **`cpEffW`** - to samo CP, ktorym liczy sie W'bal
(UWAGA: NIE `getEffectiveLtpWatts()`, to LTP ~240 W, inna liczba niz CP ~253 W).

| stan | warunek | line2 | severity | rytm |
|---|---|---|---|---|
| TRZYMASZ | \|moc-CP\| <= 10 W | `TRZYMASZ!` | WARNING | co 60 s, 10 s na ekranie |
| odbudowa | moc < CP-10 | `odbudowa MM:SS` | WARNING | co 60 s, 10 s na ekranie |
| bomba daleko | moc > CP+10, t > 2 min | `bomba MM:SS` | WARNING | co 30 s, 10 s |
| bomba blisko | 30 s - 2 min | `bomba MM:SS` | WARNING | co 10 s, 10 s |
| bomba krytyczna | < 30 s | `bomba MM:SS` | CRITICAL | co 1 s (tyka), beep |
| PRZEPAL | 0% W' + moc > CP+10 | `PRZEPAŁ` | CRITICAL | co 10 s |

Wzory:
- bomba: `t = W'bal[J] / (moc - CP)` (spadek jest liniowy)
- odbudowa: `tau = 546*e^(-0.01*(CP-moc)) + 316`, `t = tau * ln(luka_teraz / luka_cel)`,
  cel = **90%**. 100% jest ASYMPTOTA (kazda sekunda domyka ulamek luki) - czas do pelna
  formalnie nieskonczony, dlatego cel 90%.

Martwa strefa +-10 W istnieje, bo obie formuly maja `(moc-CP)` w mianowniku - przy jezdzie
dokladnie na progu dawaly absurdy typu "bomba 247:33".

PRZEPAL = model mowi "pusto", a nogi jada dalej => dowod, ze **W' jest ustawione za nisko**
(material do kalibracji breakthrough).

### Zamrozenie W' (RideDataAggregator)
`setEffectiveWPrime(baseCp * cf, baseWp)` - dzienny wspolczynnik `cf` (gotowosc x upal x dryf)
dziala juz **wylacznie na CP**. Wczesniej skalowal tez sufit W' (w dol).
Powody: (1) W' to pojemnosc strukturalna, stabilniejsza niz CP; (2) ruchomy sufit uniemozliwia
kalibracje breakthrough - mierzysz wzgledem celu, ktory sam sie przesuwa; (3) obnizone CP juz
przyspiesza drenaz, obnizanie baku karze za gorszy dzien dwa razy.
Odwracalne: przywroc `baseWp * cf.coerceAtMost(1.0f)`.

Testy: `ClimbPacingProducerTest` (8).
