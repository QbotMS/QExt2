package com.qext2.primary.active

/**
 * Zero-input fueling reminders. No logging, no acknowledgements.
 * - EAT: driven by accumulated recommended carb intake (from carbsGPerH model);
 *        fires every `packetSizeG` grams of recommended intake.
 * - DRINK: driven by accumulated recommended fluid (from fluidLPerH model);
 *          fires every ~0.25 L.
 * - SODIUM: hourly when physiological temp >= 28C.
 * State lives for the whole session (owned by aggregator), survives view switches.
 */
class FuelReminderProducer(private val logger: (String) -> Unit = {}) {

    companion object {
        private const val DRINK_STEP_L = 0.25f
        private const val SODIUM_TEMP_C = 28f
        private const val SODIUM_INTERVAL_MS = 60 * 60 * 1000L
        private const val MIN_ELAPSED_BEFORE_FIRST_MS = 20 * 60 * 1000L
        private const val MSG_GAP_MS = 90_000L  // never stack fuel msgs closer than this
    }

    private var carbAccumG = 0f
    private var carbRemindedG = 0f
    private var fluidAccumL = 0f
    private var fluidRemindedL = 0f
    private var lastSodiumMs = 0L
    private var lastAnyMsgMs = 0L
    private var sessionStartMs = 0L

    fun reset() {
        carbAccumG = 0f; carbRemindedG = 0f
        fluidAccumL = 0f; fluidRemindedL = 0f
        lastSodiumMs = 0L; lastAnyMsgMs = 0L; sessionStartMs = 0L
    }

    /** Call once per second while moving. Rates are per hour. */
    fun tick(carbsGPerH: Int, fluidLPerH: Float, isMoving: Boolean) {
        if (!isMoving) return
        if (carbsGPerH > 0) carbAccumG += carbsGPerH / 3600f
        if (fluidLPerH > 0f) fluidAccumL += fluidLPerH / 3600f
    }

    fun checkAndProduce(physioTempC: Float?, packetSizeG: Int, nowMs: Long): ActiveMessage? {
        if (sessionStartMs == 0L) sessionStartMs = nowMs
        if (nowMs - sessionStartMs < MIN_ELAPSED_BEFORE_FIRST_MS) return null
        if (nowMs - lastAnyMsgMs < MSG_GAP_MS) return null

        val packet = packetSizeG.coerceIn(15, 60)

        // Priority 1: sodium in heat (least frequent, easiest to forget)
        if (physioTempC != null && physioTempC >= SODIUM_TEMP_C) {
            if (lastSodiumMs == 0L) lastSodiumMs = nowMs  // first interval counts from now
            else if (nowMs - lastSodiumMs >= SODIUM_INTERVAL_MS) {
                lastSodiumMs = nowMs
                lastAnyMsgMs = nowMs
                logger("FUEL_TRIGGER type=sodium temp=$physioTempC")
                return ActiveMessage(
                    id = "fuel_sodium_$nowMs",
                    title = "S\u00d3D 500-800mg",
                    line1 = "${physioTempC.toInt()}\u00b0C \u00b7 elektrolity",
                    line2 = null,
                    severity = ActiveMessageSeverity.WARNING,
                    priority = ActiveMessagePriority.WARNING,
                    resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    createdAtMs = nowMs,
                    expiresAtMs = nowMs + 12_000L,
                )
            }
        }

        // Priority 2: eat — every packet-worth of recommended intake
        if (carbAccumG - carbRemindedG >= packet) {
            carbRemindedG = carbAccumG
            lastAnyMsgMs = nowMs
            logger("FUEL_TRIGGER type=eat accum=${carbAccumG.toInt()}g packet=$packet")
            return ActiveMessage(
                id = "fuel_eat_$nowMs",
                title = "ZJEDZ",
                line1 = "zalecane \u0142\u0105cznie: ${carbAccumG.toInt()}g",
                line2 = null,
                severity = ActiveMessageSeverity.INFO,
                priority = ActiveMessagePriority.INFO,
                resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                createdAtMs = nowMs,
                expiresAtMs = nowMs + 12_000L,
            )
        }

        // Priority 3: drink — every 0.25 L of recommended fluid
        if (fluidAccumL - fluidRemindedL >= DRINK_STEP_L) {
            fluidRemindedL = fluidAccumL
            lastAnyMsgMs = nowMs
            logger("FUEL_TRIGGER type=drink accum=${"%.2f".format(fluidAccumL)}L")
            return ActiveMessage(
                id = "fuel_drink_$nowMs",
                title = "PIJ",
                line1 = "~250ml \u00b7 plan ${"%.1f".format(fluidAccumL)}L",
                line2 = null,
                severity = ActiveMessageSeverity.INFO,
                priority = ActiveMessagePriority.INFO_LOW,
                resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                createdAtMs = nowMs,
                expiresAtMs = nowMs + 10_000L,
            )
        }

        return null
    }
}
