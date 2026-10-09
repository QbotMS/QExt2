package com.qext2.primary.engine

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

class StatsCalculator(var ftpWatts: Int = 200) {

    private val powerBuffer30s = ArrayDeque<Int>(30)
    private var sumOf4thPowers = 0.0
    private var count4thPowers = 0L

    private var totalPowerSum = 0L
    private var totalPowerCount = 0L
    private var totalEnergyKj = 0.0

    private val decoupleHr = mutableListOf<Int>()
    private val decouplePower = mutableListOf<Int>()

    var todayFactor: Float = 1.0f
    var bodyWeightKg: Float = 75f
    var humidityPercent: Float = 50f
    var ctlXssForBudget: Float = 0f

    private var batteryPctStart: Int? = null
    private var batteryPctCurrent: Int? = null
    private var batteryStartMs: Long? = null
    private var batteryIsCharging: Boolean? = null

    private var lastMovingSec: Long = 0L

    // RSRV v2 (ReserveModelV2): stan biezacej jazdy; LTHR i HRmax z /ride-readiness (applyAthleteData).
    private val reserveV2 = ReserveModelV2.State()
    var lthrBpm: Float = 148f
    var maxHrBpm: Int = 184

    private var wPrimeKj: Float = 0f
    private var ltpWatts: Float = 0f
    private var wBalKj: Float = 0f
    // E1.4 (plan v2): 0% W' to poprawny stan; pelna rezerwa tylko przy PIERWSZYM ustawieniu W' w jezdzie.
    private var wBalInitialized = false

    // XSS (odpowiednik Xert Strain Score) -- ten sam wzor co ModelQ w QBocie (DECISIONS.md 2026-07-06).
    // Rosnacy licznik przez cala jazde, 1h @ CP = 100 XSS z definicji.
    private var xssAccum: Float = 0f

    // CP_eff (LIN) + IF_eff -- DECISIONS.md 2026-07-18. realLtp trzymane OSOBNO,
    // bo ltpWatts jest nadpisywane cf-owym CP przez setEffectiveWPrime.
    private var realLtpWatts: Float = 0f
    private val powerBuffer300s = ArrayDeque<Int>(300)
    private var sumOf4thPowersEff = 0.0
    private var countEff = 0L
    private var lastCpEffLinW: Float = 0f

    private val wBalHistory = ArrayDeque<Pair<Long, Int>>()

    fun wBalanceTrend(): String {
        if (wBalHistory.size < 3) return "stable"
        val recent = wBalHistory.takeLast(3)
        val deltaPerMin = ((recent.last().second - recent.first().second).toFloat() /
            ((recent.last().first - recent.first().first) / 60_000f)).coerceIn(-100f, 100f)
        return when {
            deltaPerMin < -10f -> "plummeting"
            deltaPerMin < -2f -> "falling"
            deltaPerMin > 2f -> "rising"
            else -> "stable"
        }
    }

    fun setWPrimeParams(wPrime: Float, ltp: Float) {
        if (wPrime > 0f) {
            wPrimeKj = wPrime
            if (!wBalInitialized) { wBalKj = wPrime; wBalInitialized = true }
            else if (wBalKj > wPrime) wBalKj = wPrime
        }
        if (ltp > 0f) ltpWatts = ltp
    }

    // Per-tick effective critical power + W' capacity (CP anchored on FTP, scaled by
    // readiness/heat/decoupling). Does NOT refill wBalKj on zero (only clamps down).
    fun setEffectiveWPrime(cpEff: Float, wPrimeMaxEff: Float) {
        if (cpEff > 0f) ltpWatts = cpEff
        if (wPrimeMaxEff > 0f) {
            wPrimeKj = wPrimeMaxEff
            if (wBalKj > wPrimeKj) wBalKj = wPrimeKj
        }
    }

    /** WATEK 2 (Strona A): efektywne CP i W' aktualnie uzyte przez model W'bal (do FIT). */
    fun effectiveCpW(): Float = ltpWatts
    fun effectiveWPrimeKj(): Float = wPrimeKj

