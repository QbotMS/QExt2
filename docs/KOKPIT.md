# KOKPIT — pola na ekranie z mapą (QExt2)

Stan: 2026-10-05, budowa 218. Mockup referencyjny: kanwa „QExt2 — pola Karoo”, plansza **„KOKPIT — AKTUALNY”**
(plik `Kokpit6b.dc.html`, rozmiar Karoo 474×126 px na pole). Pozostałe plansze kokpitu są oznaczone PRZESTARZAŁE.

Dwa nowe pola testowe (stare ACTIVE/PRIMARY bez zmian):
- `qext2-kokpit-nav` — **KOKPIT nawigacja (test)**, górne pole: `kokpit/KokpitNavDataType.kt`, `KokpitNavRenderer.kt`, `RouteMessageEngine.kt`
- `qext2-kokpit-inst` — **KOKPIT instrumenty (test)**, dolne pole: `kokpit/KokpitInstDataType.kt`, `KokpitInstRenderer.kt`

Oba rysowane jako bitmapa (RGB_565). Demo: przełącznik w SETUP „STATS v2: dane demo” (wspólny ze STATS v2).

## Zasady ogólne
- **Czytelność ocenia się na zrzucie z Karoo** (`adb exec-out screencap`), nie na mockupie — Karoo 3 ma ok. 2× większą gęstość niż monitor.
- Mockup i kod poprawiane razem; mockup w dokładnym rozmiarze pola (474×126 przy 2 polach na mapie).
- Minimalny drobny tekst: 17 px (wyjątki świadome: DTD w pionie, km/h w dwóch liniach 13 px).
- Rozmiary: Karoo daje 474×126 przy 2 polach na mapie, ok. 478×216 przy jednym. Pole niskie (< 170 px) ma osobny układ.
- Baner Karoo (np. „ACQUIRING GPS”) potrafi zasłonić górę pierwszego pola pod paskiem stanu.

## Zasada kolorów (przyjęta 2026-10-05)
**Biały = w normie, kolor = coś wymaga uwagi.** Kolor jako skala tylko w grafice (łuki, pasek trasy, trójkąt nachylenia).

| Element | Reguła |
|---|---|
| Moc (liczba) | biała; żółta ≥ 95% pułapu PacingEngine; czerwona > pułap |
| Łuk mocy | wypełnienie w kolorze bieżącej strefy Z1–Z6 (wg CP), strefy przygaszone w tle |
| Prędkość (liczba) | biała; łuk niebieski |
| Tętno (liczba) | biała Z1–Z3, żółta Z4, czerwona Z5 (strefy wg LTHR; tryb strefa/bpm z SETUP) |
| Ikona serca | biała; dryf tętna ≥ 6% pomarańczowa, ≥ 10% czerwona (HrStrainAdvisor) |
| W′ | biały > 50%, żółty 20–50%, czerwony < 20% |
| CP/5 i ⌀ śr. prędkości | etykieta zielona (rośnie), czerwona (spada), szara (bez wyraźnej zmiany) |
| ⚡ i V | szare |
| Komunikat | kolor tylko dla ostrzeżeń (W′, stromy zjazd, deszcz, jedzenie, zmrok); informacje białe |
| ETA | czerwona, gdy wypada po zmroku |

## Pole instrumentów (niskie, 474×126)
- Dwie rozsunięte ćwiartki łuku (środki ±24 px od środka pola), 15 px grubości, od dołu pola do samej góry; łuki 2 px niżej niż krawędź.
- Moc: od lewego dołu do szczytu; prędkość: od prawego dołu do szczytu.
- Znaczniki na łukach: biała kreska = CP (5 min), żółta = średnia prędkość; tylko w obrębie grubości łuku.
- Środek: „CP/5 214” (CP nad 5, przy łuku) i „17.5 ⌀” (⌀ przy łuku, 2 px od wartości), 32 px.
- Moc i prędkość na dole łuku, dosunięte do środka (±8 px), min. 54 px (= tętno), maks. 60 px; prędkość z częścią dziesiętną „.4” pół wielkości, góra równo z górą cyfr; szare „W” przy lewym łuku, „km” nad „/h” przy prawym.
- Lewo: serce + tętno (54 px, 10 px od góry), W′ (50 px) z „W′” nad „%”.
- Prawo: „KAD” przy górze cyfr + kadencja (54 px), „BIEG” + bieg rozciągnięty od łuku do krawędzi pola (textScaleX).
- Wspólna linia dołu cyfr: moc, prędkość, W′, bieg.
- Trendy (CP, śr. prędkość): zmiana w ok. 10 min (próbki co 30 s), dopiero po 5 min; progi CP ±3 W, prędkość ±0,3 km/h.

