package com.qext2.primary.active

class ClimbPacingProducer(private val logger: (String) -> Unit = {}) {

    companion object {
        private const val TOO_HARD_RATIO = 1.15f
        private const val WBAL_LOW = 55
        private const val MIN_ASCENT_M = 100
        private const val HARD_COOLDOWN_MS = 60_000L
        private const val MODE_MSG_COOLDOWN_MS = 600_000L  // 10 min — tylko przy zmianie kontekstu
    }

    private var lastHardMs = 0L
    private var lastClimbIndex = -1
    private var lastModeMs = 0L
    private var lastModeCtx = ""   // "climbing" lub "endurance"

    fun checkAndProduce(
        power: Int,
        wBalancePct: Int,
        effectiveLtpW: Float,
        isWithinBounds: Boolean,
        ascentLeftM: Int,
        grade: Double,
        climbIndex: Int,
        modeFactor: Float,
        nowMs: Long,
    ): ActiveMessage? {
        if (effectiveLtpW < 50f) return null
        if (wBalancePct < 0) return null

        val isClimbing = isWithinBounds && ascentLeftM >= MIN_ASCENT_M
        val modeCtx = if (isClimbing) "climbing" else "endurance"

        // Reset przy nowym podjeździe
        if (climbIndex != lastClimbIndex) {
            lastClimbIndex = climbIndex
            lastHardMs = 0L
            logger("PACING_RESET climbIndex=$climbIndex effectiveLtp=${effectiveLtpW.toInt()} mode=$modeFactor")
        }

        val tooHardAt = (effectiveLtpW * (TOO_HARD_RATIO * modeFactor).coerceAtLeast(1.04f)).toInt()

        // Priority 1: ZA MOCNO + W' w strefie zagrożenia (alert — zostaje)
        if (power > tooHardAt && wBalancePct < WBAL_LOW) {
            if (nowMs - lastHardMs > HARD_COOLDOWN_MS) {
                lastHardMs = nowMs
                logger("PACING_TRIGGER type=too_hard power=$power tooHardAt=$tooHardAt w=$wBalancePct%")
                return ActiveMessage(
                    id = "pace_hard_$nowMs",
                    title = "ZA MOCNO",
                    line1 = "W' ${wBalancePct}%",
                    line2 = null,
                    severity = ActiveMessageSeverity.WARNING,
                    priority = ActiveMessagePriority.WARNING,
                    resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    createdAtMs = nowMs,
                    expiresAtMs = nowMs + 10_000L,
                )
            }
        }

        // Priority 2: komunikat trybu — tylko przy zmianie kontekstu (climbing ↔ endurance)
        if (modeCtx != lastModeCtx || nowMs - lastModeMs > MODE_MSG_COOLDOWN_MS) {
            lastModeCtx = modeCtx
            lastModeMs = nowMs
            val title = if (isClimbing) "PACING CLIMBING ON" else "PACING ENDURANCE ON"
            logger("PACING_TRIGGER type=mode_change ctx=$modeCtx")
            return ActiveMessage(
                id = "pace_mode_$nowMs",
                title = title,
                line1 = "",
                line2 = null,
                severity = ActiveMessageSeverity.INFO,
                priority = ActiveMessagePriority.INFO,
                resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                createdAtMs = nowMs,
                expiresAtMs = nowMs + 8_000L,
            )
        }

        return null
    }

    fun reset() {
        lastClimbIndex = -1
        lastHardMs = 0L
        lastModeMs = 0L
        lastModeCtx = ""
    }
}
