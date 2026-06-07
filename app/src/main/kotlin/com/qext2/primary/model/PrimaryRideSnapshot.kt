package com.qext2.primary.model

import android.graphics.Color

data class PrimaryRideSnapshot(
    val hr: Int = 0,
    val cadence: Int = 0,
    val power3s: Int = 0,
    val speedKmh: Double = 0.0,
    val gearFront: Int = 0,
    val gearRear: Int = 0,
    val gradePercent: Double = 0.0,
    val hrFreshnessMs: Long = 30000L,
    val cadenceFreshnessMs: Long = 30000L,
    val powerFreshnessMs: Long = 30000L,
    val speedFreshnessMs: Long = 30000L,
    val gearFreshnessMs: Long = 30000L,
    val gradeFreshnessMs: Long = 30000L,
    val powerColor: Int = Color.WHITE,
    val hrColor: Int = Color.WHITE,
    val cadenceColor: Int = Color.WHITE,
    val speedColor: Int = Color.WHITE,
    val gradeColor: Int = Color.WHITE,
    val gearColor: Int = Color.WHITE,
    val speedValue: String = "",
    val powerValue: String = "",
    val hrValue: String = "",
    val cadenceValue: String = "",
    val gradeValue: String = "",
    val gearValue: String = "",
    val maxHr: Int = 180,
) {
    companion object {
        // legacy_live_snapshot_mapping
        // TODO remove after full migration to FieldOutput-derived values end-to-end.
        private const val HR_STALE_MS = 12000L
        private const val CADENCE_STALE_MS = 8000L
        private const val POWER_STALE_MS = 8000L
        private const val SPEED_STALE_MS = 12000L
        private const val GEAR_STALE_MS = 15000L
        private const val GRADE_STALE_MS = 45000L
        private const val SPEED_ZERO_THRESHOLD = 0.01
        private const val GEAR_HYSTERESIS_MS = 30_000L

        private var lastGearColor = Color.WHITE
        private var gearColorSinceMs = 0L
        private var gearInitialized = false

        private var lastPowerColor = Color.WHITE
        private var powerColorSinceMs = 0L
        private var powerInitialized = false

        @JvmStatic
        fun computeColors(
            power: Int, hr: Int, cadence: Int, speedKmh: Double,
            gearFront: Int, gearRear: Int, grade: Double,
            distanceMeters: Double, elapsedSec: Long, ascentLeftM: Int,
            ftp: Int, todayFactor: Float, maxHr: Int, nowMs: Long = 0L,
        ): IntArray {
            val adjFtp = (ftp * todayFactor.coerceIn(0.5f, 1.1f)).toInt().coerceAtLeast(50)
            val rawPower = powerColor(power, grade, ascentLeftM, adjFtp)
            val pc = powerColorHysteresis(rawPower, nowMs)
            val hc = Color.WHITE
            val cc = cadenceColor(cadence, grade)
            val sc = speedColor(speedKmh, distanceMeters, elapsedSec)
            val gc = gradeColor(grade)
            val rawGear = gearColor(power, cadence, gearFront, gearRear, grade, adjFtp)
            val gearC = gearColorHysteresis(rawGear, nowMs)
            return intArrayOf(pc, hc, cc, sc, gc, gearC)
        }

        private fun powerColor(watts: Int, grade: Double, ascentLeftM: Int, adjFtp: Int): Int {
            if (watts <= 0 || adjFtp <= 0) return Color.WHITE
            val targetLow: Int
            val targetHigh: Int
            if (grade > 3.0) {
                if (ascentLeftM > 0 && ascentLeftM <= 500) {
                    targetLow = (adjFtp * 0.80).toInt()
                    targetHigh = (adjFtp * 1.05).toInt()
                } else {
                    targetLow = (adjFtp * 0.55).toInt()
                    targetHigh = (adjFtp * 0.75).toInt()
                }
            } else {
                targetLow = (adjFtp * 0.75).toInt()
                targetHigh = (adjFtp * 0.87).toInt()
            }
            return when {
                watts < targetLow -> Color.WHITE
                watts <= targetHigh -> Color.parseColor("#22C55E")
                watts <= (targetHigh * 1.20).toInt() -> Color.parseColor("#F97316")
                else -> Color.parseColor("#EF4444")
            }
        }

        private fun cadenceColor(rpm: Int, grade: Double): Int {
            if (rpm <= 0) return Color.WHITE
            if (grade < -2.0) return Color.WHITE
            val (lo, hi) = when {
                grade > 4.0 -> 55 to 65
                else -> 60 to 70
            }
            return when {
                rpm in lo..hi -> Color.parseColor("#22C55E")
                rpm < lo - 5 -> Color.parseColor("#EF4444")
                rpm < lo -> Color.parseColor("#F97316")
                else -> Color.WHITE
            }
        }

        private fun speedColor(speedKmh: Double, distanceMeters: Double, elapsedSec: Long): Int {
            if (speedKmh < 1.0) return Color.WHITE
            val netAvg = if (elapsedSec > 0L && distanceMeters > 0.0) {
                (distanceMeters / 1000.0) / (elapsedSec / 3600.0)
            } else 0.0
            if (netAvg < 1.0) return Color.WHITE
            return when {
                speedKmh > netAvg * 1.15 -> Color.parseColor("#22C55E")
                speedKmh < netAvg * 0.85 -> Color.parseColor("#EF4444")
                else -> Color.WHITE
            }
        }

        private fun gradeColor(grade: Double): Int = when {
            grade in -2.0..2.0 -> Color.parseColor("#22C55E")
            grade in -5.0..5.0 -> Color.WHITE
            grade in -9.0..9.0 -> Color.parseColor("#F97316")
            else -> Color.parseColor("#EF4444")
        }

        private fun gearColor(
            power: Int, cadence: Int, gearFront: Int, gearRear: Int,
            grade: Double, adjFtp: Int,
        ): Int {
            if (cadence <= 0 || power <= 0 || gearFront <= 0 || gearRear <= 0) return Color.WHITE
            if (adjFtp <= 0) return Color.WHITE
            return when {
                cadence <= 50 && power >= adjFtp * 1.10 && grade >= 5.0 -> Color.parseColor("#EF4444")
                cadence < 55 && power >= adjFtp * 0.75 && grade >= 2.0 -> Color.parseColor("#F97316")
                cadence >= 90 && power <= adjFtp * 0.50 -> Color.parseColor("#F97316")
                cadence in 60..75 && power in (adjFtp * 0.75).toInt()..(adjFtp * 0.87).toInt() && grade in -5.0..5.0 -> Color.parseColor("#22C55E")
                else -> Color.WHITE
            }
        }

        private fun gearColorHysteresis(rawColor: Int, nowMs: Long): Int {
            if (nowMs <= 0L) return rawColor
            if (!gearInitialized) {
                lastGearColor = rawColor
                gearColorSinceMs = nowMs
                gearInitialized = true
                return rawColor
            }
            if (rawColor == lastGearColor) {
                gearColorSinceMs = nowMs
                return rawColor
            }
            if (nowMs - gearColorSinceMs < GEAR_HYSTERESIS_MS) return lastGearColor
            lastGearColor = rawColor
            gearColorSinceMs = nowMs
            return rawColor
        }

        private const val POWER_HYSTERESIS_MS = 8_000L

        fun resetLegacyState() {
            lastGearColor = Color.WHITE
            gearColorSinceMs = 0L
            gearInitialized = false
            lastPowerColor = Color.WHITE
            powerColorSinceMs = 0L
            powerInitialized = false
        }

        private fun powerColorHysteresis(rawColor: Int, nowMs: Long): Int {
            if (nowMs <= 0L) return rawColor
            if (!powerInitialized) {
                lastPowerColor = rawColor
                powerColorSinceMs = nowMs
                powerInitialized = true
                return rawColor
            }
            if (rawColor == lastPowerColor) {
                powerColorSinceMs = nowMs
                return rawColor
            }
            val isDowngrade = rawColor != Color.WHITE && lastPowerColor == Color.WHITE
            if (!isDowngrade && nowMs - powerColorSinceMs < POWER_HYSTERESIS_MS) return lastPowerColor
            lastPowerColor = rawColor
            powerColorSinceMs = nowMs
            return rawColor
        }
    }

    val hrDisplay: String
        get() {
            val raw = if (hrValue.isNotEmpty()) hrValue else if (hrFreshnessMs < HR_STALE_MS) hr.toString() else "NO"
            if (raw == "NO" || raw == "WAIT" || raw == "INV") return raw
            val bpm = raw.toIntOrNull() ?: return raw
            val inZoneMode = com.qext2.primary.data.AthleteDataStore.loadHrZoneMode()
            if (!inZoneMode) return bpm.toString()
            val pct = bpm.toFloat() / maxHr
            return when {
                pct < 0.60f -> "Z1"
                pct < 0.75f -> "Z2"
                pct < 0.85f -> "Z3"
                pct < 0.95f -> "Z4"
                else -> "Z5"
            }
        }

    val cadenceDisplay: String
        get() = if (cadenceValue.isNotEmpty()) cadenceValue else if (cadenceFreshnessMs < CADENCE_STALE_MS) cadence.toString() else "NO"

    val powerDisplay: String
        get() {
            if (powerValue.isNotEmpty()) return powerValue
            if (powerFreshnessMs >= POWER_STALE_MS) return "NO"
            return power3s.toString()
        }

    val speedDisplay: String
        get() {
            if (speedValue.isNotEmpty()) return speedValue
            if (speedFreshnessMs >= SPEED_STALE_MS) return "0.0"
            return String.format("%.1f", speedKmh)
        }

    val gearDisplay: String
        get() {
            if (gearValue.isNotEmpty()) return gearValue
            if (gearFreshnessMs >= GEAR_STALE_MS) return "NO"
            if (gearFront > 0 && gearRear > 0) return "${gearFront}\u00D7${gearRear}"
            return "NO"
        }

    val gradeDisplay: String
        get() {
            if (gradeValue == "WAIT") return "WAIT"
            if (gradeFreshnessMs >= GRADE_STALE_MS) return "NO"
            if (gradeValue.isNotEmpty()) {
                return if (gradeValue.startsWith("-") || gradeValue.startsWith("+")) gradeValue
                else "+$gradeValue"
            }
            val intGrade = gradePercent.toInt()
            return if (intGrade >= 0) "+$intGrade" else "$intGrade"
        }
}
