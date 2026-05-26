# QExt2 Architecture Contracts

Centralny dokument kontraktów architektury. Źródło prawdy dla wszystkich funkcji QExt2.

## A. QExt2 Primary Field (LIVE MVP 3x2)

| feature | source type | exact source path | required inputs | readiness | failure behavior | allowed fallback | forbidden fallback | UI | log tag | tests | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SPEED | SDK | `DataType.Type.SPEED` / `Field.SPEED` | sensor data | `state.speedKmh != null` | `WAIT/NO_DATA/missing_speed`; STALE po `config.speedStaleSec` | `0.0` OK (below_reference) | `NaN/Infinity` -> INVALID | `field_primary_4col.xml` | `QEXT_LAB_CORE: QEXT_FIELD_OUTPUT` | SyntheticVirtualRideScenariosTest, VirtualReplayGateTest | WORKING |
| POWER | SDK | `DataType.Type.POWER` / `Type.SMOOTHED_3S_AVERAGE_POWER` | sensor data | `state.powerW != null` | `WAIT/NO_DATA/missing_power`; STALE po `config.sensorStaleSec` | `0` OK (coasting) | `NaN/Infinity` -> INVALID | `field_primary_4col.xml` | `QEXT_LAB_CORE: QEXT_FIELD_OUTPUT` | SyntheticVirtualRideScenariosTest, VirtualReplayGateTest | WORKING |
| HR | SDK | `DataType.Type.HEART_RATE` / `Field.HEART_RATE` | sensor data | `state.hrBpm != null`, hr != 0 | `hr==0` -> `WAIT/NO_DATA/hr_zero_or_not_ready`; `hr<30` -> INVALID; STALE | none | `0` used as real value | `field_primary_4col.xml` | `QEXT_LAB_CORE: QEXT_FIELD_OUTPUT` | RideStateTest.hrZeroIsNoDataNotInvalid | WORKING |
| CADENCE | SDK | `DataType.Type.CADENCE` / `Field.CADENCE` | sensor data | `state.cadenceRpm != null` | `WAIT/NO_DATA/missing_cadence`; STALE | `0` OK (coasting_or_stopped) | `NaN/Infinity` -> INVALID | `field_primary_4col.xml` | `QEXT_LAB_CORE: QEXT_FIELD_OUTPUT` | SyntheticVirtualRideScenariosTest | WORKING |
| GRADE | SDK | `DataType.Type.ELEVATION_GRADE` | sensor/barometric data | `state.gradeDisplayPct != null` | `WAIT/NO_DATA/missing_grade`; STALE | `0%` OK (flat_deadband) | fake climb from idle | `field_primary_4col.xml` | `QEXT_LAB_CORE: QEXT_FIELD_OUTPUT` | SyntheticVirtualRideScenariosTest, route_loaded_no_motion | WORKING |
| GEAR | SDK | `DataType.Type.SHIFTING_GEARS` | Di2/AXS gear data | `front != null, rear != null` | `WAIT/NO_DATA/missing_gear`; STALE; no shifting sensor -> NO_DATA | none | fake gear value | `field_primary_4col.xml` | `QEXT_LAB_CORE: QEXT_FIELD_OUTPUT` | VirtualReplayGateTest (GEAR NO_DATA all ticks) | WORKING (NO_DATA when source missing) |

## B. STATS / Advanced Fields (3x3)

