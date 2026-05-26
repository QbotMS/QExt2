# QExt2 — Specyfikacja 2026-05-22

## Architektura ogólna

```
Karoo SDK → streamy (HEART_RATE, POWER, SPEED, ...)
    ↓
RideDataAggregator (pętla 1 Hz)
    ├── PrimaryRideSnapshot → CompositePrimaryDataType (pole graficzne PRIMARY)
    ├── StatsRideSnapshot  → StatsDataType (pole graficzne STATS)
    └── bezpośrednie streamy → CompositeActiveDataType (pole graficzne ACTIVE)
```

### Procesy
- Extension service (`QExt2PrimaryExtension`) i DataType (`CompositePrimaryDataType`, `CompositeActiveDataType`, `StatsDataType`) — **ten sam proces**
- SetupActivity — **inny proces** → komunikacja przez `SharedPreferences` (`AthleteDataStore`)

---

## 1. PRIMARY (`qext2-primary`)

### Układ: 4 kolumny poziome
```
┌──────────┬──────────┬──────────┬──────────┐
│    HR    │  POWER   │  SPEED   │  GEAR    │
│   135    │   218    │   28.5   │  38×21   │
│ CADENCE  │          │          │  GRADE   │
│    72    │          │          │   +5%    │
└──────────┴──────────┴──────────┴──────────┘
```
Tło: `#0D1424`. Kolumny: `#111827`. Font: monospace. Kolumny oddzielone 1dp `#0D1424`.

### HR (kol 1, góra)
- **Wartość:** BPM. Gdy HR zone mode w SETUP: `Z1`–`Z5`.
- **Ikona:** ♥ (7sp, `#9CA3AF`)
- **Kolor:** z `HrStrainAdvisor` (decoupling)
  - `#FFFFFF` — NEUTRAL, brak danych, <15 min jazdy
  - `#22C55E` — GOOD (Z2 60-75% maxHR + decoupling ≤3%)
  - `#F97316` — WARN (Z4 85-95% lub decoupling 6-10%)
  - `#EF4444` — BAD (Z5 >95% lub decoupling >10%)
- **Histereza:** 30s. Aktywacja: 15 min (częściowa), 30 min (pełna).
- **Rozmiar:** 24sp, right-aligned. Font: monospace.

### Cadence (kol 1, dół)
- **Wartość:** RPM
- **Ikona:** ↻ (7sp, `#9CA3AF`)
- **Kolor:**
  - `#FFFFFF` — rpm=0, zjazd (<-2%), powyżej targetu
  - `#22C55E` — w target: 60-70 (płasko), 55-65 (podjazd >4%)
  - `#F97316` — lekko poniżej: 0-5 rpm pod target
  - `#EF4444` — znacznie poniżej: >5 rpm pod target
- **Rozmiar:** 24sp. Font: monospace.

### Power (kol 2, cała wysokość)
- **Wartość:** Waty (3s)
- **Ikona:** ⚡ + "POWER" (9sp, `#9CA3AF`)
- **Kolor:** `adjFtp = ftp × todayFactor`
  - `#FFFFFF` — poniżej targetu, brak danych
  - `#22C55E` — w target: 75-87% płasko, 80-105% krótki podjazd (>3%, ≤500m ascentLeft), 55-75% długi podjazd
  - `#F97316` — powyżej targetu
  - `#EF4444` — >120% targetu
- **Rozmiar:** 42sp (3 cyfry), 36sp (4 cyfry), 30sp (5 cyfr). Font: monospace.

### Speed (kol 3, cała wysokość)
- **Wartość:** km/h (1 miejsce po przecinku)
- **Ikona:** ● + "SPEED" (9sp, `#9CA3AF`)
- **Kolor:**
  - `#FFFFFF` — norma, brak danych, <1 km/h
  - `#22C55E` — >115% średniej netto (`dystans / elapsedSec`)
  - `#EF4444` — <85% średniej netto
- **Rozmiar:** 38sp (4 cyfry), 32sp (5 cyfr), 26sp (6 cyfr). Font: monospace.

### Gear (kol 4, góra)
- **Wartość:** `przód×tył` (np. `38×21`) lub `NO`
- **Ikona:** ⚙ (7sp, `#9CA3AF`)
- **Kolor:** histereza 30s
  - `#FFFFFF` — OK, brak danych
  - `#22C55E` — sweet spot: cad 60-75, power 75-87% adjFTP, płasko ±5%
  - `#F97316` — za twardo (cad<55, power≥75%, grade≥2%) lub za lekko (cad≥90, power≤50%)
  - `#EF4444` — mielenie: cad≤50, power≥110%, grade≥5%
- **Rozmiar:** przód 16sp, tył 24sp. Font: monospace.

### Grade (kol 4, dół)
- **Wartość:** `+X%` lub `-X%`
- **Ikona:** ▲ (7sp, `#9CA3AF`)
- **Kolor:**
  - `#FFFFFF` — brak danych
  - `#22C55E` — płasko (-2% do +2%)
  - `#F97316` — stromo (-9%/-5% lub +5%/+9%)
  - `#EF4444` — bardzo stromo (<-9% lub >+9%)
- **Rozmiar:** 24sp. Font: monospace. Jednostka `%` 16sp.

---

## 2. ACTIVE (`qext2-active`)

### Układ: 4 kolumny × 2 wiersze
```
┌──────────┬──────────┬──────────┬──────────┐
│  D  45.2 │ DTD 12.3 │ IF10 .88 │ WIND →12 │
│          │          │          │    m/s   │
├──────────┼──────────┼──────────┼──────────┤
│  V  28.5 │ TEMP 22° │ W'BAL 85 │    ""    │
└──────────┴──────────┴──────────┴──────────┘
```
Tło: `#0D1424`. Kolumny: `#111827`.

### D — dystans (wiersz 1, kol 1)
- **Wartość:** km (`.1f` gdy <100, `.0f` gdy ≥100)
- **Źródło:** `DISTANCE` stream
- **Kolor:** zawsze biały

### DTD — distance to destination (wiersz 1, kol 2)
- **Wartość:** km (format jw.) lub `--`
- **Źródło:** `DISTANCE_TO_DESTINATION` stream
- **Kolor:**
  - `#FFFFFF` — brak trasy
  - `#22C55E` — ETA ≤ deadline × 0.85 (margines >15%)
  - `#EF4444` — ETA > deadline
- ETA: `EtaCalculator.calculateEtaMs()`. Deadline: `resolveDeadlineMs()`.

### IF10 — intensity factor 10-min (wiersz 1, kol 3)
- **Wartość:** `0.XX`
- **Źródło:** własny `IF10Calculator` (30s rolling NP / FTP)
- **Kolor:** zawsze biały

### WIND (wiersz 1, kol 4)
- **Wartość:** liczba całkowita m/s + strzałka kierunku (lewo)
- **Jednostka:** `m` nad `s` pionowo, prawo od wartości
- **Źródło:** `karoo-headwind` streamy: headwind, headwindSpeed, headwindDirection, windDirection, windSpeed
- **Kolor:** biały. Strzałka biała.

### V — prędkość średnia (wiersz 2, kol 1)
- **Wartość:** `X.X` km/h
- **Źródło:** `AVERAGE_SPEED` stream
- **Kolor:** zawsze biały

### TEMP — temperatura (wiersz 2, kol 2)
- **Wartość:** `X°C`, wyrównane do prawej
- **Źródło:** `karoo-headwind temperature` + `TEMPERATURE` stream
- **Kolor:** zawsze biały

### W'BAL — W prime balance (wiersz 2, kol 3)
- **Wartość:** `XX` + `%`
- **Źródło:** `WPrimeCalculator` — model wykładniczy: tau=546, depletion od `ltpWatts`, recovery exp(-1/tau)
- **todayFactor:** start i cap od `todayFactor × wPrimeMax`. Wyświetlacz zawsze 100% z mniejszej bazy.
- **Kolor (trend):**
  - `#FFFFFF` — stable
  - `#22C55E` — rising
  - `#EF4444` — falling, plummeting
