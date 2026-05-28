# QExt2 — Raport z naprawy 2026-05-28

**Zakres:** naprawa na podstawie audytu `QExt2_Audit_2026-05-28.md`  
**Commity:** 10 (od `932e8ec` do `f1474b0`)  
**Pliki:** 28 zmienionych (+704 / -611 linii)  
**Testy:** 223 wszystkie zielone  

---

## Etap 1 — Hotfixy krytyczne (F-01, F-12, F-16, F-19, F-22)

| ID | Naprawa | Plik |
|----|---------|------|
| F-01 | **W'Balance 1000× bug** — `wPrimeJoules` (J) → `wPrimeKj` (kJ) w 4 miejscach | `BpActiveStaticDataType.kt`, `CompositeActiveDataType.kt` |
| F-12 | **Token z URL** — usunięty `?token=` z GateOpenClient, tylko header `X-Gate-Token` | `GateOpenClient.kt` |
| F-16 | **SensorMessageProducer.reset()** — dodane `cooldowns.clear()` | `SensorMessageProducer.kt` |
| F-19 | **HrStrainAdvisor.colorWithHysteresis** — nie resetuje już timera przy braku zmiany koloru (oscylacje nie blokują) | `HrStrainAdvisor.kt` |
| F-22 | **QExt2DebugConfig** — `var` → `val` (mutowalny singleton utwardzony) | `QExt2DebugConfig.kt` |

---

## Etap 2 — Naprawa algorytmów (F-04, F-03, F-13, F-08)

| ID | Naprawa | Plik |
|----|---------|------|
| F-04 | **movingElapsedSec** — dedykowany licznik czasu ruchu. `StatsCalculator.update()` dostaje `movingElapsedSec` zamiast `elapsedSec`. NP nie zaniża się przez postoje, TSS liczony z czasu ruchu, CARB z faktycznego czasu jazdy. | `RideDataAggregator.kt`, `StatsCalculator.kt` |
| F-03 | **Subskrypcja POWER usunięta** — freshness jest teraz z `updatePower()` (SMOOTHED_3S), nie z osobnego strumienia POWER. Jedno źródło mocy = jedna freshness. | `RideDataAggregator.kt` |
| F-13 | **climbKey → climbIndex** — klucz podjazdu oparty na `climbIndex` z SDK (stały identyfikator) zamiast `distanceToClimbM` (dynamiczny, zmieniał się co metr). Margines GPS w `ActiveClimbResolver`: 25m → 100m. | `ClimbAnnouncementProducer.kt`, `ActiveClimbResolver.kt` |
| F-08 | **Snapshot v4** — crash-recovery snapshot z prefixem wersji `v4|`, jawne sprawdzenie długości pól (16), dodane pole `movingElapsedSec`. Stare snapshoty ignorowane. | `RideDataAggregator.kt` |

---

## Etap 3 — Bezpieczeństwo i stabilność (F-02, F-11, F-15)

| ID | Naprawa | Plik |
|----|---------|------|
| F-02 | **WeatherClient** — z `CountDownLatch.await(15s)` (blokujący wątek IO) na `suspendCancellableCoroutine` + `withTimeoutOrNull`. 0ms blokady wątku. | `WeatherClient.kt`, `QExt2PrimaryExtension.kt` |
| F-11 | **AthleteDataStore atomowe commity** — `resetCarbSessionState()` w jednym `edit().apply()` zamiast 3 osobnych. Null-guard przywrócony do cichego fallbacku (konstruktory DataType wołają `load()` przed `init()`). | `AthleteDataStore.kt` |
| F-15 | **ActiveMessageManager** — pojedynczy slot `suspended` → `ArrayDeque<ActiveMessage>`. Resume wybiera najwyżej priorytetową wiadomość, czyści przeterminowane. | `ActiveMessageManager.kt` |

---

## Etap 4 — Testy jednostkowe

| Plik | Testy | Pokrycie |
|------|-------|----------|
| `StatsCalculatorTest.kt` | 21 testów | NP stała/zmienna/dead-time, W' deplecja/regeneracja/brak-params, CARB intensity/duration, FLUID temperatura, BAT drain/okno/null-preserve/charging/0, snapshot restore, reset, decoupling drift, TSS at FTP, VI constant, reserve TSS/decoupling |
| `SensorMessageProducerTest.kt` | 11 testów | power/HR/route/sensors missing, cooldowny, reset, progi prędkości/mocy |

---

## Etap 5 — Refaktoring architektury

| ID | Zmiana | Plik |
|----|--------|------|
| 5.3 | **PrimaryRenderOptimizer** — `object` → `class`, instancja per `startView()` (brak wyścigu między polami na różnych ekranach) | `PrimaryRenderOptimizer.kt`, `CompositePrimaryDataType.kt` |
| 5.4 | **HrDecouplingBuffer.snapshotAll()** — cache z inwalidacją na `add()`/`clear()`. Zero alokacji 2400-elementowej listy poza pierwszym tickiem. | `HrDecouplingBuffer.kt` |
| 5.1 | **Unifikacja W'Balance** — usunięte `SwPrimeCalc` (BpActiveStaticDataType) i `WPrimeCalculator` (CompositeActiveDataType). Oba ACTIVE czytają W' z `aggregator.statsSnapshot.wBalancePercent` (StatsCalculator). Jedno kanoniczne źródło. -118 linii. | `BpActiveStaticDataType.kt`, `CompositeActiveDataType.kt`, `StatsRideSnapshot.kt` |

---

## Hotfixy po-instalacyjne (na żywo z Karoo)

| Problem | Przyczyna | Fix |
|---------|-----------|-----|
| QExt2 Keeps stopping | `AthleteDataStore.load()` checkNotNull — DataType konstruowane przed `init()` | Przywrócony silent fallback z default AthleteData |
| W' zawsze 100% | `setWPrimeParams()` resetowało `wBalKj = wPrimeKj` przy każdym odświeżeniu danych (baro, Q server fetch) | Reset tylko gdy `wBalKj <= 0` (stan początkowy) |
| NULL -12g po restarcie | Detekcja nowej sesji CARB nie łapała skoku `elapsedSec` do przodu między sesjami | Dodany warunek `elapsedSec > storedLastElapsed + 120L` |
| W' label | Kolizja z nazwą | W' → D BAT w STATS |

---

## Stan końcowy

```
28 plików zmienionych, +704 / -611 linii
223 testy zielone
APK zainstalowany na Karoo 00447GA253070066
Backup: QExt2_repair_final_20260528_1440.zip (2.4 MB)

Git log (od baseline):
  f1474b0 UI: rename W' → D BAT w STATS grid label
  86b9559 Fix: NULL -12g po restarcie krotkiej sesji
  e8b9017 Fix: revert AthleteDataStore.load() null-guard
  b128a08 Fix: W' reset na 100% przy kazdym odswiezeniu danych
  ec86694 QExt2 repair — Etap 5.1: unifikacja W'Balance
  8fea78f QExt2 repair — Etap 5 czesciowy: refaktoring
  e7dfa5f QExt2 repair — Etap 4: testy jednostkowe
  bdd6105 QExt2 repair — Etapy 1-2: hotfixy + algorytmy
  932e8ec QExt2 pre-fix baseline 2026-05-28
```

**Pozostało do zrobienia (Etap 5.5):** Dekompozycja `RideDataAggregator` — wyciągnięcie `CarbNutritionTracker`, `NavigationStateTracker`, `BatteryMonitor` do osobnych klas. Czysty refaktoring, zero wpływu na działanie.
