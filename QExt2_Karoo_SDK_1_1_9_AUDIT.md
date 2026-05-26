# QExt2 / Karoo 3 — audyt SDK `karoo-ext` 1.1.9 i zasady implementacji

**Data:** 2026-05-24  
**Projekt:** QExt2 / QBot / Karoo 3 LIVE + ACTIVE + STATS  
**Zakres:** SDK `io.hammerhead:karoo-ext:1.1.9`, integracja z QExt2, mapowanie danych SDK-first, zasady implementacji pól i komunikatów.  
**Status:** roboczy dokument techniczny do wgrania do projektu jako instrukcja dla Code/OpenCode.

---

## 0. Powód powstania dokumentu

W toku implementacji QExt2 pojawiły się błędne założenia:

- mylenie „klasa istnieje w SDK” z „mamy emitter w `DataTypeImpl`”;
- mylenie „zrobione w kodzie” z „potwierdzone na Karoo”;
- zbyt szybkie przechodzenie na własne fallbacki zamiast sprawdzenia oficjalnego SDK;
- mylenie samej wgranej trasy z aktywnym stanem `NavigatingRoute`;
- nieprawidłowe raportowanie beepu jako „zrobiony”, gdy faktycznie był tylko fallback albo brak ścieżki.

Ten dokument ustawia twarde zasady:

1. **SDK-first**: używać danych Karoo SDK wszędzie, gdzie są dostępne.
2. **Fallback tylko jawny**: fallback musi mieć `source/reason` w logu i nie może udawać SDK.
3. **Raportowanie prawdy**: „działa” oznacza potwierdzone na Karoo albo w logu z realnego urządzenia, nie tylko build/test.
4. **Nie rzeźbić własnej logiki tam, gdzie SDK daje źródło.**

---

## 1. Źródła i wnioski pewne

### 1.1. `karoo-ext` jako zależność

Oficjalne repo `hammerheadnav/karoo-ext` opisuje bibliotekę jako Android library dla komputerów Hammerhead Karoo i pokazuje sposób dodania zależności Gradle:

```kotlin
implementation("io.hammerhead:karoo-ext:1.x.y")
```

Repozytorium GitHub Packages wymaga autoryzacji (`gpr.user`, `gpr.key`) nawet dla publicznego package.

### 1.2. Wersja 1.1.9 istnieje

GitHub Packages pokazuje `io.hammerhead:karoo-ext 1.1.9` jako latest package, opublikowany 2026-03-17. Projekt QExt2 został skutecznie podniesiony z `1.1.8` do `1.1.9`.

**Wniosek:** dalszy rozwój QExt2 powinien zakładać `karoo-ext:1.1.9`, chyba że build albo Karoo OS pokaże regresję.

### 1.3. `KarooSystemService.dispatch(KarooEffect)` istnieje

Dokumentacja 1.1.9 pokazuje, że `KarooSystemService` ma:

```kotlin
fun dispatch(effect: KarooEffect): Boolean
```

Opis: wysyła `KarooEffect` do Karoo System service.

**Wniosek:** efekty typu `PlayBeepPattern`, `ShowMapPage`, `InRideAlert`, `MarkLap` itd. nie muszą mieć emitera w `DataTypeImpl`. One idą przez `KarooSystemService.dispatch(...)`.

### 1.4. `PlayBeepPattern` istnieje i jest `KarooEffect`

Dokumentacja 1.1.9:

```kotlin
data class PlayBeepPattern(
    val tones: List<PlayBeepPattern.Tone>
) : KarooEffect
```

`Tone`:

```kotlin
data class Tone(
    val frequency: Int?,
    val durationMs: Int
)
```

Opis: odtwarza beep pattern na wewnętrznym beeperze urządzenia.

**Wniosek:** poprawna implementacja beepu w QExt2 to **nie** Android `ToneGenerator`, tylko:

```kotlin
karooSystem.dispatch(PlayBeepPattern(tones))
```

### 1.5. `KarooEffect` obejmuje akcje systemowe

`KarooEffect` jest sealed class dla efektów wysyłanych do Karoo System. Wśród dziedziczących są m.in.:

