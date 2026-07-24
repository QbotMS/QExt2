# QExt2 — kontekst dla Claude

**Stan: build-122 (2026-06-16). Czytaj ten plik na początku każdej sesji.**

---

## Co to jest

QExt2 to rozszerzenie Kotlin/Android dla komputera rowerowego **Hammerhead Karoo**. Wystawia niestandardowe pola danych (data fields) widoczne podczas jazdy. Właściciel: Michał (użytkownik Claude), główna komunikacja przez iPhone.

**Deploy loop:** commit do `main` → GitHub Actions (`.github/workflows/build.yml`) → `app-debug.apk` → Release `build-NN` → instalacja przez Karoo companion app na iPhonie.

---

## Architektura

### Pola (DataType) — 4 aktywne

| Plik | Pole | Rozmiar |
|------|------|---------|
| `CompositePrimaryDataType.kt` | Pole główne: HR, moc, prędkość, kadencja, grade z kolorowym tłem | 4×2 |
| `CompositeActiveDataType.kt` | Pole aktywne: W', RSRV, wiatr, karb, komunikaty | 4×2 |
| `BpActiveStaticDataType.kt` | Pole statyczne BP: ciśnienie baro, zachód słońca, barometr | 2×2 |
| `StatsDataType.kt` | Pole statystyk: NP, VI, IF, TSS, ETA, czas, wattki śr. | 3×3 |

### Rdzeń silnika

```
QExt2PrimaryExtension (serwis Android)
  └── RideDataAggregator (engine/)
        ├── StatsCalculator — W'bal, RSRV, TSS, karby, dekoupling
        ├── HrStrainAdvisor — HR drift/strain
        ├── AthleteDataStore — SharedPreferences + dane z QBota
        └── WeatherClient — OpenWeatherMap API
```

### Produktory komunikatów (`active/`)

| Klasa | Komunikaty |
|-------|-----------|
| `ClimbAnnouncementProducer` | Pre-climb (podejście), PODJAZD DONE |
| `ClimbPacingProducer` | UWAGA! — stan W' (TRZYMASZ / odbudowa MM:SS / bomba MM:SS / PRZEPAŁ), PACING CLIMBING ON |
| `WeatherMessageProducer` | WX BURZA, WX ULEWA, WX UPAL, WX WIATR itd. |
| `SensorMessageProducer` | BRAK MOCY, BRAK HR, BRAK SENSORÓW |
| `FuelReminderProducer` | ZJEDZ ~Ng, PIJ, SÓD 500-800mg |

---

## Bramkowanie widoczności pola (KRYTYCZNE)

**Build #101/#119.** Agregator NIE działa 24/7. Startuje gdy pole QExt2 jest na ekranie, zatrzymuje się (soft stop) 20s po zniknięciu.

- `onFieldVisible()` → start streamingu + pollingu (jeśli był zatrzymany)
- `onFieldHidden()` → po 20s debounce: `stopStreamingSoft()`
- **`stopStreamingSoft()`** = odepnij strumienie + tick, ZACHOWAJ stan sesji (W', RSRV, TSS, karby, HR buffer). Resetuje tylko `stopStreamingInternal()` (disconnect serwisu).
- Weather poll + battery poll też gated na widoczności.
- Auto-fetch danych z QBota przy pierwszym `onFieldVisible()` jeśli dane starsze >30 min.

---

## Model mocy — kolor pola Power

Trzy kolory robocze (bez szarego):
- 🔴 `#FF5252` — moc ≥ ceiling
- 🟢 `#4ADE80` — moc ≥ ceiling × 0.85
- ⚪ WHITE — poniżej celu
- Jasnoszary `#CBD5E1` — tylko brak sygnału/stale

**Ceiling** (dynamiczny):
```
ceiling = effectiveLTP × min(wFac, rsvFac) × modeFactor × decFac

wFac   = 1 + 0.20 × wBal%          (W' short-term)
rsvFac = 1 + 0.20 × projectedRSRV% (RSRV long-term, wymaga trasy)
modeFactor = 0.88/1.00/1.12         (tryb jazdy)
decFac = 1 - 0.01×(decoupling-5%)  (HR decoupling, floor 0.90)
```

**Tryb jazdy (SetupActivity):** DEFENSYWNA=0.88 / NORMALNA=1.00 / OFENSYWNA=1.12 / AUTO (default). AUTO = z todayFactor: <0.90→def, >1.02→off, else norm. Ustawiony przy każdym fetchu QBota.

---

## Model W' (W prime)

**Build #122 — krytyczna poprawka architektury:**

- **Fizyka W'** (`StatsCalculator.updateWBalance`) liczy na BAZOWYCH parametrach Xerta (LTP 194W, W' 22kJ). Deplecja tylko powyżej realnego progu fizjologicznego.
- **Czynniki korygujące** (todayFactor, temperatura, modeFactor) działają WYŁĄCZNIE na pacing layer (ceiling koloru, targety komunikatów). Nie wchodzą do `setWPrimeParams`.
- Model Skiby: `wBal -= (P - LTP)/1000` gdy P>LTP; `wBal += (wMax-wBal)×(1-e^(-1/tau))` gdy P<LTP; τ=546s.