## Pole nawigacji
Układ: komunikat (ok. 30% wysokości, tekst ok. 80% paska) | wiersz A: km zrobione/całość, DTD (pionowo) zostało km, nachylenie | cienki pasek trasy | wiersz B: temperatura (+ opad), ETA, wiatr. Odstępy: 3/5/4/8 px.
- Pasek trasy: przejechane niebieskie, pozostałe wg nawierzchni z QBota (asfalt/szuter/sypkie), postoje ≥ 10 min pomarańczowe, biała pozycja.
- Wiatr: strzałka względem jazdy z rozszerzenia karoo-headwind; zapas: kierunek świata z OpenWeather.
- Opad: teraz (OpenWeather, mm/h) albo zagrożenie (Open-Meteo, % i za ile minut).

## Komunikaty (RouteMessageEngine + RouteMessageRotator)
Priorytet:
0. **W′** (< 55%): „W′ 32%: BOMBA 1:45 / ODBUDOWA m:ss / TRZYMASZ! / PRZEPAŁ” — logika jak ClimbPacingProducer; pokazywany bez przeplatania.
1. Pilne: deszcz teraz / w ciągu 30 min, jedzenie (bilans ≤ −30 g), zmrok (meta po zmroku / < 45 min).
2. Na trasie do 3 km (od najbliższego): stromy zjazd ≤ −6% (z profilu Karoo, + nawierzchnia), podjazd (Karoo), zmiana nawierzchni (QBot).
3. POI z QBota do 2 km: woda, sklep, jedzenie (godziny na dziś; zamknięte pomijane; woda pierwsza).
4. Domyślnie: najbliższa zmiana nawierzchni lub podjazd; bez danych QBota: „brak danych o nawierzchni (QBot)”; bez trasy: „brak trasy”.

Czas: pilny sam 20 s, potem na zmianę co 8 s z kolejnymi; min. 5 s na komunikat; znika, gdy przestaje być aktualny.

## Źródła danych
- QBot: `/api/surface/by-name` (nawierzchnia), `/api/poi/by-name` (POI; proxy w qbot-mcp-bridge), dopasowanie trasy po nazwie lub `#numerze`.
- Karoo SDK: podjazdy (NavigationState), profil wysokości (ETA v2, strome zjazdy), CIVIL_DAWN/CIVIL_DUSK.
- Open-Meteo (prognoza opadu, bez klucza), OpenWeather (pogoda bieżąca).

## Zachowanie bez danych
- Brak QBota: pasek trasy bez nawierzchni, brak komunikatów nawierzchni i POI; ETA z profilu Karoo bez nawierzchni.
- Brak trasy: km bez całości, „zostało —”, ETA „brak”, komunikat „brak trasy” (lub pilny).
- Instrumenty nie zależą od trasy ani od QBota na bieżąco.

## Demo
Zwykła jazda w normie; co minutę 10 s alarmu (moc > pułap, Z5, W′ 14%, spadek CP, ostrzeżenie w komunikacie, czerwone serce; od 40. s serce pomarańczowe).

## KOKPIT 2 (stan na 2026-10-10, build 299)
Pola produkcyjne (stare KOKPIT zostają jako testowe, bez demo):
- `qext2-kokpit2-nav` – **QExt2 KOKPIT 2 nav** (górne), `qext2-kokpit2-instr` – **QExt2 KOKPIT 2 instr** (dolne).
- Kod: `kokpit/Kokpit2.kt` (Kokpit2NavRenderer, Kokpit2InstRenderer, Kokpit2Route, Kokpit2Demo, TurnClassifier, Kokpit2Fonts),
  `kokpit/Kokpit2Config.kt` (ustawienia z QBota); dane: `KokpitNavDataType` / `KokpitInstDataType` z `v2 = true`.
- Demo: SETUP „KOKPIT 2 i STATS v2: dane demo” (pref `kokpit2_demo`) – dotyczy też produkcyjnego STATS v2; działa po ponownym
  wejściu na stronę z polami. Demo nav: rotacja pogody co 8 s (6 scenariuszy, temp. ujemne, śnieg, burza, mgła) i manewrów co 5 s.