- Trend z ostatnich 3 próbek % na minutę.

### NULL (wiersz 2, kol 4)
- **Wartość:** `""` (puste)
- Nie używane.

---

## 3. STATS (`qext2-stats`)

### Układ: 3 kolumny × 5 wierszy (14 aktywnych + 4 puste)
```
┌──────────┬──────────┬──────────┐
│    NP    │    IF    │    VI    │
│   238    │   0.88   │   1.04   │
├──────────┼──────────┼──────────┤
│   TSS    │   RSRV   │   ETA    │
│   145    │    62%   │  18:45   │
├──────────┼──────────┼──────────┤
│   CARB   │   FLUID  │   KCAL   │
│    75g   │   0.7L   │   1230   │
├──────────┼──────────┼──────────┤
│    UP    │   LEFT   │ SUNSET   │
│   850m   │  1200m   │  20:47   │
├──────────┼──────────┼──────────┤
│  BAT/h   │   LEFT   │          │
│  8.2%/h  │   5:42   │          │
└──────────┴──────────┴──────────┘
```
Tło: `#111315`. Nagłówki: 9sp bold `#9CA3AF`. Wartości: 20sp bold `#FFFFFF`.

### Rząd 1: NP, IF, VI

| Pole | Wartość | Źródło | Kolor |
|---|---|---|---|
| NP | Waty | SDK: `NORMALIZED_POWER` | Biały |
| IF | 0.XX | SDK: `INTENSITY_FACTOR` | Biały |
| VI | 0.XX | SDK: `VARIABILITY_INDEX` | `<1.05` biały, `<1.10` `#F59E0B`, ≥1.10 `#EF4444` |

### Rząd 2: TSS, RSRV, ETA

| Pole | Wartość | Źródło | Kolor |
|---|---|---|---|
| TSS | Całkowite | SDK: `TRAINING_STRESS_SCORE` | Biały |
| RSRV | Procent | Własny model: `todayFactor×100 − √TSS×5 − (IF-0.80)×100 − timePenalty − decPenalty + expRecovery` | ≥40 `#22C55E`, ≥20 `#F59E0B`, <20 `#EF4444` |
| ETA | HH:MM | `EtaCalculator.calculateEtaMs()` | Biały |

### Rząd 3: CARB, FLUID, KCAL

| Pole | Wartość | Źródło | Kolor |
|---|---|---|---|
| CARB | Gramy | `StatsCalculator.carbsGPerH()` z `adjIf` | Biały |
| FLUID | Litry (1 dec) | `StatsCalculator.fluidLPerH()` z `adjIf` | Biały |
| KCAL | Całkowite | SDK: `CALORIES` | Biały |

### Rząd 4: UP, LEFT, SUNSET

| Pole | Wartość | Źródło | Kolor |
|---|---|---|---|
| UP | Metry | SDK: `ELEVATION_GAIN` | Biały |
| LEFT | Metry | SDK: `DISTANCE_TO_DESTINATION` + `FIELD_ASCENT_REMAINING_ID` | Biały |
| SUNSET | HH:MM | SDK: `CIVIL_DUSK` (fallback: API `sunsetTimestampMs`) | Biały |

### Rząd 5: BAT/h, LEFT

| Pole | Wartość | Źródło | Kolor |
|---|---|---|---|
| BAT/h | %/h lub `CHG` | `StatsCalculator.batteryDrainPctPerHour()` z pollingu `ACTION_BATTERY_CHANGED` | Biały |
| LEFT | H:MM lub `CHG` | `(batteryPct/drainPerHour)×3600` | Biały |

### Pola wyzerowane (zawsze `--`): HRD, DEC, WBAL, TREND, BAT%, DLTAV, RD

---

## 4. Źródła danych — pełna lista

### SDK (natywne streamy Karoo)
| Stream | Używany przez | Uwagi |
|---|---|---|
| `HEART_RATE` | PRIMARY HR, HrStrainAdvisor | |
| `CADENCE` | PRIMARY Cadence, HrStrainAdvisor | |
| `POWER` | PRIMARY Power, ACTIVE W'BAL, HrStrainAdvisor | często 0.0 na Karoo |
| `SMOOTHED_3S_AVERAGE_POWER` | PRIMARY Power | fallback dla POWER |
| `SPEED` | PRIMARY Speed, HrStrainAdvisor | m/s ×3.6 → km/h |
| `ELEVATION_GRADE` | PRIMARY Grade, Cadence | low-pass 0.7/0.3 + deadband <0.8% |
| `SHIFTING_GEARS` | PRIMARY Gear | front/rear teeth + battery fallback |
| `ELAPSED_TIME` | PRIMARY Speed avg, STATS | z auto-pauzą ≈ moving time |
| `DISTANCE` | PRIMARY Speed avg, ACTIVE D, ETA, STATS | |
| `DISTANCE_TO_DESTINATION` | ACTIVE DTD, STATS LEFT, ETA | + `FIELD_ASCENT_REMAINING_ID` |
| `ELEVATION_GAIN` | STATS UP | |
| `TEMPERATURE` | ACTIVE TEMP, CARB/FLUID | |
| `AVERAGE_SPEED` | ACTIVE V | |
| `NORMALIZED_POWER` | STATS NP | od SDK |
| `INTENSITY_FACTOR` | STATS IF | od SDK |
| `VARIABILITY_INDEX` | STATS VI | od SDK |
| `TRAINING_STRESS_SCORE` | STATS TSS | od SDK |
| `CALORIES` | STATS KCAL | od SDK |
| `CIVIL_DUSK` | STATS SUNSET, deadline | od SDK |
| `karoo-headwind:*` | ACTIVE WIND, TEMP | headwind, headwindSpeed, headwindDirection, windDirection, windSpeed; temperature |

### API (Qbot `ride-readiness`)
| Pole | Używane przez |
|---|---|
| `todayFactor` | POWER target, RSRV, GEAR, W'BAL, CARB/FLUID (adjIf) |
| `ftpWatts` | POWER target, RSRV, GEAR, W'BAL |
| `ltpWatts` | W'BAL deplecja |
| `wPrimeKj` | W'BAL pojemność |
| `maxHr` / `MaxHRBPM` | HR decoupling, HR zones |
| `bodyWeightKg` | CARB |
| `humidityPercent` | FLUID |
| `baroMultiplier` | todayFactor adjustment |
| `sunsetTimestampMs` | deadline fallback |
| `ctl` / `atl` | diagnostyczne |
| `hrvToday` / `hrvBaseline30d` / `hrvDeviation30d` | SETUP display |
| `sleepTodayH` / `sleepBaseline30d` / `sleepDev` | SETUP display |
| `pressureHpa` / `pressureChange24h` / `pressureDeficit` | SETUP display, baro adjustment |

### System
| Źródło | Używane przez |
|---|---|
| `ACTION_BATTERY_CHANGED` (polling 30s) | STATS BAT/h, LEFT |
| `System.currentTimeMillis()` | timestampy, ride elapsed |

---

## 5. Modele i algorytmy — PEŁNE WZORY

### TodayFactor
```
todayFactor_raw  = z API ride-readiness (0.50–1.10)
baroMultiplier   = z API (0.80–1.00)
todayFactor      = todayFactor_raw × baroMultiplier    (gdy baroSensitive = true)
todayFactor      = clamp(todayFactor, 0.70, 1.10)
```
- NIE jest liczony lokalnie — przychodzi z Qbot API
- Baro adjustment: obniża todayFactor przy niskim ciśnieniu (maks -20%)
- Używany przez: POWER target, RSRV, GEAR, W'BAL cap, CARB/FLUID (adjIf)

---

### POWER — target i kolor

