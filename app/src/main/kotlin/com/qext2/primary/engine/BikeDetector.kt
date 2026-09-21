package com.qext2.primary.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Rozpoznaje rower na starcie jazdy i ZATRZASKUJE decyzje na cala jazde.
 *  - Oparte na "czy AXS w tej jezdzie sie odezwal" (zatrzask), nie na chwilowej
 *    swiezosci -> odporne na drop AXS (wymiana baterii, zanik sygnalu).
 *  - Monster (mechanik) rozpoznany POZYTYWNIE: brak AXS + realny ruch po karencji,
 *    nigdy jako domyslna pustka.
 *  - Reczny wybor = bezpiecznik sesyjny, wygrywa; reset po jezdzie.
 *
 * Grizl vs Grail wymaga sourceId Quarqa (zbieramy z logu). Dzis sa 2 rowery, wiec
 * sama obecnosc AXS wystarcza: knownQuarqSourceId=null -> AXS = GRIZL.
 */
class BikeDetector {

    enum class Bike { UNKNOWN, GRIZL, GRAIL, MONSTER }

    @Volatile var knownQuarqSourceId: String? = null

    private val latched = AtomicReference(Bike.UNKNOWN)
    @Volatile private var manual: Bike? = null

    private companion object {
        const val MOVE_FRESH_MS = 8_000L        // moc/predkosc swieze = realny ruch
        const val MONSTER_GRACE_SEC = 45L       // tyle ruchu bez AXS zanim orzekniemy Monster
        const val TAG = "QExt2BikeDetector"
    }

    /** Reset na start i koniec jazdy. */
    fun reset() {
        latched.set(Bike.UNKNOWN)
        manual = null
    }

    /** Reczny wybor (bezpiecznik). null = wylacz. */
    fun setManual(b: Bike?) { manual = b }
    fun manual(): Bike? = manual

    /**
     * Karmione co tick (1 Hz). Zatrzaskuje pierwsze pewne rozpoznanie.
     * @return aktualnie rozpoznany rower (lub UNKNOWN gdy jeszcze nie wiadomo).
     */
    fun feed(
        axsEverSeen: Boolean,
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

        val decided = when {
            axsEverSeen -> {
                val q = knownQuarqSourceId
                when {
                    q == null -> Bike.GRIZL
                    powerSourceId == q -> Bike.GRIZL
                    else -> Bike.GRAIL
                }
            }
            move && elapsedSec >= MONSTER_GRACE_SEC -> Bike.MONSTER
            else -> Bike.UNKNOWN
        }
        if (decided != Bike.UNKNOWN && latched.compareAndSet(Bike.UNKNOWN, decided)) {
            Log.i(TAG, "QEXT_BIKE_DETECTED bike=$decided axsEverSeen=$axsEverSeen powerSrc=$powerSourceId elapsed=$elapsedSec")
        }
        return latched.get()
    }

    /** Aktualny rower: reczny (jesli ustawiony) albo zatrzasniety. */
    fun current(): Bike = manual ?: latched.get()
}