    // --- CP_eff (LIN) i IF_eff (DECISIONS.md 2026-07-18) ---
    fun setRealLtp(ltp: Float) { if (ltp > 0f) realLtpWatts = ltp }
    private fun cpEffLinNow(): Float {
        val cp = ftpWatts.toFloat()
        if (realLtpWatts <= 0f || cp <= 0f || wPrimeKj <= 0f) return cp
        val bal = (wBalKj / wPrimeKj).coerceIn(0f, 1f)
        return realLtpWatts + bal * (cp - realLtpWatts)
    }
    fun cpEffLinW(): Float = lastCpEffLinW
    fun ifEff5Live(): Float {
        val cpe = lastCpEffLinW
        if (cpe <= 0f || powerBuffer300s.isEmpty()) return 0f
        val p5 = powerBuffer300s.average().toFloat()
        return (p5 / cpe).coerceIn(0f, 2.5f)
    }
    fun ifEffWholeRide(): Float {
        if (countEff == 0L) return 0f
        return (sumOf4thPowersEff / countEff).pow(0.25).toFloat().coerceIn(0f, 2.5f)
    }

    private fun updateWBalance(powerWatts: Int, dt: Float) {
        if (ltpWatts <= 0f || wPrimeKj <= 0f) return
        // XSS: zmeczenie liczone ze stanu W'bal PRZED zuzyciem tej sekundy (spojnie z ModelQ).
        val fatigue = (1f - (wBalKj / wPrimeKj)).coerceIn(0f, 1f)
        val pw = powerWatts.toFloat()
        xssAccum += (minOf(pw, ltpWatts) / ltpWatts + (pw - ltpWatts).coerceAtLeast(0f) / ltpWatts * (1f + XSS_BETA * fatigue)) * (100f / 3600f) * dt
        if (powerWatts > ltpWatts) {
            wBalKj -= (powerWatts - ltpWatts) * dt / 1000f
        } else {
            val dcp = (ltpWatts - powerWatts).coerceAtLeast(0f)
            val tauRec = 546f * exp(-0.01f * dcp) + 316f
            wBalKj += (wPrimeKj - wBalKj) * (1f - exp(-dt / tauRec))
        }
        wBalKj = wBalKj.coerceIn(0f, wPrimeKj)
    }

    /** WATEK 2 (Strona A): XSS narastajaco od startu jazdy (do FIT + ewentualnego UI). */
    fun xssValue(): Float = xssAccum

    fun wBalancePercent(nowMs: Long = System.currentTimeMillis()): Int {
        val pct = if (wPrimeKj > 0f) ((wBalKj / wPrimeKj) * 100f).roundToInt().coerceIn(0, 100) else -1
        wBalHistory.addLast(nowMs to pct)
        while (wBalHistory.size > 60) wBalHistory.removeFirst()
        return pct
    }

