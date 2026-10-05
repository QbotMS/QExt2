package com.qext2.primary.statsv2

/**
 * PRZEBIEG jazdy w oknach 5 min (czas ruchu): NP i EF (NP / srednie tetno) kazdego okna.
 * Karmione co ~1 s z QExt2PrimaryExtension (niezaleznie od tego, czy pole STATS jest widoczne).
 * NP liczone jak zwykle: srednia 4. potegi z 30-s sredniej kroczacej mocy, pierwiastek 4. stopnia.
 */
object RideWindows {
    const val WINDOW_SEC = 300
    private const val MAX_WINDOWS = 48

    data class Win(val np: Int, val ef: Float?)

    private val done = ArrayList<Win>()
    private val roll = ArrayDeque<Int>()
    private var rollSum = 0L
    private var p4 = 0.0
    private var n4 = 0
    private var hrSum = 0L
    private var hrN = 0
    private var secInWin = 0
    private var lastFeedMs = 0L
    private var lastDistKm = 0f

    @Synchronized
    fun reset() {
        done.clear(); roll.clear(); rollSum = 0L; p4 = 0.0; n4 = 0; hrSum = 0L; hrN = 0; secInWin = 0
        lastFeedMs = 0L; lastDistKm = 0f
    }

    @Synchronized
    fun feed(nowMs: Long, power: Int?, hr: Int?, moving: Boolean, distKm: Float) {
        if (distKm + 0.5f < lastDistKm) reset()          // nowa jazda (dystans wrocil do zera)
        lastDistKm = distKm
        if (nowMs - lastFeedMs < 950L) return
        lastFeedMs = nowMs
        if (!moving) return
        val p = (power ?: 0).coerceAtLeast(0)
        roll.addLast(p); rollSum += p
        if (roll.size > 30) rollSum -= roll.removeFirst()
        val avg30 = rollSum.toDouble() / roll.size
        p4 += avg30 * avg30 * avg30 * avg30; n4++
        if (hr != null && hr > 40) { hrSum += hr; hrN++ }
        secInWin++
        if (secInWin >= WINDOW_SEC) {
            done.add(current())
            if (done.size > MAX_WINDOWS) done.removeAt(0)
            p4 = 0.0; n4 = 0; hrSum = 0L; hrN = 0; secInWin = 0
        }
    }

    private fun current(): Win {
        val np = if (n4 > 0) Math.pow(p4 / n4, 0.25).toInt() else 0
        val ef = if (hrN > 30 && np > 0) np.toFloat() / (hrSum.toFloat() / hrN) else null
        return Win(np, ef)
    }

    /** Zakonczone okna + biezace (niepelne) na koncu, gdy trwa > 60 s. */
    @Synchronized
    fun snapshot(): Pair<List<Win>, Boolean> {
        val out = ArrayList(done)
        val partial = secInWin > 60
        if (partial) out.add(current())
        return out to partial
    }
}
