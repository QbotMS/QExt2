# QExt2 — Decyzje projektowe · Sesja 2026-05-28

## Źródła danych: SDK > lokalne

1. **IF, TSS, NP, VI, KCAL** — zawsze z natywnych streamów Karoo SDK, nigdy z `StatsCalculator`
2. **RSRV** — IF i TSS z SDK, decoupling lokalny (brak źródła w SDK)
3. **VśrB** — `grossElapsedSec` (wall clock), nie SDK elapsed (żeby pauzy nie zaniżały)
4. **POWER** — tylko `SMOOTHED_3S_AVERAGE_POWER`. Stream `POWER` usunięty. Freshness z `updatePower()`
5. **LEFT/UP/DISTANCE_TO_DESTINATION** — `singleValue` zignorowane na rzecz `values[Field.*]` (bug: singleValue=0.0 maskował prawdziwe dane)
6. **DISTANCE, SMOOTHED_3S, NORMALIZED_POWER, INTENSITY_FACTOR, TSS, CALORIES** — to samo. Wszystkie streamy multi-field naprawione

## Algorytmy

7. **movingElapsedSec** — dedykowany licznik czasu ruchu. `StatsCalculator.update()` dostaje go zamiast `elapsedSec`. NP nie zaniża się przez postoje
8. **ETA** — prosta średnia ruchoma 30 min (historia prędkości tylko gdy `isMoving && speed≥1`). `EtaCalculator.kt` usunięty
9. **W'Balance** — jeden kanoniczny model w `StatsCalculator`. `SwPrimeCalc` i `WPrimeCalculator` usunięte. Oba ACTIVE czytają z `snap.wBalancePercent`
10. **W' reset** — `setWPrimeParams()` nie resetuje `wBalKj` przy odświeżeniu danych (tylko przy starcie lub gdy cap się zmienił)
11. **CARB** — nie akumuluje poniżej 60s ruchu (guard przed GPS jitter). `dtSec` cap 30s. Grace 2 min po ruchu
12. **CARB detekcja nowej jazdy** — `elapsedSec ≤ 30` LUB `elapsedSec + 120 < storedLastElapsed` LUB `elapsedSec > storedLastElapsed + 120`
13. **CARB reset przy stop** — `stopStreamingInternal()` resetuje `carb_needed_total_g`
14. **CARB snapshot** — `carbNeededTotalGRef` w snapshocie v4 (pole 17). Nowa jazda bez snapshota = reset do 0
15. **Histereza ruchu** — start >1.4 km/h, stop <0.8 km/h z fallback na power>0 && cadence>0
16. **Decoupling** — `decoupleHr`/`decouplePower` capped na 3600 sampli (~1h danych)

## Kolory pól

17. **POWER** — 5 stref Coggana (%FTP): zielony(<55), biały(55-76), żółty(76-91), pomarańczowy(91-106), czerwony(>106)
18. **HR** — kolor z `HrStrainAdvisor` (decoupling + strefy), nie zawsze biały
19. **SPEED** — zielony/żółty vs. średnia (z `FieldComputers`)
20. **CADENCE** — zielony 54-77 rpm (z `FieldComputers`)

## UI

21. **BLEFT** — pole baterii LEFT → BLEFT (odróżnienie od LEFT przewyższeń)
22. **NULL → C.+/-** — pole bilansu węgli w ACTIVE
23. **W' → D BAT** — pole W' w STATS
24. **BAT/h** — wartość "4.2%" (dodany znak %)
25. **Kalibracja** — overlay "SKALIBRUJ / MIERNIK MOCY" przy załadowaniu profilu przed STARTEM. CRITICAL priorytet, znika po ruszeniu
26. **RESET CARB** — przycisk w SETUP na dole

## Crash recovery

27. **Snapshot v4** — prefix "v4|", 17 pól (w tym `movingElapsedSec` + `carbNeededTotalGRef`). Stare snapshoty ignorowane
28. **Stan CARB** — przywracany ze snapshota przy crash recovery, zerowany przy nowej jeździe

## Stabilność

29. **removeFirst() → removeAt(0)** — Kotlin extension `MutableList.removeFirst()` niedostępna na Karoo API 33
30. **try-catch na `handlePowerSample()`** — oba ACTIVE datatypes
31. **try-catch na navigation callback** — `OnNavigationState.onEvent`
32. **WeatherClient** — `suspendCancellableCoroutine` zamiast `CountDownLatch` (0ms blokady wątku)
33. **ActiveMessageManager** — `ArrayDeque` zamiast pojedynczego slota `suspended`. Resume wybiera najwyższy priorytet
34. **HrStrainAdvisor.colorWithHysteresis** — nie resetuje timera przy braku zmiany
35. **QExt2DebugConfig** — `var` → `val`
36. **SensorMessageProducer.reset()** — czyści `cooldowns`
37. **Token GateOpenClient** — tylko w headerze `X-Gate-Token`, nie w URL
38. **ActiveMessageRenderer** — usunięty dedup `lastId` (blokował rendering na nowych RemoteViews)
39. **HrDecouplingBuffer** — `@Volatile cachedSnapshot` (cache z inwalidacją)
40. **AthleteDataStore** — `resetCarbSessionState()` atomowe (jeden `edit().apply()`)
41. **CARB button feedback** — `apply()` → `commit()` dla natychmiastowej reakcji wizualnej
42. **AthleteDataStore.init()** — w `StatsDataType.bind()` (prefs dostępne w kontekście widoku)

