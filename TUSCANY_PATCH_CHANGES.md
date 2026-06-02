# QExt2 Tuscany Safe Patch — Raport zmian
**Branch:** `tuscany-full-safe-patch`  
**Commity:** `825696d` → `4ada01e`  
**Data:** 2026-06-02

---

## P0 — Wszystkie zmiany

### 1. Build marker
**Plik:** `app/build.gradle.kts:38`
```diff
- versionName = "0.1.${versionCode}"
+ versionName = "0.2.0-tuscany"
```

### 2. WeatherMessageProducer — hazard-only
**Plik:** `WeatherMessageProducer.kt` (przebudowany)

Stara logika (4 alerty: deszcz, wiatr, upał, mróz) zastąpiona nową (7 alertów, priorytetyzacja):

| Alert | Warunek | Priorytet | Cooldown | Expiry |
|-------|---------|-----------|----------|--------|
| WX BURZA | condition zawiera thunder/storm/burza | CRITICAL | 10 min | 12s |
| WX ULEWA | rain1hMm ≥ 2.0 | CRITICAL | 10 min | 12s |
| WX ZIMNO+MOKRO | rain ≥ 0.5 && temp ≤ 8°C | WARNING | 15 min | 10s |
| WX DESZCZ | rain1hMm ≥ 0.5 | WARNING | 10 min | 10s |
| WX UPAL | temp ≥ 35°C | WARNING | 15 min | 10s |
| WX MROZ | temp ≤ 0°C | WARNING | 15 min | 10s |
| WX SILNY WIATR | wind ≥ 12 m/s | WARNING | 10 min | 10s |

Kluczowe: `windSpeedMps` to WX/forecast context — NIE live wind. Live wind = `karoo-headwind` extension.

### 3. DTD color fix
**Pliki:** `CompositeActiveDataType.kt:586-590`, `BpActiveStaticDataType.kt:331-335`

```diff
- etaMs <= deadlineMs * 0.85 -> GREEN    (BŁĄD: deadlineMs to timestamp!)
+ deadlineMs - etaMs >= 30min -> GREEN   (margines >30 min)
+ deadlineMs - etaMs <= 10min -> AMBER   (margines ≤10 min)
+ etaMs > deadlineMs -> RED              (spóźnienie)
```

### 4. Overlay kalibracji — łatwiejsze usuwanie
**Plik:** `CompositeActiveDataType.kt:149-158, 529-531`

Zmiany:
- Expiry: `Long.MAX_VALUE` → `createdAt + 120_000L` (2 minuty max)
- Usuwanie: `speed > 5.0` → `speed > 2.0 LUB cadence>0+fresh LUB power>0+fresh`

### 5. Grade EMA smoothing
**Plik:** `RideDataAggregator.kt:59, 498-503`

```kotlin
// Nowe pole
private val gradeFilterInitializedRef = AtomicReference(false)

// EMA filter: alpha = 0.10
val filtered = if (gradeFilterInitializedRef.get()) {
    filteredGradeRef.get() + 0.10 * (v - filteredGradeRef.get())
} else {
    gradeFilterInitializedRef.set(true)
    v
}
```

Alpha=0.10 → ~10 próbek (10s) do 65% wartości, ~20s do 90%. Wygładza szum GPS bez opóźniania realnych zmian.

### 6. Power color — FieldOutput first
**Plik:** `RideDataAggregator.kt:754`

```diff
- powerColor = computePowerColor(powerRef.get(), statsCalc.ftpWatts)
+ powerColor = powerOut?.color?.toAndroidColor() ?: computePowerColor(...)
```

Kolor z modelu LabRideState ma priorytet. Fallback do computePowerColor (strefy %FTP).

### 7. W'bal — zatrzymanie przy dropout mocy
**Pliki:** `StatsCalculator.kt:84, 112`, `RideDataAggregator.kt:771-773`

```kotlin
// Nowy parametr opcjonalny
fun update(..., powerFresh: Boolean = true)

// W'bal tylko gdy świeża moc
if (powerFresh) updateWBalance(powerWatts)

// Wywołanie z agregatora
val powerFresh = now - powerFreshnessRef.get() < 8_000L
statsCalc.update(powerWatts, hr, movingElapsedSec, elapsedSec, powerFresh = powerFresh)
```

### 8. PendingIntent — stałe request codes
**Plik:** `StatsDataType.kt:73-83, 170-173`

```diff
- requestCode: (carbClickId % 10000).toInt()
+ requestCode: REQ_CARB  (= 1001)
+ requestCode: REQ_GATE  (= 1002)
```

---

## Testy
- Wszystkie 223 testy zielone
- Zaktualizowane: WeatherClientTest (WX* tytuły), StatsAdvancedFieldPolicyTest (6.5%), StatsSdkFirstGateTest (4.2%)

## Build
- ✅ `assembleDebug` PASS
- ✅ `testDebugUnitTest` PASS
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Ryzyka
- Weather alerty: nieprzetestowane z live OWM + Headwind
- Grade EMA: alpha=0.10 to konserwatywny smoothing — może być za wolny na stromych rampach
- DTD color: wymaga aktywnej trasy z ETA
- W'bal freeze: nie testowany z realnym dropoutem sensora

## Rekomendacja
✅ **TAK** — zainstaluj przed Toskanią.
