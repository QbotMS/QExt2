# QExt2 Project Audit — 2026-05-23

## 1) Project Overview

| Property | Value |
|---|---|
| Extension ID | `qext2` |
| Namespace | `com.qext2.primary` |
| Karoo SDK | `io.hammerhead:karoo-ext:1.1.8` |
| Kotlin | 2.2.20 |
| AGP | 8.13.2 |
| Compile SDK | 35 |
| Min SDK | 23 |
| Target SDK | 35 |
| Build | PASS (37 actionable tasks, 0 failures) |
| Unit Tests | PASS (43 actionable tasks, 0 failures) |

## 2) All Kotlin/Java Files

### Main Sources (18 files)

| # | File | Lines | Package |
|---|---|---|---|
| 1 | `app/src/main/kotlin/com/qext2/primary/QExt2PrimaryExtension.kt` | 222 | Main extension service |
| 2 | `app/src/main/kotlin/com/qext2/primary/actions/StatsActionReceiver.kt` | 86 | BroadcastReceiver for CARB/GATE/UNDO |
| 3 | `app/src/main/kotlin/com/qext2/primary/datatypes/CompositePrimaryDataType.kt` | 160 | DataType: PRIMARY 4-column HUD |
| 4 | `app/src/main/kotlin/com/qext2/primary/datatypes/CompositeActiveDataType.kt` | 560 | DataType: ACTIVE 4x2 HUD |
| 5 | `app/src/main/kotlin/com/qext2/primary/datatypes/StatsDataType.kt` | 241 | DataType: STATS 3x3 grid |
| 6 | `app/src/main/kotlin/com/qext2/primary/engine/RideDataAggregator.kt` | 723 | Core data pipeline, 1s loop |
| 7 | `app/src/main/kotlin/com/qext2/primary/engine/PrimaryRenderOptimizer.kt` | 164 | PRIMARY render dedupe/throttle |
| 8 | `app/src/main/kotlin/com/qext2/primary/engine/StatsCalculator.kt` | 365 | NP/IF/VI/TSS/W'/CARB/HRD math |
| 9 | `app/src/main/kotlin/com/qext2/primary/engine/EtaCalculator.kt` | 62 | ETA / deadline speed |
| 10 | `app/src/main/kotlin/com/qext2/primary/engine/hrdecoupling/HrStrainAdvisor.kt` | 204 | HR strain assessment |
| 11 | `app/src/main/kotlin/com/qext2/primary/engine/hrdecoupling/HrDecouplingBuffer.kt` | 25 | Ring buffer (2400 samples) |
| 12 | `app/src/main/kotlin/com/qext2/primary/engine/hrdecoupling/HrSample.kt` | 10 | Data class: HR sample |
| 13 | `app/src/main/kotlin/com/qext2/primary/gate/GateOpenClient.kt` | 134 | GATE HTTP client (ngrok) |
| 14 | `app/src/main/kotlin/com/qext2/primary/data/AthleteDataStore.kt` | 294 | SharedPreferences persistence |
| 15 | `app/src/main/kotlin/com/qext2/primary/model/PrimaryRideSnapshot.kt` | 182 | PRIMARY data model + colors |
| 16 | `app/src/main/kotlin/com/qext2/primary/model/StatsRideSnapshot.kt` | 38 | STATS data model |
| 17 | `app/src/main/kotlin/com/qext2/primary/field/StatsValueFormatter.kt` | 115 | Value formatting for STATS |
| 18 | `app/src/main/kotlin/com/qext2/primary/setup/SetupActivity.kt` | 216 | Settings UI (CARB+deadline) |

### Test Sources (2 files)

| # | File | Lines |
|---|---|---|
| T1 | `app/src/test/kotlin/com/qext2/primary/gate/GateOpenClientTest.kt` | 105 |
| T2 | `app/src/test/kotlin/com/qext2/primary/engine/hrdecoupling/HrStrainAdvisorTest.kt` | 133 |

## 3) XML Layout/Resource Files

### Layouts (4 files)

