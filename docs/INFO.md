# Pole INFO (QExt2 0.4.0, 16.10.2026)

Pole Karoo **QExt2 INFO** (`qext2-info`, `info/InfoDataType.kt`, rysowanie `StatsV2Renderer.renderInfo`).
Uklad 480x760: TRASA (201) | PRZEWYZSZENIE (92) | POGODA (463). Mockup: kanwa Design „QExt2 — pole INFO”.

## Pogoda (liczy QBot, Karoo tylko rysuje)
- Na trasie: `GET /api/weather/by-name?name=<nazwa trasy>&km=<biezacy km>` (Bearer jak POI). QBot uruchamia `route_meteo_engine`
  (ten sam silnik co Analiza trasy i Planer) z czasem przesunietym tak, by model byl w biezacym km teraz. Zwraca od biezacego km:
  `km, t (min od teraz), feels (odczuwalna jak w Analizie trasy), mm, prob, head (+ w twarz / - w plecy, m/s), gust`.
- Bez trasy: `GET /api/weather/point?lat&lon&heading&hours=7` (rdzen pogody QBota, jak strona Pogoda).
- Odswiezanie (`InfoWeather`): brak danych, zmiana trasy, co 15 min albo gdy ETA przesunie sie o > 10 min; po bledzie po 2 min.
- Dotkniecie pola zmienia skale: 20 km / 50 km / cala reszta (bez trasy 1 h / 3 h / 6 h).
- Wykres: temperatura odczuwalna, opad (slupki mm/h + linia szansy %), wiatr wzgledem jazdy (rozowy = w twarz, zielony = w plecy, kropki = porywy),
  noc = ciemniejsze tlo, swit/zmierzch = przerywana linia + podpis na osi X.
- Dziennik jazdy: `VIEW INFO`, `INFO_WX fetch/ok/FAIL`, `INFO_SCALE`.

## Tryb jazdy (16.10.2026)
Sygnal jazdy z Karoo (POST /ride-readiness `{"ping":{lat,lon,route,km,eta}}` co ~5 min) zapisuje trase/km/ETA w QBot
(`/opt/qbot/artifacts/qext2_live.json`). QBot (qbot_api `_qext2_live_loop`) sam przelicza pogode reszty trasy co 15 min:
temperatura i wiatr z modelu (route_meteo_engine), **opad na najblizsze 120 min z radaru QBota** (EUMETNET OPERA co 5 min,
obraz przesuniety wzdluz ruchu opadu do chwili przejazdu odcinka; `src: radar`), dalej model. `/api/weather/by-name` oddaje gotowy wynik
od razu (`live: true`). Na wykresie: jasne slupki = radar, ciemne = model, w naglowku OPAD „· radar N′”.
Mgly radar nie widzi (brak kropel) -- tylko z modelu.
