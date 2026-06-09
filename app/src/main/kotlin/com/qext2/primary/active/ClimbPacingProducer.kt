package com.qext2.primary.active

class ClimbPacingProducer(private val logger: (String) -> Unit = {}) {

    companion object {
        private const val TOO_HARD_RATIO = 1.15f   // power > LTP * 1.15 while W' depleting
        private const val PUSH_RATIO = 0.88f        // power < LTP * 0.88 with W' full
        private const val WBAL_LOW = 55             // W'% below this = warn
        private const val WBAL_HIGH = 75            // W'% above this = can push
        private const val MIN_ASCENT_M = 100
        private const val TARGET_COOLDOWN_MS = 300_000L  // 5 min between target msgs
        private const val HARD_COOLDOWN_MS = 60_000L
        private const val PUSH_COOLDOWN_MS = 90_000L
    }

    private var lastTargetMs = 0L
    private var lastHardMs = 0L
    private var lastPushMs = 0L
    private var lastClimbIndex = -1

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
        if (!isWithinBounds) return null
        if (effectiveLtpW < 50f) return null
        if (wBalancePct < 0) return null
        if (ascentLeftM < MIN_ASCENT_M) return null

        if (climbIndex != lastClimbIndex) {
            lastClimbIndex = climbIndex
            lastTargetMs = 0L
            lastHardMs = 0L
            lastPushMs = 0L
            logger("PACING_RESET climbIndex=$climbIndex effectiveLtp=${effectiveLtpW.toInt()} mode=$modeFactor")
        }

        val targetLow = (effectiveLtpW * 0.92f * modeFactor).toInt()
        val targetHigh = (effectiveLtpW * 1.08f * modeFactor).toInt()
        val tooHardAt = (effectiveLtpW * (TOO_HARD_RATIO * modeFactor).coerceAtLeast(1.04f)).toInt()
        val pushBelow = (effectiveLtpW * PUSH_RATIO * modeFactor).toInt()

        // Priority 1: too hard + W' in danger zone
        if (power > tooHardAt && wBalancePct < WBAL_LOW) {
            if (nowMs - lastHardMs > HARD_COOLDOWN_MS) {
                lastHardMs = nowMs
                logger("PACING_TRIGGER type=too_hard power=$power tooHardAt=$tooHardAt w=$wBalancePct%")
                return ActiveMessage(
                    id = "pace_hard_$nowMs",
                    title = "ZA MOCNO",
                    line1 = "CEL: $targetLow-$targetHigh W",
                    line2 = "W' ${wBalancePct}%",
                    severity = ActiveMessageSeverity.WARNING,
                    priority = ActiveMessagePriority.WARNING,
                    resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    createdAtMs = nowMs,
                    expiresAtMs = nowMs + 10_000L,
                )
            }
        }

        // Priority 2: target power on climb entry (or refresh every 5 min)
        if (nowMs - lastTargetMs > TARGET_COOLDOWN_MS) {
            lastTargetMs = nowMs
            val gradeStr = "${if (grade >= 0) "+" else ""}${grade.toInt()}%"
            logger("PACING_TRIGGER type=target ltp=${effectiveLtpW.toInt()} grade=${grade.toInt()} mode=$modeFactor")
            return ActiveMessage(
                id = "pace_target_$nowMs",
                title = "CEL: $targetLow-$targetHigh W",
                line1 = "$gradeStr \u00b7 \u2191${ascentLeftM}m",
                line2 = null,
                severity = ActiveMessageSeverity.INFO,
                priority = ActiveMessagePriority.INFO,
                resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                createdAtMs = nowMs,
                expiresAtMs = nowMs + 10_000L,
            )
        }

        // Priority 3: nudge — only in normal/offensive mode
        if (modeFactor >= 1.0f && power in 50..pushBelow && wBalancePct > WBAL_HIGH) {
            if (nowMs - lastPushMs > PUSH_COOLDOWN_MS) {
                lastPushMs = nowMs
                logger("PACING_TRIGGER type=push power=$power ltp=${effectiveLtpW.toInt()} w=$wBalancePct%")
                return ActiveMessage(
                    id = "pace_push_$nowMs",
                    title = "MOZESZ MOCNIEJ",
                    line1 = "CEL: $targetLow-$targetHigh W",
                    line2 = "W' ${wBalancePct}%",
                    severity = ActiveMessageSeverity.INFO,
                    priority = ActiveMessagePriority.INFO,
                    resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    createdAtMs = nowMs,
                    expiresAtMs = nowMs + 8_000L,
                )
            }
        }

        return null
    }

    fun reset() {
        lastClimbIndex = -1
        lastTargetMs = 0L
        lastHardMs = 0L
        lastPushMs = 0L
    }
}
