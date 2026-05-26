# BASELINE LAB 2026-05-24

Source of truth: `docs/QEXT2_ARCHITECTURE_CONTRACTS.md`

- applicationId: `com.qext2.primary`
- versionName: `0.1.0`
- marker: `QExt2 LAB baseline|real_ride_gate_pass|synthetic_gate_pass`
- wynik `./gradlew test`: `BUILD SUCCESSFUL`
- wynik `./gradlew assembleDebug`: `BUILD SUCCESSFUL`
- wynik `./gradlew installDebug`: `PASS`
- QEXT_SELF_CHECK: `PASS`
- real ride gate: `PASS`
- synthetic scenarios: `PASS`
- route_loaded_no_motion: `PASS`
- GEAR: `KNOWN_MISSING_SOURCE / NO_DATA`
- Stats SDK-first gate: `PASS`
- brak `INVALID/FATAL/Exception` z QExt2
- warning: `HHApp IllegalArgumentException` spoza QExt2
- warning: legacy mapping w `PrimaryRideSnapshot.kt` oznaczony
- zakres, ktory NIE zostal przetestowany: realna jazda z ruchem/czujnikami w terenie

## Checklista przed testem terenowym

- adb logcat clean
- start activity
- verify baseline logs
- verify no INVALID
- verify no grade spike at stop
- verify no reset after screen/profile switch

## Repo snapshot

- `git status --short`: `fatal: not a git repository (or any of the parent directories): .git`
- `git diff --stat`: `warning: Not a git repository. Use --no-index to compare two paths outside a working tree`
- commit/tag: nie wykonano (brak repozytorium Git w `~/Downloads/QExt2`)

## Fields status (2026-05-24 full regression)

### WORKING (16 pol)
| pole | source | readiness |
|---|---|---|
| SPEED/POWER/HR/CADENCE/GRADE/GEAR (LIVE 6) | core/FieldOutput | OK |
| TSS | SDK `TRAINING_STRESS_SCORE` | sdk_field_not_available -> WAIT/NO_DATA |
| KCAL | SDK `CALORIES` | sdk_field_not_available -> WAIT/NO_DATA |
| UP | SDK route snapshot | route_not_loaded -> NO_DATA; flat -> OK |
| LEFT | SDK route snapshot | route_not_loaded -> NO_DATA; flat -> OK |
| BAT_DRAIN | headunit_polling | pct missing -> NO_DATA |
| BAT_LEFT | headunit_polling + drain estimate | pct missing -> NO_DATA |
| ETA | local_model | eta_no_route / eta_model_not_ready |
| WPRIME/W' | local_model (policy ready) | wprime_no_cp_or_wprime |
| RSRV | local_model | rsrv_model_not_ready |
| CARB | local_model | carb_model_not_ready (NP=0 / <60s) |
| CARB_BALANCE | local_model | carb_model_not_ready |
| FLUID | local_model | fluid_model_not_ready (<60s) |

### BLOCKED (brak)
- Zadne pole nie pozostaje bez source/readiness.
- `sdk_field_not_available` dla TSS/KCAL gdy Kamień nie emituje danych — to poprawne i bezpieczne.

## Post-static smoke update: HR zero handling

- data/czas: 2026-05-24 ~19:00
- `./gradlew test` -> `BUILD SUCCESSFUL`
- `./gradlew assembleDebug` -> `BUILD SUCCESSFUL`
- `./gradlew installDebug` -> `BUILD SUCCESSFUL`
- `QEXT_SELF_CHECK` -> `PASS`
- HR=0 behavior after fix:
  - `value=WAIT`
  - `status=NO_DATA`
  - `reason=hr_zero_or_not_ready`
- INVALID after new process PID 12527: **none from QExt2**
- remaining external warning: `HHApp IllegalArgumentException: Service not registered...` (outside QExt2)
- blockers: **none**

### Current field status (full list, post-HR fix)

WORKING:
- `SPEED` (core/FieldOutput)
- `POWER` (core/FieldOutput)
- `HR` (core/FieldOutput; HR=0 -> WAIT/NO_DATA/hr_zero_or_not_ready)
- `CADENCE` (core/FieldOutput)
- `GRADE` (core/FieldOutput; NO_DATA when source missing)
- `GEAR` (core/FieldOutput; NO_DATA when source missing)
- `UP` (SDK route snapshot)
- `LEFT` (SDK route snapshot)
- `BAT_DRAIN` (headunit_polling)
- `BAT_LEFT` (headunit_polling + drain estimate)
- `TSS` (SDK-only, `sdk_field_not_available` -> WAIT/NO_DATA)
- `KCAL` (SDK-only, `sdk_field_not_available` -> WAIT/NO_DATA)
- `ETA` (local_model)
- `WPRIME/W'` (local_model; UI pole dodane w slocie deadline; source CP/W' z `QEXT_READINESS_URL`, nie QBot; domyslne CP=0/W'=0 blokuja model bez serwera -> WAIT/NO_MODEL)
- `RSRV` (local_model; readiness wymaga aktywnosci `hasActivity` — static/no-motion -> WAIT/NO_MODEL)
- `CARB` (local_model)
- `CARB_BALANCE` (local_model)
- `FLUID` (local_model; readiness wymaga aktywnosci `hasActivity` — static/no-motion -> WAIT/NO_MODEL)