| feature | source type | exact source path | required inputs | readiness | failure behavior | allowed fallback | forbidden fallback | UI | log tag | tests | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| TSS | SDK | `DataType.Type.TRAINING_STRESS_SCORE` -> snapshot | SDK field | `tssValue > 0` from SDK | `WAIT/NO_DATA/sdk_field_not_available` | none | local model fallback | `field_stats_3x3.xml tv_tss` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING (SDK-only) |
| KCAL | SDK | `DataType.Type.CALORIES` -> snapshot | SDK field | `kcal > 0` from SDK | `WAIT/NO_DATA/sdk_field_not_available` | none | local model fallback | `field_stats_3x3.xml tv_cal` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING (SDK-only) |
| UP | runtime/snapshot | `DataType.Type.ELEVATION_GAIN` -> snapshot | route + climb source | `hasRoute && routeClimbSourceReady` | route_not_loaded -> NO_DATA; no climb -> NO_MODEL | `0` OK (flat_route_or_zero_ascent) | fake climb value | `field_stats_3x3.xml tv_asc_done` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING |
| LEFT | runtime/snapshot | `DataType.Type.ELEVATION_REMAINING` -> snapshot | route + climb source | `hasRoute && routeClimbSourceReady` | route_not_loaded -> NO_DATA; no climb -> NO_MODEL | `0` OK (flat_route_or_zero_ascent) | fake climb value | `field_stats_3x3.xml tv_asc_left` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING |
| BAT_DRAIN | runtime/system | `ACTION_BATTERY_CHANGED` -> snapshot | headunit battery pct + trend | `batterySourceReady && batteryDrainReady` | pct missing -> NO_DATA; drain not ready -> NO_MODEL | none | fake drain rate without source | `field_stats_3x3.xml tv_bat_drain` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING_CONDITIONAL |
| BAT_LEFT | runtime/system | `ACTION_BATTERY_CHANGED` -> snapshot | headunit battery pct + drain estimate | `batterySourceReady && batteryEstimateReady` | pct missing -> NO_DATA; estimate not ready -> NO_MODEL | none | fake runtime without source | `field_stats_3x3.xml tv_bat_left` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING_CONDITIONAL |
| ETA | local_model | `EtaCalculator` + aggregator -> snapshot | route + distance left + speed | `hasRoute && etaModelReady (etaMs > 0)` | no_route -> NO_DATA; not ready -> NO_MODEL | `source=local_model` | DEADLINE/SUN/sunset as ETA input | `field_stats_3x3.xml tv_eta` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest (inc. etaDoesNotDependOnDeadlineOrSunset) | WORKING |
| WPRIME/W' | local_model | `QEXT_READINESS_URL` JSON -> AthleteDataStore -> StatsCalculator.setWPrimeParams -> snapshot | wPrimeKj + ltpWatts from server + power stream | `wPrimeModelReady (wBalance >= 0 from set CP)` | no CP/W' -> NO_MODEL; StatsCalculator defaults `0/0` block model | `source=local_model` | fake defaults (was 21.3/192, FIXED to 0/0) | `field_stats_3x3.xml tv_wprime` (replaced deadline) | `QEXT_STATS_ADV`, `QEXT_READINESS_FETCH_*` | StatsAdvancedFieldPolicyTest | WORKING_CONDITIONAL (server required) |
| RSRV | local_model | `StatsCalculator.rideReservePercent` + aggregator -> snapshot | TSS + IF + decoupling + elapsed + activity | `rsrvModelReady (hasActivity)` | no activity -> NO_MODEL | `source=local_model` | fake reserve without activity | `field_stats_3x3.xml tv_rsrv` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING |
| CARB | local_model | `StatsCalculator.carbsGPerH` + aggregator -> snapshot | NP + VI + temp + bodyWeight | `carbModelReady (NP > 0 && elapsed > 60s)` | not ready -> NO_MODEL | `source=local_model` | fake carb rate without power | `field_stats_3x3.xml tv_carb` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING |
| CARB_BALANCE | local_model | aggregator intake - needed -> snapshot | CARB model + carb intake store | `carbModelReady` | not ready -> NO_MODEL | `source=local_model`; `0g` OK | fake balance without intake | `field_stats_3x3.xml tv_carb_balance` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING |
| FLUID | local_model | `StatsCalculator.fluidLPerH` + aggregator -> snapshot | IF + temp + humidity + bodyWeight + activity | `fluidModelReady (elapsed > 60s && hasActivity)` | no activity -> NO_MODEL | `source=local_model` | fake fluid without activity | `field_stats_3x3.xml tv_fluid` | `QEXT_STATS_ADV` | StatsAdvancedFieldPolicyTest | WORKING |

## C. ACTIVE MSG

