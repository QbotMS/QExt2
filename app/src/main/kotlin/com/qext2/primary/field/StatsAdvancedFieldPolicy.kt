package com.qext2.primary.field

import pl.qbot.karoo.core.FieldStatus
import java.util.Locale

data class AdvancedFieldDecision(
    val value: String,
    val status: FieldStatus,
    val reason: String,
    val source: String? = null,
)

object StatsAdvancedFieldPolicy {
    fun waitNoModel(reason: String): AdvancedFieldDecision =
        AdvancedFieldDecision(value = "WAIT", status = FieldStatus.NO_MODEL, reason = reason)

    fun waitNoData(reason: String): AdvancedFieldDecision =
        AdvancedFieldDecision(value = "WAIT", status = FieldStatus.NO_DATA, reason = reason)

    fun sanitizeAvg(avgKmh: Double?): AdvancedFieldDecision {
        if (avgKmh == null) return waitNoData("missing_distance_or_time")
        if (avgKmh < 0.0 || avgKmh > 80.0) return waitNoModel("avg_unrealistic")
        return AdvancedFieldDecision(value = String.format("%.1f", avgKmh), status = FieldStatus.OK, reason = "avg_valid")
    }

    fun batteryDrain(
        batterySourceReady: Boolean,
        batteryDrainReady: Boolean,
        dropPctPerHour: Float?,
        batterySource: String?,
    ): AdvancedFieldDecision {
        if (!batterySourceReady) return waitNoData("battery_source_not_connected")
        if (!batteryDrainReady) return AdvancedFieldDecision(value = "—", status = FieldStatus.OK, reason = "battery_tracking_not_enough_data", source = batterySource ?: "headunit_polling")
        val value = dropPctPerHour ?: return AdvancedFieldDecision(value = "—", status = FieldStatus.OK, reason = "battery_tracking_not_enough_data", source = batterySource ?: "headunit_polling")
        if (!value.isFinite() || value < 0f) return AdvancedFieldDecision(value = "—", status = FieldStatus.OK, reason = "battery_tracking_not_enough_data", source = batterySource ?: "headunit_polling")
        return AdvancedFieldDecision(
            value = String.format(Locale.US, "%.1f%%", value.coerceIn(0f, 100f)),
            status = FieldStatus.OK,
            reason = "battery_drain_from_headunit",
            source = batterySource ?: "headunit_polling"
        )
    }

    fun batteryLeft(
        batterySourceReady: Boolean,
        batteryEstimateReady: Boolean,
        leftSec: Long?,
        batterySource: String?,
    ): AdvancedFieldDecision {
        if (!batterySourceReady) return waitNoData("battery_source_not_connected")
        if (!batteryEstimateReady) return AdvancedFieldDecision(value = "—", status = FieldStatus.OK, reason = "battery_tracking_not_enough_data", source = batterySource ?: "headunit_polling")
        val sec = leftSec ?: return AdvancedFieldDecision(value = "—", status = FieldStatus.OK, reason = "battery_tracking_not_enough_data", source = batterySource ?: "headunit_polling")
        if (sec <= 0L) return AdvancedFieldDecision(value = "—", status = FieldStatus.OK, reason = "battery_tracking_not_enough_data", source = batterySource ?: "headunit_polling")
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return AdvancedFieldDecision(
            value = "${h}:${m.toString().padStart(2, '0')}",
            status = FieldStatus.OK,
            reason = "battery_runtime_from_headunit",
            source = batterySource ?: "headunit_polling"
        )
    }

    fun ascentDone(hasRoute: Boolean, routeClimbSourceReady: Boolean, ascentDoneM: Int, ascentLeftM: Int): AdvancedFieldDecision {
        if (!hasRoute) return waitNoData("route_not_loaded")
        if (!routeClimbSourceReady) return waitNoModel("route_climb_model_not_ready")
        if (ascentDoneM < 0) return waitNoData("ascent_done_invalid")
        if (ascentDoneM == 0 && ascentLeftM == 0) {
            return AdvancedFieldDecision(value = "0", status = FieldStatus.OK, reason = "flat_route_or_zero_ascent", source = "route_snapshot")
        }
        return AdvancedFieldDecision(value = ascentDoneM.toString(), status = FieldStatus.OK, reason = "route_loaded_with_climb_data", source = "route_snapshot")
    }

    fun ascentLeft(hasRoute: Boolean, routeClimbSourceReady: Boolean, ascentDoneM: Int, ascentLeftM: Int): AdvancedFieldDecision {
        if (!hasRoute) return waitNoData("route_not_loaded")
        if (!routeClimbSourceReady) return waitNoModel("route_climb_model_not_ready")
        if (ascentLeftM < 0) return waitNoData("ascent_left_invalid")
        if (ascentDoneM == 0 && ascentLeftM == 0) {
            return AdvancedFieldDecision(value = "0", status = FieldStatus.OK, reason = "flat_route_or_zero_ascent", source = "route_snapshot")
        }
        return AdvancedFieldDecision(value = ascentLeftM.toString(), status = FieldStatus.OK, reason = "route_loaded_with_climb_data", source = "route_snapshot")
    }

