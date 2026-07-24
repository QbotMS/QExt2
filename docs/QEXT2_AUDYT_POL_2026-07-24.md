# QExt2 — audyt pol: zrodla danych, wspolne bazy, korelacja logiczna
_Data: 2026-07-24. Zrodlo: zywy kod (nie dokumentacja). Metoda: przesledzenie kazdej
wyswietlanej liczby do jej zrodla._

## Zakres
Pola `qext2-primary` (4col), `qext2-stats` (3x3), `qext2-active` (4x2) + 7 pol developerskich FIT.

---

## A. Bazy progowe — SIEDEM roznych "progow" w jednej aplikacji

| # | Nazwa | Wzor | Co napedza |
|---|---|---|---|
| 1 | `ftpWatts` | surowe FTP z API QBota | fallback `ifValue()` / `tssValue()`, baza dla #3 |
| 2 | `adjFtp` | FTP x todayFactor | `adjIf` -> **carbsGPerH, fluidLPerH** |
| 3 | `cpEff` | FTP x cf (cf = tf x upal x dryf, 0.88-1.06) | **W'bal, XSS, komunikaty W' w ACTIVE** |
| 4 | `cpEffLin` | LTP + bal x (FTP - LTP) | **IFe** (wyswietlane), `ifEff5Live` |
| 5 | `getEffectiveLtpWatts()` | LTP x (tf x upal), clamp 0.75-1.10 | PacingEngine, AdaptiveModeTracker, bramka ACTIVE |
| 6 | `realLtpWatts` | surowe LTP | baza dla #4 |
| 7 | **FTP ustawione w Karoo** | poza kontrolla apki | **IF i TSS ze strumieni SDK** |

### A1 (KRYTYCZNE) — IF i IFe pochodza z roznych FTP
`tv_if` <- `ifRef` <- `DataType.Type.INTENSITY_FACTOR` (Karoo liczy z **wlasnego** FTP z ustawien urzadzenia).
`tv_wprime` (etykieta "IFe") <- `statsCalc.ifEffWholeRide()` <- `cpEffLin` (LTP..FTP z QBota).
`adjIf` = NP / (FTP_qbot x tf) — trzecia liczba, napedza zywienie, NIGDZIE nie wyswietlana.

Skutek: dwie sasiednie komorki 3x3 opisuja innego zawodnika, jesli FTP w Karoo != FTP z QBota.
Nie da sie ich porownywac ani wyciagac wnioskow z ich roznicy.

---

## B. Aktualnosc zrodel

### B1 (KRYTYCZNE) — brak bramki wieku danych zawodnika w sciezce jazdy
`AthleteData.fetchTimestamp` istnieje i jest zapisywany, `SetupActivity` ostrzega przy wieku > 12 h,
ale **zaden konsument w trakcie jazdy nie sprawdza wieku**. Odswiezenie jest oportunistyczne:
`CompositeActiveDataType.refreshAthleteData()` / `BpActiveStaticDataType` porownuja
`fresh.fetchTimestamp > athleteData.fetchTimestamp` — czyli "czy przyszlo cos nowszego",
a nie "czy to, co mam, nie jest za stare".

Skutek: brak zasiegu rano albo niedostepny serwer => wczorajszy (lub starszy) `todayFactor`,
FTP, LTP, W' i CTL cicho napedzaja CP, RSRV, pacing i zywienie jako dane biezace.

### B2 — swiezosc, ktora dziala poprawnie
- Moc: bramka 8 s (`powerFresh`) — W'bal pomija nieswieze probki. OK.
- Pogoda: `physioTempC()` spada na czujnik Karoo gdy `weatherFresh == false`. OK.
- HR / kadencja / predkosc / bieg / nachylenie: wiek sledzony i przekazywany do FieldComputers. OK.
- Bateria: flagi gotowosci (`batterySourceReady`, `batteryDrainReady`, `batteryEstimateReady`). OK.
- IF / TSS z SDK: **brak bramki wieku**; fallback tylko gdy wartosc == 0.

---

## C. Korelacja logiczna

### C1 (KRYTYCZNE) — RSRV miesza dwie skale obciazenia
`StatsCalculator.rideReservePercent(tss, intensityFactor, decoupling, elapsedSec)`:
```
dailyBudgetTss = ctlForBudget x 5.4   (clamp 300-600)   <- budzet w TSS
tssPenalty     = tssSafe x (100 / dailyBudgetTss)
```
Wywolanie (`RideDataAggregator:994`):
```
reserve = statsCalc.rideReservePercent(effectiveXss, ifWhole, decouplingForReserve, elapsedSec)
```
Do parametru `tss` trafia **XSS**, a budzet jest wyrazony w **TSS**. To rozne skale:
TSS = 1 h @ FTP = 100; XSS = 1 h @ CP = 100 i dodatkowo niesie mnoznik zmeczenia
(`XSS_BETA`), wiec dla tej samej jazdy XSS > TSS. Rezerwa spada systematycznie za szybko.

Dodatkowo `ifWhole` to **IF z SDK** (FTP Karoo), a `effectiveXss` bazuje na **CP z QBota** —
jeden wzor, dwa uklady odniesienia.

