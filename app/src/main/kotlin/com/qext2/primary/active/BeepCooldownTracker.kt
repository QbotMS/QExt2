package com.qext2.primary.active

enum class BeepSuppressionReason {
    SUCCESS_COOLDOWN,
    ERROR_COOLDOWN,
}

class BeepCooldownTracker(
    private val successCooldownMs: Long = 10_000L,
    private val errorCooldownMs: Long = 2_000L,
) {
    private var lastSuccessMs = 0L
    private var lastErrorMs = 0L

    fun suppression(nowMs: Long): BeepSuppressionReason? {
        if (lastSuccessMs > 0L && nowMs - lastSuccessMs < successCooldownMs) return BeepSuppressionReason.SUCCESS_COOLDOWN
        if (lastErrorMs > 0L && nowMs - lastErrorMs < errorCooldownMs) return BeepSuppressionReason.ERROR_COOLDOWN
        return null
    }

    fun onSuccess(nowMs: Long) {
        lastSuccessMs = nowMs
    }

    fun onFailure(nowMs: Long) {
        lastErrorMs = nowMs
    }
}
