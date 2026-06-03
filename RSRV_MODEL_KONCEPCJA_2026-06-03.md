# RSRV — koncepcja modelu, kalibracja, stan i backlog

**Data:** 2026-06-03 · **Pole:** RSRV (rezerwa dzienna) w STATS · **Repo:** QExt2 (logika) + QBot (dane/config)

## 1. Problem wyjściowy
RSRV ma pokazywać, ile zostało „na dziś" — start ~100%, spadek w trakcie jazdy
(wolno gdy spokojnie, szybko gdy ostro), 0% = realne wyczerpanie. Karoo dawało
absurdy: **TSS 48 / 20 km → ~60%**. Przyczyna: wzór `sqrt(TSS)*5` (wklęsły, żre
za dużo na starcie) + budżet zaszyty ~3,3× za mały + podwójne kary IF i czasu.

## 2. Koncepcja (po kilku iteracjach)
- **Jednostka = TSS (strain), nie glikogen.** Glikogen odrzucony, bo wymagałby
  podawania węgli w trasie (niemożliwe). TSS już zawiera intensywność (IF²×czas),
  więc rezerwa spada **liniowo w TSS** — żadnych dodatkowych kar IF/czas.
- **Drain (live):** `reserve -= TSS × 100/budżet`. Nieliniowość intensywności
  siedzi w samym TSS (lekko = tanio, ostro = drogo).
- **Budżet dzienny** = mianownik definiujący 0%. Nie zamrożony — docelowo skaluje
  się formą: `budżet = ζ × TrainingLoad × świeżość`.
- **Świeżość / wczorajszy load:** `todayFactor` z Xerta (forma), skaluje start.
- **0% = sufit modelowy**, którego Michał nie dotyka (nie jeździ w trupa).
  Normalny dzień ląduje 28–88%. To jest poprawne, nie zepsute.
- **Dryf HR (dekupling):** jedyny sygnał na upał/gorszy dzień live (moc tego nie
  widzi). RHR u Michała NIE reaguje (patrz kalibracja) — stąd dryf, nie bezwzgl. HR.
- **Recovery na postojach:** eksponenta τ=30 min (już było, zostaje).

## 3. Kalibracja na danych Michała (z QBota, 2026-06-03)
Sygnatura Xert (`qbot_v2.xert_profile_snapshots`):
- TP 245 W · LTP 193 W · W' 21 kJ · PP 999 W
- Training Load 56 · Recovery Load 49 · form_ratio +0.13 · status "Fresh"
- HR max 184 (Intervals) · RHR ~46 (wellness_daily; min 42, avg 46,4)

Najcięższy zarejestrowany dzień (`qbot_v2.training_sessions`):
- **2026-05-21:** 102 km, 6,3 h, NP 165, IF 0.67, TSS 280, HR śr 120 / max 173.
- Subiektywnie zostało mu ~20–30% (czyli NIE na zero).

Dose-response (TSS dnia → samopoczucie następnego ranka, training_sessions ⋈ wellness_daily):
- **RHR praktycznie płaskie** — nawet po 280 TSS tylko +2 bpm. Michał regeneruje się
  tak dobrze, że RHR nie nadaje się jako wykrywacz sufitu (dałby absurdalne ~600–900).
- **Body Battery** spada ~−0,21/TSS → ekstrapolacja do redline ~380–450 TSS.

**Konwergencja:** subiektywne 25% → ~390 · BB dose-response → ~380–450. Zgodne.
→ **BUDŻET = 390 TSS**, czyli **ζ = 390/56 ≈ 7,0**.

## 4. Co zaimplementowano (patch 2026-06-03, OpenCode)
Plik: `app/.../engine/StatsCalculator.kt` → `fun rideReservePercent(...)`
- `sqrt(TSS)*5` → **`TSS × 100/390`** (liniowo, budżet 390 zaszyty)
- **usunięto** karę IF `(if-0.80)*100` (podwójne liczenie intensywności)
- **usunięto** karę czasu `(h-1.5)*4` (podwójne liczenie czasu)
- dekupling i recovery — **bez zmian**
Walidacja: TSS 48 → ~88% (było ~60%) · 204 → ~48% · 280 → ~28%.

## 5. Weryfikacja wiringu (2026-06-03, potwierdzone na żywo)
- **todayFactor działa.** Żywy `/ride-readiness` obsługuje `mcp_server.py`
  (`_compute_today_factor`, ~linia 2898; payload ~3093). Dziś live `todayFactor=0.993`,
  `ftpWatts=244.6`, klucze zgodne z parserem QExt2 (`QExt2PrimaryExtension.kt` ~181).
  Łańcuch: fetch → parse → applyBaroAdjustment (neutralny gdy off) → save →
  updateAthleteData → `statsCalc.todayFactor` → captureStartReserve. OK.
