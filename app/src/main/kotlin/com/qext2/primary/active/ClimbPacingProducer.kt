package com.qext2.primary.active

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class ClimbPacingProducer(private val logger: (String) -> Unit = {}) {

    companion object {
        private const val MIN_ASCENT_M = 100
        private const val MODE_MSG_COOLDOWN_MS = 600_000L  // 10 min

        // --- Komunikat stanu W' (2026-07-24, zastapil "ZA MOCNO") ---
        const val WBAL_MSG_THRESHOLD = 55           // komunikaty startuja ponizej tego %
        private const val DEAD_ZONE_W = 10f         // +-10 W wokol CP => TRZYMASZ
        private const val RECOVERY_TARGET = 0.90f   // 100% jest asymptota (nieosiagalne)
        private const val MIN_CP_W = 50f

        private const val CALM_COOLDOWN_MS = 60_000L    // TRZYMASZ / odbudowa
        private const val CALM_TTL_MS = 10_000L
        private const val DIVE_FAR_COOLDOWN_MS = 30_000L    // > 2 min do bomby
        private const val DIVE_FAR_TTL_MS = 10_000L
        private const val DIVE_NEAR_COOLDOWN_MS = 10_000L   // 30 s - 2 min
        private const val DIVE_NEAR_TTL_MS = 10_000L
        private const val DIVE_CRIT_COOLDOWN_MS = 1_000L    // < 30 s: tyka co sekunde
        private const val DIVE_CRIT_TTL_MS = 1_000L

        private const val BOMB_NEAR_S = 120f
        private const val BOMB_CRIT_S = 30f

        fun formatMmSs(seconds: Float): String {
            val s = seconds.coerceIn(0f, 5999f).roundToInt()
            return "%d:%02d".format(s / 60, s % 60)
        }

        /** Czas do bomby [s] przy utrzymaniu mocy: W'bal / (moc - CP). */
        fun bombSeconds(wBalJ: Float, overCpW: Float): Float =
            if (overCpW <= 0f) Float.MAX_VALUE else wBalJ / overCpW

        /**
         * Czas odbudowy [s] do RECOVERY_TARGET wg modelu Skiba/Bartram uzytego w StatsCalculator:
         * tau = 546*e^(-0.01*(CP-moc)) + 316; luka do pelna maleje wykladniczo.
         */
        fun recoverySeconds(balFraction: Float, cpW: Float, powerW: Float): Float {
            val gapNow = (1f - balFraction).coerceAtLeast(0f)
            val gapTarget = 1f - RECOVERY_TARGET
            if (gapNow <= gapTarget) return 0f
            val dcp = (cpW - powerW).coerceAtLeast(0f)
            val tau = 546f * exp(-0.01f * dcp) + 316f
            return tau * ln(gapNow / gapTarget)
        }
    }

    private data class Plan(
        /** Stan W' pokazywany w line1 -- line2 bywa obcinana na malym polu. */
        val state: String,
        val severity: ActiveMessageSeverity,
        val priority: ActiveMessagePriority,
        val cooldownMs: Long,
        val ttlMs: Long,
        val kind: String,
    )

    private var lastClimbIndex = -1
    private var lastWPrimeMsgMs = 0L
    private var lastModeMs = 0L
    private var lastModeCtx = ""   // "climbing" lub "endurance"

    fun checkAndProduce(
        power: Int,
        wBalancePct: Int,
        effectiveLtpW: Float,
        cpEffW: Float,
        wPrimeEffKj: Float,
        isWithinBounds: Boolean,
        ascentLeftM: Int,
        grade: Double,
        climbIndex: Int,
        modeFactor: Float,
        nowMs: Long,
    ): ActiveMessage? {
        if (effectiveLtpW < MIN_CP_W) return null
        if (wBalancePct < 0) return null

        val isClimbing = isWithinBounds && ascentLeftM >= MIN_ASCENT_M
        val modeCtx = if (isClimbing) "climbing" else "endurance"

        // Reset przy nowym podjezdzie
        if (climbIndex != lastClimbIndex) {
            lastClimbIndex = climbIndex
            lastWPrimeMsgMs = 0L
            logger("PACING_RESET climbIndex=$climbIndex effectiveLtp=${effectiveLtpW.toInt()} mode=$modeFactor")
        }

        // Priority 1: stan W' (UWAGA! ...% W' + odbudowa / bomba / TRZYMASZ / PRZEPAL)
        wPrimeMessage(power, wBalancePct, cpEffW, wPrimeEffKj, nowMs)?.let { return it }

        // Priority 2: komunikat trybu - tylko przy zmianie kontekstu (climbing <-> endurance)
        if (modeCtx != lastModeCtx || nowMs - lastModeMs > MODE_MSG_COOLDOWN_MS) {
            lastModeCtx = modeCtx
            lastModeMs = nowMs
            // ENDURANCE tryb-komunikat wylaczony 2026-07-20 (patrz docs/QEXT2_ACTIVE_MSG_AUDIT.md).
            // Stan trybu sledzony powyzej -> CLIMBING ON dziala; endurance = brak komunikatu. Odwracalne.
            if (!isClimbing) return null
            logger("PACING_TRIGGER type=mode_change ctx=$modeCtx")
            return ActiveMessage(
                id = "pace_mode_$nowMs",
                title = "PACING CLIMBING ON",
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

    /**
     * Jeden komunikat, cztery oblicza - os "pale / odbudowuje", nie "powyzej/ponizej 0%".
     * Moc podawana jako srednia 3 s; CP to cpEffW (to samo CP, ktorym liczy sie W'bal).
     */
    private fun wPrimeMessage(
        power: Int,
        wBalancePct: Int,
        cpEffW: Float,
        wPrimeEffKj: Float,
        nowMs: Long,
    ): ActiveMessage? {
        if (wBalancePct >= WBAL_MSG_THRESHOLD) return null
        if (cpEffW < MIN_CP_W || wPrimeEffKj <= 0f) return null

        val diff = power - cpEffW
        val balFraction = wBalancePct / 100f
        val wBalJ = balFraction * wPrimeEffKj * 1000f

        val plan = when {
            // Bak pusty, a dalej nad CP -> model mowi "koniec", nogi jada. Sygnal breakthrough.
            wBalancePct == 0 && diff > DEAD_ZONE_W -> Plan(
                state = "PRZEPA\u0141",
                severity = ActiveMessageSeverity.CRITICAL,
                priority = ActiveMessagePriority.CRITICAL,
                cooldownMs = DIVE_NEAR_COOLDOWN_MS,
                ttlMs = DIVE_NEAR_TTL_MS,
                kind = "overdraft",
            )
            // Nurkowanie: im blizej bomby, tym czesciej
            diff > DEAD_ZONE_W -> {
                val t = bombSeconds(wBalJ, diff)
                val state = "BOMBA ${formatMmSs(t)}"
                when {
                    t < BOMB_CRIT_S -> Plan(
                        state, ActiveMessageSeverity.CRITICAL, ActiveMessagePriority.CRITICAL,
                        DIVE_CRIT_COOLDOWN_MS, DIVE_CRIT_TTL_MS, "bomb_crit",
                    )
                    t < BOMB_NEAR_S -> Plan(
                        state, ActiveMessageSeverity.WARNING, ActiveMessagePriority.WARNING,
                        DIVE_NEAR_COOLDOWN_MS, DIVE_NEAR_TTL_MS, "bomb_near",
                    )
                    else -> Plan(
                        state, ActiveMessageSeverity.WARNING, ActiveMessagePriority.WARNING,
                        DIVE_FAR_COOLDOWN_MS, DIVE_FAR_TTL_MS, "bomb_far",
                    )
                }
            }
            // Odbudowa
            diff < -DEAD_ZONE_W -> Plan(
                state = "ODBUDOWA ${formatMmSs(recoverySeconds(balFraction, cpEffW, power.toFloat()))}",
                severity = ActiveMessageSeverity.WARNING,
                priority = ActiveMessagePriority.WARNING,
                cooldownMs = CALM_COOLDOWN_MS,
                ttlMs = CALM_TTL_MS,
                kind = "recover",
            )
            // Ani nie nurkuje, ani nie odbudowuje
            else -> Plan(
                state = "TRZYMASZ!",
                severity = ActiveMessageSeverity.WARNING,
                priority = ActiveMessagePriority.WARNING,
                cooldownMs = CALM_COOLDOWN_MS,
                ttlMs = CALM_TTL_MS,
                kind = "hold",
            )
        }

        if (lastWPrimeMsgMs > 0L && nowMs - lastWPrimeMsgMs < plan.cooldownMs) return null
        lastWPrimeMsgMs = nowMs
        logger("WPRIME_MSG kind=${plan.kind} pct=$wBalancePct power=$power cp=${cpEffW.toInt()} wp=${wPrimeEffKj} state=${plan.state}")
        // Tytul + line1 to jedyne linie, co do ktorych mamy pewnosc, ze sa widoczne
        // na polu ACTIVE. Wczesniej stan i czas szly w line2 i nie docieraly do
        // zawodnika (zgloszone z trasy 2026-07-25). Pilnosc niesie kolor tla,
        // wiec tytul "UWAGA!" byl zbedny -- lepiej pokazac procent.
        return ActiveMessage(
            id = "wprime_${plan.kind}_$nowMs",
            title = "W' $wBalancePct%",
            line1 = plan.state,
            line2 = null,
            severity = plan.severity,
            priority = plan.priority,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + plan.ttlMs,
        )
    }

    fun reset() {
        lastClimbIndex = -1
        lastWPrimeMsgMs = 0L
        lastModeMs = 0L
        lastModeCtx = ""
    }
}