- `PlayBeepPattern`
- `InRideAlert`
- `ShowMapPage`
- `ZoomPage`
- `PauseRide`
- `ResumeRide`
- `MarkLap`
- `SystemNotification`

**Wniosek:** dla ACTIVE MSG:

- beep: `PlayBeepPattern`
- wymuszenie mapy: `ShowMapPage`
- alarm systemowy/ride alert: `InRideAlert`

Wszystkie powinny iść przez `karooSystem.dispatch(...)`.

### 1.6. `OnNavigationState` jest oficjalnym źródłem stanu trasy/nawigacji

Dokumentacja 1.1.9:

```kotlin
data class OnNavigationState(
    val state: OnNavigationState.NavigationState
) : KarooEvent
```

Opis: obserwuje stan nawigacji: route selection lub destination.

Stany:

- `Idle`
- `NavigatingRoute`
- `NavigatingToDestination`

`NavigatingRoute` zawiera:

- `routePolyline`
- `routeDistance`
- `routeElevationPolyline`
- `rejoinPolyline`
- `rejoinDistance`
- `name`
- `reversed`
- `breadcrumb`
- `pois`
- `climbs`

`Climb` zawiera:

- `startDistance`
- `length`
- `grade`
- `totalElevation`

**Wniosek:** route/climb detection w QExt2 ma bazować na `OnNavigationState`, nie na zgadywaniu z `distanceToDestination > 0`.

### 1.7. Wgrana trasa ≠ aktywna nawigacja

Log QExt2 pokazał dwa poprawne stany:

```text
QEXT_NAV_STATE type=NavigatingRoute navigating=true climbs=0
QEXT_ROUTE_STATE rawRoute=true effectiveRoute=true source=NAV
```

oraz:

```text
QEXT_NAV_STATE type=Idle navigating=false
QEXT_ROUTE_STATE rawRoute=false effectiveRoute=false source=MISSING
```

**Wniosek:** jeżeli Karoo jest `Idle`, QExt2 ma prawo pokazywać `route=false`, nawet jeśli plik trasy jest „wgrany” gdzieś w urządzeniu. Dla QExt2 liczy się aktywna nawigacja (`NavigatingRoute` albo `NavigatingToDestination`).

---

## 2. Architektura SDK — co czym robić

### 2.1. `KarooSystemService`

Używać do:

- połączenia z Karoo System (`connect`, `disconnect`);
- subskrypcji eventów (`addConsumer<T : KarooEvent>`);
- wysyłania systemowych efektów (`dispatch(KarooEffect)`).

Minimalny wzorzec:

```kotlin
val karooSystem = KarooSystemService(context)

karooSystem.connect { connected ->
    log("QEXT_KAROO_CONNECT connected=$connected")
}

val id = karooSystem.addConsumer<OnNavigationState>(
    onError = { error -> log("QEXT_NAV_CONSUMER_ERROR error=$error") },
    onComplete = { log("QEXT_NAV_CONSUMER_COMPLETE") }
) { event ->
    handleNavigationState(event.state)
}
```

Efekt:

```kotlin
val ok = karooSystem.dispatch(ShowMapPage(zoom = false))
```

Beep:

```kotlin
val ok = karooSystem.dispatch(
    PlayBeepPattern(
        listOf(PlayBeepPattern.Tone(5000, 120))
    )
)
```

### 2.2. `KarooExtension`

`KarooExtension` udostępnia:

- `types`: lista `DataTypeImpl`;
- `startMap(Emitter<MapEffect>)`;
- `startFit(Emitter<FitEffect>)`;
- `startScan(Emitter<Device>)`;
- `connectDevice`;
- `onBonusAction`.

**Ważne:** brak `Emitter<KarooEffect>` nie oznacza, że `KarooEffect` jest nieużywalny. Do `KarooEffect` służy `KarooSystemService.dispatch(...)`.

### 2.3. `DataTypeImpl`

`DataTypeImpl` jest do:

- `startStream(Emitter<StreamState>)`;
- `startView(Context, ViewConfig, ViewEmitter)`.

Nie dispatchować `KarooEffect` przez `ViewEmitter`. Nie próbować tworzyć emitera `KarooEffect` w data field.

