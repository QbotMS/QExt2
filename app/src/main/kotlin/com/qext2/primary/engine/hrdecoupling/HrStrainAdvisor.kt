package com.qext2.primary.engine.hrdecoupling

enum class StatusColor(val hex: Int) {
    GOOD(0xFF4ADE80.toInt()),
    NEUTRAL(0xFFFFFFFF.toInt()),
    WARN(0xFFFB923C.toInt()),
    BAD(0xFFFF5252.toInt()),
}

data class HrStrainResult(
    val bpm: Int?,
    val color: StatusColor,
    val decouplingPct: Float,
    val reasonCode: String,
)

class HrStrainAdvisor(private val buffer: HrDecouplingBuffer) {

    private var lastColor = StatusColor.NEUTRAL
    private var lastColorSinceMs = 0L

    companion object {
        private const val VALID_POWER_MIN = 80
        private const val VALID_CADENCE_MIN = 35
        private const val VALID_SPEED_KMH_MIN = 6.0
        private const val WINDOW_POWER_AVG_MIN = 100.0

        private const val BASELINE_START_SEC = (8.0 * 60).toLong()
        private const val BASELINE_END_SEC = (18.0 * 60).toLong()
        private const val CURRENT_WINDOW_SEC = (8.0 * 60).toLong()

        private const val FULL_ACTIVATION_MIN = 30.0

        private const val DECOUPLING_GOOD = 3f
        private const val DECOUPLING_NEUTRAL = 6f
        private const val DECOUPLING_WARN = 10f

        private const val HYSTERESIS_MS = 30_000L
        private const val COLOR_UPDATE_MIN_MS = 10_000L
    }

    fun assess(nowMs: Long, maxHr: Int): HrStrainResult {
        val allSamples = buffer.snapshotAll()
        if (allSamples.isEmpty()) {
            return HrStrainResult(null, colorWithHysteresis(StatusColor.NEUTRAL, nowMs), 0f, "HR_NO_DATA")
        }

        val latest = allSamples.last()
        val bpm = if (latest.hr > 0) latest.hr else null

        if (maxHr <= 0) {
            return HrStrainResult(bpm, colorWithHysteresis(StatusColor.NEUTRAL, nowMs), 0f, "HR_NO_MAX_HR")
        }

        val movingSec = latest.elapsedSec
        val movingMin = movingSec / 60.0

        val baselineSamples = windowByMovingSec(allSamples, BASELINE_START_SEC, BASELINE_END_SEC)
        val currentSamples = windowByMovingSec(allSamples, (movingSec - CURRENT_WINDOW_SEC).coerceAtLeast(0L), movingSec)

        val baselineValid = isWindowValid(baselineSamples)
        val currentValid = isWindowValid(currentSamples)

        val decouplingPct = if (baselineValid && currentValid) {
            val baselineCost = avgHrCost(baselineSamples)
            val currentCost = avgHrCost(currentSamples)
            if (baselineCost > 0f) {
                ((currentCost / baselineCost) - 1f) * 100f
            } else 0f
        } else 0f

        val activationWeight = if (movingMin < 15.0) {
            0f
        } else if (movingMin < FULL_ACTIVATION_MIN) {
            ((movingMin - 15.0f) / 15.0f).toFloat().coerceIn(0f, 1f)
        } else {
            1.0f
        }

        val hrPct = bpm?.toFloat()?.div(maxHr) ?: 0f
        val reasonCode = buildReasonCode(bpm, maxHr, decouplingPct, baselineValid, currentValid, movingMin, hrPct, activationWeight)

        val rawColor = computeRawColor(hrPct, decouplingPct, activationWeight, baselineValid, currentValid)

        return HrStrainResult(bpm, colorWithHysteresis(rawColor, nowMs), decouplingPct, reasonCode)
    }

    private fun computeRawColor(
        hrPct: Float,
        decouplingPct: Float,
        activationWeight: Float,
        baselineValid: Boolean,
        currentValid: Boolean,
    ): StatusColor {
        val decouplingActive = baselineValid && currentValid && activationWeight > 0f
        val hrZone = hrZoneSeverity(hrPct)

        if (decouplingActive) {
            val decouplingState = decouplingSeverity(decouplingPct, activationWeight, baselineValid, currentValid)
            if (decouplingState != null) {
                return maxOf(hrZone, decouplingState)
            }
        }

        return when (hrZone) {
            StatusColor.BAD -> StatusColor.BAD
            StatusColor.WARN -> StatusColor.WARN
            else -> StatusColor.NEUTRAL
        }
    }