| File | Purpose |
|---|---|
| `app/src/main/res/layout/field_primary_4col.xml` | PRIMARY HUD (HR/CAD/PWR/SPD/GEAR/GRADE) |
| `app/src/main/res/layout/field_active_4x2.xml` | ACTIVE HUD (D/IF10/—/W' + DTD/Vsr/T/WIND) |
| `app/src/main/res/layout/field_stats_3x3.xml` | STATS grid (CARB/GATE buttons + NP/IF/VI/TSS/RSRV/ETA/CARB/FLUID/KCAL/UP/LEFT/SUNSET/BAT/h) |
| `app/src/main/res/layout/field_primary_fallback.xml` | Fallback view ("QExt2" text) — **UNUSED** in any DataType |

### Config/Manifest (3 files)

| File | Purpose |
|---|---|
| `app/src/main/AndroidManifest.xml` | Service + Activity + Receiver registration |
| `app/src/main/res/xml/extension_info.xml` | 3 DataTypes registered: qext2-primary, qext2-active, qext2-stats |
| `app/src/main/res/xml/network_security_config.xml` | HTTPS-only, system+user CAs |

### Resources (1 strings + 9 drawables)

| File | Purpose |
|---|---|
| `app/src/main/res/values/strings.xml` | 9 strings (names + descriptions) |
| `app/src/main/res/drawable/ic_qext2_hr.xml` | HR icon |
| `app/src/main/res/drawable/ic_qext2_cadence.xml` | Cadence icon |
| `app/src/main/res/drawable/ic_qext2_power.xml` | Power icon (also app icon) |
| `app/src/main/res/drawable/ic_qext2_speed.xml` | Speed icon |
| `app/src/main/res/drawable/ic_qext2_gear.xml` | Gear icon |
| `app/src/main/res/drawable/ic_qext2_grade.xml` | Grade icon |
| `app/src/main/res/drawable/ic_qext2_distance.xml` | Distance icon |
| `app/src/main/res/drawable/ic_qext2_dtd.xml` | DTD icon |
| `app/src/main/res/drawable/btn_sync.xml` | Sync button drawable |

### Fonts (2 files — CORRUPTED)

| File | Size | MIME | Status |
|---|---|---|---|
| `app/src/main/res/font/roboto_condensed_medium.ttf` | 306,727 B | `text/html` | **CORRUPTED** |
| `app/src/main/res/font/roboto_condensed_regular.ttf` | 306,740 B | `text/html` | **CORRUPTED** |

## 4) Registered DataTypes

From `app/src/main/res/xml/extension_info.xml`:

| typeId | Display Name | Class | Layout |
|---|---|---|---|
| `qext2-primary` | QExt2 Primary | `CompositePrimaryDataType` | `field_primary_4col.xml` |
| `qext2-active` | QExt2 Active | `CompositeActiveDataType` | `field_active_4x2.xml` |
| `qext2-stats` | QExt2 STATS | `StatsDataType` | `field_stats_3x3.xml` |

All three are registered as `graphical="true"` in the extension info.

The Karoo extension service class `QExt2PrimaryExtension` exposes them via:
```kotlin
override val types: List<DataTypeImpl> = listOf(
    CompositePrimaryDataType(),
    CompositeActiveDataType(),
    StatsDataType()
)
```

## 5) Action Receivers

**One BroadcastReceiver registered** in `AndroidManifest.xml`:
```xml
<receiver android:name=".actions.StatsActionReceiver" android:exported="false" />
```

| Action String | Constant | Behavior |
|---|---|---|
| `com.qext2.primary.action.CARB_ADD` | `ACTION_CARB_ADD` | Add carb packet (debounced 700ms, idempotent via clickId) |
| `com.qext2.primary.action.CARB_UNDO` | `ACTION_CARB_UNDO` | Undo last carb packet (subtract from total) |
| `com.qext2.primary.action.GATE_TAP` | `ACTION_GATE_TAP` | Trigger GATE HTTP call (`goAsync()`) |

The CARB/GATE `PendingIntent`s are created in `StatsDataType.bind()` with request codes 301 (CARB) and 302 (GATE). The GATE button in the 3x3 grid is now wired to UNDO action (requestCode 302 → ACTION_CARB_UNDO).

## 6) Current Gate Open Implementation

### Architecture
```
StatsActionReceiver (ACTION_GATE_TAP)
  → QExt2PrimaryExtension.instance?.karooSystem (get SDK handle)
  → GateOpenClient(KarooSdkHttpCaller(system))
    → SdkHttpCaller (fun interface)
    → KarooSdkHttpCaller (implements via KarooSystemService.addConsumer<OnHttpResponse>)
```

### GateOpenClient (`app/src/main/kotlin/com/qext2/primary/gate/GateOpenClient.kt`)

- **Constructor injects**: `SdkHttpCaller`, `gateUrlProvider`, `gateTokenProvider`, `logger`
- **URL**: `BuildConfig.QEXT_GATE_URL` — defaults to `https://ankle-wool-undusted.ngrok-free.dev/gate/open`
- **Token**: `BuildConfig.QEXT_GATE_TOKEN` — from `local.properties` (hardcoded!)
- **Token passed as**: query param `?token=...` AND header `X-Gate-Token`
- **Additional header**: `ngrok-skip-browser-warning: true`
- **Debounce**: 15,000ms (15s) client-side — requests faster than this return `RateLimited`
- **Result mapping**:
  - HTTP 200 → `GateResult.Ok`
  - HTTP 403 → `GateResult.Forbidden`
  - HTTP 429 → `GateResult.RateLimited`
  - Other/error → `GateResult.Error`
- **UI feedback**: Stores state to `AthleteDataStore.saveGateUiState()` which `StatsDataType` reads:
  - `"FURTKA..."` — pending
  - `"FURTKA OK"` — success
  - `"FURTKA FAIL"` — forbidden/error
  - `"FURTKA WAIT"` — rate limited
  - `"GATE"` — idle (reset after 3s via `Handler.postDelayed`)
- **goAsync()**: Used in `StatsActionReceiver` to prevent broadcast timeout during HTTP call
- **Missing token → Forbidden** without making HTTP call

### Tests

`GateOpenClientTest.kt` (105 lines, 6 tests):
- `maps200ToOk`
- `maps403ToForbidden`
- `maps429ToRateLimited`
- `mapsSdkErrorToError`
- `debounceBlocksTooFrequentRequest`
- `missingTokenReturnsForbidden`

All use `FakeSdkHttpCaller` that synchronously returns the specified code or error.

## 7) Current Rate Limit Implementations

### Level 1: PrimaryRenderOptimizer (PRIMARY rendering throttle)
- **File**: `app/src/main/kotlin/com/qext2/primary/engine/PrimaryRenderOptimizer.kt`
- **Moving**: min 300ms between renders
- **Stationary**: min 500ms between renders
- **Deduplication**: skip if render signature (text+color) unchanged
- **Fast-path bypass**: NO↔value transitions, color changes, view switch (power/speed length bins)
- **Feature flag**: `enabled = true` (set to `false` for rollback)
- **Metrics logging**: every 60s to logcat, also optional CSV to `<files-dir>/qext2/primary_render_metrics.csv`

### Level 2: GateOpenClient debounce
- **File**: `app/src/main/kotlin/com/qext2/primary/gate/GateOpenClient.kt:125`
- **`DEBOUNCE_MS = 15_000L`** — 15 seconds between GATE requests
- Last request timestamp stored in `AthleteDataStore` (`gate_last_request_ms`)

### Level 3: CARB button debounce
- **File**: `app/src/main/kotlin/com/qext2/primary/actions/StatsActionReceiver.kt:16`
- **`CARB_DEBOUNCE_MS = 700L`** — 700ms between CARB taps
- Last tap timestamp stored in `AthleteDataStore` (`carb_last_tap_ms`)

### Level 4: CARB idempotence
- **File**: `app/src/main/kotlin/com/qext2/primary/actions/StatsActionReceiver.kt:31-36`
- **`clickId`** mechanism: each `bind()` call generates a unique `System.currentTimeMillis()` ID
- Duplicate PendingIntent replays with same clickId are rejected

## 8) Font Verification — roboto_condensed_medium.ttf

### VERDICT: CORRUPTED — NOT A VALID FONT FILE

| Check | Result |
|---|---|
| `file` command | `HTML document text, Unicode text, UTF-8 text` |
| Magic bytes (hex) | `0a0a 0a0a 0a0a 0a0a 3c21 444f 4354 5950` |
| Decoded magic | `<!DOCTYPE html>` |
| Expected TTF magic | `00 01 00 00` (TrueType) or `4F 54 54 4F` (OpenType) |
| File size | 306,727 bytes (HTML content, not font data) |

The file `app/src/main/res/font/roboto_condensed_medium.ttf` starts with `<!DOCTYPE html>` followed by a complete HTML document. It is **not** a TrueType or OpenType font file. Both font files in the directory are identically corrupted:
- `roboto_condensed_medium.ttf` — HTML (306,727 B)
- `roboto_condensed_regular.ttf` — HTML (306,740 B)

**Neither font is referenced anywhere** in the codebase. All layouts use `android:fontFamily="monospace"` (system font). The font files are unused dead weight.

## 9) Security Issues

| Severity | Finding | Location |
|---|---|---|
| **HIGH** | Hardcoded `QEXT_GATE_TOKEN` in local.properties | `/Users/MichalSta/Downloads/QExt2/local.properties:4` |
| **HIGH** | Hardcoded ngrok URL in QExt2PrimaryExtension | `app/src/main/kotlin/com/qext2/primary/QExt2PrimaryExtension.kt:98` |
| **HIGH** | `settings.gradle.kts` references `gpr.key` / `TOKEN` env var for GitHub Packages auth | `settings.gradle.kts:18` |
| **MEDIUM** | `BuildConfig.QEXT_GATE_TOKEN` injected at build time from `local.properties` (potentially committed) | `app/build.gradle.kts:18` |

## 10) Dead/Unused Code

| Finding | File |
|---|---|
| `field_primary_fallback.xml` not referenced by any DataType | `app/src/main/res/layout/field_primary_fallback.xml` |
| Two corrupted font files (306KB each) not referenced | `app/src/main/res/font/roboto_condensed_*.ttf` |
| `demoSnapshot()` method defined but not called | `StatsDataType.kt:140` |
| `bindUnit()` method defined but not called | `StatsDataType.kt:185` |
| `GATE_TAP` action in companion still defined but GATE button now wired to UNDO | `StatsActionReceiver.kt:83` |

## 11) Test Build Results

```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL (37 tasks)
./gradlew :app:testDebugUnitTest → BUILD SUCCESSFUL (43 tasks)
./install-karoo.command → SUCCESS (Karoo 00447GA253070066)
```

- Unit tests: 19 tests total (6 GateOpenClient + 13 HrStrainAdvisor), all passing
- No integration/UI tests present
