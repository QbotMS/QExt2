# QExt2 Major Implementation Plan

Based on: MAJOR_FEATURES_ROADMAP, RIDE_ASK_PREDESIGN, ARCHITECTURE_CONTRACTS, FEATURE_MAP, OPEN_ITEMS_AUDIT.

Status: DESIGN PHASE ONLY. No runtime implementation authorized without explicit decision.

## Feature Design & Implementation Readiness

| # | feature | design ready | impl ready | prerequisite | backend needed | SDK source needed | field test before impl | risk | recommended phase |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Ride Ask / QBot Advice | YES (PREDESIGN complete) | NO | QBot `/qext/advice` contract confirmed | YES | NO (OnHttpResponse exists) | YES (verify network) | MEDIUM | P0 Backend Contract |
| 2 | AXS Button Intake Actions | NO | NO | SDK audit: AXS button events in SDK 1.1.9 | NO | **YES (UNKNOWN)** | YES (verify AXS events) | MEDIUM | P0 SDK Audit |
| 3 | Fuel & Hydration Coach | PARTIAL | PARTIAL (local models ready) | Intake input path (UI or AXS) | NO (local only) | Only for AXS path | YES | LOW | P1 after intake path |
| 4 | Ride Strategy Engine | YES (data available) | PARTIAL | All models ready (they are) | NO (local only) | NO | YES | LOW | P1 Runtime |
| 5 | Adaptive Active Messages 2.0 | YES (system exists) | PARTIAL | New producers (fuel, gear, crash, QBot) | Only for QBot producer | Existing streams OK | YES | LOW | P1 Runtime |
| 6 | Crash / Incident Detection | PARTIAL | NO | Confidence model + cancel path; sensor audit | NO (local only) | Existing streams OK | YES (safety critical) | HIGH | P0 Safety Design |
| 7 | Gear Intelligence | YES (data exists) | PARTIAL | SHIFTING_GEARS source (Di2/AXS required) | NO (local only) | YES (SHIFTING_GEARS) | YES | LOW | P2 after gear source |
| 8 | Route-Aware Pacing | YES (data exists) | PARTIAL | Route + climbs + field test | NO (local only) | NO (OnNavigationState exists) | YES | LOW | P2 Runtime |
| 9 | Weather-Aware Ride Risk | YES (client exists) | PARTIAL | Weather field/network validation | External (OWM) | NO | YES | MEDIUM | P0 Weather Validation |
| 10 | Post-Ride Learning Loop | PARTIAL | NO | QBot `/ride-report` contract | YES | NO | NO | LOW | P2 Backend Contract |

## Implementation Order (Gated Phases)

### P0 — Design & Backend Contracts (current phase, no runtime code)
| task | detail | action |
|---|---|---|
| Ride Ask contract | Define `POST /qext/advice` request/response with Q | **AWAITING BACKEND DECISION** |
| Crash Detection safety design | Define POSSIBLE_INCIDENT / FALSE_ALARM / cancel path | **AWAITING SENSOR AUDIT** |
| AXS SDK audit | Verify Karoo SDK 1.1.9 exposes AXS button events | **AWAITING SDK VERIFICATION** |
| Weather field validation | Verify WeatherClient fetch works on Karoo with network | **AWAITING NETWORK TEST** |
| Ride Ask mock test | Create JVM mock client + snapshot builder tests | **CAN START NOW** (Phase 0 from PREDESIGN) |

### P0 — Mock / Prototype (JVM only, no Karoo runtime)
| task | detail | action |
|---|---|---|
| Ride Ask snapshot builder | Build + test snapshot JSON from aggregator | **CAN START** |
| Ride Ask mock client | Mock HTTP responses + timeout/error tests | **CAN START** |
| Ride Ask state store | State transitions + TTL tests | **CAN START** |
| Ride Ask renderer | UI binding tests with mock data | **CAN START** |
| Strategy engine prototype | Algorithm prototype with synthetic data | **CAN START** |

### P1 — Runtime Implementation (real code, gated)
| task | prerequisite | action |
|---|---|---|
| Fuel & Hydration Coach alerts | Intake input path confirmed | **AWAITING INTAKE PATH** |
| Ride Strategy Engine | All models confirmed ready | **AWAITING mock validation** |
| Active MSG 2.0 new producers | Producer prototypes tested | **AWAITING producer design** |
| Ride Advice DataType + real client | Backend contract CONFIRMED | **AWAITING BACKEND** |
| AXS Intake Actions | SDK source CONFIRMED | **AWAITING SDK VERIFICATION** |
| Gear Intelligence advisor | SHIFTING source CONFIRMED | **AWAITING GEAR SOURCE** |

