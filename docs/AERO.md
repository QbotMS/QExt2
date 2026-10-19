# AERO — zmiana oporu powietrza w trakcie jazdy (QExt2 0.4.1, 2026-10-17)

Decyzja Michała (16–17.10.2026): badanie wpływu pozycji (klamki / dropy / lemondka) na jednym rowerze, bez oznaczania pozycji.
Strona QBota (porównania odzieży, kół, rowerów między jazdami): `/opt/qbot/app/docs/TODO.md` [AERO-CDA].

## Gdzie
- SETUP → „KOKPIT 2: pole dynamiczne”: **DYNAMIC** (domyślnie, rotacja jak dotąd) albo **AERO**.
  AERO zastępuje pole dynamiczne w całości — bez wyjątków (także bez „wymaganej średniej”) i bez rotacji.
- Pref: `kokpit2_dyn_aero` (`AthleteDataStore.load/saveKokpit2DynAero`).
- Estymator liczy ZAWSZE (także przy DYNAMIC) i zapisuje wynik do FIT — pole jest tylko widokiem.

## Wyświetlanie (`aero  −11 W ●`)
- **−11** = tyle watów MNIEJ potrzeba przy bieżącej prędkości niż w poprzedniej pozycji (minus = zysk), **+6** = strata, **0** = bez zmiany.
- **···** = zbiera pierwszy poziom (30 s ważnej jazdy), **—** = brak warunków.
- Kropka: zielona = pewny (≥ 20 s po zmianie), żółta = wynik się zbiera.

## Algorytm (`engine/AeroEstimator.kt`)
1. Co ~1 s: Pa = P·0,976 − m·g·v·(nachylenie + 0,007) − m·v·a; x = ½·ρ·v³ (ρ z temperatury Karoo); m = waga z QBota + 9 kg.
2. Sekunda ważna: moc i prędkość świeże (< 3 s), v ≥ 18 km/h, P ≥ 60 W, kadencja ≥ 40, |nachylenie| ≤ 4%, |a| ≤ 0,6 m/s².
3. Poziom = ważne sekundy w tym samym kierunku (zmiana > 35° albo przerwa > 20 s → nowy poziom bez odniesienia).
4. CdA = ΣPa / Σx (stosunek sum). Zmiana: ostatnie 12 s vs wcześniejsza część poziomu (≥ 30 s), różnica > max(0,02; 3·szum)
   przez 5 kolejnych s → odniesienie = poprzedni poziom, nowy poziom = ostatnie 12 s.
5. Wynik dW = ½·ρ·(CdA_teraz − CdA_odniesienia)·v³.
- Toczenie i wiatr są stałe w krótkim oknie (ten sam kierunek) → znoszą się; dlatego porównanie tylko „przed/po” w jednym kierunku.
- Znane ograniczenia v1: moc 3 s (lekkie opóźnienie), nachylenie z Karoo wygładzone, porywy wiatru dają fałszywe zmiany
  (wymóg 5 s utrzymania + próg szumu), kręte drogi często resetują poziom.

## FIT (dev fields, pola dodatkowe — schemat bez zmian)
`qext2_aero_state` (0/1/2), `qext2_aero_dw_w` (W), `qext2_aero_cda` (CdA bieżącego poziomu), `qext2_aero_ref_cda` (odniesienie).
Dziennik `ride_log.txt`: `AERO_STEP ref= new= v= thr=`, `AERO_RESET przerwa|kierunek`.
Po co w FIT: ocena, jak pole działało na żywo (kiedy wykryło zmianę, czy zgadza się z odczuciem). Analiza QBota liczy własnym
modelem z surowych danych 1 Hz — pola AERO nie są jej wejściem.


## v2 (18.10.2026)
Po jezdzie 18.10 (AERO policzylo 1 raz; 87 restartow 'kierunek', a na prostym odcinku km 10-12 pole stalo na 0 W):
- licza sie tylko sekundy ROWNEJ jazdy: kadencja w ostatnich 20 s +-8 obr/min, moc 3 s +-20% sredniej z 20 s, |a| <= 0,4 m/s2, kadencja >= 50;
- zmiana = ostatnie 30 s vs poprzednie 60 s (z sum mocy i v^3), prog 0,10 CdA, utrzymana 5 s;
- kazda zmiana oporu jest zmiana (nawierzchnia, jazda za kims, pozycja) -- Crr stale;
- restart po zmianie kierunku > 35 st. tylko przy wietrze >= 3 m/s; przerwa / nierowna jazda > 30 s -> od nowa.
Symulacja na FIT 18.10: ok. 8 zmian/h rownej jazdy, w tescie wykryte 3 z 4 zmian chwytu (bez pierwszego zejscia -- przed nim rozpedzanie).

## v3 „POZYCJA” (19.10.2026, QExt2 0.4.2)
Zadanie: QBot `docs/QEXT2_AERO_V3_TASK.md`. Cel (decyzja Michała 19.10): optymalizować opór, na który Michał ma wpływ (pozycja),
z odjętym wiatrem i drogą; zmiana nawierzchni to NIE pozycja.
Logika: `engine/AeroCore.kt` (bez Androida — ten sam kod na Karoo, w testach i w odtwarzaniu jazd); most Karoo: `engine/AeroEstimator.kt`.
- **Bilans energii** co sekundę: Pa = P·η − m·g·dh/dt − ½·m·d(v²)/dt − m·g·Crr[nawierzchnia]·v; X = ½·ρ·va·|va|·v; CdA = ΣPa/ΣX.
  Sumy na odcinku = różnica wysokości i prędkości na końcach (nie chwilowe nachylenie). Wysokość: barometr Karoo
  (`PRESSURE_ELEVATION_CORRECTION`), surowa (Karoo już wygładza; średnie 3–5 s psuły bilans na załamaniach profilu).
  Przetwarzanie opóźnione o 2 s, żeby moc 3 s była wyśrodkowana względem prędkości.
