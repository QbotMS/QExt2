# QExt2 — komunikaty ACTIVE: co widze na ekranie i co to znaczy

Prostym jezykiem. Pole **ACTIVE MSG** (`qext2-active`) to pasek, ktory pojawia sie tylko wtedy,
gdy jest cos do powiedzenia. Brak komunikatu = pole puste (to normalne, nie awaria).

Kolory: **niebieski** = informacja, **zolty** = ostrzezenie, **czerwony** = pilne (+ beep).

---

## 1. Stan W' — `UWAGA!`

Pokazuje sie, gdy zapas beztlenowy (W') spadnie **ponizej 55%**.
Pierwsza linia zawsze: `X% W'` (ile zostalo w baku).
Druga linia zalezy od tego, co robisz **teraz** (moc liczona ze sredniej 3 s):

| widzisz | znaczy |
|---|---|
| `TRZYMASZ!` | jedziesz mniej wiecej dokladnie na CP — bak ani nie rosnie, ani nie maleje |
| `odbudowa 8:20` | jedziesz ponizej CP, bak sie laduje; tyle zostalo do **90%** |
| `bomba 3:20` | jedziesz powyzej CP, palisz zapas; tyle zostalo do wyczerpania |
| `PRZEPAŁ` | bak pusty (0%), a Ty dalej ciSniesz nad CP |

**Dlaczego odbudowa do 90%, a nie do 100%?**
Odbudowa jest wykladnicza — kazda sekunda domyka tylko ulamek brakujacej luki, wiec 100%
jest asymptota (formalnie nieosiagalne, praktycznie ~20 min). 90% to moment, w ktorym
realnie odzyskales zdolnosc do wysilku.

**Co znaczy PRZEPAŁ?**
Model mowi "koniec", a nogi jada dalej. To znak, ze **W' masz ustawione za nisko** —
i jednoczesnie najlepszy material do kalibracji (breakthrough). Warto zapamietac taka jazde.

**Jak czesto sie odzywa?**
Im blizej bomby, tym czesciej: powyzej 2 min — co 30 s; 30 s-2 min — co 10 s;
ponizej 30 s — co sekunde, na czerwono i z beepem. Spokojne stany (TRZYMASZ, odbudowa) — co 60 s.

---

## 2. Podjazdy

| komunikat | kiedy |
|---|---|
| `PODJAZD: Xkm ↑Ym +Z%` | 500 m przed podjazdem z trasy |
| `PODJAZD DONE ↑Ym` | po zjechaniu z podjazdu |
| `PACING CLIMBING ON` | wejscie w tryb podjazdowy (max co 10 min) |

Wymaga zaladowanej trasy. Bez trasy podjazdy sa nieznane.

---

## 3. Jedzenie i picie

| komunikat | kiedy |
|---|---|
| `ZJEDZ ~Ng` | co kolejna porcje weglowodanow wg modelu `carbsGPerH` |
| `PIJ` | co 0,25 L zalecanego plynu |
| `SÓD 500-800mg` | co godzine, gdy odczuwalna temperatura >= 28 st. C |

Napedzane wykonana praca, nie zegarem. Pierwszy komunikat po 20 min jazdy.

---

## 4. Pogoda (`WX ...`)

`WX BURZA` (sprawdz radar), `WX ULEWA`, `WX DESZCZ`, `WX MŻAWKA` (z mm/h),
`WX ZIMNO+MOKRO`, `WX UPAL`, `WX MROZ` (z temperatura), `WX SILNY WIATR` (w m/s).

Wymaga swiezych danych pogodowych. Znane ograniczenia (stan 2026-07-20):
brak retry po nieudanym pobraniu (cisza do 10 min) oraz deszcz wykrywany wylacznie
z liczbowego `rain.1h` — samo slowo "Rain" z OpenWeatherMap jest ignorowane.

---

## 5. Sensory

| komunikat | znaczy |
|---|---|
| `BRAK MOCY` | brak danych z miernika mocy — sprawdz sensor |
| `BRAK HR` | brak tetna, pacing dziala w ograniczonym zakresie |
| `BRAK SENSORÓW` | brak mocy, tetna i kadencji naraz |
| `BRAK TRASY` | nie ma zaladowanej trasy, funkcje podjazdowe wylaczone |

---

## Zasady wspolne

- Komunikat o wyzszym priorytecie przerywa nizszy (`INFO_LOW < INFO < WARNING < CRITICAL`).
- Beep tylko dla WARNING i CRITICAL, z wyciszeniem 10 s (nie zabipie co sekunde).
- Pole nie ma stanow `WAIT` / `NO_DATA` — przy braku tresci po prostu znika.

Szczegoly techniczne i historia decyzji: `docs/QEXT2_ACTIVE_MSG_AUDIT.md`.
