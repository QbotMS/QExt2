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

## KOKPIT 2 (2026-10-10, budowa 246)
Nowe pola produkcyjne (stare KOKPIT bez zmian na czas testow):
- `qext2-kokpit2-nav` — **QExt2 KOKPIT 2 nav** (gorne), `qext2-kokpit2-instr` — **QExt2 KOKPIT 2 instr** (dolne).
- Kod: `kokpit/Kokpit2.kt` (Kokpit2NavRenderer, Kokpit2InstRenderer, Kokpit2Route, Kokpit2Demo); dane z tych samych
  `KokpitNavDataType` / `KokpitInstDataType` z parametrem `v2 = true`.
- Mockup: kanwa „QExt2 — pola Karoo”, plansza **„KOKPIT — v10 ROBOCZA”** (`Kokpit9.dc.html`) + „Komunikat — tlo wg waznosci” (`Komunikaty.dc.html`).
- Demo: przelacznik w SETUP „KOKPIT 2: dane demo” (pref `kokpit2_demo`, domyslnie wyl.), dziala po ponownym wejsciu na strone z polami.

Uklad (474x126, wieksze pole = ta sama skala):
- nav: komunikat 41 px (tlo: krytyczne bordo #8B0A1A / ostrzezenie zolte #FFC21A z czarnym tekstem / informacja #1E2731),
  wiersz pogody (temp + opad/niebo | wiatr | nachylenie), wiersz DST/DTD/ETA na dolnej krawedzi (pionowe podpisy).
- instr: belka trasy na gorze (przejechane #2E7BFF, asfalt #F2F4F7, szuter #FFB300, trudny #FF2A2A, postoje #FF3DF5,
  pozycja = limonkowe kolo #39FF14), KAD | sr. predkosc | NP 5 | tetno, na dole BIEG (blat maly, koronka duza) | V | W | W′.
- Moc: kolor liczby z PacingEngine (jak PRIMARY); obok W piorun + nr strefy wg CP, oba w kolorze strefy. Bez lukow.
- Strzalka wiatru: czerwona = wiatr w twarz (skladowa czolowa >= 3 m/s i >= 70% wiatru), zielona = w plecy, biala = boczny/slaby
  (`getHeadwindSignedMps`, + w twarz).
