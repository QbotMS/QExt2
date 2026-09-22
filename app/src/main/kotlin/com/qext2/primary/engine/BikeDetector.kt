package com.qext2.primary.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Rozpoznaje rower i ZATRZASKUJE decyzje na cala jazde.
 *
 * KLUCZOWE (lekcja z jazdy 2026-09-21): nie "czy AXS kiedykolwiek sie odezwal",
 * tylko "czy AXS jest SWIEZY w momencie decyzji". Zaparkowany obok rower AXS
 * (np. Grizl) jest sparowany i w zasiegu na starcie -> jego strumien biegow
 * odpala sie i zatrzasnalby bledny rower. Po odjechaniu milknie w kilkanascie
 * sekund (zasieg ~10-30 m), wiec decyzje podejmujemy dopiero po karencji ruchu,
 * gdy zaparkowany AXS jest juz cichy.
 *
 *  - AXS swiezy przy decyzji  -> rower AXS (Grizl / Grail wg sourceId Quarqa)
 *  - AXS cichy + realny ruch  -> Monster (mechanik)
 *  - Reczny wybor = bezpiecznik sesyjny, wygrywa; reset po jezdzie.
 */
class BikeDetector {

    enum class Bike { UNKNOWN, GRIZL, GRAIL, MONSTER }

    @Volatile var knownQuarqSourceId: String? = null

    private val latched = AtomicReference(Bike.UNKNOWN)
    @Volatile private var manual: Bike? = null

    private companion object {
        const val MOVE_FRESH_MS = 8_000L          // moc/predkosc swieze = realny ruch
        const val DECISION_GRACE_SEC = 60L        // tyle jazdy zanim orzeknie rower
        const val AXS_FRESH_MS = 45_000L          // AXS "swiezy" jesli odezwal sie w tym oknie
        const val TAG = "QExt2BikeDetector"
    }

    fun reset() {
        latched.set(Bike.UNKNOWN)
        manual = null
    }

    fun setManual(b: Bike?) { manual = b }
    fun manual(): Bike? = manual

    /**
     * Karmione co tick (1 Hz). Decyzja dopiero po karencji ruchu; potem zatrzask.
     * @param axsFreshMs  ms od ostatniego zdarzenia AXS (Long.MAX gdy nigdy)
     */
    fun feed(
        axsFreshMs: Long,
        powerFreshMs: Long,
        speedFreshMs: Long,
        powerSourceId: String?,
        elapsedSec: Long,
    ): Bike {
        manual?.let { return it }
        val cur = latched.get()
        if (cur != Bike.UNKNOWN) return cur

        val power = powerFreshMs in 0..MOVE_FRESH_MS
        val move = power || (speedFreshMs in 0..MOVE_FRESH_MS)
        // Bez ruchu lub przed karencja: nie decyduj (zaparkowany AXS moze byc jeszcze w zasiegu).
        if (!move || elapsedSec < DECISION_GRACE_SEC) return Bike.UNKNOWN

        val axsFresh = axsFreshMs in 0..AXS_FRESH_MS
        val decided = if (axsFresh) {
            val q = knownQuarqSourceId
            when {
                q == null -> Bike.GRIZL
                powerSourceId == q -> Bike.GRIZL
                else -> Bike.GRAIL
            }
        } else {
            Bike.MONSTER
        }
        if (latched.compareAndSet(Bike.UNKNOWN, decided)) {
            Log.i(TAG, "QEXT_BIKE_DETECTED bike=$decided axsFreshMs=$axsFreshMs powerSrc=$powerSourceId elapsed=$elapsedSec")
        }
        return latched.get()
    }

    fun current(): Bike = manual ?: latched.get()
}
