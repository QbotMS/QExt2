package com.qext2.primary.demo

/**
 * Tryb NAGRANIE (21.10.2026, sesja demo-2110) -- czysta logika decyzji, bez Androida (testy JVM: RecPolicyTest).
 * Wszystkie miejsca, ktore cos wysylaja do QBota albo zapisuja do pliku jazdy, pytaja TEN obiekt.
 * Tryb wylaczony i zwykla jazda => allowSend = true i brak pola qext2_demo, czyli QExt2 dziala jak dotad.
 */
object RecPolicy {
    /** Czy wolno wyslac do QBota (dziennik jazdy, HRV z pasa, autotest, brama, sygnal jazdy). */
    fun allowSend(recOn: Boolean, recRide: Boolean): Boolean = !recOn && !recRide

    /** Wartosc pola FIT qext2_demo: 1 w jezdzie nagrania; null = pola nie piszemy (plik jak przed 0.4.20). */
    fun demoFitValue(recOn: Boolean, recRide: Boolean): Double? = if (recOn || recRide) 1.0 else null

    /** Klatka scenariusza dla chwili nowMs (petla, 1 klatka = 1 s). */
    fun frameIndex(nowMs: Long, n: Int): Int = if (n <= 0) 0 else ((nowMs / 1000L) % n).toInt()
}