### 2.4. `DataType.Field`

Dokumentacja pokazuje pre-existing Karoo fields. Istotne dla QExt2:

- `DISTANCE`
- `DISTANCE_TO_DESTINATION`
- `ELAPSED_TIME`
- `RIDE_TIME`
- `PAUSED_TIME`
- `ELEVATION_GAIN`
- `ASCENT_REMAINING`
- `DESCENT_REMAINING`
- `ELEVATION_GRADE`
- `AVERAGE_SPEED`
- `SPEED`
- `POWER`
- `HEART_RATE`
- `CADENCE`
- `CALORIES`
- `BATTERY_PERCENT`
- `ON_ROUTE`
- `NAVIGATION_STATE`

**Zasada:** najpierw potwierdzić właściwą stałą w SDK/kodzie, potem dopiero mapować. Nie zgadywać nazw typu `FIELD_ELEVATION_REMAINING_ID`.

### 2.5. `DataType.Type` vs `DataType.Field`

`DataType.Type` i `DataType.Field` nie są tym samym. W projekcie trzeba utrzymywać mapę:

- `fieldName` użytkownika;
- SDK `Field` lub `Type`;
- jednostka;
- freshness;
- fallback;
- status potwierdzenia na Karoo.

Nie wolno mieszać ich bez logowania `source`.

---

## 3. Twardy podział: SDK primary vs QExt2 logic

### 3.1. SDK PRIMARY

Te pola mają używać SDK jako podstawy:

| Pole QExt2 | Źródło primary | Uwagi |
|---|---|---|
| D / distance | SDK `DISTANCE` | dystans aktywności |
| DTD | SDK `DISTANCE_TO_DESTINATION` albo nawigacja | przy braku SDK można fallback `routeDistance - distanceDone`, jawnie |
| UP done | SDK `ELEVATION_GAIN` | nie liczyć samemu, jeśli SDK świeże |
| UP LEFT / ascent remaining | SDK `ASCENT_REMAINING` / potwierdzony field remaining | nie używać DTD jako elevation |
| TSS | SDK primary | lokalny TSS tylko fallback diagnostyczny |
| KCAL | SDK `CALORIES` primary | lokalny fallback tylko jawny |
| Speed | SDK | jednostki potwierdzić |
| Vśr / avg speed | SDK `AVERAGE_SPEED` primary | jeżeli zła wartość, logować źródło/jednostkę |
| Vśr brutto | QExt2 derived z SDK distance + SDK elapsed | lokalny wskaźnik, ale z SDK inputs |
| Elapsed | SDK `ELAPSED_TIME`/timer primary | walidacja ms/s obowiązkowa |
| Gear | SDK/sensor stream | grace na reconnect |
| HR / Power / Cadence | SDK/sensor stream | freshness + grace |
| Battery % | SDK/hardware/field | battery drain local |
| Route state | `OnNavigationState` | `NavigatingRoute`/`NavigatingToDestination` |
| Climbs | `OnNavigationState.NavigationState.Climb` | brak climbów = `climbs=[]`, nie własny fallback bez decyzji |

### 3.2. QExt2 LOGIC

Te pola są naszą logiką, ale powinny brać SDK inputs:

| Funkcja | Logika |
|---|---|
| ETA | QExt2: DTD / speedBasis; display `--/WAIT/calculated` |
| CARBS | QExt2: narastający deficyt, input time/intensity |
| RSRV | QExt2: model rezerwy; brak danych ≠ 0% |
| W′ | QExt2: CP/W′ model; brak config ≠ valid 100% |
| ACTIVE MSG | QExt2: producenci + priority engine + renderer |
| Power/HR/Cadence tint | QExt2 interpretacja/smoothing/hysteresis |
| Gear intelligence | QExt2 interpretacja |
| Weather/rain-on-route | QExt2/provider zewnętrzny |
| Safety/SOS | QExt2 + Q backend |

---

## 4. Szczegółowe zasady implementacji QExt2

### 4.1. Zasada `source/reason`

Każde pole, które może pochodzić z SDK albo fallbacku, musi mieć log:

```text
QEXT_<FIELD>_SOURCE sdk=... local=... chosen=... source=SDK|LOCAL_FALLBACK|MISSING reason=...
```

