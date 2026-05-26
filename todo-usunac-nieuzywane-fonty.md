# Do zrobienia: usunąć nieużywane fonty

## Problem
`res/font/roboto_condensed_regular.ttf` i `res/font/roboto_condensed_medium.ttf` (600KB łącznie) są w APK, ale od wersji `monospace` żaden layout ich nie używa.

## Zakres
1. Usunąć pliki:
   - `app/src/main/res/font/roboto_condensed_regular.ttf`
   - `app/src/main/res/font/roboto_condensed_medium.ttf`
2. Jeśli katalog `res/font/` zostanie pusty, usunąć i katalog.
3. Zbudować: `./gradlew app:assembleDebug`
4. Sprawdzić, czy APK nie zawiera już `res/font/`.
