# QExt2 — Podsumowanie sesji 2026-05-22

## Architektura

### Rozszerzenia
- **QExt2 Primary** (`qext2-primary`) — 4-kolumnowe pole graficzne: HR/Cadence, Power, Speed, Gear/Grade
- **QExt2 Active** (`qext2-active`) — 4×2 pole graficzne: D/DTD/IF10/Vsr/NULL/T/W'/W

### Przepływ danych
1. `QExt2PrimaryExtension.onCreate()` → singleton `instance`, tworzy `KarooSystemService`, łączy asynchronicznie
2. Po połączeniu → tworzy `RideDataAggregator` → subskrybuje 6 strumieni (HR, CADENCE, POWER, SPEED, GRADE, GEAR)
3. Agregator emituje `PrimaryRideSnapshot` co 1s (bez warunku dataUpdated — bug fixed)
4. `CompositePrimaryDataType.startView()` → w preview pokazuje XML (NO), w normal subskrybuje snapshot flow
5. `CompositeActiveDataType.startView()` → subskrybuje 8 strumieni (DISTANCE, DISTANCE_TO_DEST, AVERAGE_SPEED, TEMPERATURE, POWER, Headwinds×2)

## Co zrobiono

### Bugi naprawione
1. **dataUpdated gate blokował snapshot** — `AtomicBoolean` powodował zatrzymanie emisji snapshotów gdy brak nowych danych. Freshness w ostatnim snapshocie zamarzał → stare dane wyglądały jak świeże. **Fix**: usunięto dataUpdated, snapshot co 1s zawsze.
2. **`types` getter tworzył nową instancję** — `get() = listOf(CompositePrimaryDataType())` tworzyło nowy obiekt przy każdym dostępie. **Fix**: backing field `private val _types`.
3. **Preview pokazywał demo dane (HR=139)** — zamiast live danych lub "NO". **Fix**: preview pokazuje XML (NO), live subskrybuje agregator.
4. **`collectLatest` gubił aktualizacje** — zastąpiono `collect`.
5. **Brak launcher intent** — SetupActivity nie miał MAIN/LAUNCHER, ikona nie pojawiała się w launcherze.

### QExt2 Active — layout
- 4 kolumny × 2 wiersze, wagi 25/25/25/25
- Nagłówki: D, DTD, IF10 (IF nad 10), Vsr (V nad sr), NULL, T, W', W
- Ikony D: `ic_distance_mini` z VinHKE (trasa/odometer)
- Ikona DTD: `ic_down` z VinHKE (strzałka w dół)
- Wiatr: strzałka pchania (↑→↓←↗↘↙↖) + prędkość m/s + m nad s (12sp)
- Temperatura: wartość + ° (24sp)
- W' balance: wartość 0-100% + % (16sp grey)

### Źródła danych dla Active
| Pole | DataType ID | Źródło |
|------|-------------|--------|
| D | `TYPE_DISTANCE_ID` | SDK |
| DTD | `TYPE_DISTANCE_TO_DESTINATION_ID` | SDK |
| IF10 | Liczony z POWER | NP 10min / FTP (kroczące okno) |
| Vsr | `TYPE_AVERAGE_SPEED_ID` | SDK (auto-pause) |
| T | `TYPE_TEMPERATURE_ID` | SDK |
| W' | Liczony z POWER | W' balance (τ=360s, FTP/W'max z Q) |
| W | `TYPE_EXT::karoo-headwind::headwind` | Headwinds |

### W' balance algorytm
- Gdy moc > FTP: `W' -= (P - FTP) × Δt` (depletion)
- Gdy moc ≤ FTP: `W' += (FTP - P) × Δt / τ` (recovery, τ=360s)
- W'max z Q server (HIE = 21.3 kJ = 21 300 J)

### IF10 algorytm
- 30s wygładzanie kroczące mocy
- NP = ⁴√(średnia(30s_avg⁴))
- IF10 = NP / FTP
- Okno 10-minutowe, inkrementalna aktualizacja O(1)

### Pobieranie danych z Q server
- Endpoint: `https://ankle-wool-undusted.ngrok-free.dev/ride-readiness`
- Metoda: `OnHttpResponse.MakeHttpRequest(GET, waitForConnection=true)`
- Działa przez Companion App (bez WiFi)
- Odpowiedź JSON: `{ ftpWatts, wPrimeKj, todayFactor, ltpWatts, bodyWeightKg, ... }`
- Store: SharedPreferences (`AthleteDataStore`)
- Wyświetlanie: SetupActivity z datą pobrania

### Pliki
```
app/src/main/kotlin/com/qext2/primary/
├── QExt2PrimaryExtension.kt          — entry point, singleton, tworzy agregator
├── engine/RideDataAggregator.kt       — 6 strumieni, snapshot co 1s
├── model/PrimaryRideSnapshot.kt       — model danych PRIMARY
├── data/AthleteDataStore.kt           — przechowuje FTP/W'max z Q server
├── setup/SetupActivity.kt             — wyświetla dane z Q, fetch przez Karoo HTTP
├── datatypes/
│   ├── CompositePrimaryDataType.kt    — PRIMARY field (4-col)
│   └── CompositeActiveDataType.kt     — ACTIVE field (4×2, W', IF10, Headwinds)
app/src/main/res/
├── layout/
│   ├── field_primary_4col.xml         — layout PRIMARY
│   ├── field_active_4x2.xml           — layout ACTIVE
│   ├── field_primary_fallback.xml     — fallback PRIMARY
│   └── activity_setup.xml             — Setup z danymi sportowca
├── drawable/
│   ├── ic_qext2_distance.xml          — ikona D z VinHKE
│   ├── ic_qext2_dtd.xml               — ikona DTD z VinHKE
│   └── ic_qext2_*.xml                 — pozostałe ikony (HR, cadence, power, etc.)
└── xml/extension_info.xml             — rejestracja DataType
```

### Problemy otwarte
- Ngrok tunel pada — Q server na maszynie użytkownika musi być uruchomiony
- IF10 i W' używają domyślnego FTP=250 gdy brak danych z Q
- Headwinds musi być zainstalowane na Karoo dla wiatru
- DTD (DISTANCE_TO_DESTINATION) wymaga załadowanej trasy