Przykłady:

- `QEXT_TSS_SOURCE`
- `QEXT_KCAL_BIND`
- `QEXT_ETA_BIND`
- `QEXT_ROUTE_STATE`
- `QEXT_TIME_RAW`
- `QEXT_TIME_STATE`
- `QEXT_AVG_GROSS_BIND`
- `QEXT_RSRV_DIAG`

### 4.2. Brak danych nigdy nie może wyglądać jak valid zero

| Stan | Display |
|---|---|
| brak route | `--` |
| route jest, ale brakuje danych wejściowych | `WAIT` |
| source nie przyszedł jeszcze | `WAIT` albo `--`, zależnie od pola |
| valid zero z SDK | `0` |
| valid wynik | wartość |

Przykłady:

- TSS: fresh SDK 0 → `0`; missing → `WAIT`/`--`
- KCAL: fresh SDK 0 → `0`; missing → `WAIT`/`--`
- RSRV: no snapshot → `--`; route missing/model not ready → `WAIT`; valid zero → `0%` czerwone
- ETA: route false → `--`; route true but speed/DTD unstable → `WAIT`; valid → godzina/czas

### 4.3. Elapsed: obowiązkowa walidacja jednostek

W logach wykryto, że SDK elapsed może przychodzić jako wartości typu `16000`, co oznaczało najprawdopodobniej ms, a nie s.

Implementacja musi:

- sprawdzać `raw`;
- interpretować `asIs` i `asMs`;
- porównywać z `localElapsed`;
- wybierać najbliższe sensowne;
- logować:

```text
QEXT_TIME_RAW raw=... asIs=... asMs=... local=... chosen=... unit=ms|sec|unknown
QEXT_TIME_STATE sdkElapsed=... localElapsed=... chosenElapsed=... source=SDK_ELAPSED_VALID|LOCAL_FALLBACK_SDK_OUTLIER|...
```

Nie wolno dopuścić, by absurdalne `11000s` po `77s` jazdy rozwalało CARBS/TSS/W′.

### 4.4. Route: `OnNavigationState` jako primary

Implementacja:

```kotlin
karooSystem.addConsumer<OnNavigationState> { event ->
    when (val state = event.state) {
        is OnNavigationState.NavigationState.NavigatingRoute -> {
            routeActive = true
            routeName = state.name
            routeDistance = state.routeDistance
            climbs = state.climbs
            routeElevationPolyline = state.routeElevationPolyline
        }
        is OnNavigationState.NavigationState.NavigatingToDestination -> {
            routeActive = true
            climbs = state.climbs
        }
        is OnNavigationState.NavigationState.Idle -> {
            routeActive = false
        }
    }
}
```

Logi:

```text
QEXT_NAV_CONSUMER_START
QEXT_NAV_CONSUMER_OK id=...
QEXT_NAV_STATE type=NavigatingRoute navigating=true climbs=...
QEXT_ROUTE_STATE rawRoute=true effectiveRoute=true source=NAV
QEXT_ROUTE_CLIMB index=... start=... len=... elev=... grade=...
```

Grace:

- po `Idle` można utrzymać `effectiveRoute=true` przez 10–15 s;
- jeśli nigdy nie było `NavigatingRoute`, `lastSeenAgo=never source=MISSING`.

### 4.5. Climbs: SDK-only na tym etapie

Jeżeli `NavigatingRoute(... climbs=[])`, to:

- route działa;
- SDK nie podało climbów dla tej trasy;
- `ClimbAnnouncementProducer` ma logować `reason=no_sdk_climbs`;
- nie wolno własnoręcznie wykrywać podjazdów bez osobnej decyzji.

Dopuszczalne:

- test fake mode do pipeline;
- real mode tylko SDK climbs.

### 4.6. ACTIVE MSG engine

Architektura:

```text
Producer(s) -> MessagePriorityEngine -> ActiveMessageRenderer -> overlay
```

Zasady:

- `qext2-active` ma overlay + engine;
- `qext2-active-static` jest fallbackiem bez overlay i ma pozostać nietknięty;
- producer emituje kandydatów;
- engine decyduje o priorytetach;
- renderer tylko renderuje;
- żadnej logiki decyzji w rendererze.

