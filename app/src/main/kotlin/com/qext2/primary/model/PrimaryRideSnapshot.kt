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
    val powerBgColor: Int = 0,  // Color.TRANSPARENT
    val hrColor: Int = Color.WHITE,
    val cadenceColor: Int = Color.WHITE,
    val speedColor: Int = Color.WHITE,
    val gradeColor: Int = Color.WHITE,
    val gradeBgColor: Int = 0xFF111827.toInt(),
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
        // LTHR estimate z fitmodel_segment (32 odcinki, 170-240W, >=4min, maj-lip 2026); Coggan %LTHR strefy
        private const val LTHR_BPM = 132

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
            val cc = cadenceColor(cadence, grade, adjFtp.toFloat(), power,
                SurfaceType.PAVED, todayFactor, 0f)
            val sc = speedColor(speedKmh, distanceMeters, elapsedSec)
            val gc = gradeColor(grade)
            val rawGear = gearColor(power, cadence, gearFront, gearRear, grade, adjFtp,
                SurfaceType.PAVED, todayFactor, 0f)
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
                watts <= targetHigh -> Color.parseColor("#4ADE80")
                watts <= (targetHigh * 1.20).toInt() -> Color.parseColor("#FB923C")
                else -> Color.parseColor("#FF5252")
            }
        }

        private fun cadenceColor(
            rpm: Int,
            grade: Double,
            effectiveFtp: Float,
            power: Int,
            surface: com.qext2.primary.model.SurfaceType,
            todayFactor: Float,
            decouplingPct: Float,
        ): Int {
            if (rpm <= 0) return Color.WHITE
            if (grade < -2.0) return Color.WHITE  // zjazd — nie oceniamy
            val range = com.qext2.primary.active.OptimalCadenceModel.compute(
                powerW = power.coerceAtLeast(0),
                effectiveFtp = effectiveFtp,
                gradePercent = grade,
                surface = surface,
                todayFactor = todayFactor,
                decouplingPct = decouplingPct,
            )
            return when (com.qext2.primary.active.OptimalCadenceModel.assess(rpm, range)) {
                com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.CRITICAL_LOW -> Color.parseColor("#FF5252")
                com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.LOW         -> Color.parseColor("#FB923C")
                com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.OPTIMAL     -> Color.parseColor("#4ADE80")
                com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.HIGH        -> Color.WHITE  // nie sygnalizujemy
                com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.NO_DATA     -> Color.WHITE
            }
        }

        private fun speedColor(speedKmh: Double, distanceMeters: Double, elapsedSec: Long): Int {
            if (speedKmh < 1.0) return Color.WHITE
            val netAvg = if (elapsedSec > 0L && distanceMeters > 0.0) {
                (distanceMeters / 1000.0) / (elapsedSec / 3600.0)
            } else 0.0
            if (netAvg < 1.0) return Color.WHITE
            return when {
                speedKmh > netAvg * 1.15 -> Color.parseColor("#4ADE80")
                speedKmh < netAvg * 0.85 -> Color.parseColor("#FF5252")
                else -> Color.WHITE
            }
        }

        fun gradeBackground(grade: Double): Int = when {
            grade < -8.0 -> Color.parseColor("#2D58AF")
            grade < -5.0 -> Color.parseColor("#4FC3F7")
            grade < -2.0 -> Color.parseColor("#FFFFFF")
            grade < 1.0 -> Color.parseColor("#111827")
            grade < 2.0 -> Color.parseColor("#58C597")
            grade < 5.0 -> Color.parseColor("#079D78")
            grade < 8.0 -> Color.parseColor("#E7E021")
            grade < 11.0 -> Color.parseColor("#E59174")
            grade < 14.0 -> Color.parseColor("#E7693A")
            grade < 20.0 -> Color.parseColor("#C82425")
            else -> Color.parseColor("#B222A3")
        }

        fun contrastText(bg: Int): Int {
            val r = (bg shr 16) and 0xFF
            val g = (bg shr 8) and 0xFF
            val b = bg and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            return if (lum >= 150.0) Color.parseColor("#0B0F1A") else Color.WHITE
        }

        private fun gradeColor(grade: Double): Int = when {
            grade <= -3.0 -> Color.parseColor("#38BDF8")
            grade < 3.0 -> Color.parseColor("#4ADE80")
            grade < 6.0 -> Color.parseColor("#FACC15")
            grade < 9.0 -> Color.parseColor("#FB923C")
            else -> Color.parseColor("#FF5252")
        }

        private fun gearColor(
            power: Int, cadence: Int, gearFront: Int, gearRear: Int,
            grade: Double, adjFtp: Int,
            surface: com.qext2.primary.model.SurfaceType,
            todayFactor: Float,
            decouplingPct: Float,
        ): Int {
            if (cadence <= 0 || power <= 0 || gearFront <= 0 || gearRear <= 0) return Color.WHITE
            if (adjFtp <= 0) return Color.WHITE
            val effectiveFtp = adjFtp.toFloat()
            val range = com.qext2.primary.active.OptimalCadenceModel.compute(
                powerW = power,
                effectiveFtp = effectiveFtp,
                gradePercent = grade,
                surface = surface,
                todayFactor = todayFactor,
                decouplingPct = decouplingPct,
            )
            // Kolorowanie czcionki pola GEAR na podstawie odchylenia kadencji od optimum:
            // Czerwony = za ciężki bieg (kadencja poniżej zakresu — zrzuć)
            // Pomarańczowy = lekko za ciężki (zrzuć 1 zębatkę)
            // Biały = optimum
            // Zielony = za lekki (wrzuć 1 zębatkę)
            // Jasno-zielony = znacząco za lekki (wrzuć ≥2 zębatki)
            return when {
                cadence < range.low - 10 -> Color.parseColor("#FF5252")   // za ciężko ≥2 zębatki
                cadence < range.low - 5  -> Color.parseColor("#FB923C")   // za ciężko 1 zębatka
                cadence > range.high + 10 -> Color.parseColor("#86EFAC")  // za lekko ≥2 zębatki
                cadence > range.high + 5  -> Color.parseColor("#4ADE80")  // za lekko 1 zębatka
                else -> Color.WHITE                                         // optimum
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
            val pct = bpm.toFloat() / LTHR_BPM
            return when {
                pct < 0.81f -> "Z1"
                pct < 0.90f -> "Z2"
                pct < 0.95f -> "Z3"
                pct < 1.06f -> "Z4"
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
            val intGrade = Math.round(gradePercent).toInt()
            return if (intGrade >= 0) "+$intGrade" else "$intGrade"
        }
}