**Korekty na pacing layer** (nie na fizyce):
- `effectiveLTP = baseLTP × cf` gdzie `cf = todayFactor × tempFactor` (floor 0.75, cap 1.10)
- `tempFactor`: -0.7%/°C powyżej 20°C, floor 0.85 — używa `physioTempC()`
- `physioTempC()` = API pogodowe gdy świeże, fallback na sensor Karoo (sensor zaniżony w pędzie!)

---

## Grade — kolorowe tło

Paleta z `timklge/karoo-routegraph` (odwzorowanie Karoo CLIMBER):

| Próg | Tło | Tekst |
|------|-----|-------|
| < −8% | `#2D58AF` ciemnoniebieski | biały |
| −8..−5% | `#4FC3F7` jasnoniebieski | czarny |
| −5..−2% | `#FFFFFF` biały | czarny |
| −2..1% | `#111827` ciemny (neutral) | biały |
| 1..2% | `#58C597` jasnozielony | czarny |
| 2..5% | `#079D78` ciemnozielony | biały |
| 5..8% | `#E7E021` żółty | czarny |
| 8..11% | `#E59174` jasnopomarańcz | czarny |
| 11..14% | `#E7693A` ciemnopomarańcz | biały |
| 14..20% | `#C82425` czerwony | biały |
| ≥20% | `#B222A3` fiolet | biały |

Tekst dobierany luminancją tła (≥150→czarny, else biały).

---

## Komunikaty podjazdów

**Dane ze SDK** `OnNavigationState.ns.climbs` (te same co Karoo CLIMBER, nie własna detekcja).

`ActiveClimbResolver`:
- `isWithinClimbBounds = distanceMeters ∈ [startDistance, startDistance+length+200m]`
- `avgGradePercent = candidate.grade` (średnia z trasy, nie live sensor)
- `distanceToClimbM = (startDistance - distanceMeters).coerceAtLeast(0)`

`ClimbAnnouncementProducer`:
- `checkPreClimb` → "PODJAZD: Xkm ↑Ym +Z%" (500m przed)
- `checkClimbActive` → tylko zarządza stanem (brak komunikatu — zastąpiony pacing)
- `checkClimbFinish` → "PODJAZD DONE ↑Ym"

`ClimbPacingProducer` — dwa niezalezne watki:

**1. Stan W' (Priority 1)** — dziala ZAWSZE, nie tylko na podjezdzie.
Wyzwalacz: `W'bal < 55%`. Moc: srednia 3 s. CP: `cpEffW` (NIE `getEffectiveLtpWatts()` — to LTP, inna liczba!).
Jeden komunikat `UWAGA!` / `X% W'` + druga linia zalezna od tego, czy palisz czy odbudowujesz:

| druga linia | warunek | severity | rytm |
|---|---|---|---|
| `TRZYMASZ!` | \|moc-CP\| <= 10 W | WARNING | co 60 s |
| `odbudowa MM:SS` | moc < CP-10 (cel 90%) | WARNING | co 60 s |
| `bomba MM:SS` | moc > CP+10, t > 2 min | WARNING | co 30 s |
| `bomba MM:SS` | 30 s - 2 min | WARNING | co 10 s |
| `bomba MM:SS` | < 30 s | CRITICAL | co 1 s + beep |
| `PRZEPAŁ` | 0% W' i moc > CP+10 | CRITICAL | co 10 s |

**2. PACING CLIMBING ON (Priority 2)** — przy zmianie kontekstu na climbing (cooldown 10 min).
Wariant ENDURANCE wylaczony 2026-07-20.

UWAGA — czego TU NIE MA (bylo w starej wersji tego dokumentu, w kodzie nie istnieje):
"CEL: X-Y W" i "MOZESZ MOCNIEJ" nie sa produkowane. "ZA MOCNO" usuniete 2026-07-24.

---

## Komunikaty fueling (zero klikania)

`FuelReminderProducer` — napędzany pracą, nie zegarem:

- **ZJEDZ ~Ng** — co `packetSizeG` gramów zalecanego spożycia (model `carbsGPerH`)
- **PIJ** — co 0.25L zalecanego płynu (model `fluidLPerH`)
- **SÓD 500-800mg** — co godzinę gdy `physioTempC >= 28°C`
- Pierwsze po 20 min jazdy, min 90s między komunikatami fuel
- Stan przeżywa soft stop (view switch), resetuje tylko pełny stop

---

## Pogoda

- OpenWeatherMap API, key w `BuildConfig.OPENWEATHER_API_KEY`
- Key osadzony jako fallback w `app/build.gradle.kts` (buduj lokalny: `local.properties`)
- **WAŻNE**: po wyjeździe zrotować klucz na openweathermap.org i przenieść do GitHub Actions Secret `OPENWEATHER_API_KEY`
- Poll co ~10 min, tylko gdy streaming aktywny
- Lokalizacja: GPS z jazdy (save co 30s), fallback: ostatnia znana
- Fresh = <30 min; stale → komunikaty WX odrzucane