Priorytety:

```text
INFO_LOW < INFO < WARNING < CRITICAL
```

Resume:

- `DROP_ON_INTERRUPT`
- `RESUME_IF_STILL_VALID`
- `STICKY_UNTIL_ACK` — zarezerwowane, nie używać bez ACK

### 4.7. ACTIVE MSG beep — poprawna implementacja

Poprawna implementacja w SDK 1.1.9:

```kotlin
import io.hammerhead.karooext.models.PlayBeepPattern

fun playWarningBeep(karooSystem: KarooSystemService): Boolean {
    return karooSystem.dispatch(
        PlayBeepPattern(
            listOf(PlayBeepPattern.Tone(5000, 120))
        )
    )
}
```

Critical:

```kotlin
karooSystem.dispatch(
    PlayBeepPattern(
        listOf(
            PlayBeepPattern.Tone(5000, 180),
            PlayBeepPattern.Tone(null, 60),
            PlayBeepPattern.Tone(5000, 220)
        )
    )
)
```

Status:

```text
ACTIVE_BEEP_STATUS = ENABLED_KAROO_DISPATCH
```

Log:

```text
QEXT_ACTIVE_BEEP id=... severity=... backend=KAROO_SYSTEM_DISPATCH reason=played|dispatch_false|dispatch_error|suppressed_cooldown|resume_no_beep|info_no_beep
```

Zakazane:

- `ToneGenerator`
- `MediaPlayer`
- Android stream hacks
- raportowanie jako SDK beep, jeśli dispatch nie jest używany

### 4.8. `ShowMapPage` dla ACTIVE MSG

`ShowMapPage(zoom = false)` jest `KarooEffect`.

Użycie:

```kotlin
karooSystem.dispatch(ShowMapPage(zoom = false))
```

Zasady QExt2:

- docelowo ACTIVE MSG może przełączać na mapę;
- nie dla `INFO_LOW`;
- nie przy resume;
- cooldown 20–30 s;
- `CRITICAL` ignoruje cooldown albo ma osobną politykę;
- nie mieszać furtki/Gate z ACTIVE.

### 4.9. `InRideAlert`

`InRideAlert` jest `KarooEffect` dla ważnych alertów ride app. Używać tylko dla naprawdę ważnych/critical, nie do zwykłych pacing info.

---

## 5. Mapowanie konkretnych pól QExt2

### 5.1. LIVE / PRIMARY

| Pole | Źródło | Logika |
|---|---|---|
| Speed | SDK current speed | display szybko, tint z histerezą |
| Power | SDK power | tint z bezwładnością; nie flicker |
| HR | SDK HR | smoothing/strain |
| Cadence | SDK cadence | nie szosowy target 90 rpm |
| Grade | SDK grade/elevation | smoothing/hysteresis |
| Gear | SDK/sensor | grace 5–10 s po reconnect/page switch |

### 5.2. ACTIVE

| Pole | Źródło | Uwagi |
|---|---|---|
| D / distance | SDK | nie `NO` przy krótkim reconnect |
| Vśr | SDK `AVERAGE_SPEED` albo własna jasno oznaczona | zweryfikować jednostki |
| Vśr B | SDK distance + validated elapsed | brutto, z pauzami |
| DTD | SDK `DISTANCE_TO_DESTINATION` | fallback routeDistance - D tylko jawny |
| T / temp | SDK/weather provider | tint opadowy osobno |
| W / wind | Headwind/SDK external | runtime integration |

### 5.3. STATS

| Pole | Źródło | Zasada |
|---|---|---|
| TSS | SDK primary | `WAIT/0/value` |
| KCAL | SDK primary | local fallback tylko jawny |
| CARBS | QExt2 | deficyt narastający, nigdy ujemny absurd |
| RSRV | QExt2 | missing ≠ 0% |
| W′ | QExt2 | config required; brak config ≠ valid 100% |
| ETA | QExt2 | route/DTD/speed inputs |
| UP | SDK `ELEVATION_GAIN` | no random 0/7 |
| LEFT | SDK ascent remaining / nav route | no random |
| BAT / %h / left | SDK battery + local drain calc | WAIT until enough samples |
| SUN | battery row | sprawdzić wizualnie |
| Gate | działa na STATS, nie ACTIVE | nie integrować z DYNMSG |

