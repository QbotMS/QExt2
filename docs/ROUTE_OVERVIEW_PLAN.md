# Przeglad trasy (minimapa + profil + chevrony) — plan (2026-10-04)

Status: PLAN, nic nie wdrozone. Baza: timklge/karoo-routegraph (Apache 2.0, aktywny, 14.09.2026) — przenosimy WYLACZNIE logike rysowania (MinimapDataType ~1100 l., RouteGraphDataType ~700 l., LineUtils, TileDownloadService, getSurfaceConditionPaints + zasoby cross_pattern*, GradientIndicator/GradientIndicatorFrequency/InclineColors + drawable chevron*), z podaniem autora w NOTICE/naglowkach. Bez Glance: rysunek na Bitmap -> ImageView w RemoteViews (jak FIELD_LOOK_PLAN).

## Decyzje uzytkownika
- Wcielone do QExt2 (nie osobne rozszerzenie).
- Minimapa obracana KIERUNKIEM JAZDY (heading-up).
- Tlo: podklad OSM (internet przez companion app + cache na Karoo), KOLORY JAK W RG.
- Profil przewyzszen SPRZEZONY ZE SKALA NASZEJ MINIMAPY (nie z zoomem glownej mapy).
- Profil MUSI pokazywac nawierzchnie jak RG (kreskowanie na szutrze/sypkim na tle koloru nachylenia).
- Chevrony nachylenia jak RG; ZJAZDY: wlasna zimna paleta + INNY znaczek niz podjazd (patrz nizej). Ksztalt znaczka zjazdu DO PRZECWICZENIA.
- Cieniowanie trasy (ciagla linia w kolorach) na glownej mapie Karoo: ODRZUCONE (czytelnosc, maly pozytek). Chevrony to co innego (rzadkie znaczki) — dopuszczone.

## Minimapa
- RouteGraph rysuje polnoca do gory, obraca tylko strzalke. U nas: canvas obrocony o -kierunek wokol pozycji; pozycja w dolnej 1/3 pola, kierunek jazdy = gora.
- Kierunek z PRZEBIEGU TRASY (bearing pozycji -> punkt ~kilkaset m dalej po trasie, wygladzony), nie z GPS -> brak krecenia na postoju i drobnych skretach. Poza trasa: kierunek z GPS (OnLocationChanged.orientation) z wygladzaniem.
- Stuk w pole = zmiana skali (kilka poziomow, np. 2 / 5 / 10 / 20 km do gory pola).
- Kolory podkladu jak RG (MinimapDataType ~l. 333-353): tryb dzienny = oryginalne kolory OSM; tryb nocny = ColorMatrix odwrocenie (-1, +255) polaczone z desaturacja (setSaturation 0) -> ciemna szara mapa. QExt2 dzis NIE wykrywa trybu dzien/noc Karoo -> dodac wykrywanie (to samo potrzebne w FIELD_LOOK_PLAN p.5).
- Kafelki OSM: tile.openstreetmap.org wg polityki OSM — wlasny User-Agent, cache na dysku, BEZ masowego pobierania calej trasy z gory (najwyzej okolica pozycji). Przy obrocie napisy na mapie obracaja sie razem z podkladem (swiadomy kompromis). Brak internetu i cache -> sama linia trasy na ciemnym tle.

## Profil (pod minimapa, w tym samym polu)
- Okno profilu = dystans widoczny na minimapie PRZED pozycja (od pozycji do gornej krawedzi). Zmiana skali minimapy zmienia oba naraz.
- Dane wysokosci: `routeElevationPolyline` (precyzja 1, kolejnosc jazdy, bez upraszczania) + podjazdy z `NavigatingRoute.climbs`.
- Wypelnienie profilu kolorem nachylenia (jak RG: podjazdy; przy duzym przyblizeniu kazde 100 m osobno). Ta sama paleta co chevrony (w tym zjazdy).
- NAWIERZCHNIA jak RG (RouteGraphDataType ~l. 439-485, SurfaceConditionRetrievalService.getSurfaceConditionPaints):
  - odcinek przyciety do ksztaltu profilu; na nim kreskowanie (BitmapShader cross_pattern / cross_pattern_white w nocy, przezroczystosc 1/3) NA kolorze nachylenia — kolor nachylenia nadal widoczny;
  - linia profilu pogrubiona: GRAVEL 8 px w kolorze linii, LOOSE 10 px i czerwona;
  - minimalna szerokosc odcinka 5 px (krotkie odcinki nie znikaja).
- Klasy nawierzchni: QExt2 `SurfaceType` PAVED/GRAVEL/LOOSE = te same 3 klasy co RG — bez mapowania.
- Zrodlo nawierzchni: 1) QBot (`SurfaceProfileCache`, /api/surface/by-name, reguly QBota: jawny tag OSM wygrywa, tracktype grade1-4 != ryzyko); 2) zapas: strumien nawierzchni RouteGraph (wymaga zainstalowanego RG); 3) brak -> profil bez kreskowania + znacznik "nawierzchnia nieznana". UWAGA: strumien RG daje tylko biezaca nawierzchnie, nie profil na przod — przy zapasie 2 kreskowanie dopiero po przejechaniu albo trzeba przeniesc czytnik map offline RG (Mapsforge, osobny duzy kawalek — decyzja pozniej).