---

## QBot integracja

Fetch athlete data z `https://qbot.cytr.us/ride-readiness` (Bearer token w AthleteDataStore).

Kluczowe pola z odpowiedzi:
- `todayFactor` → tryb jazdy AUTO + pacing korekty
- `ltpWatts`, `wPrimeKj`, `ftpWatts` → baza modelu W' (fizyka)
- `baroMultiplier` → korekta barometryczna
- `maxHrBpm` (lowercase r! JSON: `"maxHrBpm"`, nie `"MaxHRBPM"`)
- `sources: ["intervals","xert","partial"]` → "partial" = Garmin nie zaimportował, NOT błąd. Profil OK jeśli ltpWatts>0 i ftpWatts>0.

Auto-fetch przy `onFieldVisible()` gdy dane >30 min stare.

---

## Paleta kolorów (ujednolicona)

Używaj tych wartości wszędzie:
- Zielony: `#4ADE80` (nie `#22C55E` — za ciemny)
- Czerwony: `#FF5252` (nie `#EF4444` — za ciemny)
- Pomarańcz: `#FB923C` (nie `#F97316`)
- Żółty: `#FACC15` (nie `#F59E0B` / `#E7E021` — amber wygląda pomarańczowo w słońcu)
- Jasnoniebieski: `#38BDF8`
- Szary label: `#CBD5E1`
- Szary nieaktywny: `#6B7280`

---

## Znane ograniczenia i TODO

**Architektoniczne:**
- Klucz OWM w kodzie (publiczne repo!) — zrotować po wyjeździe + przenieść do Secrets
- `ClimbPacingProducer` i inne nowe pliki są untracked w lokalnym klonie → przy commitach przez API zawsze dodawać explicite do file listy

**Pacing:**
- RSRV projection (rsvFac) działa tylko gdy jest załadowana trasa z remaining distance
- Decoupling factor: <5% normalne, 5-15% liniowe zaciskanie ceilingiem (−1%/pkt), cap −10%
- `projectedRSRV` zakłada stałą stopę drain (liniowa ekstrapolacja) — wystarczające dla komunikatów

**Fueling:**
- Brak inputu od użytkownika o tym co zjadł (świadoma decyzja) — model prescription-only
- `carbsGPerH` i `fluidLPerH` w `StatsCalculator` są proporcjonalne do IF i temperatury; formuły warto zaudytować po wyjeździe
- Pre-climb fueling ("zjedz PRZED podjazdem") — niezaimplementowany, dobry next step

**Do rozważenia:**
- Post-ride summary → QBot (Michał niezdecydowany)
- Zrotacja klucza OWM + GitHub Actions Secret
- Merge brancha do main (ostatni merge był przed Toskanią)

---

## Deploy + workflow

```bash
# Token: w pamięci Claude (userMemories) i AthleteDataStore — nie wpisuj do plików
# Repo: QbotMS/QExt2, branch: main
# Instalacja APK: https://github.com/QbotMS/QExt2/releases/latest

# WAŻNE przy nowych plikach (create_file): git diff nie widzi untracked files
# → dodawaj explicite do file listy przy commit przez GitHub API

# Brace balance check przed commitem:
for f in *.kt; do
  o=$(tr -cd '{' <"$f"|wc -c); c=$(tr -cd '}' <"$f"|wc -c)
  [ "$o" != "$c" ] && echo "MISMATCH $f"
done

# XML check:
python3 -c "import xml.dom.minidom as m; m.parse('layout.xml'); print('OK')"
```

---

## Kluczowe lekcje z Toskanii 2026

1. **`stopStreamingSoft()` vs `stopStreamingInternal()`** — soft stop zachowuje stan sesji, full stop zeruje. Mylenie = data loss mid-ride (było w build #101, naprawione #119).
2. **cf NIE idzie do fizyki W'** — tylko do pacing layer. Inaczej deplecja 3× za szybka (było w build #104, naprawione #122).
3. **Temperatura z API, nie z sensora** — Karoo sensor zaniżony w pędzie. `physioTempC()` = API gdy świeże.
4. **Untracked files w git** — `create_file` tworzy lokalnie, nie przez git. `git diff --name-only` pomija. Dodawaj explicite.
5. **`contains("partial")` na sources array** — fałszywy alarm profilu incomplete. "partial" to nazwa źródła, nie błąd.
6. **Szary kolor mocy** — niewidoczny w słońcu przy niskiej jasności. Trzy kolory wystarczą.
7. **`maxHrBpm` lowercase r** — JSON ma "maxHrBpm" nie "MaxHRBPM".
8. **`ClimbPacingProducer.kt` był untracked** — deploy przez API builds omijał go w commitach.