    fun sdkTss(tssValue: Float): AdvancedFieldDecision {
        if (!tssValue.isFinite() || tssValue < 0f) return waitNoData("sdk_field_invalid")
        if (tssValue <= 0f) return waitNoData("sdk_field_not_available")
        return AdvancedFieldDecision(
            value = tssValue.toInt().coerceIn(0, 9999).toString(),
            status = FieldStatus.OK,
            reason = "sdk_training_stress_score",
            source = "sdk_training_stress_score",
        )
    }

    fun sdkCalories(kcal: Int): AdvancedFieldDecision {
        if (kcal < 0) return waitNoData("sdk_field_invalid")
        if (kcal == 0) return waitNoData("sdk_field_not_available")
        return AdvancedFieldDecision(
            value = kcal.coerceAtMost(99999).toString(),
            status = FieldStatus.OK,
            reason = "sdk_calories",
            source = "sdk_calories",
        )
    }

    fun localEta(
        hasRoute: Boolean,
        etaModelReady: Boolean,
        etaTimestamp: Long,
    ): AdvancedFieldDecision {
        if (!hasRoute) return waitNoData("eta_no_route")
        if (!etaModelReady) return waitNoModel("eta_model_not_ready")
        if (etaTimestamp <= 0L) return waitNoModel("eta_model_not_ready")
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = etaTimestamp
        val text = String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        return AdvancedFieldDecision(
            value = text,
            status = FieldStatus.OK,
            reason = "local_eta_prediction",
            source = "local_model",
        )
    }

    fun localWPrime(
        wPrimeModelReady: Boolean,
        wBalancePercent: Int,
    ): AdvancedFieldDecision {
        if (!wPrimeModelReady) return waitNoModel("wprime_no_cp_or_wprime")
        if (wBalancePercent < 0 || wBalancePercent > 100) return waitNoModel("wprime_invalid")
        return AdvancedFieldDecision(
            value = "${wBalancePercent}%",
            status = FieldStatus.OK,
            reason = "local_wprime_balance",
            source = "local_model",
        )
    }

    fun localRsrv(
        rsrvModelReady: Boolean,
        rideReservePercent: Int,
    ): AdvancedFieldDecision {
        if (!rsrvModelReady) return waitNoModel("rsrv_model_not_ready")
        if (rideReservePercent < 0 || rideReservePercent > 100) return waitNoModel("rsrv_invalid")
        return AdvancedFieldDecision(
            value = "${rideReservePercent}%",
            status = FieldStatus.OK,
            reason = "local_reserve_estimate",
            source = "local_model",
        )
    }

    fun localCarb(
        carbModelReady: Boolean,
        carbsGPerH: Int,
    ): AdvancedFieldDecision {
        if (!carbModelReady) return waitNoModel("carb_model_not_ready")
        if (carbsGPerH < 0 || carbsGPerH > 200) return waitNoModel("carb_invalid")
        return AdvancedFieldDecision(
            value = "${carbsGPerH}g/h",
            status = FieldStatus.OK,
            reason = "local_carb_estimate",
            source = "local_model",
        )
    }

    fun localCarbBalance(
        carbModelReady: Boolean,
        carbBalanceG: Int,
    ): AdvancedFieldDecision {
        if (!carbModelReady) return waitNoModel("carb_model_not_ready")
        val text = if (carbBalanceG > 0) "+${carbBalanceG}g" else "${carbBalanceG}g"
        return AdvancedFieldDecision(
            value = text,
            status = FieldStatus.OK,
            reason = "local_carb_balance",
            source = "local_model",
        )
    }

    fun localFluid(
        fluidModelReady: Boolean,
        fluidLPerH: Float,
    ): AdvancedFieldDecision {
        if (!fluidModelReady) return waitNoModel("fluid_model_not_ready")
        if (!fluidLPerH.isFinite() || fluidLPerH < 0f || fluidLPerH > 10f) return waitNoModel("fluid_invalid")
        return AdvancedFieldDecision(
            value = String.format(Locale.US, "%.1fL/h", fluidLPerH.coerceIn(0f, 9.9f)),
            status = FieldStatus.OK,
            reason = "local_fluid_estimate",
            source = "local_model",
        )
    }

    fun localAvgGross(distanceKm: Float, grossElapsedSec: Long): AdvancedFieldDecision {
        if (distanceKm <= 0f || grossElapsedSec <= 0L) return waitNoData("avg_gross_no_distance_or_time")
        val avg = (distanceKm / (grossElapsedSec / 3600.0))
        if (!avg.isFinite() || avg <= 0.0 || avg > 80.0) return waitNoModel("avg_gross_unrealistic")
        return AdvancedFieldDecision(
            value = String.format(Locale.US, "%.1f", avg),
            status = FieldStatus.OK,
            reason = "avg_gross_from_snapshot",
            source = "local_model",
        )
    }
}
