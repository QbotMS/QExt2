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

    private var batteryPctStart: Int? = null
    private var batteryPctCurrent: Int? = null
    private var batteryStartMs: Long? = null
    private var batteryIsCharging: Boolean? = null

    private var lastMovingSec: Long = 0L

    private var lastReserve: Float = 100f
    private var startReserve: Float = 100f

    private var wPrimeKj: Float = 0f
    private var ltpWatts: Float = 0f
    private var wBalKj: Float = 0f
    private val tau: Float = 546f

    private val wBalHistory = ArrayDeque<Pair<Long, Int>>()

    fun captureStartReserve() {
        startReserve = safetyFloat(todayFactor) * 100f
        lastReserve = startReserve
    }

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
            if (wBalKj <= 0f || wBalKj > wPrime) wBalKj = wPrime
        }
        if (ltp > 0f) ltpWatts = ltp
    }

    private fun updateWBalance(powerWatts: Int) {
        if (ltpWatts <= 0f || wPrimeKj <= 0f) return
        if (powerWatts > ltpWatts) {
            wBalKj -= (powerWatts - ltpWatts) * 1f / 1000f
        } else {
            wBalKj += (wPrimeKj - wBalKj) * (1f - exp(-1f / tau))
        }
        wBalKj = wBalKj.coerceIn(0f, wPrimeKj)
    }

    fun wBalancePercent(nowMs: Long = System.currentTimeMillis()): Int {
        val pct = if (wPrimeKj > 0f) ((wBalKj / wPrimeKj) * 100f).roundToInt().coerceIn(0, 100) else -1
        wBalHistory.addLast(nowMs to pct)
        while (wBalHistory.size > 60) wBalHistory.removeFirst()
        return pct
    }

    fun update(powerWatts: Int, heartRate: Int, movingSec: Long, elapsedSec: Long) {
        if (elapsedSec <= 0L) return
        val movingAdvanced = movingSec > lastMovingSec
        val hasPower = powerWatts > 0
        val activeSample = movingAdvanced && hasPower

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
            totalEnergyKj += powerWatts / 1000.0
        }

        if (activeSample && heartRate > 0) {
            decoupleHr.add(heartRate)
            decouplePower.add(powerWatts)
        }

        updateWBalance(powerWatts)
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

    fun tssValue(movingSec: Long): Float {
        val ftp = ftpWatts.toFloat()
        if (ftp <= 0f || movingSec <= 0L) return 0f
        val np = safetyFloat(npWatts().toFloat())
        if (np <= 0f) return 0f
        val ifVal = (np / ftp).coerceIn(0f, 2f)
        val result = ((movingSec * np * ifVal) / (ftp * 3600f) * 100f)
        return result.coerceIn(0f, 9999f)
    }

    fun hasDecouplingData(): Boolean = decoupleHr.size >= 120

    fun decouplingPercent(): Float {
        val n = decoupleHr.size
        if (n < 120) return 0f
        val half = n / 2
        val firstHr = decoupleHr.subList(0, half).average()
        val firstPwr = decouplePower.subList(0, half).average()
        val secondHr = decoupleHr.subList(half, n).average()
        val secondPwr = decouplePower.subList(half, n).average()
        if (firstPwr <= 0.0 || secondPwr <= 0.0) return 0f
        val r1 = firstHr / firstPwr
        val r2 = secondHr / secondPwr
        if (r1 <= 0.0) return 0f
        val drift = ((r2 - r1) / r1) * 100f
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

    fun rideReservePercent(tss: Float, intensityFactor: Float, decoupling: Float, elapsedSec: Long): Int {
        val tssSafe = safetyFloat(tss)
        val ifSafe = safetyFloat(intensityFactor)
        val decoupleSafe = safetyFloat(decoupling)
        val baseReserve = safetyFloat(todayFactor) * 100f
        var reserve = baseReserve

        val tssPenalty = if (tssSafe > 0f) kotlin.math.sqrt(tssSafe) * 5.0f else 0f
        reserve -= tssPenalty

        val ifPenalty = if (ifSafe > 0.80f) (ifSafe - 0.80f) * 100f else 0f
        reserve -= ifPenalty

        val movingHours = lastMovingSec / 3600f
        val timePenalty = (movingHours - 1.5f).coerceAtLeast(0f) * 4f
        reserve -= timePenalty

        if (hasDecouplingData() && decoupleSafe > 5f) {
            reserve -= (decoupleSafe - 5f) * 1.5f
        }

        val stopSec = (elapsedSec - lastMovingSec).coerceAtLeast(0L)
        val recoveryPotential = (startReserve - lastReserve).coerceAtLeast(0f)
        val recoveryTau = 1800.0
        val recoveryAmount = recoveryPotential * (1.0 - kotlin.math.exp(-stopSec / recoveryTau)).toFloat()
        val raw = reserve.coerceIn(0f, startReserve)
        lastReserve = if (raw < lastReserve || recoveryAmount > 0f) {
            (raw + recoveryAmount).coerceIn(raw, startReserve)
        } else {
            (lastReserve + (raw - lastReserve) * 0.03f).coerceAtMost(startReserve)
        }
        return lastReserve.roundToInt().coerceIn(0, 100)
    }

    fun updateBattery(currentPct: Int?, charging: Boolean?, nowMs: Long) {
        if (charging != null) batteryIsCharging = charging
        if (currentPct == null) return
        if (batteryPctStart == null) {
            batteryPctStart = currentPct.coerceIn(0, 100)
            batteryStartMs = nowMs
        }
        batteryPctCurrent = currentPct.coerceIn(0, 100)
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
        lastMovingSec = 0L
        lastReserve = 100f
        startReserve = 100f
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
            lastReserve = lastReserve,
            startReserve = startReserve,
            wBalKj = wBalKj,
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
        lastReserve = snap.lastReserve
        startReserve = snap.startReserve
        wBalKj = snap.wBalKj
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
        val lastReserve: Float,
        val startReserve: Float,
        val wBalKj: Float,
        val batteryPctStart: Int? = null,
        val batteryPctCurrent: Int? = null,
        val batteryStartMs: Long? = null,
        val batteryIsCharging: Boolean? = null,
    )

    private fun roundToNearest5(value: Float): Int = (kotlin.math.round(value / 5f) * 5).toInt()

    companion object {
        @JvmStatic
        fun safetyFloat(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v.coerceAtLeast(0f)
    }
}