    /** dtSec = rzeczywisty czas od poprzedniej probki (E1.5); 0 = luka w danych (bez zmian modeli czasowych). */
    fun update(powerWatts: Int, heartRate: Int, movingSec: Long, elapsedSec: Long, powerFresh: Boolean = true, dtSec: Double = 1.0) {
        if (elapsedSec <= 0L) return
        val dtf = dtSec.coerceIn(0.0, 5.0).toFloat()
        val movingAdvanced = movingSec > lastMovingSec
        val hasPower = powerWatts > 0
        val activeSample = movingAdvanced && hasPower && powerFresh

        // RSRV v2: kazda sekunda ruchu ze swieza moca (0 W na zjezdzie tez); postoj sie nie liczy.
        if (movingAdvanced) {
            val dt = (movingSec - lastMovingSec).coerceIn(1L, 5L).toDouble()
            if (powerFresh) {
                ReserveModelV2.tick(reserveV2, powerWatts, heartRate, ftpWatts * todayFactor.coerceIn(0.70f, 1.10f).toDouble(), lthrBpm.toDouble(), maxHrBpm, dt)
                reserveV2.noPowerMovingS = 0L
            } else {
                reserveV2.noPowerMovingS += dt.toLong()
            }
        }

        if (activeSample) {
            powerBuffer30s.addLast(powerWatts)
            if (powerBuffer30s.size > 30) powerBuffer30s.removeFirst()
            if (powerBuffer30s.size == 30) {
                val avg30s = powerBuffer30s.average()
                sumOf4thPowers += avg30s.pow(4)
                count4thPowers++
            }
            totalPowerSum += powerWatts
            totalPowerCount++
            totalEnergyKj += powerWatts * dtf / 1000.0
            powerBuffer300s.addLast(powerWatts)
            if (powerBuffer300s.size > 300) powerBuffer300s.removeFirst()
        }

        if (activeSample && heartRate > 0) {
            decoupleHr.add(heartRate)
            decouplePower.add(powerWatts)
            if (decoupleHr.size > 3600) {
                val remove = decoupleHr.size - 3600
                repeat(remove) { decoupleHr.removeAt(0); decouplePower.removeAt(0) }
            }
        }

        if (powerFresh && dtf > 0f) updateWBalance(powerWatts, dtf)
        lastCpEffLinW = cpEffLinNow()
        if (activeSample && powerBuffer30s.size == 30 && lastCpEffLinW > 0f) {
            val avg30sEff = powerBuffer30s.average()
            val eff = avg30sEff / lastCpEffLinW
            sumOf4thPowersEff += eff.pow(4)
            countEff++
        }
        lastMovingSec = kotlin.math.max(lastMovingSec, movingSec)
    }

    fun npWatts(): Int {
        if (count4thPowers == 0L) return 0
        return (sumOf4thPowers / count4thPowers).pow(0.25).roundToInt()
    }

    fun ifValue(): Float {
        val ftp = ftpWatts.toFloat()
        return if (ftp > 0f) (npWatts() / ftp).coerceAtMost(2.0f) else 0f
    }

    fun viValue(): Float {
        if (totalPowerCount == 0L) return 0f
        val avg = totalPowerSum.toFloat() / totalPowerCount
        return if (avg > 0f) (npWatts() / avg).coerceAtMost(2.0f) else 0f
    }

    fun hasDecouplingData(): Boolean = decoupleHr.size >= 120

    fun decouplingPercent(): Float {
        val n = decoupleHr.size
        if (n < 120) return 0f
        val half = n / 2
        // Dryf "przy tej samej mocy" (DECISIONS 2026-07-18): binuj moc co 15 W i porownaj
        // srednie HR w tych samych binach mocy (2. polowa vs 1.). Odporne na to, ze zmiana
        // tempa (spadek mocy) sama zawyza stosunek HR/moc na jazdach lekkich/szarpanych.
        val firstByBin = HashMap<Int, MutableList<Int>>()
        val secondByBin = HashMap<Int, MutableList<Int>>()
        for (i in 0 until half) {
            firstByBin.getOrPut(decouplePower[i] / 15) { mutableListOf() }.add(decoupleHr[i])
        }
        for (i in half until n) {
            secondByBin.getOrPut(decouplePower[i] / 15) { mutableListOf() }.add(decoupleHr[i])
        }
        var num = 0.0
        var base = 0.0
        var den = 0.0
        for ((bin, first) in firstByBin) {
            val second = secondByBin[bin] ?: continue
            if (first.size >= 20 && second.size >= 20) {
                val m1 = first.average()
                val m2 = second.average()
                val w = kotlin.math.min(first.size, second.size).toDouble()
                num += (m2 - m1) * w
                base += m1 * w
                den += w
            }
        }
        if (den < 200.0 || base <= 0.0) return 0f
        val drift = (num / base) * 100.0
        return drift.toFloat().coerceIn(0f, 50f)
    }

