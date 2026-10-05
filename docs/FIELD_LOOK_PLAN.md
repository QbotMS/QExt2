# Wyglad pol — plan (2026-10-04)

Status: PLAN, nic nie wdrozone. Zrodlo wiedzy: Barberfish (Apache 2.0) `docs/sdk-findings.md`, `docs/architecture.md` (BitmapValue.kt, BarberfishView).

## Stan obecny (zweryfikowany w `res/layout/field_primary_4col.xml`)
- Moc: 3 osobne TextView (`tv_power_3/4/5`, 38/33/27 sp) przelaczane visibility, gravity=center + paddingTop=10dp -> mniejsza czcionka = inna wysokosc linii cyfr (skok).
- Predkosc: to samo (`tv_speed_4/5/6`, 34/29/23 sp).
- Niespojne wyrownanie: HR/kadencja end|bottom bold 25 sp; moc/predkosc center; nachylenie center bold 30 sp; bieg 16 + 25 sp.
- Dwa style naglowkow: moc/predkosc = ikona drawable + napis 9 sp; reszta = znak unicode 7 sp (♥ ↻ ⚙ ▲).
- Brak danych = tekst "NO".
- Bezpieczne wzgledem SDK: brak setGravity/setTextAlignment/setTranslationY w runtime, brak ConstraintLayout/Space. Czasy ELAPSED_TIME/RIDE_TIME uzyte poprawnie.

## Ograniczenia SDK (z Barberfish, do przestrzegania)
- RemoteViews allowlist: tylko FrameLayout/LinearLayout/RelativeLayout/GridLayout..., liscie TextView/ImageView/ImageButton/Button/ProgressBar/Chronometer/TextClock. NIE: View, Space, ConstraintLayout, wlasne klasy (kompiluje sie, w jezdzie pusta komorka).
- Karoo 2 (Android 8): `setGravity`, `setTextAlignment`, `setTranslationY` przez RemoteViews = wyjatek i pusta komorka. Atrybuty wpisywac w XML (wariant layoutu na wartosc).
- Kontener komorki = dokladnie granice komorki (brak offsetu do kompensacji).
- Komunikat nawigacji (zakret/reroute) zmniejsza komorki BEZ ponownego startView -> nie polegac na viewSize; centrowac spacerami z weight.
- Wymiary natywne (Karoo 3, density 1.875, px): 1x1/2x1 wartosc 180, etykieta 36; 3x1 170/36; 4x1 130/36; 2x2/3x2/4x2 94/33; 5x1 104/33; 5x2 Large 78/33, Small 88/29. Wartosc lekko powyzej srodka. Naglowek ~22 dp, etykieta do prawej, condensed caps.
- Pomiar: `adb shell "dumpsys activity top"` (uiautomator nie dziala w jezdzie); skrypty Barberfish walk_layouts.sh + measure_alignment.py (cel +-2 px).

## Krok 1 [NASTEPNY]: pole mocy jako bitmapa
1. `tv_power_3/4/5` -> jeden ImageView. Kotlin rysuje liczbe na Bitmap: rozmiar dobierany do szerokosci, baseline = dolna krawedz bitmapy (cyfry bez descenderow), stala wysokosc bitmapy, `Bitmap.density = DENSITY_NONE`.
2. Centrowanie pod naglowkiem: dwa TextView-spacery weight=1 nad i pod ImageView.
3. Kolory bez zmian: cyfra z `pacingPowerColor()`, tlo `fl_power_cell` z `PacingEngine.assessPower`.
4. Render tylko przy zmianie wartosci lub koloru (cache).
5. Weryfikacja: tryb `QEXT_FAKE_RIDE`, zrzut + dumpsys przed/po, roznica linii cyfr <= 2 px przy 95/250/1100 W; potem zdjecie w sloncu.

## Kolejne kroki (po akceptacji kroku 1)
2. Predkosc ta sama metoda.
3. Ujednolicenie komorek: jedno wyrownanie cyfr, jedna czcionka, jeden styl naglowka (ikony drawable zamiast znakow unicode).
4. Stan braku danych w stylu Karoo (np. "Szukam…", "Brak czujnika") zamiast "NO".
5. Opcjonalnie: tryb jasny Karoo (dzis QExt2 go nie wykrywa).

## Nie przenosic
- Palet kolorow Barberfish "w ciemno" — QExt2 ma wlasne prace pod slonce (`docs/KONTRAST_2026-08.md`); Barberfish celuje w wyglad natywny, nie w maks. czytelnosc.