```
adjFtp  = (ftp × todayFactor).toInt().coerceAtLeast(50)

┌─────────────────────────────────────────────────────────────┐
│ Warunek                    │ targetLow  │ targetHigh        │
├─────────────────────────────────────────────────────────────┤
│ Płasko (grade ≤ 3%)        │ adjFtp×0.75│ adjFtp×0.87       │
│ Krótki podjazd             │ adjFtp×0.80│ adjFtp×1.05       │
│   (grade > 3%, ascent ≤500)│            │                   │
│ Długi podjazd              │ adjFtp×0.55│ adjFtp×0.75       │
│   (grade > 3%, ascent >500)│            │                   │
└─────────────────────────────────────────────────────────────┘

Kolor:
  watts < targetLow                 → #FFFFFF (biały)
  watts ∈ [targetLow, targetHigh]   → #22C55E (zielony)
  watts ∈ (targetHigh, high×1.2]   → #F97316 (pomarańcz)
  watts > targetHigh × 1.20         → #EF4444 (czerwony)
  watts ≤ 0 lub adjFtp ≤ 0          → #FFFFFF (biały)
```

---

### HR — decoupling (HrStrainAdvisor)

```
Ważna próbka: power ≥ 80W, cadence ≥ 35, speed ≥ 6 km/h, HR > 0

Baseline window:  elapsedSec ∈ [480, 1080]   (8–18 min)
Current window:   elapsedSec ∈ [now−480, now] (ostatnie 8 min)

hrCost         = Σ(HRᵢ / Powerᵢ) / N      (średnia z ważnych próbek)
decouplingPct  = (currentCost / baselineCost − 1) × 100

Aktywacja:
  movingMin < 15           → activationWeight = 0      (NEUTRAL zawsze)
  movingMin ∈ [15, 30]     → activationWeight = (min−15)/15
  movingMin ≥ 30           → activationWeight = 1      (pełna)

decouplingSeverity:
  ≤ 3%    → GOOD    (#22C55E)
  3–6%    → NEUTRAL (#FFFFFF)
  6–10%   → WARN    (#F97316)
  > 10%   → BAD     (#EF4444)

hrZoneSeverity (gdy decoupling nieaktywny):
  Z2 (60–75% maxHR)   → GOOD       (ale tylko z decoupling)
  Z3 (75–85% maxHR)   → NEUTRAL    (bez decoupling: NEUTRAL)
  Z4 (85–95% maxHR)   → WARN
  Z5 (95%+ maxHR)     → BAD

FinalColor = max(hrZoneSeverity, decouplingSeverity)
  ale GOOD tylko gdy OBA = GOOD (Z2 + decoupling ≤3%)

Histereza: 30s przed zejściem z wyższego severity
Color update: max co 10s
```

---

### CADENCE — target i kolor

```
┌──────────────────────────────────────────────────┐
│ Teren            │ targetLow │ targetHigh │ okLow │
├──────────────────────────────────────────────────┤
│ Płasko (-2%..+4%)│    60     │     70     │  55   │
│ Podjazd (>4%)    │    55     │     65     │  50   │
│ Zjazd (<-2%)     │   —      │     —      │  —    │
└──────────────────────────────────────────────────┘

Kolor:
  rpm ∈ [targetLow, targetHigh]  → #22C55E (zielony)
  rpm ∈ [targetLow−5, targetLow) → #F97316 (pomarańcz, lekko poniżej)
  rpm < targetLow − 5            → #EF4444 (czerwony, znacznie poniżej)
  rpm > targetHigh               → #FFFFFF (biały, nie karany)
  rpm = 0                        → #FFFFFF (odpoczynek)
  zjazd (< -2%)                  → #FFFFFF (zawsze biały)
```

---

### SPEED — kolor

```
netAvg     = distanceKm / (elapsedSec / 3600)    [km/h]
speedColor:
  speed > netAvg × 1.15  → #22C55E (zielony)
  speed < netAvg × 0.85  → #EF4444 (czerwony)
  else                   → #FFFFFF (biały)
  speed < 1.0            → #FFFFFF (biały)
  netAvg < 1.0           → #FFFFFF (biały)
```

---

### GRADE — kolor

```
gradeColor:
  grade ∈ [-2.0, +2.0]           → #22C55E (zielony, płasko)
  grade ∈ [-5.0, -2.0) ∪ (+2.0, +5.0] → #FFFFFF (biały, umiarkowanie)
  grade ∈ [-9.0, -5.0) ∪ (+5.0, +9.0] → #F97316 (pomarańcz, stromo)
  grade < -9.0 ∨ grade > +9.0    → #EF4444 (czerwony, bardzo stromo)
```

---

### GEAR — assessment

```
adjFtp = (ftp × todayFactor).toInt().coerceAtLeast(50)

  cad ≤ 50 ∧ power ≥ adjFtp×1.10 ∧ grade ≥ 5.0        → #EF4444 (mielenie)
  cad < 55 ∧ power ≥ adjFtp×0.75 ∧ grade ≥ 2.0        → #F97316 (za twardo)
  cad ≥ 90 ∧ power ≤ adjFtp×0.50                      → #F97316 (za lekko)
  cad ∈ [60,75] ∧ power ∈ [adjFtp×0.75, adjFtp×0.87] 
    ∧ grade ∈ [-5.0, +5.0]                             → #22C55E (sweet spot)
  else                                                 → #FFFFFF (OK)

Histereza: 30s (jak w QExp FieldClassifier)
```

---

### RSRV (Ride Reserve)

```
baseReserve  = todayFactor × 100
tssPenalty   = √max(0, TSS) × 5.0
ifPenalty    = max(0, IF − 0.80) × 100
timePenalty  = max(0, movingHours − 1.5) × 4
decPenalty   = (decoupling − 5) × 1.5        (gdy decoupling > 5%)
stopSec      = elapsedSec − movingSec
recovery     = (startReserve − lastReserve) × (1 − e^(−stopSec/1800))

reserve = baseReserve − tssPenalty − ifPenalty − timePenalty − decPenalty
reserve = clamp(reserve, 0, startReserve)
reserve = reserve + recovery                  (regeneracja podczas postoju)

lastReserve:
  if reserve < lastReserve → reserve
  else → lastReserve + (reserve−lastReserve)×0.03   (wygładzanie wzrostu)

Wynik: round(lastReserve).clamp(0, 100)
```

---

### W'BAL (ACTIVE)

```
Model wykładniczy Skiba:
  tau        = 546
  ltpWatts   = z API (próg mleczanowy, default 192W)
  cap        = wPrimeMax × todayFactor.coerceIn(0.5, 1.1)

Co 1 sekundę:
  if power > ltpWatts:
    wBalKj  -= (power − ltpWatts) / 1000        [deplecja]
  else:
    wBalKj  += (cap − wBalKj) × (1 − e^(−1/546))  [recovery wykładnicza]

  wBalKj = clamp(wBalKj, 0, cap)

Wyświetlacz:
  percent = round(wBalKj / cap × 100).clamp(0, 100)

Start:
  wBalKj = cap   → wyświetlacz zawsze 100% (z mniejszej bazy przy zmęczeniu)

Trend (W'BAL w ACTIVE):
  historia ostatnich 3 wartości (timestamp, percent)
  deltaPerMin = ((last.pct − first.pct) / ((last.ms − first.ms) / 60000))
  deltaPerMin < −10  → "plummeting"
  deltaPerMin < −2   → "falling"
  deltaPerMin > +2   → "rising"
  else               → "stable"

  Kolor trendu:
    plummeting, falling → #EF4444 (czerwony)
    rising              → #22C55E (zielony)
    stable              → #FFFFFF (biały)
```

---

### ETA (EtaCalculator)