    fun carbsGPerH(intensityFactor: Float, movingSec: Long, vi: Float, tempCelsius: Float?, bodyWeightKg: Float): Int {
        val ifClamped = intensityFactor.coerceIn(0.4f, 1.1f)
        val base = 25f + ((ifClamped - 0.4f) / 0.7f) * 65f
        val movingHours = movingSec / 3600f
        val durationMultiplier = when {
            movingHours < 1.0f -> 1.0f
            movingHours < 2.0f -> 1.08f
            movingHours < 3.0f -> 1.15f
            else -> 1.22f
        }
        val viMultiplier = when {
            vi <= 1.05f -> 1.0f
            vi <= 1.12f -> 1.05f
            else -> 1.10f
        }
        val weightMultiplier = (bodyWeightKg / 75f).coerceIn(0.85f, 1.20f)
        val tempMultiplier = when {
            tempCelsius == null -> 1.0f
            tempCelsius < 5f -> 0.95f
            tempCelsius < 25f -> 1.0f
            tempCelsius < 32f -> 1.05f
            else -> 1.08f
        }
        val result = base * durationMultiplier * viMultiplier * weightMultiplier * tempMultiplier
        return roundToNearest5(result).coerceIn(20, 110)
    }

    fun fluidLPerH(intensityFactor: Float, tempCelsius: Float?): Float {
        val base = when {
            intensityFactor < 0.55f -> 0.40f
            intensityFactor < 0.75f -> 0.50f
            intensityFactor < 0.87f -> 0.60f
            else -> 0.70f
        }
        val tm = when {
            tempCelsius == null -> 1.0f
            tempCelsius < 5f -> 0.75f
            tempCelsius < 12f -> 0.85f
            tempCelsius < 18f -> 0.95f
            tempCelsius < 24f -> 1.10f
            tempCelsius < 30f -> 1.30f
            tempCelsius < 35f -> 1.50f
            else -> 1.70f
        }
        val hm = when {
            humidityPercent < 40f -> 0.90f
            humidityPercent < 60f -> 1.00f
            humidityPercent < 75f -> 1.10f
            humidityPercent < 85f -> 1.20f
            else -> 1.30f
        }
        return ((base * tm * hm * (bodyWeightKg / 70f) / 0.05f).roundToInt() * 0.05f).coerceIn(0.30f, 1.50f)
    }

    /** RSRV v2: ulamek zapasu zuzyty w tej jezdzie (1.0 = pusty bak). */
    fun reserveSessionLoad(): Double = reserveV2.load

    /** RSRV v2 w % (dzienna baza = wczesniejsze jazdy dzis). Start zawsze 100 %. */
    fun rideReservePercentV2(dailyBaseLoad: Double): Int = ReserveModelV2.percent(dailyBaseLoad, reserveV2.load)

    /** false = brak danych (zero sekund z moca albo >5 min jazdy bez mocy) -> pole RSRV "czekam". */
    fun reserveReady(): Boolean = reserveV2.nP > 0 && reserveV2.noPowerMovingS < ReserveModelV2.NO_POWER_LIMIT_S

    fun updateBattery(currentPct: Int?, charging: Boolean?, nowMs: Long) {
        if (charging != null) batteryIsCharging = charging
        if (currentPct == null) return
        if (batteryPctStart == null) {
            batteryPctStart = currentPct.coerceIn(0, 100)
            batteryStartMs = nowMs
        }
        batteryPctCurrent = currentPct.coerceIn(0, 100)
    }

    fun seedBatteryStartIfAbsent(currentPct: Int?, charging: Boolean?, nowMs: Long) {
        if (currentPct == null) return
        if (charging == true) return
        if (batteryPctStart != null) return
        batteryPctStart = currentPct.coerceIn(0, 100)
        batteryStartMs = nowMs
        if (batteryPctCurrent == null) batteryPctCurrent = currentPct.coerceIn(0, 100)
    }

    fun resetBattery() {
        batteryPctStart = null
        batteryPctCurrent = null
        batteryStartMs = null
        batteryIsCharging = null
    }

    fun batteryDrainPctPerHour(nowMs: Long): Float? {
        if (batteryIsCharging == true) return null
        val start = batteryPctStart ?: return null
        val current = batteryPctCurrent ?: return null
        val startMs = batteryStartMs ?: return null
        val elapsedSec = ((nowMs - startMs).coerceAtLeast(0L)) / 1000L
        if (elapsedSec < 300L) return null
        val drop = start - current
        if (drop < 0) return null
        val hours = elapsedSec / 3600f
        if (hours <= 0f) return null
        return (drop / hours).coerceAtLeast(0f)
    }

