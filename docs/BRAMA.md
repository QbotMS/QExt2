# BRAMA z Karoo (przebudowa 16.10.2026, QBot TODO [GATE-PRZEBUDOWA])

Pole Karoo **QExt2 BRAMA** (`qext2-brama`, `gate/BramaDataType.kt`). Cale pole jest przyciskiem.

## Jak dziala
1. Pole zna punkt bramy z QBota (`GET /gate/open?mode=home`, z tokenem; zapis w prefs `qext2_gate`) i biezaca pozycje (Karoo, gdy pole widoczne).
2. Dalej niz promien (300 m) albo bez GPS: ramka szara przerywana, tapniecie nic nie wysyla.
3. Blisko: ramka niebieska **OTWORZ** -> 1. tap: zolta **POTWIERDZ** (3 s) -> 2. tap: **WYSYLAM...** -> zielona **OTWARTA** albo czerwona **BLAD** z powodem
   (brak sieci / za daleko / token / zegar / serwer-HikConnect / brak odpowiedzi). Wynik widoczny 5 s.
4. Zadanie idzie bez czekania na zasieg (`waitForConnection=false`) z czasem i pozycja: `GET /gate/open?ts=..&lat=..&lon=..` (naglowek `X-Gate-Token`).
   Karoo czeka maks. 20 s.

## Serwer (QBot: `qbot_qlab_server.py`, `gate_hikconnect.py`)
Kody: 200 ok, 400 brak ts/pozycji, 403 token, 408 zadanie starsze niz 20 s, 412 za daleko, 429 limit 15 s / w toku, 503 blad (powod w `reason`).
Sesja HikConnect zapamietana; jedna proba otwarcia; dziennik `/opt/qbot/artifacts/gate_log.jsonl`; ostatnia proba w `/gate/status`.
Punkt bramy: `/opt/qbot/artifacts/gate_home.json` (mediana startow jazd) albo `GATE_LAT`/`GATE_LON` w `.env`; promien `GATE_RADIUS_M` (dom. 300).

## Dziennik jazdy (ride_log.txt)
`VIEW BRAMA`, `GATE_HOME ok/FAIL`, `GATE_ARMED`, `GATE_TAP blocked=..`, `GATE_SEND`, `QEXT_GATE_RESULT code=..`, `GATE_RESULT state=..`.