```
Bufory prędkości (tylko gdy speed > 0):
  speedHistory30s  — ostatnie 30 sekund
  speedHistory10m  — ostatnie 10 minut
  speedHistory30m  — ostatnie 30 minut

smartAvgKph:
  if size(30m) < 10              → wholeRideNetMovingAvg
  if size(10m) < 5               → 0.5×avg30m + 0.5×wholeRideNetMovingAvg
  else                           → 0.30×avg10m + 0.40×avg30m + 0.30×wholeRideNetMovingAvg

predictedSpeed:
  if elapsedSec < 60s            → 0
  if smartAvg ≥ 3                → smartAvg
  if currentSpeed ≥ 3            → currentSpeed
  else                           → 0

totalStoppedMs = elapsedMs − movingMs

stopRateSecPerKm  = (totalStoppedMs/1000) / distanceKm        (gdy distance > 1km)
progress          = distanceKm / (distanceKm + remainingKm)
endDecay          = if progress > 0.85 → (1−progress)/0.15 else 1.0
predictedStopsSec = stopRateSecPerKm × remainingKm × endDecay

movingHours       = remainingKm / predictedSpeed
etaMs             = now + (movingHours×3600 + predictedStopsSec) × 1000

requiredSpeedKph:
  budgetMs        = deadlineMs − now
  movingBudgetH   = (budgetMs/1000 − predictedStopsSec) / 3600
  requiredSpeed   = remainingKm / movingBudgetH

deadlineDelta = requiredSpeed − smartAvg
deadlineStatus:
  !hasRoute ∨ etaMs ≤ 0 ∨ deadline ≤ now  → "--"
  etaMs ≤ deadline                         → "OK"
  deadlineDelta > 2.5                      → "IMPOSSIBLE"
  else                                     → "LATE"
```

---

### CARB (węglowodany g/h)

```
adjIf  = min(2.0, npWatts / (ftpWatts × todayFactor))

base   = 25 + ((adjIf − 0.4) / 0.7) × 65                          [25–90 g/h]

durationMultiplier:
  movingHours < 1.0 → 1.00
  movingHours < 2.0 → 1.08
  movingHours < 3.0 → 1.15
  else              → 1.22

viMultiplier:
  VI ≤ 1.05 → 1.00
  VI ≤ 1.12 → 1.05
  else      → 1.10

weightMultiplier = clamp(bodyWeightKg / 75, 0.85, 1.20)

tempMultiplier:
  temp < 5°C  → 0.95
  temp < 25°C → 1.00
  temp < 32°C → 1.05
  else        → 1.08

carbsGPerH = roundToNearest5(base × duration × vi × weight × temp).clamp(20, 110)
```

---

### FLUID (płyny L/h)

```
base:
  adjIf < 0.55 → 0.40
  adjIf < 0.75 → 0.50
  adjIf < 0.87 → 0.60
  else         → 0.70

tempMultiplier:
  temp < 5°C  → 0.75
  temp < 12°C → 0.85
  temp < 18°C → 0.95
  temp < 24°C → 1.10
  temp < 30°C → 1.30
  temp < 35°C → 1.50
  else        → 1.70

humidityMultiplier:
  hum < 40% → 0.90
  hum < 60% → 1.00
  hum < 75% → 1.10
  hum < 85% → 1.20
  else      → 1.30

fluidLPerH = (base × temp × hum × (bodyWeightKg/70) / 0.05 → round→ ×0.05).clamp(0.30, 1.50)
```

---

### DTD — kolor w ACTIVE

```
etaMs      = EtaCalculator.calculateEtaMs(now, remainingKm, distanceKm, predictedSpeed)
deadlineMs = resolveDeadlineMs(now)     [min(userDeadline, sunset jeśli capTwilight)]

DTD color:
  !hasRoute ∨ etaMs ≤ 0 ∨ deadlineMs ≤ 0  → #FFFFFF (biały)
  etaMs > deadlineMs                       → #EF4444 (czerwony)
  etaMs ≤ deadlineMs × 0.85               → #22C55E (zielony, margines >15%)
  else                                     → #FFFFFF (biały, na styk)
```

---

### HR Zone labels (dla SETUP checkbox)

```
maxHr = z API (MaxHRBPM, default 180)

Z1: bpm < maxHr × 0.60
Z2: bpm < maxHr × 0.75
Z3: bpm < maxHr × 0.85
Z4: bpm < maxHr × 0.95
Z5: bpm ≥ maxHr × 0.95
```

---

### NP/IF/VI/TSS/KCAL — źródła SDK

```
NP   ← DataType.Type.NORMALIZED_POWER       (stream SDK)
IF   ← DataType.Type.INTENSITY_FACTOR       (stream SDK)
VI   ← DataType.Type.VARIABILITY_INDEX      (stream SDK)
TSS  ← DataType.Type.TRAINING_STRESS_SCORE  (stream SDK)
KCAL ← DataType.Type.CALORIES               (stream SDK)
```
Wszystkie 5 pobierane bezpośrednio z natywnych streamów Karoo.
NIE liczone lokalnie przez StatsCalculator.

---

### WIND / Headwind (ACTIVE) — PEŁNA LOGIKA

#### Architektura subskrypcji

ACTIVE subskrybuje 5 niezależnych streamów z rozszerzenia `karoo-headwind`.
Każdy stream po odebraniu próbki woła `applyWindSample()` i `emitUpdate()`.

```
┌────────────────────────────────────────────────────────────┐
│ Stream                               │ Priorytet │ Rola   │
├────────────────────────────────────────────────────────────┤
│ karoo-headwind::headwindDirection    │    1      │ kierunek│
│ karoo-headwind::headwindSpeed        │    2      │ prędkość│
│ karoo-headwind::headwind             │    3      │ kierunek│
│ karoo-headwind::windDirection        │    4      │ kierunek│
│ karoo-headwind::windSpeed            │    5      │ prędkość│
└────────────────────────────────────────────────────────────┘
```

#### Stan wewnętrzny

```kotlin
var lastDirectionDeg     = Double.NaN   // kierunek w stopniach (0-360)
var lastWindSpeedMs      = Double.NaN   // prędkość ogólna (m/s)
var lastHeadwindSpeedMs  = Double.NaN   // prędkość czołowa (m/s)
var lastWindUpdateMs     = 0L           // timestamp ostatniej aktualizacji
```

#### Przetwarzanie próbek — `applyWindSample(source, rawValue)`

```
headwindDirection:
  IF rawValue ∈ [0.0, 360.0] THEN
    lastDirectionDeg = rawValue
    lastWindUpdateMs = now

headwindSpeed:
  IF |rawValue| ≤ 60.0 THEN
    lastHeadwindSpeedMs = rawValue
    lastWindUpdateMs = now

headwind:
  IF rawValue ∈ [0.0, 360.0] THEN
    lastDirectionDeg = rawValue
    lastWindUpdateMs = now

windDirection:     ← FALLBACK (tylko gdy brak headwindDirection/headwind)
  IF rawValue ∈ [0.0, 360.0] THEN
    lastDirectionDeg = rawValue
    lastWindUpdateMs = now

windSpeed:         ← FALLBACK (tylko gdy brak headwindSpeed)
  IF |rawValue| ≤ 60.0 THEN
    lastWindSpeedMs = |rawValue|
    lastWindUpdateMs = now
```

Kluczowa zasada: wszystkie wartości `lastDirectionDeg`, `lastWindSpeedMs`, `lastHeadwindSpeedMs` są **niezależne**. Mogą pochodzić z różnych streamów. `headwind*` ma priorytet — jest nadpisywany tylko przez własny stream.

#### Wybór prędkości do wyświetlenia — `currentWindSpeedMs()`

```
FUNKCJA currentWindSpeedMs():
  IF !lastWindSpeedMs.isNaN()   → RETURN lastWindSpeedMs         (priorytet 1: windSpeed)
  IF !lastHeadwindSpeedMs.isNaN() → RETURN |lastHeadwindSpeedMs|  (priorytet 2: headwindSpeed, abs)
  RETURN Double.NaN                                              (brak danych)
```

Dlaczego `abs` dla headwindSpeed: headwindSpeed to składowa czołowa — może być ujemna (wiatr w plecy), ale wyświetlacz pokazuje zawsze wartość bezwzględną.

#### Formatowanie wartości — `formatWind()`

```
FUNKCJA formatWind():
  ageMs = now − lastWindUpdateMs
  IF lastWindUpdateMs == 0 ∨ ageMs > 10_000ms   → RETURN "--"    (brak danych / przestarzałe)
  
  speedMs = currentWindSpeedMs()
  IF speedMs.isNaN()                              → RETURN "--"    (brak obu źródeł)
  IF speedMs > 60.0                               → RETURN "--"    (sanity — błędny odczyt)
  
  RETURN round(speedMs).toInt().toString()                         (całkowite m/s)
```

