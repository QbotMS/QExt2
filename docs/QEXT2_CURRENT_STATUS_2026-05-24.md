# QEXT2 Current Status 2026-05-24

Source of truth: `docs/QEXT2_ARCHITECTURE_CONTRACTS.md`

## Application/package

- `com.qext2.primary`

## Backup

- `QExt2_BASELINE_STATIC_ROUTE_PASS_2026-05-24.zip` (983K)
- `QExt2_BASELINE_WPRIME_UI_COMPLETE_2026-05-24.zip` (986K)
- `QExt2_RELEASE_CANDIDATE_2026-05-24.zip` (989K)
- `QExt2_RELEASE_CANDIDATE_QBOT_READINESS_2026-05-24.zip` (991K)
- `QExt2_RELEASE_CANDIDATE_NO_FAKE_DEFAULTS_2026-05-24.zip` (993K)
- `QExt2_RELEASE_CANDIDATE_REFRESH_FIXED_2026-05-24.zip` (994K)
- `QExt2_RELEASE_CANDIDATE_WEATHER_CONFIGURED_2026-05-24.zip` (1.0M) — latest, excludes local.properties

## Test status

- `./gradlew test`: `BUILD SUCCESSFUL`
- `./gradlew assembleDebug`: `BUILD SUCCESSFUL`
- `./gradlew installDebug`: `BUILD SUCCESSFUL` (last with qbot readiness URL)
- `QEXT_SELF_CHECK`: `PASS`
- Q readiness fetch verified on Karoo: `W'max=21.1kJ`, `FTP=246`, `ltpWatts` present
- ngrok endpoint DEPRECATED, not active default
- Setup ODŚWIEŻ fixed: previously fake refresh (only timestamp), now real HTTP GET via `refetchAthleteData()`

## Release readiness

- ACTIVE MSG: `WORKING`
- Weather: `CONFIGURED` — key set in local.properties, polling active; fetch requires active network route (Companion App/phone or Wi-Fi); fails safely without
- ACTIVE MSG weather: `CONFIGURED_WAITING_FOR_FRESH_DATA` — producer ready, requires working fetch + freshness
- OpenWeatherMap key: configured via local.properties, not logged, not hardcoded
- local.properties excluded from backup (.gitignore) and not in ZIP
- local.properties.example exists without secrets
- CARB/FLUID use weather only when weatherFresh=true
- Real ride virtual replay: `PASS`
- Stats SDK-first/local_model gate: `PASS`
- Synthetic scenarios: `PASS`
- Static route smoke: `PASS`
- Connectors: all `COMPLETE` (6 fields)
- Blockers: **none**
- Active QEXT_READINESS_URL: `https://qbot.cytr.us/ride-readiness`
- Latest backup: `QExt2_RELEASE_CANDIDATE_QBOT_READINESS_2026-05-24.zip`
- Required next action: **controlled field test only**

## Gates

| gate | status |
|---|---|
| real ride virtual replay | PASS |
| stats SDK-first/local_model gate | PASS |
| synthetic scenarios | PASS |
| route_loaded_no_motion | PASS |
| static route smoke | PASS |

## Working fields

### QExt2 primary field (LIVE)
- `SPEED`, `POWER`, `HR`, `CADENCE`, `GRADE`, `GEAR` (readiness/NO_DATA when source missing)

### SDK-only
- `TSS`, `KCAL`

### SDK/runtime/snapshot
- `UP`, `LEFT`, `BAT_DRAIN`, `BAT_LEFT`

### local_model
- `ETA`, `WPRIME/W'`, `RSRV`, `CARB`, `CARB_BALANCE`, `FLUID`

### Active Message (dynamic overlay)
- Status: `WORKING`
- DataType: `qext2-active` (`CompositeActiveDataType`)
- Layout: `field_active_4x2.xml`
- Renderer: `ActiveMessageRenderer.bind()`
- Sources: `ActiveClimbResolver` (climbs), `SensorState` (sensors), `hasRoute`, `KarooSystemService` (beep)
- No message: overlay `GONE` (nie WAIT/NO_DATA)
- Nie nalezy do STATS ani QExt2 primary field
- Debug modes: `false` (prod-ready)
- Tests: 32 (across 6 test classes)
- Blockers: none

## Important fixes

- `HR=0` -> `WAIT/NO_DATA`, not `INVALID`
- `FLUID` static/no-motion -> `WAIT/NO_MODEL`
- `RSRV` static/no-motion -> `WAIT/NO_MODEL`
- `UP/LEFT` flat route -> `0 OK flat_route_or_zero_ascent`
- `GRADE` static/no-motion -> `WAIT/NO_DATA`, no fake climb

## Known warnings

- `HHApp IllegalArgumentException: Service not registered...` (outside QExt2)
- `WPRIME/W'` policy ready, no dedicated UI field in `field_stats_3x3.xml` (FIXED: dodane w slocie `deadline` -> `wprime`)
  - WPRIME/W': COMPLETE (UI + connector)
  - Source CP/W': `BuildConfig.QEXT_READINESS_URL` — ACTIVE: `https://qbot.cytr.us/ride-readiness` (ngrok DEPRECATED)
  - Confirmed JSON: `wPrimeKj=21.1, ltpWatts=193.7, ftpWatts=246`
  - /sse NOT used for readiness (SSE stream, incompatible with current JSON client)
  - External references audit PASS:
    - Hidden fake W'/CP defaults removed from `QExt2PrimaryExtension` (ngrok fallback), `CompositeActiveDataType`, `BpActiveStaticDataType`, `StatsCalculator`
    - All W'/CP calculators start at `0/0` — require server response for activation
    - No active ngrok runtime reference in readiness path (only gate remains on ngrok — accepted warning, separate system)
  - Defaulty CP/W' `0f/0f` w `StatsCalculator` — bez serwera model blokowany (`WAIT/NO_MODEL`)
  - Candidate endpoint `qbot.cytr.us/sse`: CANDIDATE_ONLY, unreachable from local test, contract not verified, config NOT changed
  - Warning: endpoint `ngrok-free.dev` moze byc niedostepny
  - UI field added in `field_stats_3x3.xml`
  - dead deadline/SUN slot replaced
  - ETA inputs verified: route + distance/speed only; DEADLINE/SUN/sunset NOT used in ETA computation
  - `etaDoesNotDependOnDeadlineOrSunset` regression test added
  - test/assemble/install PASS from last smoke
  - known warning: STATS_ADV logs require active Karoo data type/profile
- `PrimaryRideSnapshot` legacy mapping marked
- field test with real movement: `NOT DONE`
- project folder is not Git repo

## Blockers

- none

## Next allowed action

- controlled field test only, using checklist from `docs/BASELINE_LAB_2026-05-24.md`
- no new feature work before that unless explicitly requested