    fun reset() {
        powerBuffer30s.clear()
        sumOf4thPowers = 0.0
        count4thPowers = 0L
        totalPowerSum = 0L
        totalPowerCount = 0L
        totalEnergyKj = 0.0
        decoupleHr.clear()
        decouplePower.clear()
        wBalKj = wPrimeKj
        wBalInitialized = wPrimeKj > 0f
        xssAccum = 0f
        powerBuffer300s.clear()
        sumOf4thPowersEff = 0.0
        countEff = 0L
        lastCpEffLinW = 0f
        lastMovingSec = 0L
        reserveV2.emaP = 0.0
        reserveV2.emaH = 0.0
        reserveV2.nP = 0L
        reserveV2.nH = 0L
        reserveV2.load = 0.0
        reserveV2.noPowerMovingS = 0L
        wBalHistory.clear()
        batteryPctStart = null
        batteryPctCurrent = null
        batteryStartMs = null
        batteryIsCharging = null
    }

    fun snapshotForCrashRecovery(): StatsCalcSnapshot {
        return StatsCalcSnapshot(
            count4thPowers = count4thPowers,
            sumOf4thPowers = sumOf4thPowers,
            totalPowerSum = totalPowerSum,
            totalPowerCount = totalPowerCount,
            totalEnergyKj = totalEnergyKj,
            lastMovingSec = lastMovingSec,
            reserveLoad = reserveV2.load,
            reserveEmaP = reserveV2.emaP,
            reserveEmaH = reserveV2.emaH,
            reserveNP = reserveV2.nP,
            reserveNH = reserveV2.nH,
            wBalKj = wBalKj,
            xssAccum = xssAccum,
            batteryPctStart = batteryPctStart,
            batteryPctCurrent = batteryPctCurrent,
            batteryStartMs = batteryStartMs,
            batteryIsCharging = batteryIsCharging,
        )
    }

    fun restoreFromSnapshot(snap: StatsCalcSnapshot) {
        count4thPowers = snap.count4thPowers
        sumOf4thPowers = snap.sumOf4thPowers
        totalPowerSum = snap.totalPowerSum
        totalPowerCount = snap.totalPowerCount
        totalEnergyKj = snap.totalEnergyKj
        lastMovingSec = snap.lastMovingSec
        reserveV2.load = snap.reserveLoad
        reserveV2.emaP = snap.reserveEmaP
        reserveV2.emaH = snap.reserveEmaH
        reserveV2.nP = snap.reserveNP
        reserveV2.nH = snap.reserveNH
        wBalKj = snap.wBalKj
        wBalInitialized = true
        xssAccum = snap.xssAccum
        batteryPctStart = snap.batteryPctStart
        batteryPctCurrent = snap.batteryPctCurrent
        batteryStartMs = snap.batteryStartMs
        batteryIsCharging = snap.batteryIsCharging
    }

    data class StatsCalcSnapshot(
        val count4thPowers: Long,
        val sumOf4thPowers: Double,
        val totalPowerSum: Long,
        val totalPowerCount: Long,
        val totalEnergyKj: Double,
        val lastMovingSec: Long,
        val reserveLoad: Double = 0.0,
        val reserveEmaP: Double = 0.0,
        val reserveEmaH: Double = 0.0,
        val reserveNP: Long = 0L,
        val reserveNH: Long = 0L,
        val wBalKj: Float,
        val xssAccum: Float = 0f,
        val batteryPctStart: Int? = null,
        val batteryPctCurrent: Int? = null,
        val batteryStartMs: Long? = null,
        val batteryIsCharging: Boolean? = null,
    )

    private fun roundToNearest5(value: Float): Int = (kotlin.math.round(value / 5f) * 5).toInt()

    companion object {
        // Skalibrowane do Xert training_load (EWMA-CTL 59.6 vs 62.4) -- DECISIONS.md 2026-07-06.
        const val XSS_BETA = 1.0f

        @JvmStatic
        fun safetyFloat(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v.coerceAtLeast(0f)
    }
}