Wartość wyświetlana jako **integer** (bez miejsc po przecinku).

#### Formatowanie kierunku — `formatWindDir()`

```
FUNKCJA formatWindDir():
  ageMs = now − lastWindUpdateMs
  IF lastWindUpdateMs == 0 ∨ ageMs > 10_000ms   → RETURN ""      (brak danych)
  
  IF lastDirectionDeg.isNaN() ∨ lastDirectionDeg < 0:
    IF currentWindSpeedMs().isNaN()              → RETURN ""      (brak obu: pusto)
    ELSE                                         → RETURN "↑"     (jest speed, brak kierunku → domyślnie)
  
  arrows = ["↑", "↗", "→", "↘", "↓", "↙", "←", "↖"]
  idx = ((lastDirectionDeg + 22.5) % 360.0).toInt() / 45
  RETURN arrows[idx]
```

8 kierunków — co 45°, przesunięte o 22.5° (żeby granice wypadały między strzałkami).

#### Aktualizacja widoku

Każdy stream woła `emitUpdate(emitter, context)` po przetworzeniu próbki. `emitUpdate` buduje nowy `RemoteViews` z layoutu `field_active_4x2` i wstawia:

```kotlin
views.setTextViewText(R.id.tv_active_wind, formatWind())     // wartość m/s
views.setTextViewText(R.id.tv_active_wind_dir, formatWindDir()) // strzałka
views.setTextViewText(R.id.tv_active_wind_unit, "m\ns")      // jednostka pionowo
```

Jeśli `formatWindDir()` zwróci `""`, strzałka jest ukrywana (`View.GONE`).

#### Świeżość (timeout)

- Wszystkie 5 streamów aktualizują `lastWindUpdateMs` przy każdej poprawnej próbce
- Jeśli od ostatniej aktualizacji minęło >10 sekund → wyświetlacz pokazuje `"--"`
- Timeout 10s zapobiega pokazywaniu starych danych gdy sensor wiatru się rozłączy

#### Sanity guardy

| Warunek | Akcja |
|---|---|
| Prędkość > 60 m/s | Ignorowana — błędny odczyt (>216 km/h) |
| Prędkość < -60 m/s | Ignorowana (dla headwindSpeed) |
| Kierunek < 0 lub > 360 | Ignorowany |
| `rawValue` = null | Pomijane |

#### Layout w ACTIVE

```
┌──────────────────────────────┐
│  → 12                        │
│     m                        │
│     s                        │
└──────────────────────────────┘
```

| Element | ID | Styl |
|---|---|---|
| Strzałka | `tv_active_wind_dir` | 12sp, biała, bold, przed wartością |
| Wartość | `tv_active_wind` | 24sp, biała, bold, monospace |
| Jednostka | `tv_active_wind_unit` | `m\ns`, 8sp, `#9CA3AF`, pionowo po prawej |

---

## 6. TodayFactor — przepływ

```
API ride-readiness → todayFactor (0.50-1.10)
    ↓
AthleteDataStore.load()
    ↓
applyBaroAdjustment(baroSensitive)
    todayFactor × baroMultiplier (0.80-1.00)
    clamp (0.70-1.10)
    ↓
┌─── RideDataAggregator.todayFactorRef ──→ POWER target (adjFtp = ftp × tf)
│                                          GEAR assessment (adjFtp)
├─── StatsCalculator.todayFactor ──→ RSRV baseReserve (tf × 100)
│                                    W'BAL cap (wPrimeMax × tf)
└─── AthleteData (SETUP display) ──→ kolorowane ≥0.90 zielony, ≥0.80 żółty
```

### Baro adjustment (QExp 1:1)
```kotlin
fun applyBaroAdjustment(baroSensitive: Boolean): AthleteData {
    if (!baroSensitive) return this
    val m = baroMultiplier.coerceIn(0.80f, 1.00f)
    if (m >= 1.00f) return this
    return copy(todayFactor = (todayFactor * m).coerceIn(0.70f, 1.10f))
}
```

---

## 7. SETUP — układ i funkcje

```
QExt2                              ← nagłówek
Primary + Active                   ← podtytuł
v0.1.0                             ← wersja
[      ODSWIEZ      ]              ← odświeża dane z cache (lokalnie)
Dane z API: 22.05.2026 15:30      ← data fetchu + czas ostatniego odświeżenia
──────────────────────────────────
Dane sportowca                     ← nagłówek sekcji
Today Factor               0.92    ← ≥0.90 #22C55E, ≥0.80 #F59E0B, else #EF4444
FTP                       250 W
W' max (HIE)             3750 J
PP                        192 W
HRV (dzis / 30d)    45/52 (+3)    ← ≥-3 zielony, ≥-8 żółty, else czerwony
Sen (dzis / 30d)  7.5h/7.0h(±…)  ← ≥-0.5 zielony, ≥-1.5 żółty, else czerwony
Cisnienie (hPa/24h)   1013/+1
Cisnienie — korekta     brak       ← zielony/żółty
GBOT profile OK                    ← zielony/żółty
──────────────────────────────────
Deadline                           ← nagłówek sekcji
[       21:00       ]              ← klikalny → TimePickerDialog
Zachód słonca           20:47      ← z CIVIL_DUSK SDK
☐ Limit do zmroku                  ← checkbox: deadline = min(user, sunset)
Aktywny deadline: 21:00            ← podsumowanie
──────────────────────────────────
☑ Wrazliwosc na cisnienie          ← checkbox baro adjustment
☐ HR: strefy Z1-Z5 zamiast bpm    ← checkbox HR zone mode
```

### Checkboxy i preferencje
| Checkbox | Pref | Domyślnie | Działanie |
|---|---|---|---|
| Wrażliwość na ciśnienie | `baro_sensitive` | `true` | todayFactor × baroMultiplier |
| HR: strefy Z1-Z5 | `hr_zone_mode` | `false` | PRIMARY HR pokazuje Z2 zamiast 135 |
| Limit do zmroku | `cap_twilight` | `false` | deadline = min(user, sunset) |
| Deadline | `deadline_hour`/`deadline_min` | 21:00 | HH:MM przez TimePickerDialog |

### Zasady działania SETUP

#### Architektura procesów
SETUP (`SetupActivity`) i extension (`QExt2PrimaryExtension`) to **dwa różne procesy**. Nie mogą wywoływać swoich metod bezpośrednio. Komunikacja tylko przez `SharedPreferences` (`AthleteDataStore`).

#### ODŚWIEŻ — co robi
1. Odczytuje dane z `AthleteDataStore.load()` (cache z ostatniego fetchu HTTP)
2. Odświeża wszystkie pola w UI: Today Factor, FTP, W', PP, HRV, Sen, Ciśnienie, Korekta, Status
3. **NIE robi HTTP.** Dane pochodzą z cache zapisanego przez extension przy ostatnim fetchu.
4. Działa natychmiast, bez WiFi, bez opóźnień.
5. Zapisuje timestamp ostatniego odświeżenia w `last_refresh_ts`.

#### Fetch HTTP (skąd biorą się dane)
- Fetch HTTP jest robiony **TYLKO przez extension** (`QExt2PrimaryExtension.fetchAthleteData()`), NIGDY przez SETUP
- Extension odpala fetch przy połączeniu z KarooSystemService (start jazdy/reconnect)
- URL: `https://ankle-wool-undusted.ngrok-free.dev/ride-readiness` (Qbot API)
- Timeout: 10s. Przez Karoo SDK `OnHttpResponse.MakeHttpRequest` → companion app → internet
- Po odebraniu odpowiedzi:
  1. Parsuje JSON (25 pól + `signals` obiekt)
  2. Zapisuje do `AthleteDataStore.save()`
  3. Stosuje `applyBaroAdjustment()` z uwzględnieniem preferencji `baro_sensitive`
  4. Wywołuje `aggregator.updateAthleteData(adjusted)` → POWER/RSRV/GEAR dostają skorygowany todayFactor
