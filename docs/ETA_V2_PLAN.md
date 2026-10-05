# ETA v2 — plan (2026-10-04)

Status: CZESCIOWO WDROZONE (2026-10-05): poziom 2 (profil Karoo + tabela QBota wpisana w aplikacje + nawierzchnia QBot gdy jest) i poziom 3 (dwie srednie + wartosc startowa), wspolny silnik `eta/EtaEngine.kt`, przelacznik w ustawieniach (eta_v2, domyslnie ON), logi QEXT_ETA_PROFILE / QEXT_ETA / QEXT_ETA_CHECK / QEXT_ETA_ARRIVAL. NIE wdrozone: poziom 1 (endpoint QBota), znacznik poziomu, obejscie karoo-ext #81 (resubskrypcja). Krok 0 (gestosc profilu) = linia QEXT_ETA_PROFILE w logu po wczytaniu trasy.

## Stan obecny (zweryfikowany w kodzie)
- Jedyne zrodlo ETA: `RideDataAggregator.kt` (~l. 1117-1130).
- ETA = teraz + km_do_konca / srednia predkosc z ostatnich 30 min ruchu.
- Brak nachylenia, nawierzchni, przewyzszenia, postojow. `ascentLeftM` i `SurfaceBridge` sa dostepne, ale ETA z nich nie korzysta.

## Cel
ETA = godzina przyjazdu NA MIEJSCE (czas calkowity: jazda + krotkie postoje).

## Poziomy (od najlepszego)
1. Trasa przeanalizowana w QBot -> profil czasu z QBota (`estimate_route_time_v2`, tryb normalny), nowy endpoint `/api/eta/by-name` obok `/api/surface/by-name`. Brak analizy -> endpoint moze zlecic precompute w tle, QExt2 ponawia co kilka min.
2. Brak analizy -> liczy Karoo: tabela predkosci QBota (2 nawierzchnie x 11 kubelkow grade, `SPEED_TABLE` w `qbot_route_time_tools.py`) + parametry postojow (mikro 0.22 min/km, krotki 4.5 min co 9 km) pobrane RAZ i trzymane na Karoo (z wersja) + profil wysokosci trasy z Karoo (`routeElevationPolyline`), wygladzony oknem 200 m (jak kalibracja). Nawierzchnia: RouteGraph albo srednia paved/unpaved. Odcinki ~100 m -> plan czasu do kazdego km. Brak/dziurawy profil -> poziom 3.
3. Nic nie ma -> dwie srednie (szybka ~5 min, wolna ~1 h, jak Barberfish) + wartosc startowa = typowa predkosc z poprzednich jazd zapisana na Karoo.

## Wspolny silnik w trakcie jazdy (poziomy 1-2)
- Korekta "jak jedziesz dzis": realny czas RUCHU na przejechanych odcinkach / plan; czesc szybka + wolna, granice bezpieczenstwa, waga rosnie stopniowo od startu.
- Postoj nie psuje tempa (korekta tylko z czasu ruchu); w trakcie postoju ETA przesuwa sie z zegarem.
- Pula krotkich postojow: przedluzony postoj zjada pule; >20 min = dlugi (tylko przesuwa godzine). Gdy stajesz czesciej niz model -> pula na reszte rosnie (z limitem).
- Objazd/zmiana trasy -> pozostaly dystans z nawigacji Karoo, przeliczenie planu.
- Znacznik poziomu (1/2/3) przy polu ETA.
- Log na koniec jazdy: ETA przewidywane vs rzeczywisty przyjazd (walidacja).

## Fakty o SDK (Barberfish docs/sdk-findings.md, pomiary na urzadzeniu)
- `routeElevationPolyline`: Google Encoded Polyline z PRECYZJA 1; NIE upraszczany (rozdzielczosc ustalona przy tworzeniu/imporcie trasy, niezalezna od zoomu).
- `routeElevationPolyline`, `climbs[].startDistance`, `DISTANCE_TO_DESTINATION` = kolejnosc JAZDY; `routePolyline` = kolejnosc ZAPISU. Przy `NavigatingRoute.reversed == true` odwrocic punkty GPS samemu.
- `routeDistance` (os Karoo) dluzszy od dlugosci polyline o ok. 0.1% (34151 vs 34113.5 m) -> przeskalowac przed porownaniem pozycji.
- Trasa importowana (np. Strava) = wierzcholki pliku bez resamplingu.

## Kroki
0. [NASTEPNY] Pomiar profilu wysokosci z SDK: jedna linia logu przy otrzymaniu NavigatingRoute: liczba punktow `routeElevationPolyline`, sredni odstep [m], dlugosc trasy [km]. Uzytkownik wczytuje trase, odczyt przez adb. Od tego zalezy, czy wygladzanie 200 m ma sens.
   - Uwaga SDK (karoo-ext issue #81, Karoo 2, 1.1.9): gdy nasluch OnNavigationState zaczal sie PRZED startem nawigacji, NavigatingRoute nie przychodzi po starcie. Obejscie: przy wejsciu na trase odlaczyc i podlaczyc consumera ponownie. Sprawdzic tez wplyw na obecne zapowiedzi podjazdow.
1. Test na serwerze (bez Karoo): na trasach przeanalizowanych policzyc czas "po karoowemu" (bez nawierzchni, wygladzony profil) vs pelny model -> ile tracimy na poziomie 2.
2. QBot: endpoint `/api/eta/by-name` + endpoint tabeli predkosci (z wersja).
3. QExt2: silnik ETA (wspolny), poziomy 1-3, znacznik, log walidacyjny.

## Zrodla
- Barberfish (Apache 2.0): dwie srednie DEWMA + prior w ETA; docs/sdk-findings.md.
- QBot: `docs/ROUTE_TIME_ESTIMATE_V2.md` (dokladnosc czesci jezdnej ~+-15%).
- Powiazane: `docs/FIELD_LOOK_PLAN.md` (wyglad pol).
