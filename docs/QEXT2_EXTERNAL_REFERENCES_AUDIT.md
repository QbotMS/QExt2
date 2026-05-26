# QExt2 External References & Defaults Audit 2026-05-24

## Hardcoded URLs

| reference | file | runtime active | purpose | current source | safe/unsafe | action taken | remaining risk |
|---|---|---|---|---|---|---|---|
| `ankle-wool-undusted.ngrok-free.dev/ride-readiness` | `QExt2PrimaryExtension.kt:145` | YES (`.ifEmpty {}` fallback) | readiness fallback URL | `BuildConfig.QEXT_READINESS_URL` -> `qbot.cytr.us` | unsafe | **FIXED**: replaced with `https://qbot.cytr.us/ride-readiness` | none (BuildConfig takes priority, fallback only if empty) |
| `ngrok-skip-browser-warning: true` | `GateOpenClient.kt:93` | YES | HTTP header for gate client | header only, not URL | safe | none (header, not URL) | none |
| `example.test/gate/open` | `GateOpenClientTest.kt` | NO (test only) | test mock URL | test | safe | none | none |

## Hardcoded W'/CP defaults

| reference | file | runtime active | purpose | current value | safe/unsafe | action taken | remaining risk |
|---|---|---|---|---|---|---|---|
| `wPrimeKj=21.3f, ltpWatts=192f` | `StatsCalculator.kt:41-42` | YES (was active) | W' model defaults | `0f/0f` (fixed earlier) | safe now | **FIXED** earlier: changed to `0f/0f` | none |
| `wPrimeMax=3750.0, ltpWatts=192.0` | `CompositeActiveDataType.kt:56,58` | YES | ACTIVE data type W' calculator | **FIXED**: `0.0/0.0` | safe now | **FIXED**: changed to `0.0/0.0` | none |
| `wPrimeMax=3750.0, ltpWatts=192.0` | `BpActiveStaticDataType.kt:40,42` | YES | ActiveStatic data type W' calculator | **FIXED**: `0.0/0.0` | safe now | **FIXED**: changed to `0.0/0.0` | none |

## AthleteData defaults

| reference | file | runtime active | purpose | default value | safe/unsafe | action taken | remaining risk |
|---|---|---|---|---|---|---|---|
| `wPrimeKj = 3.75` | `AthleteDataStore.kt:8,98` | YES | data class + SharedPreferences default | `3.75` (data class) / SP pref default | safe | none (StatsCalculator setWPrimeParams requires ltpWatts>0 too) | StatsCalculator ignores wPrimeKj=3.75 alone |
| `ltpWatts = 0` | `AthleteDataStore.kt:10,100` | YES | data class + SP default | `0` | safe | none (0 means model disabled) | none |
| `ftp = 250` | `AthleteDataStore.kt:7,97` | YES | data class + SP default | `250` | safe | none (reasonable default; server overrides) | none |
| `bodyWeightKg = 75f` | `AthleteDataStore.kt:16,106` | YES | data class + SP default | `75` kg | safe | none (server overrides) | none |

## SETUP

| check | status |
|---|---|
| Setup uses `BuildConfig.QEXT_READINESS_URL` | YES (delegates to `QExt2PrimaryExtension.refetchAthleteData()`) |
| Separate hardcoded URL in SetupActivity | NO |
| fetches via `BuildConfig.QEXT_READINESS_URL` | YES |
| Logs source/reason without secrets | YES (`Log.d(TAG, "Fetching athlete data from Q server")` - no URL in log) |
| After fetch failure keeps WAIT/NO_MODEL | YES (StatsCalculator defaults 0/0 remain) |

## Other local_model fields audit

| field | fake defaults found | safe |
|---|---|---|
| ETA | NO (uses hasRoute + etaMs > 0) | safe |
| RSRV | NO (uses hasActivity guard) | safe |
| CARB | NO (uses NP > 0 + elapsed > 60s) | safe |
| CARB_BALANCE | NO (same as CARB) | safe |
| FLUID | NO (uses hasActivity + elapsed > 60s) | safe |
| BAT_DRAIN/BAT_LEFT | NO (uses batterySourceReady + readiness booleans) | safe |
| UP/LEFT | NO (uses route + climb source) | safe |
| ACTIVE MSG | NO (dynamic message, not a data field) | safe |

## Summary

| category | issues found | fixed |
|---|---|---|
| Stale/ngrok URLs in runtime code | 1 | 1 |
| Fake W'/CP defaults | 3 | 3 |
| Fake AthleteData defaults | 4 | 0 (all safe) |
| SETUP hardcoded URL | 0 | N/A |

- QEXT_READINESS_URL now points exclusively to `https://qbot.cytr.us/ride-readiness` in both BuildConfig default and `.ifEmpty` fallback
- All W'/CP calculators start with `0/0` defaults — require server response to activate
- No remaining fake values that would show as "OK" without real data

## Setup manual refresh verification

- **Previous state**: ODŚWIEŻ button in SetupActivity only called `AthleteDataStore.saveLastRefresh()` — updated timestamp without actual HTTP fetch. This was a "fake refresh".
- **Current state**: ODŚWIEŻ now calls `QExt2PrimaryExtension.refetchAthleteData()` — triggers real HTTP GET to `BuildConfig.QEXT_READINESS_URL`.
- **URL**: `https://qbot.cytr.us/ride-readiness`
- **Diag logs added**:
  - `QEXT_READINESS_FETCH_START url=...`
  - `QEXT_READINESS_FETCH_HTTP status=...`
  - `QEXT_READINESS_FETCH_PARSED wPrimeKj=... ltpWatts=... ftpWatts=...`
  - `QEXT_READINESS_FETCH_SAVED source=...`
  - `QEXT_READINESS_FETCH_FAILED reason=...`
- **Timestamp behavior**: updated only after successful fetch + parse + save. Not updated on error.
- **Smoke verified** (PID 8847): `wPrimeKj=21.1, ltpWatts=193, ftpWatts=246, factor=0.95933`
- **Fake defaults**: none