- Gdy API niedostępne (brak internetu/companiona): używa ostatniego cache

#### Jak zmiana w SETUP wpływa na pola jezdne

| Co zmieniasz | Zapis do | Jak wpływa |
|---|---|---|
| Deadline (HH:MM) | `AthleteDataStore.saveDeadline()` → extension `refreshDeadlineConfig()` | `resolveDeadlineMs()` w agregatorze używa nowej godziny |
| ☑ Wrażliwość na ciśnienie | `AthleteDataStore.saveBaroSensitive()` → extension `refreshBaroSensitive()` | Agregator dostaje `todayFactor × baroMultiplier` (lub nie) |
| ☑ Limit do zmroku | `AthleteDataStore.saveCapTwilight()` → extension `refreshCapTwilight()` | `resolveDeadlineMs()` uwzględnia (lub nie) `CIVIL_DUSK` |
| ☑ HR: strefy Z1-Z5 | `AthleteDataStore.saveHrZoneMode()` | PRIMARY czyta `loadHrZoneMode()` przy każdym `setPrimaryValues()` |

**Ścieżka dla deadline i checkboxów:**
```
SETUP → AthleteDataStore.save*() → QExt2PrimaryExtension.refresh*() → RideDataAggregator.ref → aktualizacja w pętli 1 Hz
```

**Ścieżka dla HR zone mode:**
```
SETUP → AthleteDataStore.saveHrZoneMode() → CompositePrimaryDataType odczytuje loadHrZoneMode() przy renderowaniu
```

#### Wyświetlanie danych w SETUP
- **Today Factor:** `data.todayFactorDisplay` (%.2f). Uwzględnia baro adjustment. Kolor: ≥0.90 zielony, ≥0.80 żółty, <0.80 czerwony.
- **HRV:** Format `dzis / baseline30d (±dev)`. Pokazuje się tylko gdy obie wartości >0. Kolor: dev≥-3 zielony, ≥-8 żółty, < -8 czerwony.
- **Sen:** Format `dzisH / baseline30dH (±devH)`. Pokazuje się tylko gdy >0. Kolor: dev≥-0.5 zielony, ≥-1.5 żółty, < -1.5 czerwony.
- **Ciśnienie:** Format `hPa / ±zmiana/24h`. Zawsze pokazuje.
- **Korekta baro:** `brak korekty` (zielony) lub `korekta -X%` (żółty). Obliczone z `baroMultiplier`.
- **Status profilu:** `GBOT profile OK` (zielony) lub lista ostrzeżeń (żółta).
- **Data fetchu:** `Dane z API: dd.MM.yyyy HH:mm / odswiezone: HH:mm:ss`

#### Inicjalizacja danych
1. Extension `onCreate()` → `AthleteDataStore.init(this)` → ładuje cache
2. `RideDataAggregator.init` → `applyAthleteData(load().applyBaroAdjustment(loadBaroSensitive()))` → dane gotowe od startu
3. SETUP `onCreate()` → `AthleteDataStore.init(this)` → `showStoredData()` → wyświetla cache
4. SETUP `onResume()` → `showStoredData()` → odświeża przy każdej wizycie

#### Co NIE jest w SETUP (różnice vs QExp)
- Brak ręcznego ustawiania FTP (w QExp był EditText)
- Brak ręcznego ustawiania maxHR (w QExp był EditText)
- Brak auto-refreshu przy starcie (w QExp sprawdzał czy cache >2h)
- Brak dedykowanego wyświetlania FTP source (w QExp pokazywał "z Xert")
- ODŚWIEŻ nie robi HTTP (w QExp robił)

---

## 8. Struktura plików

```
app/src/main/kotlin/com/qext2/primary/
├── QExt2PrimaryExtension.kt          ← extension service (singleton)
├── CarbAddActivity.kt                ← USUNIĘTY
├── QExtFieldClickReceiver.kt         ← USUNIĘTY
├── data/
│   └── AthleteDataStore.kt           ← 25 pól atlety, preferencje, baro adjustment
├── datatypes/
│   ├── CompositePrimaryDataType.kt   ← PRIMARY (4 kolumny, kolorowanie)
│   ├── CompositeActiveDataType.kt    ← ACTIVE (4×2, WIND/W'BAL/DTD/IF10)
│   └── StatsDataType.kt             ← STATS (3×6, demo + real data)
├── engine/
│   ├── RideDataAggregator.kt         ← agregator (17 streamów, pętla 1 Hz)
│   ├── StatsCalculator.kt            ← NP/IF/VI/TSS/kcal/carbs/fluid/reserve/W'bal/HRD
│   ├── EtaCalculator.kt              ← port z QExp (bufory, smartAvg, stop prediction)
│   └── hrdecoupling/
│       ├── HrSample.kt              ← data class próbki
│       ├── HrDecouplingBuffer.kt     ← ring buffer
│       └── HrStrainAdvisor.kt        ← decoupling model + kolor
├── field/
│   └── StatsValueFormatter.kt        ← formatery wartości STATS
├── model/
│   ├── PrimaryRideSnapshot.kt        ← 6 pól + 6 kolorów + computeColors
│   └── StatsRideSnapshot.kt          ← 22 pola danych STATS
├── setup/
│   └── SetupActivity.kt              ← SETUP UI (HRV, sen, ciśnienie, deadline, checkboxes)
└── util/
    └── TapBindingHelper.kt           ← USUNIĘTY

app/src/main/res/layout/
├── field_primary_4col.xml            ← PRIMARY layout
├── field_active_4x2.xml              ← ACTIVE layout
├── field_stats_3x3.xml              ← STATS layout
├── field_primary_fallback.xml        ← fallback (błąd renderowania)
└── activity_setup.xml               ← SETUP layout

app/src/test/kotlin/.../hrdecoupling/
└── HrStrainAdvisorTest.kt           ← 10 testów jednostkowych
```

---

## 9. Decyzje projektowe

1. **POWER target**: poniżej targetu = biały (bez ostrzegania). Powyżej = ostrzeżenie.
2. **HR**: decoupling, nie strefy. Zielony tylko gdy Z2 + niski decoupling.
3. **CADENCE**: zjazdy zawsze białe. Tylko za niska karana.
4. **SPEED**: porównanie do średniej netto, bez stref.
5. **GEAR**: histereza 30s jak w QExp.
6. **GRADE**: 1:1 z QExp.
7. **W'BAL**: model wykładniczy Skiba (tau=546), nie liniowy.
8. **NP/IF/VI/TSS/KCAL**: z natywnych streamów SDK, nie z własnych kalkulacji.
9. **movingSec**: usunięty. Wszystko na `ELAPSED_TIME` (auto-pauza).
10. **DEC/HRD**: wyzerowane. Decoupling w PRIMARY przez HrStrainAdvisor.
11. **WBAL/TREND w STATS**: wyzerowane. W ACTIVE.
12. **BAT%**: wyzerowany. Na pasku Karoo.
13. **Kliknięcie w pole**: niemożliwe na Karoo (RemoteViews nie przekazuje dotknięć).
14. **BonusAction**: może nie wspierane przez firmware (niesprawdzone).
15. **Demo STATS**: zostaje do czasu decyzji o przełączeniu na live data.

---

## 10. Testy

### HrStrainAdvisor (10 testów, wszystkie zielone)
1. Brak baseline → NEUTRAL
2. Brak maxHR → NEUTRAL
3. Z2 + decoupling ≤3% → GOOD
4. Z2 + decoupling 7% → WARN
5. Decoupling >10% → BAD
6. Z4 bez decoupling → WARN
7. Z5 → BAD
8. Niska moc → brak baseline
9. Postoje → wykluczone z okna
10. Reset → NEUTRAL

---

## 11. Wymiary czcionek — wszystkie pola

### PRIMARY (`field_primary_4col.xml`)

