# QExt2 -- KONTRAKT DANYCH (Etap 0 planu v2, 2026-10-09)
Jedno zrodlo prawdy dla definicji liczb w QExt2. Kazda zmiana modelu = zmiana tego pliku w tym samym commicie.
Plan: QBot DEV exchange PLAN_POPRAWEK_QEXT2_v2_2026-10-09.md. Audyty: AUDYT_QEXT2_2026-10-09.md, AUDYT_QEXT2_NIEZALEZNY_2026-10-09.md.

## 1. Profil zawodnika (z /ride-readiness)
| Pole | Zrodlo | Uzycie |
|---|---|---|
| CP [W] | ModelQ `ftp_est_w` (= TP sygnatury MQ2) | W'bal, XSS, IF, NP5 kolor, sufit mocy, RSRV, wegle |
| LTP [W] | ModelQ `ltp_modelq_w` | strefy informacyjne |
| W' [kJ] | ModelQ GREATEST(wprime_modelq_kj, wprime_road_kj) | W'bal |
| forma [-] | ModelQ `readiness_effective` dnia jazdy, 1 + 0.10 x score, 0.70-1.10 | TYLKO tempo spadku RSRV (start zawsze 100%). NIE W'bal, NIE CP |
| LTHR, HRmax [bpm] | lthr_daily / env | strefy i kolor tetna (strefy od LTHR), RSRV v2 (tetno) |
| data profilu | dzien pobrania | profil kompletny = CP, LTP, W' obecne; niekompletny -> zalecenia wylaczone (brak porad z wartosci domyslnych) |
Zasada: profil ZAMROZONY na czas jazdy (pobranie w trakcie jazdy nie zmienia CP/W'/W'bal); nowy profil od nastepnej jazdy.
Forma nieaktualna (pobrana nie w dniu jazdy) -> 1.0 + jeden komunikat na starcie jazdy.

## 2. Probka czujnika
Kazda wartosc: (wartosc, czas zrodla [monotoniczny ms], status).
| Status | Znaczenie | Modele | Wyswietlanie |
|---|---|---|---|
| OK | swiezy pomiar > 0 | uzywany | wartosc |
| ZERO | swiezy pomiar = 0 przy jezdzie (toczenie) | uzywany jako 0 | 0 |
| BRAK | brak strumienia / czujnik nie nadaje | NIE uzywany; obniza pokrycie okna | "--" |
| STARE | wiek > limit | jak BRAK | "--" |
Limity wieku: moc 3 s, kadencja 3 s, tetno 5 s, predkosc 3 s, nachylenie 5 s od OSTATNIEJ PROBKI (nie od zmiany wartosci), bieg 15 s, temperatura bez limitu (czujnik Karoo zawsze nadaje), wiatr 15 s osobno dla kazdego kanalu.

## 3. Jazda (sesja)
- ID jazdy: nadawane przy START nagrywania Karoo; wszystkie liczniki, okna, ETA, przypomnienia, komunikaty i zapis stanu niosa ID.
- Zdarzenia: START, PAUZA (autopauza), WZNOWIENIE, KONIEC. Obliczenia trwaja od START do KONIEC niezaleznie od widocznosci pol.
- Czas: integracja po rzeczywistym dt z zegara monotonicznego; przerwa w danych > 5 s = BRAK (bez interpolacji).
- Dzien RSRV: suma jazd dnia kalendarzowego; brak resetu po postoju ani po snie.

## 4. Wielkosci wyswietlane (pola aktywne: STATS v2, KOKPIT, NAWIGACJA)
| Wielkosc | Definicja | Jednostka |
|---|---|---|
| NP calej jazdy, VI | z Karoo (NORMALIZED_POWER, VARIABILITY_INDEX) | W, - |
| IF | NP Karoo / CP (tylko przy kompletnym profilu) | - |
| NP5 (dawniej CPe5) | NP z ostatnich 5 min czasu ruchu, z ZERAMI, bez BRAK; pokrycie < 90% -> "--" | W |
| W'bal | Skiba, CP i W' z profilu bez korekt | % |
| XSS | wzor ModelQ2 (Low do TP + nadwyzka x (1+zmeczenie)), TP = CP | pkt |
| RSRV | ReserveModelV2, x = moc EMA20 / (CP x forma), tetno podbija x powyzej progu | % |
| srednia kadencja, srednie tetno, czas ruchu | z Karoo (AVERAGE_CADENCE, AVERAGE_HR, ELAPSED_TIME; autopauza zawsze wlaczona) | rpm, bpm, s |
| temperatura | WYLACZNIE czujnik Karoo = odczuwalna w pedzie (decyzja 09.10) | C |
| wiatr | WYLACZNIE karoo-headwind; wejscie przeliczane na m/s wg jawnej jednostki zrodla | m/s |
| pozycja na trasie | dlugosc aktywnej geometrii - dystans do mety (warunki: E4.1) | km |

## 5. Rower
AXS 10625 = Grizl, AXS 27856 = Grail, brak AXS = Monster.

## 6. FIT (dev fields) -- wersja schematu w E-FIT
Obecne (schemat 1): qext2_wbal_pct, qext2_cp_eff_w, qext2_wprime_eff_kj, qext2_cf, qext2_wbal_zero, qext2_readiness, qext2_rsrv_pct, qext2_xss.
Schemat 2 (do wdrozenia): + wersja schematu/modelu, build, ID jazdy, jakosc/pokrycie; bez qext2_cf; zapis tylko z aktualnego obliczenia.
