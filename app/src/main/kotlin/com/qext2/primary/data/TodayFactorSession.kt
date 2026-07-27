package com.qext2.primary.data

/**
 * Sesyjny przelacznik TodayFactor (audyt RSRV 2026-07-26).
 *
 * Kazdy start Karoo (start procesu rozszerzenia) => TF WLACZONY (enabled = true) -- to
 * jest stan domyslny. Uzytkownik moze go WYLACZYC w SETUP, gdy jest w miejscu bez sieci
 * i gotowosci nie da sie dociagnac; wtedy RSRV/CP licza sie na neutralnym todayFactor=1.0,
 * a alarm "gotowosc nieswieza" milczy.
 *
 * Celowo NIE zapisujemy tego do preferencji: wylaczenie dziala tylko do konca sesji
 * urzadzenia. Restart Karoo => obiekt inicjuje sie od nowa => TF znow wlaczony.
 */
object TodayFactorSession {
    @Volatile
    var enabled: Boolean = true
}