| Element | ID | Rozmiar | Styl | Kolor |
|---|---|---|---|---|
| Ikona HR (♥) | — | 8sp | normal | `#9CA3AF` |
| Wartość HR | `tv_hr` | 24sp | normal, monospace | dynamiczny |
| Ikona Cadence (↻) | — | 8sp | normal | `#9CA3AF` |
| Wartość Cadence | `tv_cadence` | 24sp | normal, monospace | dynamiczny |
| Ikona Power (⚡) | — | 9sp | normal | `#9CA3AF` |
| Napis "POWER" | — | 9sp | normal | `#9CA3AF` |
| Power (3 cyfry) | `tv_power_3` | 42sp | normal, monospace | dynamiczny |
| Power (4 cyfry) | `tv_power_4` | 36sp | normal, monospace | dynamiczny |
| Power (5 cyfr) | `tv_power_5` | 30sp | normal, monospace | dynamiczny |
| Ikona Speed (●) | — | 9sp | normal | `#9CA3AF` |
| Napis "SPEED" | — | 9sp | normal | `#9CA3AF` |
| Speed (4 znaki) | `tv_speed_4` | 38sp | normal, monospace | dynamiczny |
| Speed (5 znaków) | `tv_speed_5` | 32sp | normal, monospace | dynamiczny |
| Speed (6 znaków) | `tv_speed_6` | 26sp | normal, monospace | dynamiczny |
| Ikona Gear (⚙) | — | 8sp | normal | `#9CA3AF` |
| Gear przód | `tv_gear_front` | 16sp | normal, monospace | dynamiczny |
| Znak × | `tv_gear_x` | 12sp | normal | `#9CA3AF` |
| Gear tył | `tv_gear_rear` | 24sp | normal, monospace | dynamiczny |
| Ikona Grade (▲) | — | 8sp | normal | `#9CA3AF` |
| Grade wartość | `tv_grade` | 24sp | normal, monospace | dynamiczny |
| Grade jednostka `%` | `tv_grade_unit` | 16sp | normal | `#9CA3AF` |

### ACTIVE (`field_active_4x2.xml`)

| Element | ID | Rozmiar | Styl | Kolor |
|---|---|---|---|---|
| Etykieta D | — | 8sp | normal | `#9CA3AF` |
| Dystans | `tv_active_dist` | 24sp | normal, monospace | `#FFFFFF` |
| Etykieta DTD | — | 8sp | normal | `#9CA3AF` |
| DTD | `tv_active_dtd` | 24sp | normal, monospace | dynamiczny |
| Etykieta IF10 | — | 8sp | normal | `#9CA3AF` |
| IF10 | `tv_active_if10` | 24sp | normal, monospace | `#FFFFFF` |
| Etykieta V | — | 8sp | normal | `#9CA3AF` |
| Prędkość V | `tv_active_vsr` | 24sp | normal, monospace | `#FFFFFF` |
| Etykieta NULL | — | 8sp | normal | `#9CA3AF` |
| NULL | `tv_active_null` | 16sp | normal, monospace | `#9CA3AF` |
| Etykieta TEMP | — | 8sp | normal | `#9CA3AF` |
| Temperatura | `tv_active_temp` | 24sp | normal, monospace | `#FFFFFF` |
| Jednostka ° | `tv_active_temp_unit` | 24sp | normal | `#9CA3AF` |
| Etykieta W'BAL | — | 8sp | normal | `#9CA3AF` |
| W'BAL | `tv_active_wbal` | 24sp | normal, monospace | dynamiczny |
| Jednostka % | `tv_active_wbal_unit` | 16sp | normal | `#9CA3AF` |
| Etykieta WIND | — | 8sp | normal | `#9CA3AF` |
| Strzałka wiatru | `tv_active_wind_dir` | 12sp | bold | `#FFFFFF` |
| Prędkość wiatru | `tv_active_wind` | 24sp | normal, monospace | `#FFFFFF` |
| Jednostka m | `tv_active_wind_unit_m` | 8sp | normal | `#9CA3AF` |
| Jednostka s | `tv_active_wind_unit_s` | 8sp | normal | `#9CA3AF` |

### STATS (`field_stats_3x3.xml`)

| Element | Rozmiar | Styl | Kolor |
|---|---|---|---|
| Nagłówek kolumny | 9sp | **bold** | `#9CA3AF` |
| Wartość | 20sp | **bold** | `#FFFFFF` (lub dynamiczny) |

---

## 12. Kolory — pełna tabela

| Nazwa | Hex | Gdzie używane |
|---|---|---|
| Zielony (GOOD) | `#22C55E` | POWER w target, HR Z2+decoupling OK, Cadence w target, Speed >115% avg, Grade ±2%, Gear sweet spot, RSRV ≥40, VI <1.05, ETA OK, W'BAL rising |
| Pomarańczowy (WARN) | `#F97316` | POWER powyżej target, HR Z4, Cadence lekko poniżej target, Grade ±5-9%, Gear za twardo/lekko, RSRV 20-40, VI 1.05-1.10 |
| Czerwony (BAD) | `#EF4444` | POWER >120% target, HR Z5, Cadence znacznie poniżej, Speed <85% avg, Grade >±9%, Gear mielenie, RSRV <20, VI ≥1.10, DTD spóźnienie, W'BAL falling/plummeting |
| Biały | `#FFFFFF` | Wartości domyślne, NEUTRAL, OK, brak danych |
| Szary (etykiety) | `#9CA3AF` | Wszystkie etykiety pól, jednostki, nagłówki kolumn STATS |
| Ciemny szary | `#6B7280` | Tekst pomocniczy w SETUP |
| Bardzo ciemny szary | `#4B5563` | Nagłówki sekcji w STATS (POWER, FUEL, ...) |
| Tło PRIMARY/ACTIVE | `#0D1424` | Główne tło layoutów |
| Tło kolumn PRIMARY | `#111827` | Tło każdej z 4 kolumn |
| Tło STATS | `#111315` | Tło pola STATS |
| Tło SETUP | `#0D1424` | Tło activity_setup |
| Niebieski (przyciski) | `#2563EB` | Przyciski w SETUP (ZAPISZ) |
| Zielony (przycisk) | `#22C55E` | Przycisk DODAJ PAKIET (usunięty) |
| Czerwony (przycisk) | `#FFFF0000` | ODSWIEZ |

---

## 13. Formatowanie wartości — wszystkie pola

Legenda: `value` = wartość wyświetlana, `unit` = jednostka, `stale` = co gdy dane nieświeże

### PRIMARY

| Pole | Format | Jednostka | Stale (>X ms) | Zerowy/specjalny |
|---|---|---|---|---|
| HR | integer | — | `NO` (>12 000ms) | `NO` gdy HR=0 |
| HR (zone mode) | `Z1`–`Z5` | — | `NO` (>12 000ms) | zależne od maxHR |
| Cadence | integer | — | `NO` (>8 000ms) | `NO` gdy cadence=0 |
| Power | integer | — | `NO` (>8 000ms) | pokazuje 0 (nie `NO`) |
| Speed | `%.1f` | — | `NO` (>12 000ms) | `NO` gdy <0.01 km/h |
| Gear front | integer | — | `NO` (>15 000ms) | pusty string gdy brak |
| Gear rear | integer | — | `NO` (>15 000ms) | pusty string gdy brak |
| Grade | `+X` / `-X` (integer) | `%` | `NO` (>20 000ms) | jednostka ukryta gdy `NO` |

### ACTIVE

| Pole | Format | Jednostka | Stale/Brak | Uwagi |
|---|---|---|---|---|
| D (dystans) | `%.1f` (<100), `%.0f` (≥100) | — | `"0"` | km |
| DTD | `%.1f` (<100), `%.0f` (≥100) | — | `"--"` | km, kolorowany |
| IF10 | `%.2f` | — | `"0.00"` | |
| V (prędkość) | `%.1f` | — | `"0.0"` | km/h |
| NULL | — | — | `""` | puste |
| TEMP | integer (zaokrąglony) | `°` | `"NO"` | `NO` gdy <-50 lub >60°C |
| W'BAL | integer | `%` | `"W"` (bez danych mocy) | 0–100 |
| WIND | integer | `m\ns` (m nad s) | `"--"` (>10s) | m/s, zaokrąglony |

### STATS