- **Route NIE jest wymagane.** Żywy gate: `rsrvModelReady = isMoving && reserve∈[0,100]`
  (`RideDataAggregator.kt` ~885). `RsrvDisplayPolicy.decide` (route+120s+wprime+fuel)
  jest MARTWY — używają go tylko testy. Wyświetlanie idzie przez `localRsrv`.
- **Budżet od dziennego TSS.** `effectiveTss = dailyTssBase + sessionTss`. Wieloetapowość
  w obrębie dnia działa; baza persystowana w `AthleteDataStore` (ReserveDailyTssBase).

## 6. P0 — naprawione/do naprawy PRZED wyjazdem
**Reset dnia po dacie kalendarzowej.** `dailyTssBase` resetował się TYLKO przez marker
snu z `/ride-readiness` (`shouldApplySleepRefresh`). Offline / brak sync snu → dzień 2
startuje z TSS dnia 1. Jedyny backstop to guard `>500f`. Fix: persystować datę bazy i
resetować przy starcie jazdy gdy data ≠ dziś (lokalnie), niezależnie od łączności.
Dotknięte: `AthleteDataStore` (KEY_RESERVE_BASE_DATE + save/load), `RideDataAggregator`
init ~285, maybePersistReserveBase ~1231, stopStreamingInternal commit ~942.
Granica dnia = lokalna północ, sprawdzana przy starcie (jazda przez północ nie resetuje
się w trakcie). [Status: ZAINSTALOWANE 2026-06-03 — reset o lokalnej północy, sprawdzany przy starcie każdej jazdy przez porównanie daty w SharedPreferences; niezależny od snu i łączności]

## 7. Backlog — niedokończone wątki (PO powrocie)

### QExt2
- [ ] **Budżet z QBota zamiast 390 zaszytego.** QBot liczy `ζ × TL × świeżość` i podaje
      w `/ride-readiness` (tak jak `todayFactor`), QExt2 czyta zamiast stałej. Wtedy
      budżet jedzie za formą sam. `ζ≈7,0` doszlifować po kilku etapach z RPE (samouczenie).
- [ ] **Dekupling jako MNOŻNIK drainu**, nie płaskie odjęcie `(decouple-5)*1.5`.
      Czystsze fizjologicznie: `drain *= 1 + gain*(HRR_real - HRR_expected)`.
- [ ] **Sprzątnąć martwy `RsrvDisplayPolicy`** (route-gated) — testy ćwiczą logikę,
      której się NIE używa. Albo wpiąć ją na żywo, albo usunąć i przepiąć testy na realny gate.
- [ ] **Martwy `intensityFactor`/`ifSafe`** w `rideReservePercent` — do usunięcia (warning).
- [ ] **XSS low/high/peak split** zamiast jednego TSS (rozdzielić systemy: tlenowy/
      wysoki/neuromięśniowy). Xert je daje (targetXSS: xlss/xhss/xpss).

### QBot
- [ ] **Martwy duplikat `/ride-readiness` w `qbot_api.py`** (~566–763) — BEZ `todayFactor`.
      Żywy jest `mcp_server.py`. Jeśli routing kiedyś przełączy się na qbot_api.py,
      świeżość po cichu padnie do 1.0. Usunąć duplikat albo dorobić w nim todayFactor.
- [ ] **Wystawić budżet `ζ×TL` w `/ride-readiness`** (patrz QExt2 pkt 1).
- [ ] **Zweryfikować `bodyWeightKg: 102`** — karmi czynnik upału (`bodyWeightKg/70`
      w heatFactor). Sprawdzić czy to aktualna masa.

## 8. Pliki referencyjne
**QExt2:** `StatsCalculator.kt` (rideReservePercent) · `RideDataAggregator.kt`
(effectiveTss ~829, rsrvModelReady ~885, init ~285, persist ~1231, commit ~942) ·
`QExt2PrimaryExtension.kt` (~150–210 fetch+parse /ride-readiness) · `AthleteDataStore.kt`
(todayFactor, ReserveDailyTssBase, applyBaroAdjustment) · `StatsAdvancedFieldPolicy.kt`
(localRsrv = żywe wyświetlanie) · `RsrvDisplayPolicy.kt` (martwe) · `field_stats_3x3.xml`.
**QBot:** `mcp_server.py` (_compute_today_factor ~2898, payload ~3093) ·
`qbot_api.py` (duplikat endpointu) · tabele `qbot_v2.xert_profile_snapshots`,
`training_sessions`, `wellness_daily`.