- **Wiatr:** va = v + wiatr czołowy × 0,2 (`AeroParams.WIND_FACTOR`; QBot może nadpisać `windFactor`). Prognoza (10 m) przy rowerzyście
  jest dużo słabsza: odtworzenie 18.10 — rozrzut CdA najmniejszy i brak korelacji z wiatrem przy 0,15–0,2; przy 0,6 korelacja −0,56.
  Wiatr nieznany → po zmianie kursu > 35° od nowa (jak v2).
- **Nawierzchnia:** z profilu trasy QBota w bieżącym miejscu (PAVED = asfalt, GRAVEL/LOOSE = szuter); bez trasy = nieznana (Crr asfaltu).
  Zmiana klasy → od nowa (`AERO_RESET nawierzchnia`).
- **Pewność:** 2 dobra = asfalt + wiatr znany + parametry QBota; 1 niska = szuter / nieznana / bez QBota / bez wiatru. Kropka: zielona = 2, żółta = 1.
- **Sekunda ważna:** świeże dane, v ≥ 18 km/h, ostatnie 5 s pedałowania (P ≥ 60 W, kadencja ≥ 50), |nachylenie z 10 s| ≤ 4 %,
  |a| ≤ 1,5 m/s² (tylko błędy czujnika — przyspieszanie liczy bilans). Bez filtra równości mocy (sprawdzone: mało pomaga, zabiera do 2/3 wyniku).
- **Zmiana:** ostatnie 30 s vs poprzednie (do 60 s), ≥ 10 % CdA i ≥ 6 W przy bieżącej prędkości, utrzymana 8 s → nowe odniesienie.
- **Parametry z QBota** (`/ride-readiness` → `aeroModel`, zapis w pamięci Karoo): masa = riderKg + extraKg, Crr asfalt/szuter, η.
  Brak → zapas jak v2 (waga z Setup + 9 kg, Crr 0,007), pewność zawsze niska.
- **FIT (nowe pola):** `qext2_aero_ver` (3), `qext2_aero_conf` (0/1/2), `qext2_aero_wind_head` (m/s użyte w równaniu), `qext2_aero_surface` (0/1/2).

### Testy (`app/src/test/.../engine/`)
- `AeroCoreSyntheticTest` — fizyka do przodu co 0,1 s, szum miernika 3 %, prawdziwe wahania mocy 15 %, barometr 0,2 m:
  zakręt 90°/180° przy wietrze 3–6 m/s bez fałszywej zmiany > 10 W; pętla z wiatrem 5 m/s; skok −15 % CdA wykryty ≤ 30 s (i powrót);
  asfalt → szuter = „od nowa”, nie pozycja; dryf barometru 0,1 %; pagórki ±3 %; interwały 150/280 W; wiatr nieznany; JSON z QBota.
- `AeroReplayTest` — odtworzenie jazd 16.10 i 18.10 (eksport QBota 1 Hz, `src/test/resources/aero`):
  16.10 (asfalt): wynik 33 % czasu, pewność dobra 91 %, CdA mediana 0,44 (QBot Centrum CdA 0,45); 18.10 (szuter): wynik 44 %, CdA 0,555 (QBot 0,51).
  **Uczciwie:** na prawdziwych jazdach 30-sekundowe CdA ma rozstęp ćwiartkowy ~22 % (wahania wiatru, drogi, ruch), więc pole
  zgłasza ~50 zmian na godzinę wyniku. Skok −15 % jest na granicy szumu — sprawdzian na jeździe: asfalt, pętla, „okrążenie” przy zmianie chwytu.

## v3.1 — windScale z QBota (19.10.2026, QExt2 0.4.3, ver w FIT = 4)
Zadanie: QBot `docs/QEXT2_AERO_V3_TASK.md`, sekcja „windScale”. QBot sprawdził na 22 jazdach (ten sam model co Centrum CdA), że wiatr
z prognozy Open-Meteo na 10 m pasuje do jazdy dopiero po pomnożeniu przez k ≈ 0,25–0,5 (mediana 0,25).
- Wiatr czołowy w równaniu = `aeroModel.windScale` × składowa czołowa wiatru z prognozy (QExt2 bierze własną prognozę Open-Meteo 10 m,
  `RideDataAggregator.getHeadwindSignedMps` — ten sam rodzaj danych, z którego QBot liczy k). Starsze pole `windFactor` nadal czytane;
  brak obu / stary pakiet → 0,3 (domyślny QBota). Poprzednio stała 0,2 z jednej jazdy (18.10).
- FIT `qext2_aero_wind_head` = wartość już po pomnożeniu (bez zmian w zapisie); `qext2_aero_ver` = 4.
- Test `petla_windScale_025_vs_bez_skalowania`: pętla przy wietrze 3 / 4,5 / 6 m/s na 10 m, przy rowerze 0,25 × tyle —
  z mnożnikiem 0,25 zero fałszywych zmian; bez mnożnika (prognoza wprost) błąd 120 / 242 / 471 W i 18–20 fałszywych zmian.