## Chevrony nachylenia
- Gdzie: na GLOWNEJ mapie Karoo (symbole przez MapEffect, jak RG; wlacznik w ustawieniach) ORAZ na naszej minimapie (rysowane na bitmapie, MinimapDataType ~l. 867-960).
- Gestosc jak RG (GradientIndicatorFrequency): LOW 3 / MEDIUM 6 / HIGH 14 / MAX 19 na przekatna ekranu; ustawienie.
- Podjazdy: paleta RG (InclineColors): 1-2% jasnozielony, 2-5 ciemnozielony, 5-8 zolty, 8-11 jasnopomaranczowy, 11-14 ciemnopomaranczowy, 14-20 czerwony, >=20 fioletowy. Ksztalt i obrot jak RG (chevron zgodnie z kierunkiem jazdy). Plasko (-2..+1%) bez znaczka.
- Zjazdy — stan RG: ten sam ksztalt co podjazd, zgodnie z kierunkiem jazdy, tylko 3 stopnie (-2..-5 BIALY = znika na dziennym OSM, -5..-8 jasnoniebieski, < -8 ciemnoniebieski).
- Zjazdy — USTALENIA (2026-10-04):
  - paleta wlasna, zimna, 4 stopnie: -2..-4 turkus, -4..-6 niebieski, -6..-9 granat, <= -9 ciemny indygo; ciemny obrys (czytelne dzien/noc). Fiolet WYLACZNIE dla podjazdow >=20%.
  - ZNACZEK INNY niz chevron podjazdu. Kandydat 1: chevron odwrocony (orientation = bearing + 180; SDK Symbol.Icon.orientation to wspiera, na minimapie rysujemy sami). Ryzyko: odwrocona strzalka myli sie z "jedziesz pod prad" / na trasie tam-i-z-powrotem z podjazdem z drugiej strony. Uzytkownik dopuszcza tez znaczek NIEKOJARZACY SIE ze strzalka kierunku (np. inny ksztalt) — DO PRZECWICZENIA na Karoo przed wyborem (zrzuty ekranu dzien/noc, kilka kandydatow).
  - ta sama paleta zjazdow na profilu (spojnosc).
- Pulapki mapy (Barberfish sdk-findings): symbole rozszerzenia przykrywaja natywne chevrony Karoo; jedna porcja symboli = jedna transakcja Binder ~1 MB -> wysylac w paczkach <= 500 (295 km trasy = >3000 chevronow wywracalo proces); NIE ukrywac i pokazywac tego samego id w jednej emisji (symbol znika na stale); symbole przezywaja smierc procesu i restart startMap, znikaja dopiero na koniec jazdy -> przy nowym startMap ukryc poprzednia generacje z wlasnego rejestru; szerokosci w dp.

## Pulapki SDK (zrodla: Barberfish sdk-findings, karoo-ext issue #81)
- `routePolyline` (precyzja 5) = kolejnosc ZAPISU, nie przyklejony do drog (cieciwy na serpentynach). Przy `reversed == true` odwrocic punkty samemu.
- `routeDistance` / DISTANCE_TO_DESTINATION dluzsze od dlugosci polyline o ~0.1% -> przeskalowac przed laczeniem osi (dotyczy tez km nawierzchni z QBota).
- NavigatingRoute moze nie przyjsc po starcie nawigacji (Karoo 2) -> odlaczyc i podlaczyc consumera przy wejsciu na trase.
- Bitmapa w RemoteViews idzie przez Binder (limit ~1 MB na transakcje): 480x400 ARGB_8888 = ~768 KB — za blisko. Uzyc RGB_565 (~384 KB) lub mniejszego rozmiaru; zmierzyc.
- Komorka kurczy sie przy komunikacie nawigacji bez ponownego startView -> rysunek dopasowany do kontenera, nie do viewSize.
- RemoteViews allowlist / Karoo 2: patrz FIELD_LOOK_PLAN.md.
- Zapytania HTTP przez proxy Karoo (companion): limit ~100 KB na odpowiedz (wg ktor-client-karoo) — kafelki OSM i odpowiedzi QBota musza sie miescic.

## Bateria
- Przerysowanie tylko gdy pozycja przesunela sie o >= kilka px, zmienila sie skala albo doszedl kafelek. Kafelki z cache dyskowego. Chevrony na glownej mapie liczone RAZ na trase (i po objezdzie).

## Kroki
0. [NASTEPNY] Szkielet pola: sama linia trasy obracana kierunkiem jazdy + pozycja, bez kafelkow i profilu. Cel: sprawdzic obrot, przesylanie bitmapy (rozmiar, Karoo 2/3), plynnosc i baterie.
1. Podklad OSM + cache + kolory dzien/noc jak RG (z wykrywaniem trybu Karoo).
2. Profil pod minimapa, sprzezony ze skala, z kolorem nachylenia i nawierzchnia (kreskowanie + pogrubienie) z QBota.
3. Chevrony: najpierw na minimapie, potem na glownej mapie Karoo (paczki <= 500, rejestr id). Przed tym: proba kilku znaczkow zjazdu na Karoo i wybor.
4. Podjazdy na minimapie; pozniej ewentualnie POI i czytnik map offline (nawierzchnia bez QBota).

## Powiazane
- `docs/ETA_V2_PLAN.md` (te same dane wysokosci z SDK, krok 0 pomiaru gestosci profilu).
- `docs/FIELD_LOOK_PLAN.md` (rysowanie na bitmapie, ograniczenia RemoteViews, tryb jasny/ciemny).
