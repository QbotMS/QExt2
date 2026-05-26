# QExt2 Open Items Audit (Code Inventory Based)

Generated from mechanical code inventory, not from conversation memory.

## Evidence-based open items

| id | feature | evidence file:line | issue type | current behavior | risk | user-visible | blocker field test | decision | next action |
|---|---|---|---|---|---|---|---|---|---|
| O1 | Gate URL uses ngrok | `build.gradle.kts:16` | stale endpoint | gate defaults to `ngrok-free.dev/gate/open` | LOW | NO | NO | ACCEPT_WARNING | User decision: keep ngrok while it works; optional migration later |
| O2 | Gate sends ngrok-skip-browser-warning | `GateOpenClient.kt:93` | stale header | sends header to ngrok-only gate | LOW | NO | NO | ACCEPT_WARNING | User decision: keep; only relevant while gate on ngrok |
| O3 | `field_primary_fallback.xml` unused | `field_primary_fallback.xml` | dead code | layout exists but never used in code; XML comment added | LOW | NO | NO | REMOVE_LATER | Marked as legacy_unused_fallback_layout |
| O4 | PrimaryRideSnapshot legacy mapping | `PrimaryRideSnapshot.kt:34-36` | legacy | local color/speed/grade logic marked TODO | MEDIUM | YES (through LIVE) | NO | REMOVE_LATER | After FieldOutput migration |
| O5 | demoSnapshot() | `StatsDataType.kt:156` | dead/demo code | returns zero values, tested safe | LOW | NO | NO | REMOVE_LATER | Remove when dev phase complete |
| O6 | applyFakeRideData() | `RideDataAggregator.kt:1126` | debug code | guarded by DEBUG_FAKE_RIDE_MODE=false | LOW | NO | NO | ACCEPT_WARNING | Keep for dev; ensure stays false |
| O7 | DEADLINE/SUN computed but not shown | `RideDataAggregator.kt:837-849` | dead computation | deadlineMs, deadlineStatus computed, stored in snapshot, but no UI binding | LOW | NO | NO | REMOVE_LATER | Remove or optionally expose as diagnostic |
| O8 | Field test NOT DONE | global | missing validation | all gates PASS except field | HIGH | NO | YES | NEEDS_FIELD_TEST | Execute checklist from BASELINE_LAB |
| O9 | Weather not verified on Karoo with network | `WeatherClient.kt` | missing field validation | CONFIGURED; fetch failed in static Karoo without network | MEDIUM | NO | NO | NEEDS_NETWORK_TEST | Test with Karoo on network route |
| O10 | Weather ACTIVE MSG not field-tested | `WeatherMessageProducer.kt` | missing field validation | producer ready, needs fresh weather | MEDIUM | NO | NO | NEEDS_FIELD_TEST | Requires O9 first |
| O11 | BpActiveStaticDataType has no dedicated tests | `BpActiveStaticDataType.kt` | test gap | 521-line DataType with W'/IF/wind/temp — zero tests | MEDIUM | YES (field) | NO | DOCUMENT_ONLY | Covered by existing active tests indirectly; add specific tests if modified |
| O12 | PrimaryRenderOptimizer has no tests | `engine/PrimaryRenderOptimizer.kt` | test gap | render optimizer used in LIVE path | LOW | YES (through LIVE) | NO | DOCUMENT_ONLY | Covered by scenario tests indirectly |
| O13 | CARB intake button no e2e test | `StatsDataType.kt:72-98`, `StatsActionReceiver.kt` | test gap | manual carb button with intake logic | LOW | YES | NO | NEEDS_FIELD_TEST | Test button press during ride |
| O14 | Setup manual refresh old path removed but not e2e tested | `SetupActivity.kt:66-77` | test gap | now calls refetchAthleteData() | LOW | YES | NO | NEEDS_FIELD_TEST | Test ODŚWIEŻ button press with network |
| O15 | BAT_DRAIN/BAT_LEFT require >10min ride | aggregator policy | conditional readiness | shows NO_MODEL until drain window | LOW | YES | NO | NEEDS_FIELD_TEST | Verify values appear after 10+ min ride |
| O16 | QEXT_READINESS_URL verified but only at startup | `QExt2PrimaryExtension.kt:142` | external dependency | fetch works; if server down, model stays WAIT (safe) | LOW | NO | NO | ACCEPT_WARNING | Safe failure behavior confirmed |
| O17 | OpenWeatherMap unavailable without network | `WeatherClient.kt` | external dependency | fetch fails; weather stays WAIT | LOW | NO | NO | ACCEPT_WARNING | Safe failure confirmed; needs network test |
| O18 | local.properties.example missing sdk.dir | `local.properties.example` | config gap | example has all QExt2 keys but not sdk.dir (Android required) | LOW | NO | NO | DOCUMENT_ONLY | Add sdk.dir placeholder to example |

## Summary

| category | count |
|---|---|
| HIGH | 1 (O8: field test) |
| MEDIUM | 4 (O4, O9, O10, O11) |
| LOW | 13 |
| Total | 18 |

### Items previous audit missed

- O3: `field_primary_fallback.xml` dead layout (found via file inventory)
- O11: BpActiveStaticDataType no dedicated tests (521 lines, complex)
- O12: PrimaryRenderOptimizer no dedicated tests
- O18: local.properties.example missing sdk.dir

### GateOpenClient/ngrok status
- **Active open item**: YES (O1, O2). Gate URL still uses ngrok. Header `ngrok-skip-browser-warning` still present. Accepted as warning — gate is separate from QExt2 core.

### New fake defaults found
- **None**. All W'/CP calculators verified at `0f/0.0`. AthleteData defaults (`wPrimeKj=3.75, ltpWatts=0`) are safe (setWPrimeParams requires both >0).

### UI binding gaps
- **None**. All 18 `tv_*` IDs in `field_stats_3x3.xml` have corresponding bindings in `StatsDataType.kt`.
- `tv_active_null` is used for carb balance display (poorly named, but functionally bound).

### DataType without test
- O11: `BpActiveStaticDataType` (521 lines, no dedicated test class). Covered indirectly by other tests.
- O12: `PrimaryRenderOptimizer` (no dedicated test). Covered indirectly.

### Critical findings
- No fake defaults in runtime code. All W'/CP at 0/0.
- No active ngrok in readiness path. Only Gate uses ngrok.
- No UI binding gaps in STATS or ACTIVE layouts.
- All WAIT/NO_MODEL/NO_DATA statuses have explicit reasons.
- The single HIGH item is field test (O8) — not a code defect.