| feature | source type | exact source path | required inputs | readiness | failure behavior | allowed fallback | forbidden fallback | UI | log tag | tests | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| climb messages | runtime | `ActiveClimbResolver` + `ClimbAnnouncementProducer` | route + climb data (SDK `OnNavigationState` + distance) | `hasRoute`, climb proximity | no route/climb -> null (no message) | null = overlay GONE | fake climb alert without route | `field_active_4x2.xml message_overlay` | `QEXT_ACTIVE_MSG`, `QEXT_CLIMB_MSG` | ClimbAnnouncementProducerTest, ActiveClimbResolverTest | WORKING |
| sensor messages | runtime | `SensorMessageProducer` | speed + power/HR/cadence freshness | sensor dropout detected, cooldowns | no dropout -> null | null = overlay GONE | fake sensor alert without dropout | `field_active_4x2.xml message_overlay` | `QEXT_SENSOR_MSG` | SensorMessageProducerTest | WORKING |
| route missing messages | runtime | `SensorMessageProducer.checkRouteMissing` | hasRoute, elapsed > threshold | route not loaded after elapsed threshold | hasRoute -> null | null = overlay GONE | fake route missing alert | `field_active_4x2.xml message_overlay` | `QEXT_SENSOR_MSG` | SensorMessageProducerTest | WORKING |
| beep | runtime/system | `BeepCooldownTracker` + `KarooSystemService.dispatch(PlayBeepPattern)` | message severity + cooldown tracker | per-severity cooldown (success=10s, error=2s) | suppressed during cooldown | `KarooSystemService.dispatch` | ToneGenerator (not used) | none (audio) | `QEXT_ACTIVE_BEEP` | BeepCooldownTrackerTest | WORKING |
| weather messages | runtime | `WeatherMessageProducer` | weatherFresh + thresholds | fresh weather + threshold exceeded | network unavailable -> fetch fails silently; stale -> null | null = overlay GONE | fake weather alert from defaults or stale data | `field_active_4x2.xml message_overlay` | `WEATHER_TRIGGER/REJECT/SUPPRESS`, `QEXT_WEATHER_FETCH_*` | WeatherClientTest | CONFIGURED (requires active network route + fetch success) |

## D. SETUP / Readiness

| feature | source type | exact source path | required inputs | readiness | failure behavior | allowed fallback | forbidden fallback | UI | log tag | tests | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| startup fetch | external JSON | `BuildConfig.QEXT_READINESS_URL` -> `qbot.cytr.us/ride-readiness` | HTTP GET, JSON body | HTTP 200 + valid JSON | wPrimeKj defaults 0/0 keep model blocked; log FAILED | `BuildConfig` default + `local.properties` override | ngrok as active URL (FIXED) | `activity_setup.xml` | `QEXT_READINESS_FETCH_START/HTTP/PARSED/SAVED/FAILED` | external endpoint verification | WORKING |
| manual refresh (ODŚWIEŻ) | external JSON | same as startup fetch via `QExt2PrimaryExtension.refetchAthleteData()` | button click -> `refetchAthleteData()` -> HTTP GET | same as startup | same as startup | none | previously: only updated timestamp without HTTP fetch (FIXED) | `activity_setup.xml btn_refetch` | `QEXT_READINESS_FETCH_START/SETUP` | SetupActivity.kt refetch button | WORKING (FIXED) |
| AthleteDataStore | local storage | `SharedPreferences` ("qext2_athlete") | stored athlete data | `prefs != null` | returns defaults (wPrimeKj=3.75, ltpWatts=0) — safe because StatsCalculator requires both >0 | none | fake athlete data overriding server | N/A | N/A | N/A | WORKING |
| lastRefresh/timestamp | local storage | `AthleteDataStore.saveLastRefresh()` | called only after successful fetch+parse+save | post-save only | not updated on fetch failure | none | ticking timestamp without real fetch (FIXED) | `activity_setup.xml tv_fetch_date` | `QEXT_READINESS_FETCH_SAVED` | N/A | WORKING (FIXED) |

## Global Rules

1. No fake default pretending to be real data.
2. Refresh timestamp updated only after successful fetch+parse+save.
3. External JSON endpoints must have confirmed availability and field contract.
4. SDK-only fields (TSS/KCAL) must not fallback to local_model without explicit decision.
5. local_model must have `source=local_model`, readiness flag, and tests.
6. visual_only data (temp, wind) must not produce ACTIVE MESSAGES without explicit contract.
7. ACTIVE MSG: no message = overlay GONE, not fake WAIT.
8. Weather ACTIVE MSG = WORKING (conditional on OPENWEATHER_API_KEY + location + freshness). No fake alerts from defaults.
9. DEADLINE/SUN must not feed into ETA computation.
10. GEAR without real shifting source = WAIT/NO_DATA.
11. HR=0 = WAIT/NO_DATA, not INVALID.
12. FLUID/RSRV static/no-motion = WAIT/NO_MODEL.
13. QExt2 does NOT use /sse or /mcp as readiness source.
14. W'/CP calculators start at 0/0 defaults — require server response for activation.
15. QEXT_READINESS_URL active: `https://qbot.cytr.us/ride-readiness`. Ngrok DEPRECATED.
16. GateOpenClient / gate URL uses ngrok — separate system from readiness. Accepted warning. Not a blocker.
17. /sse and /mcp are NOT readiness sources for QExt2.