---

## 6. Diagnostyka obowiązkowa

### 6.1. Logi okresowe

Co 10–15 s maksymalnie:

```text
QEXT_FIELD_DIAG ...
QEXT_TIME_RAW ...
QEXT_TIME_STATE ...
QEXT_ROUTE_STATE ...
QEXT_TSS_SOURCE ...
QEXT_KCAL_BIND ...
QEXT_ETA_BIND ...
QEXT_RSRV_DIAG ...
QEXT_GEAR_STATE ...
QEXT_AVG_GROSS_BIND ...
```

Nie spamować per-sample.

### 6.2. Logi ACTIVE

```text
QEXT_ACTIVE_PRODUCER_DIAG ...
QEXT_CLIMB_MSG TRIGGER/REJECT ...
QEXT_ACTIVE_ENGINE SHOW/INTERRUPT/RESUME/DROP/EXPIRE
QEXT_ACTIVE_MSG BIND visible=...
QEXT_ACTIVE_BEEP ...
```

`BIND visible=false` nie może spamować przy `null -> null`.

### 6.3. Raporty Code/OpenCode

Każdy raport ma mieć cztery sekcje:

```text
ZROBIONE I POTWIERDZONE NA KAROO / W LOGU
ZROBIONE W KODZIE, NIEPOTWIERDZONE NA EKRANIE
NIE DZIAŁA / WYŁĄCZONE
WYMAGA DANYCH SDK / TRASY / NAWIGACJI
```

Zakazane raportowanie:

- „gotowe”, gdy tylko build przeszedł;
- „działa”, gdy działa tylko fake mode;
- „beep zrobiony”, jeśli nie ma `KarooSystemService.dispatch(PlayBeepPattern)`;
- „climb messages działają”, gdy realne `climbs=[]`.

---

## 7. Minimalne testy przed field build

### 7.1. Unit tests

Wymagane testy:

- elapsed ms/s sanity;
- route state NAV/GRACE/MISSING;
- ETA `--/WAIT/calculated`;
- TSS `WAIT/0/value`;
- KCAL `WAIT/0/value/fallback`;
- RSRV missing/not_ready/valid zero/valid nonzero;
- Vśr B distance/elapsed;
- ACTIVE priority interrupt/resume/drop;
- ACTIVE beep dispatch warning/critical/info/resume/cooldown;
- renderer dedup `null -> null`, same id, message -> null;
- gear grace;
- carbs deficit clamp.

### 7.2. Karoo smoke test

Na Karoo:

1. uruchomić aktywność bez trasy;
2. potwierdzić `OnNavigationState Idle`;
3. uruchomić aktywną nawigację po trasie;
4. potwierdzić:

```text
QEXT_NAV_STATE type=NavigatingRoute
QEXT_ROUTE_STATE rawRoute=true effectiveRoute=true source=NAV
```

5. sprawdzić UI:
   - ETA nie puste;
   - TSS/KCAL startują `WAIT` albo `0`, nie puste;
   - RSRV `WAIT/--`, nie fake red 0;
   - SUN w battery row;
   - Vśr B w miejscu SUNSET;
   - ACTIVE beep dla WARNING, jeśli trigger jest wymuszony testowo.

---

## 8. Prompt wzorcowy dla Code/OpenCode po tym audycie

```text
Użyj dokumentu QExt2_Karoo_SDK_1_1_9_AUDIT.md jako nadrzędnej instrukcji technicznej.

Zasady:
1. SDK-first.
2. Fallback tylko jawny z source/reason.
3. Nie raportuj działania bez potwierdzenia na Karoo/logu.
4. KarooEffect dispatchuj przez KarooSystemService.dispatch(...).
5. Beep implementuj przez PlayBeepPattern, nie ToneGenerator.
6. Route/climbs bierz z OnNavigationState.
7. Gate zostaje wyłącznie w STATS.
8. qext2-active-static pozostaje fallbackiem i nie może być ruszany bez zgody.
9. Brak danych nie może być pokazywany jako valid zero.
10. Każda zmiana musi mieć testy i raport zgodny z sekcjami:
   - potwierdzone,
   - niepotwierdzone,
   - wyłączone,
   - zależne od SDK/nawigacji.

Przed zmianą przeprowadź audyt lokalnego kodu:
- gdzie jest KarooSystemService,
- gdzie connect/disconnect,
- gdzie dispatch,
- gdzie OnNavigationState,
- gdzie DataType.Field/Type mapping,
- gdzie formattery UI,
- gdzie fallbacki.
```

