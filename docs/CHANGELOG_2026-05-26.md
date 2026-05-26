# QExt2 Changelog — Field Test Fixes 2026-05-26

## W′ (ACTIVE)
- `ltpWatts` nie było ustawiane przy konstrukcji WPrimeCalculator → W′=100% stale
- FIX: `CompositeActiveDataType.kt`, `BpActiveStaticDataType.kt` — `ltpWatts` z AthleteData przy konstrukcji

## Vśr netto (ACTIVE)
- SDK `AVERAGE_SPEED` zwraca m/s, wyświetlane bez konwersji → ~8 zamiast ~30
- FIX: `× 3.6` w obu ACTIVE data type'ach

## Vśr brutto (STATS)
- Było WAIT/NO_MODEL na sztywno
- FIX: liczone ze snapshota `distanceKm / elapsedSec`

## GRADE
- Podwójne filtrowanie (agregator + RideState) → lag
- FIX: surowa wartość, bez okna, integer zamiast `%.1f`

## POWER
- `PWR_3S` i `PWR_RAW` nadpisywały ten sam `powerRef` → fluktuacje
- FIX: tylko 3S ustawia wartość, RAW tylko freshness

## Bilans węgli (NULL)
- Carb akumulował tylko gdy `isMoving=true` → -3g po godzinie
- FIX: + 2min grace po jeździe (`wasActiveUntilMsRef`)

## ETA / POI nawigacja
- Route szła w MISSING po 12s grace → ETA gasło
- FIX: `resolveHasRoute` sprawdza też `distanceToDestination > 0`

## RSRV
- `dailyTssBase` zapisane jako 9999 z poprzedniej sesji → 0% od startu
- FIX: reset do 0 przy >500, log `QEXT_RSRV_CLEANUP`

## BAT
- Wymagał 10min + spadek ≥1% → często WAIT
- FIX: 5min okno, bez minimalnego spadku, `—` zamiast WAIT

## CARB button
- `requestCode=301` statyczny → Karoo nie odbierało ponownych tapów
- FIX: `carbClickId % 10000` — rotujący requestCode

## ACTIVE messages
- Logger wyciszony przez `DEBUG_LOGGING=false`
- FIX: log zawsze aktywny (`Log.i`)

## GATE
- URL na `qbot.cytr.us/gate/open`
- Ngrok usunięty z `local.properties` i `build.gradle.kts`

## ODŚWIEŻ
- Był fake refresh — tylko timestamp, bez HTTP
- FIX: `refetchAthleteData()`, 5s opóźnienie

## Weather
- `java.net.HttpURLConnection` nie działa przez Karoo SDK
- FIX: `OnHttpResponse` + `Dispatchers.IO`
- GPS: `OnLocationChanged` → dynamiczna pozycja

## W′ / CP defaults
- `StatsCalculator`: 21.3/192 → 0/0
- Wszystkie WPrimeCalculator: 3750/192 → 0/0
- Ngrok fallback w `fetchAthleteData`: zastąpiony `qbot.cytr.us`

## HR=0 → NO_DATA, nie INVALID

## Signal HRV/sleep
- QBot dodał `signals` do `/ride-readiness`