### P2 — Field Validation + UX Refinement
| task | prerequisite |
|---|---|
| Field test all new features | Runtime implementation complete |
| Weather risk alerts | Weather fetch verified on Karoo |
| Route-aware pacing | Route data + real ride |
| Post-ride loop | Backend contract + field test |
| Crash detection field test | Safety design + confidence model complete |

## Detailed Gate Decisions

### Ride Ask / QBot Advice Field
```
[ ] QBot backend contract defined POST /qext/advice  ← GATE
[ ] Phase 0 mock tests (snapshot builder, client, state, renderer)  ← CAN START
[ ] Phase 1 local mock client + renderer  ← AFTER MOCK TESTS
[ ] Phase 2 real HTTP client behind config flag  ← AFTER BACKEND CONTRACT
[ ] Phase 3 QBot endpoint integration  ← AFTER CONTRACT + REAL CLIENT
[ ] Phase 4 Karoo static smoke  ← AFTER INTEGRATION
[ ] Phase 5 field validation  ← AFTER STATIC SMOKE
```

### AXS Button Intake Actions
```
[ ] Verify AXS button events in Karoo SDK 1.1.9  ← GATE (CRITICAL)
[ ] If NOT available: mark as SDK_SOURCE_REQUIRED, do not implement
[ ] If available: define button_map_config
[ ] Implement IntakeActionMapper + debounce
[ ] Integrate with AthleteDataStore carb/fluid intake
[ ] Field validation with AXS shifters
```

### Crash / Incident Detection
```
[ ] Safety design: POSSIBLE_INCIDENT / FALSE_ALARM / cancel path  ← GATE (CRITICAL)
[ ] Define thresholds: speed drop rate, motion stop duration, sensor silence window
[ ] Define confidence model
[ ] Define cancel mechanism
[ ] Implement IncidentDetector (JVM prototype first)
[ ] Phase 1: mock validation with replay data
[ ] Phase 2: field test with real ride (no external alerts)
[ ] CRITICAL: never emit external SOS/emergency without confirmed API + user consent
```

### Weather Risk
```
[ ] Verify WeatherClient fetch on Karoo with active network route  ← GATE
[ ] If fetch fails: keep CONFIGURED_WAITING_FOR_FRESH_DATA
[ ] If fetch succeeds: enable weatherFresh flag
[ ] Implement WeatherRiskModel
[ ] Only generate weather alerts when weatherFresh=true
```

## What Must NOT Be Implemented Yet

| what | why |
|---|---|
| Ride Advice DataType / real HTTP client | QBot backend contract not defined |
| AXS intake button actions | SDK source for AXS button events not confirmed |
| Crash detection alerts | Safety design + confidence model + cancel path not done |
| Automatic QBot polling / periodic advice requests | Only manual trigger allowed |
| Weather critical alerts without network validation | Weather fetch not verified on Karoo |
| Strategy engine modifying primary field | Primary field is read-only display |
| Any feature writing to RideState / StatsCalculator | Read-only integration only |
| Any feature with fake defaults or synthesized advice | Hard rule violation |
| Any feature without JVM tests | Hard rule: tests before runtime |
| Layout changes to field_primary_4col.xml | Primary field is stable baseline |
| Post-ride endpoint integration | Backend contract not defined |

## Summary

| state | count | items |
|---|---|---|
| CAN START NOW (mock/test only) | 5 | Ride Ask mock tests (4 modules) + Strategy engine prototype |
| AWAITING BACKEND DECISION | 2 | Ride Ask contract, Post-Ride contract |
| AWAITING SDK VERIFICATION | 1 | AXS button events |
| AWAITING NETWORK TEST | 1 | Weather field validation |
| AWAITING SAFETY DESIGN | 1 | Crash detection |
| NOT YET (depends on above) | 5 | Fuel coach alerts, Active MSG 2.0, AXS intake, Gear, Pacing |

### Next Immediate Step

**Ride Ask Phase 0 mock tests** — implement:
1. `RideAdviceSnapshotBuilder` + tests
2. `RideAdviceClient` (mock only) + tests  
3. `RideAdviceStateStore` + tests
4. `RideAdviceRenderer` + tests

All JVM-only, no Karoo, no HTTP, no QBot. This establishes the contract tests before any backend integration.