| Pole | Format | Jednostka | `"--"` gdy | Uwagi |
|---|---|---|---|---|
| NP | integer | — | ≤0 | coerceAtMost(999) |
| IF | `%.2f` | — | ≤0 | coerceAtMost(1.99) |
| VI | `%.2f` | — | ≤0 | coerceAtMost(1.99), kolorowany |
| TSS | integer | — | ≤0 | coerceIn(0, 9999) |
| RSRV | integer | `%` | nigdy | coerceIn(0, 100), kolorowany |
| ETA | `HH:MM` | — | ≤0 | Calendar.HOUR_OF_DAY |
| CARB | integer | `g` | ≤0 | coerceIn(0, 999) |
| FLUID | `%.1f` | `L` | ≤0 | coerceIn(0, 9.9) |
| KCAL | integer | — | ≤0 | coerceAtMost(99999) |
| UP | integer | `m` | <0 | coerceAtMost(99999) |
| LEFT | integer | `m` | <0 | coerceAtMost(99999) |
| SUNSET | `HH:MM` | — | ≤0 | Calendar.HOUR_OF_DAY |
| BAT/h | `%.1f` | `%/h` | ≤0 | `"CHG"` przy ładowaniu |
| LEFT (batt) | `H:MM` | — | ≤0 | `"CHG"` przy ładowaniu |

### Wyzerowane w STATS (zawsze `"--"`): HRD, DEC, WBAL, TREND, BAT%, DLTAV, RD

---

## 14. Formaty specjalne

### Odległość (`formatDistanceKm`)
```
if meters ≤ 0       → "0"
km = meters / 1000
if km < 100         → "%.1f".format(km)     (np. "45.2")
else                → "%.0f".format(km)     (np. "123")
```

### Czas (`formatTime` / `batteryRuntime`)
```
if sec < 0          → "--:--"
h = sec / 3600
m = (sec % 3600) / 60
if h > 99           → "99:59"
else                → "${h}:${m.padStart(2, '0')}"
```

### ETA / Sunset (`etaTime`)
```
if ms ≤ 0           → "--"
Calendar.timeInMillis = ms
String.format("%02d:%02d", HOUR_OF_DAY, MINUTE)
```

### Temperatura (`formatTemp`)
```
if c < -50 ∨ c > 60 → "NO"
else                → round(c).toInt().toString()
```

### Wiatr (`formatWind`)
```
if lastUpdate = 0 ∨ age > 10s → "--"
if speed.isNaN()              → "--"
if speed > 60                 → "--"
else                          → round(speed).toInt().toString()
```

### Kadencja — wyświetlanie
```
"NO" gdy cadence = 0 ∨ freshness > 8s
w przeciwnym razie: cadence.toString()
```

### Power — wyświetlanie
```
"NO" gdy freshness > 8s
w przeciwnym razie: power3s.toString()
(UWAGA: pokazuje "0" gdy power=0 ale świeże)
```

### HR — wyświetlanie
```
"NO" gdy hr = 0 ∨ freshness > 12s
w przeciwnym razie: hr.toString()
ALBO (zone mode): hrZoneLabel(bpm, maxHr)
```

### Speed — wyświetlanie
```
"NO" gdy freshness > 12s ∨ speed < 0.01
w przeciwnym razie: "%.1f".format(speedKmh)
```

### Grade — wyświetlanie
```
"NO" gdy freshness > 20s
w przeciwnym razie: if intGrade ≥ 0 → "+${intGrade}" else → "${intGrade}"
```

### Gear — wyświetlanie
```
"NO" gdy freshness > 15s ∨ (front ≤ 0 ∧ rear ≤ 0)
w przeciwnym razie: "${gearFront}×${gearRear}"
```

### Progi świeżości (stale timeout)

| Sensor | Stale po |
|---|---|
| HR | 12 000 ms |
| Cadence | 8 000 ms |
| Power | 8 000 ms |
| Speed | 12 000 ms |
| Gear | 15 000 ms |
| Grade | 20 000 ms |
| Wiatr | 10 000 ms |

---

## 15. POLE WIND — pełna specyfikacja formatowania

### Layout (wewnątrz komórki ACTIVE, wiersz 1, kolumna 4)

```
┌──────────────────────────────┐
│ W                          │ ← etykieta "W", 8sp, #9CA3AF, lewy-górny róg
│                              │
│        → 12                  │ ← strzałka (12sp) + wartość (24sp) + m/s (8sp)
│           m                  │   wyrównane do prawej, wyśrodkowane w pionie
│           s                  │
└──────────────────────────────┘
```

### Struktura XML

```xml
<!-- Etykieta "W" -->
<TextView android:text="W" android:textColor="#9CA3AF" android:textSize="8sp" />

<!-- Wiersz ze strzałką + wartością + jednostką -->
<LinearLayout android:orientation="horizontal"
    android:gravity="center_vertical|end"
    android:layout_marginEnd="3dp">

    <!-- Strzałka -->
    <TextView android:id="@+id/tv_active_wind_dir"
        android:text="→"
        android:textColor="#FFFFFF"
        android:textSize="12sp"
        android:textStyle="bold"
        android:layout_marginEnd="2dp" />

    <!-- Wartość liczbowa -->
    <TextView android:id="@+id/tv_active_wind"
        android:text="--"
        android:textColor="#FFFFFF"
        android:textSize="24sp"
        android:textStyle="bold"
        android:fontFamily="monospace" />

    <!-- Jednostka m/s pionowo -->
    <TextView android:id="@+id/tv_active_wind_unit"
        android:text="m\ns"
        android:textColor="#9CA3AF"
        android:textSize="8sp"
        android:fontFamily="monospace"
        android:layout_marginStart="2dp"
        android:layout_marginTop="-6dp" />
</LinearLayout>
```

### Kolejność elementów (lewo → prawo)
1. **Strzałka** — `tv_active_wind_dir`, 12sp bold, biała, margines prawy 2dp
2. **Wartość** — `tv_active_wind`, 24sp bold, biała, monospace
3. **Jednostka** — `tv_active_wind_unit`, 8sp, szara `#9CA3AF`, monospace, `m\ns` (m nad s), margines lewy 2dp, przesunięta -6dp w górę

### Co jest renderowane

```kotlin
// W emitUpdate():
views.setTextViewText(R.id.tv_active_wind, windText)        // "12" lub "--"
views.setTextViewText(R.id.tv_active_wind_dir, windDir)     // "→" lub ""
views.setViewVisibility(R.id.tv_active_wind_dir,            // GONE gdy pusty
    if (windDir.isEmpty()) View.GONE else View.VISIBLE)
// Jednostka (tv_active_wind_unit) — zawsze "m\ns", NIE modyfikowana w kodzie
```

### Stany wyświetlacza

| Stan | Strzałka | Wartość | Jednostka |
|---|---|---|---|
| Dane OK, kierunek znany | `→` (widoczna) | `12` | `m\ns` |
| Dane OK, kierunek nieznany | `↑` (widoczna) | `12` | `m\ns` |
| Brak danych / stare >10s | ukryta (GONE) | `--` | `m\ns` |

### Wartości domyślne (przy starcie / preview)
```kotlin
views.setTextViewText(R.id.tv_active_wind, "--")
views.setTextViewText(R.id.tv_active_wind_unit, "m\ns")
views.setViewVisibility(R.id.tv_active_wind_dir, View.GONE)
```

### Kolory
| Element | Kolor |
|---|---|
| Etykieta "W" | `#9CA3AF` |
| Strzałka | `#FFFFFF` (zawsze biała) |
| Wartość liczbowa | `#FFFFFF` (zawsze biała) |
| Jednostka m/s | `#9CA3AF` |

### Wymiary
| Element | Rozmiar |
|---|---|
| Etykieta "W" | 8sp |
| Strzałka | 12sp bold |
| Wartość | 24sp bold monospace |
| Jednostka | 8sp monospace |
| Margines strzałka→wartość | 2dp |
| Margines wartość→jednostka | 2dp |
| Przesunięcie jednostki w górę | -6dp |
