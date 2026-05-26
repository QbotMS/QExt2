package com.qext2.primary.field

data class RsrvDecision(
    val value: String,
    val valid: Boolean,
    val reason: String,
)

object RsrvDisplayPolicy {
    private const val MIN_READY_ELAPSED_SEC = 120L

    fun decide(
        route: Boolean,
        elapsedSec: Long,
        reservePercent: Int,
        npWatts: Int,
        ifValue: Float,
        wBalancePercent: Int,
        carbsGph: Int,
    ): RsrvDecision {
        if (!route) return RsrvDecision(value = "WAIT", valid = false, reason = "no_route")

        val elapsedReady = elapsedSec >= MIN_READY_ELAPSED_SEC
        val hasPowerModel = npWatts > 0 || ifValue > 0f
        val hasWPrime = wBalancePercent >= 0
        val hasFuelSignal = carbsGph >= 0
        val modelReady = elapsedReady && hasPowerModel && hasWPrime && hasFuelSignal

        if (!modelReady) {
            return RsrvDecision(value = "WAIT", valid = false, reason = "model_not_ready")
        }

        return RsrvDecision(
            value = "${reservePercent.coerceIn(0, 100)}%",
            valid = true,
            reason = "calculated",
        )
    }
}