## Refaktoring

43. **PrimaryRenderOptimizer** — `object` → `class`, instancja per `startView()`
44. **Dead code** — `updateHRD()`, `HrdResult`, `HrdSample`, `caloriesKcal()`, `ctl`, `bindUnit()`, `getMvpOutputs()`, `fieldStatuses`, `fieldReasons`, `getSpeedKmh/getCadence/getHr/getPower/getHasRoute/getGradePercent`
45. **StatsRideSnapshot** — 20 martwych pól usuniętych (zostało 33)
46. **RideDataAggregator** — `initCarbSession()`, `computeCarbDtSec()`, `computeIsMoving()`, `accumulateCarbs()` wyciągnięte do metod
47. **getElapsedSec()** — pure function, efekty uboczne tylko w tickJob
48. **EtaCalculator.kt** — usunięty (62 linie, zastąpiony prostą średnią)

## Testy

49. **StatsCalculatorTest** — 25 testów (NP, W', CARB, FLUID, BAT, decoupling, TSS, VI, RSRV)
50. **SensorMessageProducerTest** — 11 testów (power, HR, route, sensors, cooldown, reset)

## CI/CD

51. **GitHub Actions** — auto-build na push, auto-release APK
52. **versionCode** = `GITHUB_RUN_NUMBER`, **versionName** = `0.1.X`
53. **karoo-ext AAR** — lokalnie w `libs/` (omija GitHub Packages auth)
54. **ORG_GRADLE_PROJECT** env vars dla auth w CI

## Komunikacja zdalna

55. **ZGLOS BLAD** — przycisk w SETUP, zbiera logi QExt2, pokazuje w scrollowalnym dialogu
56. **UpdateChecker** — sprawdza GitHub Releases API, otwiera przeglądarkę do pobrania nowej wersji


## Obsługa wielu rowerów + kalibracja · Sesja 2026-09-20

Kontekst: Grizl (AXS 1×13), Monster/Grand Canyon (SX Eagle mechaniczny), w drodze Grail CF AXS.

- **Rozpoznanie roweru na Karoo** (`BikeDetector`): na starcie jazdy, zatrzask na całą jazdę, odporne na chwilowy drop AXS. Reguła: moc z Quarqa→Grizl; AXS+moc≠Quarq→Grail; brak AXS + realny ruch (moc lub prędkość) po 45 s karencji→Monster (pozytywnie, nigdy jako pustka); brak dowodu ruchu→„czekam".
- **Grizl vs Grail** wymaga `sourceId` Quarqa — zbierany z logu `QEXT_POWER_SOURCE` (dziś 2 rowery, więc sama obecność AXS wystarcza; `knownQuarqSourceId=null`→AXS=Grizl). Uwaga: `sourceId` brany ze strumienia wygładzonej mocy 3S — jeśli okaże się pusty, dołożyć surowy POWER tylko po to ID.
- **Ręczny wybór roweru** = bezpiecznik sesyjny (Monster „głuchy": Assioma w reklamacji + padła bateria czujnika koła). Jednorazowy, reset po jeździe.
- **Kasety per rower** — wartości 1:1 z `qbot_v2.gear_cassette`: Grizl/Grail 10-46 (górska 10-52 przez override „aktualna kaseta"), Monster 11-50. Przód AXS czytany na żywo (Grizl/Grail); Monster korona 36T.
- **Estymacja biegu Monstera** — metoda 1:1 z `gear_estimate.py` (rozwinięcie na obrót korby, obwód 2.300 m); tylko wyświetlanie, źródłem prawdy do raportu zostaje serwer. Na zjeździe/bez pedałowania trzyma ostatnią koronkę.
- **Natychmiastowe przeliczanie koronki** przy zmianie napędu/kasety (koniec „override nie działa w trakcie jazdy").
- **Pole wiatru** — stała szerokość liczby (naprawa rozjazdu przy dwucyfrowym m/s).

### Komunikat kalibracji miernika — ZAWIESZONY
Flaga `CALIBRATION_MSG_ENABLED=false` w `CompositeActiveDataType`. Dziś odpalał się bezwarunkowo na starcie każdej jazdy (Quarq i Assioma tak samo). **Do wznowienia** po ustaleniu sytuacji z miernikami. Intencja docelowa: przypomnienie tylko dla miernika wymagającego ręcznego zera (Quarq), rozróżnianie **po `sourceId`**, nie po typie. Rozróżnić: Quarq-pająk-DM (Grizl), Quarq-ramię (reklamacja → prawdopodobnie Grail), Assioma (auto-zero → bez przypomnienia).

### Do potwierdzenia na sprzęcie (bez tego nie ogłaszamy sukcesu)
`sourceId` mocy niepusty i jego format; strumień AXS milczy na Monsterze a nadaje na Grizlu (1 Hz czy tylko przy przerzutce); estymacja Monstera vs serwer; pole wiatru równe przy 2 cyfrach; ręczny wybór działa i resetuje się po jeździe.
