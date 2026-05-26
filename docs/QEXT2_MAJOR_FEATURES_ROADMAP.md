# QExt2 Major Features Roadmap

Based on: architecture contracts, feature map, open items audit, current status, and all sub-system audits.

## 1. Crash / Incident Detection

### Product goal
Wykrywanie gwałtownego zatrzymania, długiego bezruchu po jeździe, ciszy sensorów — z możliwym alertem bezpieczeństwa. Nie zastępuje dedykowanych systemów SOS/IceDot.

### Current foundation
- `RideState` ma `speedKmh`, `elapsedMovingSec`, `elapsedTotalSec`
- `SensorMessageProducer` wykrywa dropouty sensorów
- `ActiveMessageManager` obsługuje priorytetyzację komunikatów
- `BeepCooldownTracker` zarządza beepami

### Required data sources
- `DataType.Type.SPEED` (już subskrybowane)
- `DataType.Type.CADENCE`, `HEART_RATE`, `POWER` (już)
- Akcelerometr SDK — jeśli dostępny przez Karoo SDK (do zweryfikowania)

### Dependencies
- **SDK**: SPEED, SENSOR streams (dostępne)
- **QBot/backend**: NONE (lokalny detection tylko)
- **UI**: ACTIVE MSG overlay dla alertu

### Architecture
```
[speed/motion/sensor streams] -> IncidentDetector
  -> POSSIBLE_INCIDENT state
  -> countdown (30s)
  -> CRITICAL ActiveMessage with cancel option
  -> FALSE_ALARM if motion resumes
```

### Status
- **P0_POSSIBLE_INCIDENT**: speed=0 po >3 min sustained motion, cadence/power=0
- **FALSE_ALARM**: resume detected within countdown window
- **NO_DATA**: brak sensorów

### Forbidden
- Nie udawać emergency/SOS bez potwierdzonego API
- Nie integrować z numerami alarmowymi bez jawnej zgody

### Tests
- `incident_sudden_stop_after_sustained_motion`
- `incident_long_no_motion_after_ride`
- `incident_false_alarm_cancel_on_resume`
- `incident_sensor_dropout_not_crash`

### Priority: P1
### Blockers: none (lokalny moduł)
### What not to do: nie wysyłać powiadomień zewnętrznych bez jawnej konfiguracji

---

## 2. AXS Button Intake Actions

### Product goal
Przypisanie przycisków AXS (Di2/AXS shifters) do rejestrowania intake'u: carbs (+20/30/40g), fluid (+250/500ml), manual undo.

### Current foundation
- `StatsActionReceiver.kt` obsługuje CARB tap w STATS UI
- `AthleteDataStore` ma `addCarbIntake(packet)`, `undoCarbIntake(packet)`
- `RideDataAggregator` ma `carbIntakeTotal`, `carbNeededTotal`, `carbBalance`
- `CompositeActiveDataType` subskrybuje `SHIFTING_GEARS`

### Required data sources
- AXS button events via Karoo SDK — sprawdzić, czy SDK 1.1.9 eksponuje AXS button press
- Jeśli nie: **SDK_SOURCE_REQUIRED** — funkcja zależna od przyszłego SDK

### Dependencies
- **SDK**: AXS button events (NIE POTWIERDZONE w SDK 1.1.9)
- **QBot/backend**: NONE
- **UI**: opcjonalne potwierdzenie na ACTIVE overlay

### Architecture
```
[AXS button event] -> IntakeActionMapper
  -> button_map_config (shift_left_small=+20g, shift_left_long=+40g, ...)
  -> AthleteDataStore.addCarbIntake / addFluidIntake
  -> ACTIVE overlay confirmation (3s)
```

### Priority: P1
### Blockers: SDK source for AXS button events required; verify with Karoo SDK
### What not to do: nie dublować intake bez debounce/idempotency

---

## 3. Ride Ask / QBot Advice Field

### Product goal
Oddzielne pole danych z przyciskiem ODPYTAJ. Wysyła snapshot jazdy do QBot, odbiera krótką odpowiedź, pokazuje ją w dedykowanym polu.

### Current foundation
- `GateOpenClient` — istniejący klient HTTP do backendu
- `BuildConfig.QEXT_READINESS_URL` — potwierdzony endpoint `qbot.cytr.us`
- `OnHttpResponse` — mechanizm HTTP w SDK
- `StatsRideSnapshot` — kompletne dane jazdy
- `ActiveMessageRenderer` — wzorzec renderowania overlay