### C2 — dwie rownolegle ksiegi dzienne
`dailyTssBaseRef` i `dailyXssBaseRef` sa utrzymywane obie. RSRV czyta XSS
(`effectiveXss`), ale zapis dobowy `committedDailyTss` (`:1165`) idzie z `sessionTssRef` (TSS).
Dwie ksiegi moga sie rozjechac; tylko jedna wplywa na rezerwe.

### C3 — `ltpWatts` w StatsCalculator trzyma CP, nie LTP
`setEffectiveWPrime(cpEff, ...)` nadpisuje `ltpWatts` wartoscia CP. W'bal i XSS licza sie
**od CP** — zachowanie poprawne, nazwa mylaca. Ta sama choroba co `cp_modelq_w` = LTP
po stronie serwera.

### C4 — drobne
- `tv_wprime` pokazuje IFe (etykieta w layoucie poprawna: "IFe"). Skutek uboczny:
  **W' w kJ nie jest wyswietlane nigdzie w STATS** — tylko W'bal% w ACTIVE i w FIT.
- `rideReservePercent(tss: Float, ...)` — parametr nazwany `tss` przyjmuje XSS.
- `PrimaryRideSnapshot.computeColors` — **brak wywolan** (martwy kod), a dubluje logike `adjFtp`.

---

## D. Co jest zdrowe
- W'bal, XSS i komunikaty W' w ACTIVE chodza po **tym samym** `cpEff` (naprawione 2026-07-24).
- `todayFactor` ma jedne kanoniczne widelki i jedno zrodlo (naprawione 2026-07-24).
- Obsluga swiezosci sensorow.

---

## C5 (NOWE, znalezione przy pkt 2b) — dwa rozne NP
`npRef` (strumien SDK) jest WYSWIETLANE w komorce NP i od 2026-07-24 dzieli sie przez FTP
QBota dajac IF. Rownolegle `statsCalc.npWatts()` (liczone wewnetrznie z bufora 30 s) napedza
`adjIf` -> model zywienia. Oba powinny byc zblizone (ten sam strumien mocy, ten sam wzor),
ale nie musza byc identyczne (inne wygladzanie / okno).
Uwaga: NP i VI ze strumieni SDK NIE maja choroby FTP — sa od FTP niezalezne
(NP to czysta matematyka z mocy, VI = NP/srednia), dlatego zostaly.
Do rozstrzygniecia w pkt 4: czy ujednolicic na jedno NP.

## E. Kolejnosc naprawy (uzgodniona)

| # | Punkt | Waga |
|---|---|---|
| 1 | Bramka wieku danych zawodnika (B1) | KRYTYCZNE |
| 2 | Jedna intensywnosc — IF vs IFe vs adjIf (A1) | KRYTYCZNE |
| 3 | RSRV w jednej walucie + spojne IF (C1, C2) | KRYTYCZNE |
| 4 | Nazwy: `ltpWatts`->`cpWatts`, param `tss`->`load`, `tv_wprime`->`tv_ife`; usuniecie `computeColors` (C3, C4) | PORZADKI |

Status naprawy dopisywac ponizej.

## Status
- [x] 1. Bramka wieku danych zawodnika — ZROBIONE 2026-07-24.
      `AthleteData.ageAdjustedTodayFactor()`: <=24 h pelna wartosc, 24-48 h liniowe
      sciaganie odchylenia do 1.0, >48 h / brak odczytu => 1.0. Przeliczane co tick
      (rampa dziala tez w dlugiej jezdzie). FTP/LTP/W'/CTL bez zmian.
      Sygnalizacja: ACTIVE `DANE STARE` / wiek / dyspozycja — raz na jazde
      (`SensorMessageProducer.checkStaleAthleteData`). Testy: +6.
- [x] 2. Jedna intensywnosc — ZROBIONE 2026-07-24 (dwa kroki).
      2a (inna sesja, `cbd74ac`): TSS usuniety z QExt2 w calosci — nie napedzal
      niczego (RSRV jedzie na XSS), a utrzymywal martwa ksiege dzienna i bral sie
      z FTP ustawionego w Karoo. Komorka TSS -> XSS (XSS bylo liczone i szlo do FIT,
      ale bylo niewidoczne dla zawodnika). Znika tez punkt C2.
      2b: IF liczone z FTP QBota zamiast ze strumienia SDK. Usuniety konsument
      `INTENSITY_FACTOR` i `ifRef`. Dzielna to NP ktore jest WYSWIETLANE (`npRef`),
      zeby arytmetyka w siatce sie zgadzala; fallback `statsCalc.ifValue()`.
      Snapshot i RSRV dostaja te sama liczbe (wczesniej snapshot bral `ifRef`
      bezposrednio, a RSRV `ifWhole` — mogly sie roznic).
      Decyzja: IF pozostaje KLASYCZNE `NP/FTP` (porownywalne miedzy jazdami).
      "Dzisiejszosc" niesie IFe, a `adjIf` = NP/(FTP x tf) zostaje wejsciem modelu
      zywienia (nadal niewyswietlane). Wszystkie trzy z JEDNEJ bazy: FTP z QBota.
      Skutek uboczny: IF w QExt2 moze sie roznic od IF w natywnych polach Karoo,
      jesli FTP w urzadzeniu != FTP z QBota. To zamierzone.
- [ ] 3. RSRV w jednej walucie
- [ ] 4. Porzadki nazewnicze
