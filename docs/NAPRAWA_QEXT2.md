# NAPRAWA QExt2 — diagnoza i plan (2026-08-11)

Zlecenie: pelna diagnoza + plan naprawczy. Motywacja uzytkownika: "im ciezszy
i zasmiecony QExt2, tym wiecej zre baterii".

## Fizyka najpierw — co NIE zre baterii

Martwy kod, brzydkie pakiety i wielkie pliki koszuja ZERO mAh — nieuzywane
bajty leza w APK i sie nie wykonuja. One kosztuja tokeny analizy i ryzyko
bledow. Baterie zre wylacznie to, co sie WYKONUJE: wybudzenia procesora,
render, zapisy na flash, logowanie, siec.

Uczciwe oczekiwania: ekran + GPS + radio czujnikow Karoo zzeraja rzad
wielkosci wiecej niz cale rozszerzenie. Naprawa da realny, ale umiarkowany
zysk (pojedyncze procenty czasu jazdy).

## Diagnoza A — realne pozeracze baterii (stan przed naprawa)

1. **Metryki renderu pisane na flash w produkcji.** PrimaryRenderOptimizer:
   `fileLoggingEnabled = true` na sztywno, wiersz do
   `qext2/primary_render_metrics.csv` co 60 s jazdy. Artefakt starego
   sledztwa wydajnosciowego; plik rosnie bez limitu; zapis na flash to
   jedna z drozszych operacji energetycznie.
2. **Petla wygaszania komunikatow budzi CPU 4x/s.** CompositeActiveDataType,
   tick 250 ms (po 3 pustych tickach +750 ms). Precyzja niepotrzebna:
   komunikaty zyja 4-10 s, a show/dismiss i tak wymuszaja render osobna
   sciezka (force).
3. **~134 logi bezwarunkowe (ze 166), czesc w goracych sciezkach.**
   Najgorszy: ActiveMessageRenderer loguje `BIND visible=...` przy KAZDYM
   renderze pola (~1/s cala jazde). Do tego `QEXT_NAV_STATE` +
   `QEXT_ROUTE_CLIMB` per przeliczenie trasy. Brak reguly R8 wycinajacej
   Log.d/v z release.
4. **Zdrowy fundament (nie ruszac):** glowna petla agregatora 1 s;
   PrimaryRenderOptimizer z dedupe po sygnaturze + prog 300/500 ms;
   Active z progiem 1,5 s; pogoda/nawierzchnia przez posrednika Karoo.

## Diagnoza B — smietnik strukturalny (zre tokeny, nie baterie)

1. RideDataAggregator: 1800 linii, bog-obiekt (streamy, tick, pogoda,
   nawigacja, kolory mocy, persystencja).
2. Dwa swiaty pakietow: pl.qbot.karoo.core (java/) + com.qext2.primary
   (kotlin/) — szew po nieukonczonej migracji z 2026-05.
3. Rozdwojona wladza nad kolorem: Lab liczy 6 kolorow, 3 nadpisywane
   po cichu w agregatorze (moc/tetno/nachylenie). Kosztowalo 2 bledne
   analizy w sesji 2026-08.
4. Testy istnieja (VirtualReplayGateTest z nagrana jazda, Synthetic
   scenariusze, kontrakty architektury), CI ich NIE uruchamia —
   build.yml robi tylko assembleDebug, ktory nie kompiluje testow.

## Kontekst historyczny modulu "Lab" (pl.qbot.karoo.core)

Powstal 2026-05-26 (e759f07) jako testowalny rdzen bez Androida:
RideSample -> RideState -> FieldComputers -> FieldOutput(value, color,
status, reason). Zaprojektowany na 12 pol, wyswietla 6 (mvp()); reszte
policzyl com.qext2 gdzie indziej; migracji nie dokonczono. Modul NIE jest
martwy — karmi wartosci wszystkich 6 pol PRIMARY i kolory 3 z nich.
RideState (159 linii) to wartosciowy akumulator (pauzy, dropouty, dystans
z predkosci, nachylenie z wysokosciomierza) — nie przepisywac od zera.

## PLAN — 4 etapy, kazdy osobny commit z osobnym powrotem

### Etap 0 — bezpiecznik: testy w CI
`./gradlew test` przed assembleDebug w build.yml. Zysk: bramka replay
ozywa (weryfikacja regul pol na nagranej jezdzie bez wsiadania na rower);
kazdy kolejny etap ma hamulec.

### Etap 1 — bateria (cztery ciecia)
- fileLoggingEnabled = false + usuniecie starego CSV z urzadzenia
- petla wygaszania 250 ms -> 1000 ms (4x mniej wybudzen)
- hot-logi (BIND, QEXT_NAV_* w petli) za DEBUG_LOGGING
- regula R8: wyciecie Log.d/Log.v z release (usuwa klase problemu)

### Etap 2 — jedna wladza nad kolorem
Lab liczy wylacznie value/status/reason. Wszystkie kolory pol PRIMARY
w jednym miejscu (agregator/ColorEngine). Koniec pulapki "kod wyglada
na zywy, a nie decyduje".

### Etap 3 — likwidacja smietnika strukturalnego
- scalenie pakietow: pl.qbot.karoo.core -> com.qext2.primary.core
  (jeden swiat, koniec java/ vs kotlin/)
- rozbicie RideDataAggregator na moduly (streamy / tick / pogoda /
  nawigacja / persystencja)
Najwieksza robota, dlatego OSTATNIA — dopiero pod ochrona Etapu 0.

### Weryfikacja ("bez dowodu nie ma sukcesu")
Porownanie %baterii Karoo na godzine jazdy przed/po Etapie 1 — z historii
QBota (batteryPctRef idzie do STATS), bez dodatkowej roboty w terenie.

## Dziennik wykonania
- [x] Etap 0 (2026-08-11, zielony run 182) — bramka od razu wykryla 3 zepsute
      testy: ClimbAnnouncementProducerTest nie kompilowal sie od 2026-06
      (brak isWithinClimbBounds + stara semantyka "active"), ActiveClimbResolverTest
      i StatsCalculatorTest odstaly od swiadomych decyzji produkcyjnych
      (preferencja ascentLeftM; bramka jakosci driftu den>=200). Produkcja
      NIE wymagala zmian — naprawione testy. Bramka replay z nagrana jazda
      przechodzi z calym pakietem zmian kontrastowych.
      CI dodatkowo wrzuca pelny output gradle jako artefakt przy porazce
      (diagnoza bez dostepu do logow Actions, przez nightly.link).
- [x] Etap 1a (2026-08-11) — metryki CSV off + kasowanie zalegajacego pliku,
      petla wygaszania 250ms->1000ms, hot-logi (BIND, QEXT_NAV_*) za DEBUG_LOGGING
- [ ] Etap 1b — CI na assembleRelease (jezdzisz na DEBUG apk = narzut ART;
      release z podpisem debug juz skonfigurowany w gradle; wymaga uspojnienia
      UpdateCheckera z nazwa assetu)
- [ ] Etap 2 — jedna wladza nad kolorem
- [ ] Etap 3 — scalenie pakietow + rozbicie agregatora
- [ ] Jazda kontrolna + porownanie baterii (z historii QBota)