### Required data sources
- Wszystkie istniejące (speed, power, HR, cadence, grade, distance, elapsed, route, climb, weather, W', RSRV, CARB, FLUID, battery)

### Dependencies
- **QBot/backend**: QBot endpoint `/advice` lub `/ask` — do potwierdzenia z Q
- **SDK**: `OnHttpResponse` (już dostępne przez `KarooSystemService`)
- **UI**: nowy DataType `qext2-advice` lub rozszerzenie istniejącego

### Architecture
```
[User tap ODPYTAJ] -> RideAskClient
  -> POST /ask with snapshot JSON
  -> timeout 15s
  -> response: { advice, rating, alert, priority }
  -> QBotAdviceField shows advice text
  -> errors logged, old advice cleared after 5min
```

### Design rules
- Nie zalewać ekranu — krótka odpowiedź
- Osobne pole, nie w primary
- Timeout z fallbackiem (po cichu WAIT)
- Offline = WAIT/NO_DATA reason=qbot_unreachable
- Nie wysyłać niczego automatycznie — tylko na żądanie

### Tests
- `advice_request_sends_snapshot`
- `advice_timeout_handled`
- `advice_response_rendered`
- `advice_offline_graceful`
- `advice_not_in_primary_field`

### Priority: P0 (szybki duży efekt)
### Blockers: QBot endpoint definition + contract
### What not to do: nie wysyłać automatycznie, nie pokazywać w primary, nie blokować UI

---

## 4. Interactive Fuel & Hydration Coach

### Product goal
CARB/FLUID z pełną historią intake'u, ręczne i AXS akcje, bilans od startu, alerty intake'u, eksport do Q po jeździe.

### Current foundation
- `StatsCalculator.carbsGPerH`, `fluidLPerH`
- `AthleteDataStore` carb intake store + button
- `StatsActionReceiver` obsługuje CARB tap
- `carbModelReady`, `fluidModelReady`
- `StatsDataType` pokazuje CARB i FLUID w 3x3

### Required data sources
- Istniejące CARB/FLUID modele (już dostępne)
- AXS button events (dla P1 — zob. funkcja 2)

### Dependencies
- **SDK**: AXS buttons (P1), obecne streams (P0 dostępne)
- **QBot/backend**: eksport po jeździe (P2)

### Architecture
```
[CARB model + intake history] -> FuelCoach
  -> current_rate, intake_total, balance
  -> alert: FUEL_NOW jeśli balance < threshold
  -> alert: DRINK_NOW jeśli fluid deficit
  -> eksport CSV/FIT po jeździe
```

### Priority: P1 (UI/alerts), P2 (Q export)
### Blockers: AXS dla P1; reszta lokalnie dostępna

---

## 5. Ride Strategy Engine

### Product goal
Centralny silnik decyzji zwracający stan jazdy: HOLD / EASE / PUSH / RECOVER / FUEL_NOW / DRINK_NOW / SAVE_FOR_CLIMB / SHIFT_EASIER / RISK_OVERLOAD z powodem i pewnością.

### Current foundation
- Wszystkie models lokalne (W', RSRV, ETA, CARB, FLUID) — już działają
- `FieldComputers` — speed, power, HR, cadence, grade
- `ClimbAnnouncementProducer` — wykrywa podjazdy
- `ActiveMessageManager` — priorytetyzacja komunikatów

### Required data sources
- Route/climb z `OnNavigationState` + existing models

### Dependencies
- **QBot/backend**: NONE (lokalny)
- **SDK**: istniejące + route data (dostępne)

### Architecture
```
[route + W' + RSRV + HR + power + weather + CARB + FLUID] -> StrategyEngine
  -> confidence_score (0-100)
  -> primary_strategy (enum)
  -> secondary_strategy (enum)
  -> reason_string
  -> expose to ACTIVE MSG, Ride Ask, future pacing
```

### Priority: P1
### Blockers: none (lokalny, wszystkie dane dostępne)

---

## 6. Adaptive Active Messages 2.0

### Product goal
System komunikatów z priorytetem, cooldownem, suppression rules i pełną typologią: route, climb, sensor, weather, fuel, hydration, gear, crash, QBot advice.

### Current foundation
- `ActiveMessageManager` — priorytety, wygasanie, wznawianie, suspend
- 4 producentów: `ClimbAnnouncementProducer`, `SensorMessageProducer`, `WeatherMessageProducer`, plus route-missing
- `BeepCooldownTracker` — zarządzanie beepami
- 32 testów (wszystkie PASS)

### Required data sources
- Wszystkie istniejące producenty + nowe (fuel, hydration, gear, crash, QBot)

### Dependencies
- **SDK**: istniejące
- **QBot/backend**: tylko QBot advice producer

### Architecture
```
[All producers] -> ActiveMessageManager (existing) ->
  -> priority queue
  -> per-type cooldown/config
  -> beep tylko CRITICAL
  -> render na ACTIVE overlay (istniejący)
```

### Priority: P1 (rozbudowa istniejącego, nie nowy system)
### Blockers: none

---

## 7. Gear Intelligence

### Product goal
Analiza przełożenia: sweet spot (60-70 rpm + optymalna moc) vs too hard (niska kadencja + wysoka moc) vs too easy (wysoka kadencja + niska moc).

### Current foundation
- `FieldComputers.gear()` — zwraca `gear_present_no_advice_model` (tylko status, brak rady)
- `PrimaryRideSnapshot` — lokalna logika kolorów gear
- `SHIFTING_GEARS` stream subskrybowany w agregatorze

### Required data sources
- `SHIFTING_GEARS` (front × rear) — istnieje
- Power, cadence, grade, speed — istnieją

### Dependencies
- **SDK**: SHIFTING_GEARS (dostępne, ale często NO_DATA bez realnego Di2/AXS)
- **QBot/backend**: NONE

### Architecture
```
[gear + power + cadence + grade] -> GearAdvisor
  -> sweet_spot: cadence 60-77, power near FTP×0.75-0.87
  -> too_hard: cadence<55, power>FTP×0.75, grade≥2%
  -> too_easy: cadence>90, power<FTP×0.50
  -> expose via ACTIVE MSG "SHIFT_EASIER"/"SHIFT_HARDER"
```

### Priority: P2 (wymaga realnego source SHIFTING)
### Blockers: brak źródła SHIFTING_GEARS w testach — działa tylko z Di2/AXS

---

## 8. Route-Aware Pacing

### Product goal
Pacing zależny od trasy i nadchodzących podjazdów: oszczędzanie W'/RSRV przed trudnym segmentem, proponowane target power.

### Current foundation
- `OnNavigationState` z `climbs` list (działa)
- `ClimbAnnouncementProducer` wykrywa podjazdy
- `ActiveClimbResolver` rozwiązuje aktywny podjazd
- W', RSRV, ETA models

### Required data sources
- Route data + climbs (SDK `OnNavigationState`, dostępne)
- Wszystkie modele (W', RSRV, power)

### Dependencies
- **QBot/backend**: NONE (lokalny)
- **SDK**: route + climbs

### Architecture
```
[route + upcoming climbs + W' + RSRV] -> PacingAdvisor
  -> next_climb_distance, next_climb_grade, next_climb_elevation
  -> estimated_Wprime_cost dla podjazdu
  -> current_Wprime_reserve vs cost
  -> advice: HOLD (oszczędzaj) / PUSH (masz zapas) / SAVE_FOR_CLIMB
```

### Priority: P2 (wymaga route + realnej jazdy do testów)
### Blockers: route required for meaningful test

---

## 9. Weather-Aware Ride Risk

### Product goal
Wiatr, deszcz, temperatura jako czynniki ryzyka jazdy. Wpływ na fluid/carb/strategy/active msg.

### Current foundation
- `WeatherClient` — pobiera dane z OpenWeatherMap (CONFIGURED)
- `WeatherMessageProducer` — alerty rain/wind/heat/cold
- `StatsRideSnapshot` — weather fields (weatherFresh, weatherSourceReady)
- CARB/FLUID models uwzględniają temperature/humidity

### Required data sources
- OpenWeatherMap API (configured, not verified on Karoo)

### Dependencies
- **QBot/backend**: NONE
- **External**: OpenWeatherMap API (CONFIGURED, NEEDS_NETWORK_TEST)

### Architecture
```
[weatherFresh=true] -> WeatherRiskModel
  -> wind_impact (strong headwind → increased carb/fluid)
  -> rain_impact (rain → increased caution message)
  -> heat_impact (>35°C → DRINK_NOW, EASE)
  -> cold_impact (<0°C → reduced fluid rate, caution)
  -> tylko gdy weatherFresh=true
```

### Priority: P2 (depends on network verification)
### Blockers: weather verification on Karoo with network

---

## 10. Post-Ride Learning Loop

### Product goal
Po jeździe eksport danych QExt2 do QBot. Porównanie przewidywań z rzeczywistością (ETA vs real, W' drain vs predicted, carb needed vs consumed). Aktualizacja profilu readiness.

### Current foundation
- `StatsRideSnapshot` — kompletny snapshot
- `AthleteDataStore` — fetchTimestamp, lastRefresh
- `QExt2PrimaryExtension.refetchAthleteData()` — wzorzec fetchu
- CARB export CSV — istniejący mechanizm eksportu

### Required data sources
- Wszystkie modele (snapshot post-ride)

### Dependencies
- **QBot/backend**: endpoint `/ride-report` do potwierdzenia
- **SDK**: NONE (post-ride, offline-safe)

### Architecture
```
[post-ride] -> snapshot + predictions + actuals -> RideReport
  -> POST /ride-report to Q
  -> response: learned adjustments (todayFactor update, carb_rate_adjust, fluid_rate_adjust)
  -> AthleteDataStore.save(updated_profile)
  -> optional: QBot re-fetch athlete data
```

### Priority: P2
### Blockers: backend endpoint definition

---

## Recommended Implementation Order

1. **Ride Ask / QBot Advice Field** (P0) — szybki duży efekt, nowy DataType, wykorzystuje istniejący snapshot
2. **AXS Button Intake Actions** (P1) — domyka CARB/FLUID, ale wymaga SDK verification
3. **Fuel & Hydration Coach** (P1) — rozbudowa istniejącego CARB/FLUID o alerty
4. **Ride Strategy Engine** (P1) — centralny silnik, wykorzystuje wszystkie modele
5. **Adaptive Active Messages 2.0** (P1) — rozbudowa istniejącego systemu o nowe typy
6. **Crash / Incident Detection** (P1) — osobny moduł bezpieczeństwa
7. **Weather-Aware Ride Risk** (P2) — wymaga network verification
8. **Gear Intelligence** (P2) — wymaga SHIFTING source
9. **Route-Aware Pacing** (P2) — wymaga route + realnej jazdy
10. **Post-Ride Learning Loop** (P2) — wymaga backend endpoint

## Hard Rules

1. QExt2 primary field (LIVE 3x2) nie może stać się dashboardem — zostaje jako SPEED/POWER/HR/CADENCE/GRADE/GEAR
2. QBot advice ma być osobnym polem, nie częścią primary
3. Dynamiczne komunikaty (ACTIVE MSG) nie w primary field — własny overlay
4. Brak danych = WAIT/NO_DATA/NO_MODEL z jawnym reason
5. Brak fake defaultów — każda wartość musi mieć potwierdzony source
6. Brak alertów pogodowych bez weatherFresh=true
7. Crash detection nie generuje powiadomień zewnętrznych bez jawnej konfiguracji i cancel path
8. AXS actions nie mogą dublować intake bez debounce (1s cooldown per button)
9. QBot request musi mieć timeout (15s) i offline fallback (WAIT/NO_DATA)
10. Każdy nowy DataType wymaga testów JVM + replay scenario

## SDK Source Dependencies

| Feature | SDK requirement | Available in SDK 1.1.9 |
|---|---|---|
| Ride Ask | OnHttpResponse, existing streams | YES |
| AXS Intake | AXS button events | UNKNOWN (needs verification) |
| Crash Detection | SPEED, SENSOR streams | YES |
| Strategy Engine | existing models + route | YES |
| Active MSG 2.0 | existing streams | YES |
| Gear Intelligence | SHIFTING_GEARS | YES (Di2/AXS required) |
| Pacing | route + climbs | YES (route required) |
| Weather Risk | OpenWeatherMap HTTP | YES (via WeatherClient) |

## Backend/QBot Dependencies

| Feature | Backend requirement | Status |
|---|---|---|
| Ride Ask | QBot `/advice` or `/ask` endpoint | NEEDS DEFINITION |
| Post-Ride Loop | QBot `/ride-report` endpoint | NEEDS DEFINITION |
| AXS Intake | NONE (local only) | — |
| Crash Detection | NONE (local only) | — |
| Strategy Engine | NONE (local only) | — |
| Weather Risk | OpenWeatherMap (external, not QBot) | CONFIGURED |
