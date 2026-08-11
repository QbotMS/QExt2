package com.qext2.primary.active

import com.qext2.primary.model.SurfaceType

/**
 * Kontekst pacingu — produkowany raz na sekundę w pętli aggregatora,
 * przekazywany do FieldComputers i CompositeActiveDataType.
 */
data class PacingContext(
    val ceilingW: Int,          // max bezpieczna moc (W' + decoupling + reserve)
    val targetLowW: Int,        // dolna granica endurance/climbing target
    val targetHighW: Int,       // górna granica target
    val isClimbing: Boolean,    // true = podjazd (climbing pacing), false = endurance
    val modeFactor: Float,      // współczynnik trybu jazdy
    val surface: SurfaceType,
    val optCadenceLow: Int,
    val optCadenceHigh: Int,
    val isActive: Boolean,      // false = brak danych (brak FTP/LTP) → nie cieniuj
)

/**
 * Silnik pacingu.
 *
 * Dwa niezależne ograniczenia działające jednocześnie:
 *  A. Power ceiling z W' — bezpieczeństwo (nie przekrocz)
 *  B. Endurance/climbing target — optimum (tu jedź)
 *
 * Wiążący jest niższy próg.
 *
 * Nawierzchnia koryguje oba progi (większy wysiłek metaboliczny poza pedałami).
 * Decoupling HR/moc = sygnał przegrzania → obniż ceiling.
 * Wiatr (headwind) = dodatkowy koszt niewidoczny w mocy chwilowej.
 * Rezerwa dzienna (reserve%) = ile energii zostało na resztę jazdy.
 */
object PacingEngine {

    fun compute(
        powerW: Int,
        effectiveLtp: Float,
        effectiveFtp: Float,
        wBalancePct: Float,       // 0..100
        reservePct: Float,        // 0..100; -1 gdy brak danych
        decouplingPct: Float,
        windSpeedMps: Float,      // prędkość wiatru m/s (proxy headwind)
        isClimbing: Boolean,
        gradePercent: Double,
        surface: SurfaceType,
        todayFactor: Float,
        modeFactor: Float,
    ): PacingContext {
        val ltp = effectiveLtp.coerceAtLeast(50f)
        val ftp = effectiveFtp.coerceAtLeast(ltp)

        if (ltp < 50f) {
            return PacingContext(
                ceilingW = 9999, targetLowW = 0, targetHighW = 9999,
                isClimbing = isClimbing, modeFactor = modeFactor,
                surface = surface, optCadenceLow = 70, optCadenceHigh = 90,
                isActive = false,
            )
        }

        // --- A. Power ceiling ---

        // W'bal factor: im mniej W' → niższy ceiling
        val wFactor = when {
            wBalancePct >= 90f -> 1.12f
            wBalancePct >= 70f -> 1.05f
            wBalancePct >= 50f -> 1.00f
            wBalancePct >= 30f -> 0.95f
            else               -> 0.90f
        }

        // Reserve factor: mało energii na resztę dnia → oszczędzaj
        val rsvFactor = when {
            reservePct < 0f    -> 1.00f  // brak danych — neutralny
            reservePct >= 80f  -> 1.08f
            reservePct >= 60f  -> 1.02f
            reservePct >= 40f  -> 0.97f
            reservePct >= 20f  -> 0.92f
            else               -> 0.88f
        }

        // Decoupling factor: serce dryfuje → organizm się przegrzewa
        val decFactor = when {
            decouplingPct > 12f -> (1f - 0.01f * (decouplingPct - 5f)).coerceAtLeast(0.88f)
            decouplingPct > 8f  -> 0.95f
            decouplingPct > 5f  -> 0.97f
            else                -> 1.00f
        }

        // Wind factor: silny wiatr = dodatkowy koszt metaboliczny
        val windFactor = when {
            windSpeedMps > 8f -> 0.94f
            windSpeedMps > 5f -> 0.96f
            windSpeedMps > 3f -> 0.98f
            else              -> 1.00f
        }

        // Surface multiplier: gravel/luźna = wyższy koszt metaboliczny poza pedałami
        val surfMult = when (surface) {
            SurfaceType.LOOSE   -> 0.92f
            SurfaceType.GRAVEL  -> 0.96f
            SurfaceType.PAVED   -> 1.00f
        }

        val ceilingRaw = ltp * minOf(wFactor, rsvFactor) * modeFactor * decFactor * windFactor * surfMult
        val ceilingW = ceilingRaw.toInt().coerceAtLeast((ltp * 0.75f).toInt())

        // --- B. Target (endurance lub climbing) ---

        val (targetLow, targetHigh) = if (isClimbing) {
            // Podjazd: intensywniej, blisko LTP/FTP
            (ltp * 0.92f * modeFactor * surfMult).toInt() to
            (ltp * 1.08f * modeFactor * surfMult).toInt()
        } else {
            // Endurance: strefa aerobowa
            (ltp * 0.75f * modeFactor * surfMult).toInt() to
            (ltp * 0.88f * modeFactor * surfMult).toInt()
        }

        // Ceiling nie może być niższy od targetLow (logika nie ma sensu)
        val finalCeiling = ceilingW.coerceAtLeast(targetLow)

        // Optymalna kadencja dla kontekstu
        val cadRange = OptimalCadenceModel.compute(
            powerW = powerW.coerceAtLeast(0),
            effectiveFtp = ftp,
            gradePercent = gradePercent,
            surface = surface,
            todayFactor = todayFactor,
            decouplingPct = decouplingPct,
        )

        return PacingContext(
            ceilingW = finalCeiling,
            targetLowW = targetLow,
            targetHighW = minOf(targetHigh, finalCeiling),
            isClimbing = isClimbing,
            modeFactor = modeFactor,
            surface = surface,
            optCadenceLow = cadRange.low,
            optCadenceHigh = cadRange.high,
            isActive = true,
        )
    }

    /**
     * Ocena mocy względem kontekstu pacingu.
     * Używana przez pole POWER do cieniowania.
     */
}