---

## 9. Bieżący status QExt2 po dotychczasowych pracach

### Potwierdzone w logu

- `OnNavigationState` consumer startuje.
- `NavigatingRoute` jest odbierany, gdy Karoo faktycznie nawiguję po trasie.
- `route=true source=NAV` działa.
- `Idle` przełącza route na `GRACE`, a potem `MISSING`.
- SDK elapsed wymaga ms/s sanity.
- `karoo-ext` 1.1.9 jest zainstalowane i build przechodzi.

### Nie wolno uznawać za w pełni potwierdzone bez kolejnej jazdy

- ETA UI na ekranie.
- TSS/KCAL display start.
- RSRV `WAIT/--/X%`.
- Vśr B layout.
- SUN battery row layout.
- W′ real behavior.
- CARBS real ride behavior.
- Gear grace po przełączaniu stron.
- Battery %/h i time remaining.
- ACTIVE beep real sound on Karoo.
- Climb messages z realnymi SDK climbs — obecna trasa zwracała `climbs=[]`.

---

## 10. Źródła

1. `karoo-ext` GitHub README — dependency, GitHub Packages, dokumentacja.  
   https://github.com/hammerheadnav/karoo-ext
2. GitHub Packages `io.hammerhead:karoo-ext 1.1.9` — wersja 1.1.9.  
   https://github.com/hammerheadnav/karoo-ext/packages/2175616
3. Dokka `KarooSystemService` 1.1.9 — `connect`, `addConsumer`, `dispatch(KarooEffect)`.  
   https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext/-karoo-system-service/index.html
4. Dokka `PlayBeepPattern` 1.1.9 — `PlayBeepPattern : KarooEffect`, `Tone(frequency, durationMs)`.  
   https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext.models/-play-beep-pattern/index.html
5. Dokka `KarooEffect` 1.1.9 — lista efektów systemowych.  
   https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext.models/-karoo-effect/index.html
6. Dokka `OnNavigationState` 1.1.9 — event nawigacji.  
   https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext.models/-on-navigation-state/index.html
7. Dokka `NavigationState` 1.1.9 — `Idle`, `NavigatingRoute`, `NavigatingToDestination`, `Climb`.  
   https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext.models/-on-navigation-state/-navigation-state/index.html
8. Dokka `DataType.Field` — field constants Karoo.  
   https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext.models/-data-type/-field/index.html
9. Hammerhead community post „New Extension Capabilities…” — navigation data exposed in karoo-ext events.  
   https://support.hammerhead.io/hc/en-us/community/posts/33059191011227-New-Extension-Capabilities-in-Karoo-Release-1-538-2049
10. Logi QExt2 z Karoo: `QEXT_NAV_STATE`, `QEXT_ROUTE_STATE`, `QEXT_TIME_RAW`, `QEXT_ACTIVE_MSG`.

---

## 11. Najważniejsze poprawki do zrobienia po audycie

1. **Beep:** usunąć Android fallback i przejść na:

```kotlin
karooSystem.dispatch(PlayBeepPattern(...))
```

2. **Raportowanie:** poprawić raporty Code/OpenCode według sekcji prawdy.
3. **SDK source map:** utrzymać centralną tabelę mapowania pól.
4. **KCAL/TSS/ETA/RSRV UI:** potwierdzić na Karoo, nie tylko w buildzie.
5. **Climbs:** nie fallbackować do własnej detekcji bez decyzji użytkownika.
6. **NO flicker:** utrzymać last known values + grace przy reconnect/switch.
7. **Debug flags:** po field testach wyłączyć nadmiarowe diagnostyki.
