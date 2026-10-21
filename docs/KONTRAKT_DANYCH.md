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

## 6. FIT (dev fields)
Schemat 1 (do 8db50b0): qext2_wbal_pct, qext2_cp_eff_w, qext2_wprime_eff_kj, qext2_cf, qext2_wbal_zero, qext2_readiness, qext2_rsrv_pct, qext2_xss
  (CP z korekta formy/upalu/dryfu, stary wzor XSS).
Schemat 2 (plan v2): jak 1 + qext2_schema (=2) + qext2_np5_w; cp_eff_w = CP ModelQ bez korekt (qext2_cf zawsze 1.0), qext2_readiness = forma
  uzyta w RSRV, XSS wzorem ModelQ2. Zapis tylko z aktywnych obliczen jazdy.
Serwer (fit_ingest): model_schema w fitmodel_qext2_ride (brak pola = 1); wbal_zero_seconds z timestampow. Porownania historii tylko w obrebie schematu.
Pola dodatkowe od 0.4.1 (2026-10-17, schemat BEZ zmian - nie dziela historii W'): qext2_aero_state (0 brak / 1 zbiera / 2 wynik),
  qext2_aero_dw_w, qext2_aero_cda, qext2_aero_ref_cda - pole AERO (docs/AERO.md). Serwer: qbot_v2.qext2_aero_record (sprawdzian Centrum CdA).
Od 0.4.2 (2026-10-19, AERO v3, schemat bez zmian): nr 18 qext2_aero_ver (uint8, wersja algorytmu: 3 = v3, od 0.4.3 = 4 z windScale), nr 19 qext2_aero_conf (uint8,
  0 brak / 1 niska / 2 dobra), nr 20 qext2_aero_wind_head (float32, m/s - wiatr czolowy uzyty w rownaniu, po skalowaniu prognozy),
  nr 21 qext2_aero_surface (uint8, 0 nieznana / 1 asfalt / 2 szuter). Zapisywane co sekunde razem z 4 polami AERO.

Od 0.4.20 (2026-10-21, tryb NAGRANIE, schemat bez zmian): nr 22 qext2_demo (uint8, 1 = jazda w trybie NAGRANIE).
  Pisane co sekunde TYLKO w jezdzie nagrania, wtedy jako jedyne pole QExt2. Poza trybem pole nie wystepuje.
  QBot pomija taki plik przy imporcie (fitmodel/qext2_demo.py; qbot_activity_ingest, fit_ingest).


### Dev fields WIATR (17.10.2026, schemat bez zmian)
| nr | nazwa | typ | jedn. | znaczenie |
|---|---|---|---|---|
| 14 | qext2_course_deg | float32 | deg | kurs GPS Karoo (OnLocationChanged.orientation), 0 = N |
| 15 | qext2_wind_from_deg | float32 | deg | wiatr z prognozy QExt2: kierunek, Z ktorego wieje |
| 16 | qext2_wind_ms | float32 | m/s | predkosc wiatru z prognozy QExt2 |
| 17 | qext2_wind_rel_deg | float32 | deg | kat, W KTORY wieje wiatr, wzgledem kierunku jazdy (0 = w plecy, 180 = w twarz) -- to rysuje KOKPIT |
Zapisywane tylko gdy znane (na postoju brak kursu). Strona B: QBot fit_ingest -> kontrola wiatru po jezdzie.