### Active Message
- Status: `WORKING`
- DataType: `qext2-active` / `CompositeActiveDataType`
- Layout: `field_active_4x2.xml`
- Renderer: `ActiveMessageRenderer.bind()` (`message_overlay` z `GONE` gdy brak komunikatu)
- Sources: `ActiveClimbResolver`, `SensorState`, `hasRoute`, `KarooSystemService` (beep)
- Nie emituje `QEXT_STATS_ADV` (osobny DataType)
- Tests: 32 (6 test classes)
- Blockers: none

### Warnings
- Setup ODŚWIEŻ: previously fake refresh (only timestamp, no HTTP fetch). FIXED — now calls `refetchAthleteData()`.
- Weather: CONFIGURED, key in local.properties (not in backup), requires active network route (Companion/phone/Wi-Fi)
- ACTIVE MSG weather: CONFIGURED_WAITING_FOR_FRESH_DATA
- local.properties excluded from backups by .gitignore
- `WPRIME/W'` source endpoint `ngrok-free.dev/ride-readiness` moze byc niedostepny — bez serwera model blokowany (`WAIT/NO_MODEL`)
- `WPRIME/W'` model/policy gotowy, ale brak dedykowanego UI pola w `field_stats_3x3.xml` (FIXED)
- `PrimaryRideSnapshot` legacy mapping oznaczony, nieusuwany w tym kroku.
- Test terenowy z realnym ruchem/czujnikami nadal nie byl wykonywany po tym baseline.

## Static route smoke update: FLUID and RSRV readiness

- data/czas: 2026-05-24 ~19:33
- `./gradlew test` -> `BUILD SUCCESSFUL`
- `./gradlew assembleDebug` -> `BUILD SUCCESSFUL`
- `./gradlew installDebug` -> `BUILD SUCCESSFUL`
- `QEXT_SELF_CHECK` -> `PASS` (PID 13643)
- INVALID/FATAL from QExt2: **none**
- FLUID static/no-motion:
  - `value=WAIT`
  - `status=NO_MODEL`
  - `reason=fluid_model_not_ready`
  - `source=--`
- RSRV static/no-motion:
  - `value=WAIT`
  - `status=NO_MODEL`
  - `reason=rsrv_model_not_ready`
  - `source=--`
- UP/LEFT route-loaded flat:
  - `value=0`
  - `status=OK`
  - `reason=flat_route_or_zero_ascent`
  - `source=route_snapshot`
- remaining external warning: `HHApp IllegalArgumentException: Service not registered...` (outside QExt2)
- blockers: **none**

### Overall status

| gate | status |
|---|---|
| QExt2 primary field (LIVE) | PASS |
| STATS SDK-first/local_model readiness | PASS |
| static route smoke | PASS |
| WPRIME/W' UI field | COMPLETE (replaced dead deadline slot) |
| field test with real movement | NOT DONE |

## WPRIME/W' UI regression smoke (2026-05-24 ~20:37)

- `./gradlew test` -> `BUILD SUCCESSFUL`
- `./gradlew assembleDebug` -> `BUILD SUCCESSFUL`
- `./gradlew installDebug` -> `BUILD SUCCESSFUL`
- `QEXT_SELF_CHECK` -> `PASS` (PID 15309)
- INVALID/FATAL from QExt2: **none**
- WPRIME/W' replaced dead `deadline`/`SUN` slot in `field_stats_3x3.xml`
- Policy: `localWPrime(wPrimeModelReady, wBalancePercent)` with `source=local_model`
- Poczatkowo W' zawsze `NO_MODEL` (brak CP); po AthleteData ready pokazuje `X%`
- CP/W' source: `QEXT_READINESS_URL` -> ACTIVE: `https://qbot.cytr.us/ride-readiness` (potwierdzone: wPrimeKj=21.1, ltpWatts=193.7); poprzedni ngrok DEPRECATED
- /sse NOT USED by QExt2 (SSE/MCP stream, niekompatybilne z JSON klientem readiness)
- Usuniete ukryte fake W'/CP defaults z: `QExt2PrimaryExtension` fallback, `CompositeActiveDataType`, `BpActiveStaticDataType`, `StatsCalculator`
- Brak aktywnych referencji do ngrok w runtime
- Defaults `0/0` w `StatsCalculator` — bez serwera model blokowany
- blockers: **none**

## First controlled field test checklist

- [ ] upewnic sie, ze backup ZIP `QExt2_BASELINE_STATIC_ROUTE_PASS_2026-05-24.zip` istnieje
- [ ] `adb logcat -c` przed startem
- [ ] start aktywnosci z trasa
- [ ] 5-10 min spokojnej jazdy
- [ ] przelaczenie ekranu/profilu 2-3 razy
- [ ] pauza/wznowienie jazdy
- [ ] zatrzymanie na 1-2 min
- [ ] zapis logcat po tescie: `adb logcat -d > field_test_logcat.txt`
- [ ] eksport aktywnosci FIT/GPX z Karoo
- [ ] sprawdzic brak INVALID/FATAL z tagow QExt2
- [ ] sprawdzic brak resetu AVG/distance
- [ ] sprawdzic GRADE na postoju (brak spike)
- [ ] sprawdzic sensor dropout/recovery
- [ ] sprawdzic STATS source/reason dla wszystkich pol