    private fun hrZoneSeverity(hrPct: Float): StatusColor = when {
        hrPct >= 0.95f -> StatusColor.BAD
        hrPct >= 0.85f -> StatusColor.WARN
        hrPct in 0.60f..0.75f -> StatusColor.GOOD
        else -> StatusColor.NEUTRAL
    }

    private fun decouplingSeverity(
        decouplingPct: Float,
        activationWeight: Float,
        baselineValid: Boolean,
        currentValid: Boolean,
    ): StatusColor? {
        if (!baselineValid || !currentValid || activationWeight <= 0f) return null
        val weightDecoupling = decouplingPct * activationWeight
        return when {
            weightDecoupling > DECOUPLING_WARN -> StatusColor.BAD
            weightDecoupling > DECOUPLING_NEUTRAL -> StatusColor.WARN
            weightDecoupling > DECOUPLING_GOOD -> StatusColor.NEUTRAL
            else -> StatusColor.GOOD
        }
    }

    private fun isWindowValid(samples: List<HrSample>): Boolean {
        if (samples.size < 15) return false
        val avgPower = samples.filter { isValidSample(it) }.map { it.power }.average()
        return avgPower >= WINDOW_POWER_AVG_MIN
    }

    private fun avgHrCost(samples: List<HrSample>): Float {
        val valid = samples.filter { isValidSample(it) }
        if (valid.isEmpty()) return 0f
        return valid.map { it.hr.toFloat() / it.power }.average().toFloat()
    }

    private fun isValidSample(s: HrSample): Boolean =
        s.power >= VALID_POWER_MIN && s.cadence >= VALID_CADENCE_MIN &&
            s.speedKmh >= VALID_SPEED_KMH_MIN && s.hr > 0 && s.power > 0

    private fun windowByMovingSec(all: List<HrSample>, fromSec: Long, toSec: Long): List<HrSample> {
        return all.filter { it.elapsedSec in fromSec..toSec }
    }

    private fun buildReasonCode(
        bpm: Int?, maxHr: Int, decouplingPct: Float,
        baselineValid: Boolean, currentValid: Boolean,
        movingMin: Double, hrPct: Float, activationWeight: Float,
    ): String {
        if (bpm == null || bpm <= 0) return "HR_SENSOR_MISSING"
        if (maxHr <= 0) return "HR_NO_MAX_HR"
        if (movingMin < 15.0) return "HR_NO_BASELINE"
        if (!baselineValid) return "HR_BASELINE_INVALID"
        if (!currentValid) return "HR_CURRENT_INVALID"

        if (activationWeight < 1f) return "HR_PARTIAL_ACTIVATION"

        return when {
            decouplingPct > 10f && hrPct >= 0.85f -> "HR_HIGH_ZONE_AND_DRIFT"
            decouplingPct > 10f -> "HR_DRIFT_BAD"
            decouplingPct > 6f -> "HR_DRIFT_WARN"
            decouplingPct > 3f -> "HR_DRIFT_NEUTRAL"
            hrPct in 0.60f..0.75f -> "HR_ECONOMIC_Z2"
            hrPct >= 0.95f -> "HR_HIGH_ZONE"
            else -> "HR_STABLE"
        }
    }

    private fun colorWithHysteresis(rawColor: StatusColor, nowMs: Long): StatusColor {
        if (rawColor == lastColor) return rawColor

        val isDowngrade = rawColor.ordinal < lastColor.ordinal

        if (isDowngrade) {
            if (nowMs - lastColorSinceMs < HYSTERESIS_MS) return lastColor
        }

        if (nowMs - lastColorSinceMs < COLOR_UPDATE_MIN_MS && rawColor.ordinal != lastColor.ordinal) {
            return lastColor
        }

        lastColor = rawColor
        lastColorSinceMs = nowMs
        return rawColor
    }

    fun reset() {
        lastColor = StatusColor.NEUTRAL
        lastColorSinceMs = 0L
    }
}