### Wygląd (pole 478×143 na ekranie jazdy, 474×126 na ekranie profilu; skala = szerokość/474)
- **Czcionka:** Saira Semi Condensed (OFL, `assets/fonts`): SemiBold – wartości, Bold – moc i prędkość; podpisy systemowe
  (sans-serif-condensed). Odrzucone: Roboto (zwężana), Saira Condensed, Oswald. `CAP = 0.688` (capHeight fontu).
- **Zasada stałości:** wielkości i pozycje liczone z WZORCÓW najszerszych wartości (moc 888, prędkość 88.8, W′ 88, bieg 52×52,
  temperatura −18°, nachylenie −18 %) – nic nie „faluje” między klatkami; wartość szersza niż wzorzec jest tylko zwężana.
- **Nav – pasek komunikatów** u góry; wysokość rośnie z nadmiarem pola. Tło wg poziomu: informacja #1E2731, ostrzeżenie #FFC21A
  (tekst czarny), krytyczny #8B0A1A. Poziom z ustawień QBota (domyślnie: W′, zjazd = krytyczny; deszcz, jedzenie, zmrok = ostrzeżenie;
  centrala wg ważności nadawcy).
- **Nav – wiersz górny (zmienne):** temperatura (termometr graficzny) | pogoda/opad (32/18 px) | **manewr** | nachylenie (do prawej
  krawędzi). Temperatura i nachylenie 42 px na sztywno; manewr dopasowany do miejsca między pogodą (18 px) a nachyleniem.
- **Nav – wiersz dolny (stabilne):** DST (z częścią dziesiętną) | DTD (bez „km”) | ETA (pionowe podpisy) | wiatr (do prawej krawędzi;
  strzałka = kierunek wiatru względem jazdy, m/s jako ułamek). Bez długości trasy.
