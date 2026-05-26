package com.qext2.primary.field

import java.util.Locale

data class StatsFormattedValue(
    val main: String,
    val unit: String? = null,
)

object StatsValueFormatter {
    fun npW(watts: Int): StatsFormattedValue {
        if (watts <= 0) return StatsFormattedValue("--")
        return StatsFormattedValue("${watts.coerceAtMost(999)}", "W")
    }

    fun ifValue(value: Float): StatsFormattedValue {
        if (value <= 0f) return StatsFormattedValue("--")
        return StatsFormattedValue(String.format(Locale.US, "%.2f", value.coerceAtMost(1.99f)))
    }

    fun vi(value: Float): StatsFormattedValue {
        if (value <= 0f) return StatsFormattedValue("--")
        return StatsFormattedValue(String.format(Locale.US, "%.2f", value.coerceAtMost(1.99f)))
    }

    fun carbsG(gPerH: Int): StatsFormattedValue {
        if (gPerH <= 0) return StatsFormattedValue("--")
        return StatsFormattedValue("${gPerH.coerceIn(0, 999)}", "g")
    }

    fun fluidL(lPerH: Float): StatsFormattedValue {
        if (lPerH <= 0f) return StatsFormattedValue("--")
        return StatsFormattedValue(String.format(Locale.US, "%.1f", lPerH.coerceIn(0f, 9.9f)), "L")
    }

    fun calories(kcal: Int): StatsFormattedValue {
        if (kcal <= 0) return StatsFormattedValue("--")
        return StatsFormattedValue("${kcal.coerceAtMost(99999)}")
    }

    fun tss(value: Float): StatsFormattedValue {
        if (value <= 0f) return StatsFormattedValue("--")
        return StatsFormattedValue("${value.toInt().coerceIn(0, 9999)}")
    }

    fun reserveNumber(percent: Int): StatsFormattedValue {
        return StatsFormattedValue("${percent.coerceIn(0, 100)}", "%")
    }

    fun decouplingPct(percent: Float): StatsFormattedValue {
        if (percent <= 0f) return StatsFormattedValue("--")
        return StatsFormattedValue(String.format(Locale.US, "%.0f", percent.coerceIn(0f, 50f)), "%")
    }

    fun wBalance(percent: Int): StatsFormattedValue {
        if (percent < 0) return StatsFormattedValue("--")
        return StatsFormattedValue("${percent.coerceIn(0, 100)}", "%")
    }

    fun trend(trend: String): StatsFormattedValue {
        return when (trend) {
            "plummeting" -> StatsFormattedValue("DOWN")
            "falling" -> StatsFormattedValue("FALL")
            "rising" -> StatsFormattedValue("UP")
            "stable" -> StatsFormattedValue("OK")
            else -> StatsFormattedValue("--")
        }
    }

    fun hrd(status: String, pct: Float, valid: Boolean): StatsFormattedValue {
        if (!valid) return StatsFormattedValue("--")
        return when (status) {
            "OK", "+", "++", "HOT" -> StatsFormattedValue(status)
            else -> StatsFormattedValue(String.format(Locale.US, "%.0f", pct.coerceIn(0f, 99f)), "%")
        }
    }

    fun ascentM(meters: Int): StatsFormattedValue {
        if (meters < 0) return StatsFormattedValue("--")
        return StatsFormattedValue("${meters.coerceAtMost(99999)}", "m")
    }

    fun etaTime(etaMs: Long): StatsFormattedValue {
        if (etaMs <= 0L) return StatsFormattedValue("--")
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = etaMs
        return StatsFormattedValue(String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)))
    }

    fun batteryPerHour(drop: Float): StatsFormattedValue {
        if (drop <= 0f) return StatsFormattedValue("--")
        return StatsFormattedValue(String.format(Locale.US, "%.1f", drop.coerceIn(0f, 100f)), "%/h")
    }

    fun batteryRuntime(sec: Long): StatsFormattedValue {
        if (sec <= 0L) return StatsFormattedValue("--")
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return StatsFormattedValue("${h}:${m.toString().padStart(2, '0')}")
    }

    fun batterySimple(percent: Int?): StatsFormattedValue {
        if (percent == null || percent <= 0) return StatsFormattedValue("--")
        return StatsFormattedValue("${percent.coerceIn(0, 100)}", "%")
    }

    fun deadlineDelta(deltaKph: Float, status: String): StatsFormattedValue {
        return when {
            status == "OK" || deltaKph <= 0f -> StatsFormattedValue("OK")
            status == "IMPOSSIBLE" -> StatsFormattedValue("LATE")
            status == "LATE" -> StatsFormattedValue(String.format(Locale.US, "+%.1f", deltaKph.coerceAtMost(99.9f)))
            else -> StatsFormattedValue("--")
        }
    }
}
