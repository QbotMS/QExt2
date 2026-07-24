# QExt2 Feature Map (from code inventory)

Generated from actual code, not from docs. 41 Kotlin source files, 5 layouts, 25 test classes.

## DataTypes / Extensions

| name | class | layout | DataType ID | tests |
|---|---|---|---|---|
| QExt2 primary | `CompositePrimaryDataType` | `field_primary_4col.xml` | `qext2-primary` | SyntheticVirtualRideScenariosTest, VirtualReplayGateTest |
| STATS | `StatsDataType` | `field_stats_3x3.xml` | `qext2-stats` | StatsSdkFirstGateTest, StatsDataTypeDemoSnapshotTest |
| ACTIVE MSG | `CompositeActiveDataType` | `field_active_4x2.xml` | `qext2-active` | ActiveMessageManagerTest, ClimbAnnouncementProducerTest, SensorMessageProducerTest, BeepCooldownTrackerTest, ActiveClimbResolverTest, NoSdkClimbLogGateTest, ClimbPacingProducerTest |
| BP ActiveStatic | `BpActiveStaticDataType` | `field_active_4x2.xml` | `qext2-active-static` | None |

## Core Engine

| module | file | tests |
|---|---|---|
| RideDataAggregator | `engine/RideDataAggregator.kt` | RideDataAggregatorSdkPolicyTest |
| StatsCalculator | `engine/StatsCalculator.kt` | (covered via policy tests) |
| EtaCalculator | `engine/EtaCalculator.kt` | (covered via policy tests) |
| ReservePolicy | `engine/ReservePolicy.kt` | ReservePolicyTest |
| HrStrainAdvisor | `engine/hrdecoupling/HrStrainAdvisor.kt` | HrStrainAdvisorTest |
| PrimaryRenderOptimizer | `engine/PrimaryRenderOptimizer.kt` | None |

## Core Lab

| module | file |
|---|---|
| RideState | `pl/qbot/karoo/core/RideState.kt` |
| FieldComputers | `pl/qbot/karoo/core/FieldComputers.kt` |
| RideSample | `pl/qbot/karoo/core/RideSample.kt` |
| FieldOutput/Status/Color | `pl/qbot/karoo/core/FieldOutput.kt` |
| LabRideStateRepository | `com/qext2/primary/core/LabRideStateRepository.kt` |

## Policies / Formatters

| module | file | tests |
|---|---|---|
| StatsAdvancedFieldPolicy | `field/StatsAdvancedFieldPolicy.kt` | StatsAdvancedFieldPolicyTest |
| StatsValueFormatter | `field/StatsValueFormatter.kt` | (covered via policy) |
| RsrvDisplayPolicy | `field/RsrvDisplayPolicy.kt` | RsrvDisplayPolicyTest |

## ACTIVE Producers

| module | file | tests |
|---|---|---|
| ActiveMessage | `active/ActiveMessage.kt` | N/A (data class) |
| ActiveMessageManager | `active/ActiveMessageManager.kt` | ActiveMessageManagerTest |
| ActiveMessageRenderer | `active/ActiveMessageRenderer.kt` | None standalone |
| ClimbAnnouncementProducer | `active/ClimbAnnouncementProducer.kt` | ClimbAnnouncementProducerTest |
| ClimbPacingProducer | `active/ClimbPacingProducer.kt` | ClimbPacingProducerTest |
| SensorMessageProducer | `active/SensorMessageProducer.kt` | SensorMessageProducerTest |
| WeatherMessageProducer | `active/WeatherMessageProducer.kt` | WeatherClientTest |
| ActiveClimbResolver | `active/ActiveClimbResolver.kt` | ActiveClimbResolverTest |
| BeepCooldownTracker | `active/BeepCooldownTracker.kt` | BeepCooldownTrackerTest |
| NoSdkClimbLogGate | `active/NoSdkClimbLogGate.kt` | NoSdkClimbLogGateTest |

## Data / Store

| module | file |
|---|---|
| AthleteData + AthleteDataStore | `data/AthleteDataStore.kt` |

## Snapshot Models

| module | file |
|---|---|
| StatsRideSnapshot | `model/StatsRideSnapshot.kt` |
| PrimaryRideSnapshot | `model/PrimaryRideSnapshot.kt` |

## Weather

| module | file | tests |
|---|---|---|
| WeatherClient | `weather/WeatherClient.kt` | WeatherClientTest |

## Gate

| module | file | tests |
|---|---|---|
| GateOpenClient | `gate/GateOpenClient.kt` | GateOpenClientTest |
| GateUiStateExpiry | `data/GateUiStateExpiryTest` | GateUiStateExpiryTest |

## Setup / Actions

| module | file |
|---|---|
| SetupActivity | `setup/SetupActivity.kt` |
| StatsActionReceiver | `actions/StatsActionReceiver.kt` |

## Debug Config

| module | file |
|---|---|
| QExt2DebugConfig | `util/QExt2DebugConfig.kt` |

## Layouts

| layout | bound by | status |
|---|---|---|
| `field_primary_4col.xml` | `CompositePrimaryDataType` | ACTIVE |
| `field_primary_fallback.xml` | NOT REFERENCED IN CODE | DEAD LAYOUT |
| `field_stats_3x3.xml` | `StatsDataType` | ACTIVE |
| `field_active_4x2.xml` | `CompositeActiveDataType`, `BpActiveStaticDataType` | ACTIVE |
| `activity_setup.xml` | `SetupActivity` | ACTIVE |

## Externally facing URLs (in BuildConfig / code)

| URL | config key | default value | active | notes |
|---|---|---|---|---|
| Readiness | `QEXT_READINESS_URL` | `qbot.cytr.us/ride-readiness` | YES | Verified on Karoo |
| Gate | `QEXT_GATE_URL` | `ngrok-free.dev/gate/open` | YES | Still uses ngrok |
| Weather | `OPENWEATHER_BASE_URL` | `api.openweathermap.org/data/2.5/weather` | YES | Conditional on API key |
| SSE | N/A | `qbot.cytr.us/sse` | NO | Not configured, not compatible |
