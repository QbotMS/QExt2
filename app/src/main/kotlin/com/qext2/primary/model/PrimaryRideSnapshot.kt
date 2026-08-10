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
        // Stale *_STALE_MS: fallback getterow *Display gdy FieldOutput value jest pusty.
        // Kolory pol licza zywe silniki: FieldComputers / HrStrainAdvisor / pacingPowerColor.
        private const val HR_STALE_MS = 12000L
        private const val CADENCE_STALE_MS = 8000L
        private const val POWER_STALE_MS = 8000L
        private const val SPEED_STALE_MS = 12000L
        private const val GEAR_STALE_MS = 15000L
        private const val GRADE_STALE_MS = 45000L



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


    }

    val hrDisplay: String
        get() {
            val raw = if (hrValue.isNotEmpty()) hrValue else if (hrFreshnessMs < HR_STALE_MS) hr.toString() else "NO"
            if (raw == "NO" || raw == "WAIT" || raw == "INV") return raw
            val bpm = raw.toIntOrNull() ?: return raw
            val inZoneMode = com.qext2.primary.data.AthleteDataStore.loadHrZoneMode()
            if (!inZoneMode) return bpm.toString()
            val pct = bpm.toFloat() / com.qext2.primary.data.AthleteDataStore.loadLthrBpm()
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
