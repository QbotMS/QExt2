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