- **Instr:** belka trasy na górze (przejechane #2E7BFF, asfalt #F2F4F7, szuter #FFB300, trudne #FF2A2A, postoje #FF3DF5, pozycja –
  limonkowe koło #39FF14 z czarną obwódką); wiersz: tętno (serce = dryf) | NP 5 | ⌀ prędkość | KAD; dół: W′ % (52 px, podpis nad) |
  ⚡+strefa (stałe miejsce przy W′) MOC | PRĘDKOŚĆ (żółta #F2C230) V km/h (stałe miejsce przy biegu) | bieg (blat szary 36 px, koronka 52 px).
  Moc i prędkość tej samej wielkości: cała wysokość pod górnym wierszem + 10 px na sztywno.
- **Alarm mocy** (> pułap tempa > 30 s): czerwone tło #FF5252 pod cyframi mocy (`KokpitInstData.powerBg`), cyfry czarne.

### Manewry (następny skręt)
- Odległość: `DataType.Type.DISTANCE_TO_NEXT_TURN` Karoo (agregator: `distToTurnRef`, świeżość 15 s).
- Rodzaj: `TurnClassifier.classify(RouteLine, pozycja + odległość)` – kierunek drogi 40 m przed / 40 m za punktem:
  < 20° prosto, < 45° lekko, < 135° skręt, < 160° ostro, ≥ 160° nawrót; **rondo**, gdy na ±35 m droga skręca w obie strony
  (≥ 40° każda). Test vs Valhalla (trasa 55957534): 19/23 zgodnie, 2 ta sama strona, 2 rondo przy starcie (próg 60° → 40°).
- Wyświetlanie: ikona + dystans (≤ 950 m w metrach co 10 m, < 100 m co 5 m, wyżej km z przecinkiem); ostro/nawrót na żółto
  (wyłączalne w ustawieniach). „Prosto” = punkt nawigacji, który przejeżdżasz na wprost (przełącznik ukrywania – do decyzji po testach).

### Ustawienia z QBota (Setup > QExt2 (Karoo))
- `/ride-readiness` (mcp_server.py, q-bot.service) zwraca `qext2Config`; QExt2 zapisuje (`AthleteDataStore.saveQext2Config`) i ładuje do
  `Kokpit2Config`. Stosowane: wł/wył i progi komunikatów, kolejność (precedencja), poziom tła, rotacja paska, próg i zasięg zjazdu,
  reguły kolorów (moc tempo/strefy, W′ trend/poziom, tętno strefa/bpm, prędkość żółta/biała, wiatr progi/biała, manewr, progi ETA).
  Brak ustawień = dotychczasowe zachowanie. Dok. po stronie QBota: `/opt/qbot/app/docs/QEXT2_CONFIG.md`.

### STATS v2 (ujednolicony z KOKPIT 2)
- Ta sama czcionka (Saira Semi Condensed), belka trasy w kolorach KOKPIT 2, demo z tego samego przełącznika.
- **Nowy układ (16.10.2026, 0.4.0):** NP/IF/VI | PRZEBIEG | RSRV / XSS / KCAL | PLAN CZASU | TEMPO | BRAMA ‖ ŚWIATŁO | CARB / KAD / BAT
  (wiersze 113/136/100/86/120/85/108 z 760). TRASA i PRZEWYŻSZENIE przenoszone do pola INFO (osobny build).
  - XSS: pasek wypełnienia do planu dnia Trenera (QBot `/ride-readiness` → `xssPlanToday`, suma XSS roweru na dziś); bez planu sama liczba.
  - PLAN CZASU: z modelu czasu przejazdu QBota (`/api/plan/by-name`, `PlanProfile`): faktyczny czas ruchu i postojów vs plan w bieżącym km
    (jazda ± / postoje ±); bez profilu zapas: bieżąca ETA vs ETA po 1. km (`StatsV2Extras`).
  - TEMPO: średnia moc i prędkość ruchu w każdej pełnej godzinie ruchu + bieżąca (≥ 5 min), `RideWindows.tempoSnapshot`; spadek mocy od 1. h.
  - BRAMA: komórka-przycisk (przezroczysta nakładka `stats_v2_gate_tap` w `field_stats_v2.xml`), logika jak pole BRAMA (`docs/BRAMA.md`).
  - ŚWIATŁO: stan ze strumienia RADAR, bateria z SavedDevices; tapnięcie = następny tryb (miganie → szybkie → stałe mocne → stałe słabe → wył.)
    przez NIEOFICJALNĄ usługę czujników Karoo (`light/LightControl.kt`, wg kxradar/karoofirefly, Apache-2.0); niebieska ramka tylko gdy sterowanie
    działa. Tryb = ostatnio ustawiony przez QExt2 (lampa go nie zgłasza). Dziennik: LIGHT, LIGHT_SET, LIGHT_TAP.

### Pole dynamiczne: DYNAMIC / AERO (0.4.1, 2026-10-17)
SETUP „KOKPIT 2: pole dynamiczne” – DYNAMIC (rotacja jw.) albo AERO: „aero −11 W ●” = zmiana oporu powietrza po zmianie pozycji
w watach przy bieżącej prędkości (kropka zielona = pewny, żółta = zbiera się; „···” zbiera poziom, „—” brak warunków).
AERO zastępuje pole w całości, bez wyjątków. Szczegóły i algorytm: `docs/AERO.md`.

### Dziennik jazdy (`ride_log.txt`, 2 MB + 1 rotacja)
`START`, `RIDE_STATE`, `VIEW`, `READINESS_FETCH/OK/FAIL`, `QEXT2_CONFIG`, `HTTP_FAIL <usługa> reason=…`, `EF_TYPICAL(_FAIL)`,
`WX_OK / WX_FAIL / WX_ERR (timeout|http|parse|onError)`, nawierzchnia i POI (`SURFACE_*`, `POI_FETCH*`), `HEADWIND`, `MSG`,
`TURN_RAW` (surowa odległość od Karoo) i `TURN … kind=` (rozpoznanie), `POWER_ALARM on/off`, `RENDER_FAIL`.
Znane: pierwsze zapytanie EF po starcie pada (`karoo_system_not_ready`), ponowienie przechodzi.


### 18.10.2026 -- znak sredniej, NP 5, pogoda bez zasiegu
- Znak sredniej predkosci (Ø przy sr. V w KOKPIT 2 instr): zielony + strzalka w gore = biezaca predkosc (wygladzona ~60 s)
  jest wyzsza od sredniej o > 0,5 km/h (podnosisz srednia); czerwony + strzalka w dol = obnizasz; szary = bez zmian / postoj.
- NP 5 = NP z ostatnich 5 min ruchu, liczone na biezaco (StatsCalculator.np5Watts). Kolor podpisu = zmiana wzgledem 10 min temu,
  dopiero od 10 W (bylo 3 W -> prawie zawsze czerwony/zielony). STATS v2 "5′" pokazuje to samo ciagle NP 5.
- INFO: brak zasiegu (code 0) nie przelacza na "bez trasy" -- zostaje ostatnia prognoza z trasy; tryb bez trasy bierze ostatni znany kurs.
