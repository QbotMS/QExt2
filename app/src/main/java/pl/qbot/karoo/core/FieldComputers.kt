package pl.qbot.karoo.core

import com.qext2.primary.core.RideContext
import com.qext2.primary.model.SurfaceType
import java.util.Locale

class FieldComputers(
    private val config: LabConfig = LabConfig()
) {
    fun speed(state: RideState): FieldOutput {
        val speed = state.speedKmh ?: return FieldOutput(
            name = "SPEED",
            value = "WAIT",
            color = FieldColor.GRAY,
            status = FieldStatus.NO_DATA,
            reason = "missing_speed"
        )

        val age = state.sensorAgeSec("speedKmh")
        if (age != null && age > config.speedStaleSec) {
            return FieldOutput(
                name = "SPEED",
                value = oneDecimal(speed),
                color = FieldColor.GRAY,
                status = FieldStatus.STALE,
                reason = "speed_stale",
                raw = mapOf("age_s" to age)
            )
        }

        if (speed < 0.0 || speed > config.maxRealisticSpeedKmh) {
            return FieldOutput(
                name = "SPEED",
                value = "INV",
                color = FieldColor.RED,
                status = FieldStatus.INVALID,
                reason = "speed_out_of_range",
                raw = mapOf("speed_kmh" to speed)
            )
        }

        // Kontrast/choinka: na podjezdzie i zjezdzie (>3%) porownanie ze srednia
        // moving nie niesie informacji (predkosc ZAWSZE odbiega) — sygnal wygaszony.
        val gradeForSpeed = state.gradeDisplayPct
        if (gradeForSpeed != null && (gradeForSpeed > 3.0 || gradeForSpeed < -3.0)) {
            return FieldOutput(
                name = "SPEED",
                value = oneDecimal(speed),
                color = FieldColor.NEUTRAL,
                status = FieldStatus.OK,
                reason = if (gradeForSpeed > 3.0) "climb_reference_suspended" else "descent_reference_suspended",
                raw = mapOf("grade_pct" to gradeForSpeed, "age_s" to age)
            )
        }

        val ref = state.avgMovingKmh ?: config.targetAvgKmh
        val (color, reason) = when {
            speed >= ref + 1.5 -> FieldColor.GREEN to "above_reference"
            speed <= ref - 1.5 -> FieldColor.AMBER to "below_reference"
            else -> FieldColor.NEUTRAL to "near_reference"
        }

        return FieldOutput(
            name = "SPEED",
            value = oneDecimal(speed),
            color = color,
            status = FieldStatus.OK,
            reason = reason,
            raw = mapOf("reference_kmh" to ref, "age_s" to age)
        )
    }

    fun avgGross(state: RideState): FieldOutput {
        val avg = state.avgGrossKmh ?: return FieldOutput(
            name = "AVG_GROSS",
            value = "WAIT",
            color = FieldColor.GRAY,
            status = FieldStatus.WAIT,
            reason = "not_enough_time_or_distance"
        )
        if (avg > config.maxRealisticAvgKmh) {
            return FieldOutput(
                name = "AVG_GROSS",
                value = "INV",
                color = FieldColor.RED,
                status = FieldStatus.INVALID,
                reason = "avg_gross_unrealistic",
                raw = mapOf("avg" to avg)
            )
        }
        return FieldOutput("AVG_GROSS", oneDecimal(avg), FieldColor.NEUTRAL, FieldStatus.OK, "distance_over_total_time")
    }

    fun avgMoving(state: RideState): FieldOutput {
        val avg = state.avgMovingKmh ?: return FieldOutput(
            name = "AVG_MOVING",
            value = "WAIT",
            color = FieldColor.GRAY,
            status = FieldStatus.WAIT,
            reason = "not_enough_moving_time_or_distance"
        )
        if (avg > config.maxRealisticAvgKmh) {
            return FieldOutput(
                name = "AVG_MOVING",
                value = "INV",
                color = FieldColor.RED,
                status = FieldStatus.INVALID,
                reason = "avg_moving_unrealistic",
                raw = mapOf("avg" to avg)
            )
        }
        return FieldOutput("AVG_MOVING", oneDecimal(avg), FieldColor.NEUTRAL, FieldStatus.OK, "distance_over_moving_time")
    }

    fun grade(state: RideState): FieldOutput {
        val g = state.gradeDisplayPct ?: return FieldOutput(
            name = "GRADE",
            value = "WAIT",
            color = FieldColor.GRAY,
            status = FieldStatus.NO_DATA,
            reason = "missing_grade"
        )
        val age = state.sensorAgeSec("gradePct")
        if (age != null && age > config.sensorStaleSec) {
            return FieldOutput(
                name = "GRADE",
                value = oneDecimal(g),
                color = FieldColor.GRAY,
                status = FieldStatus.STALE,
                reason = "grade_stale",
                raw = mapOf("age_s" to age, "grade_display" to g)
            )
        }

        val (color, reason, shown) = when {
            kotlin.math.abs(g) < config.gradeDeadbandPct -> Triple(FieldColor.NEUTRAL, "flat_deadband", "0")
            g < config.gradeDescentThresholdPct -> Triple(FieldColor.BLUE, "descent", "${g.toInt()}")
            g < config.gradeLightClimbPct -> Triple(FieldColor.AMBER, "light_climb", "+${g.toInt()}")
            g < config.gradeClimbPct -> Triple(FieldColor.ORANGE, "climb", "+${g.toInt()}")
            else -> Triple(FieldColor.RED, "steep_climb", "+${g.toInt()}")
        }

        return FieldOutput(
            name = "GRADE",
            value = shown,
            color = color,
            status = FieldStatus.OK,
            reason = reason,
            raw = mapOf("grade_raw" to state.gradeRawPct, "grade_display" to g)
        )
    }

    fun power(state: RideState): FieldOutput {
        val p = state.powerW ?: return FieldOutput("POWER", "WAIT", FieldColor.GRAY, FieldStatus.NO_DATA, "missing_power")
        val age = state.sensorAgeSec("powerW")
        if (age != null && age > config.sensorStaleSec) {
            return FieldOutput("POWER", p.toInt().toString(), FieldColor.GRAY, FieldStatus.STALE, "power_stale", mapOf("age_s" to age))
        }
        if (p < 0.0 || p > 2000.0) {
            return FieldOutput("POWER", "INV", FieldColor.RED, FieldStatus.INVALID, "power_out_of_range", mapOf("power_w" to p))
        }
        return FieldOutput("POWER", p.toInt().toString(), FieldColor.NEUTRAL, FieldStatus.OK, "power_present", mapOf("age_s" to age))
    }

    fun hr(state: RideState): FieldOutput {
        val hr = state.hrBpm ?: return FieldOutput("HR", "WAIT", FieldColor.GRAY, FieldStatus.NO_DATA, "missing_hr")
        val age = state.sensorAgeSec("hrBpm")
        if (age != null && age > config.sensorStaleSec) {
            return FieldOutput("HR", hr.toInt().toString(), FieldColor.GRAY, FieldStatus.STALE, "hr_stale", mapOf("age_s" to age))
        }
        if (hr == 0.0) {
            return FieldOutput("HR", "WAIT", FieldColor.GRAY, FieldStatus.NO_DATA, "hr_zero_or_not_ready", mapOf("hr_bpm" to hr))
        }
        if (hr < 0.0 || hr < 30.0 || hr > 230.0) {
            return FieldOutput("HR", "INV", FieldColor.RED, FieldStatus.INVALID, "hr_out_of_range", mapOf("hr_bpm" to hr))
        }
        return FieldOutput("HR", hr.toInt().toString(), FieldColor.NEUTRAL, FieldStatus.OK, "hr_present", mapOf("age_s" to age))
    }

    fun cadence(state: RideState, context: RideContext = RideContext()): FieldOutput {
        val cad = state.cadenceRpm ?: return FieldOutput("CADENCE", "WAIT", FieldColor.GRAY, FieldStatus.NO_DATA, "missing_cadence")
        val age = state.sensorAgeSec("cadenceRpm")
        if (age != null && age > config.sensorStaleSec) {
            return FieldOutput("CADENCE", cad.toInt().toString(), FieldColor.GRAY, FieldStatus.STALE, "cadence_stale", mapOf("age_s" to age))
        }
        if (cad < 0.0 || cad > 180.0) {
            return FieldOutput("CADENCE", "INV", FieldColor.RED, FieldStatus.INVALID, "cadence_out_of_range", mapOf("cadence_rpm" to cad))
        }
        if (cad == 0.0) return FieldOutput("CADENCE", "0", FieldColor.GRAY, FieldStatus.OK, "coasting_or_stopped", mapOf("age_s" to age))
        // Choinka: gdy doradca biegu (pole GEAR) ma komplet danych, to ON niesie
        // sygnal kolorem (tam jest akcja: zrzuc/wrzuc). Kadencja pokazuje wtedy
        // sama wartosc, bez koloru — koniec podwojnego alarmu z jednego zdarzenia.
        // Fallback: bez czujnika biegow kadencja sygnalizuje po staremu.
        val gearAdvisoryActive = state.gearFront != null && state.gearRear != null &&
            (state.powerW ?: 0.0) > 0.0 && context.effectiveLtp > 0f
        if (gearAdvisoryActive) {
            return FieldOutput("CADENCE", cad.toInt().toString(), FieldColor.NEUTRAL, FieldStatus.OK,
                "signal_delegated_to_gear", mapOf("age_s" to age))
        }
        val range = com.qext2.primary.active.OptimalCadenceModel.compute(
            powerW = state.powerW?.toInt() ?: 0,
            effectiveFtp = context.effectiveLtp.coerceAtLeast(50f),
            gradePercent = state.gradeDisplayPct ?: 0.0,
            surface = context.surface,
            todayFactor = context.todayFactor,
            decouplingPct = context.decouplingPct,
        )
        val (color, reason) = when (com.qext2.primary.active.OptimalCadenceModel.assess(cad.toInt(), range)) {
            com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.CRITICAL_LOW -> FieldColor.RED to "cadence_critical_low"
            com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.LOW          -> FieldColor.AMBER to "cadence_low"
            com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.OPTIMAL      -> FieldColor.GREEN to "cadence_optimal"
            com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.HIGH         -> FieldColor.NEUTRAL to "cadence_high_no_signal"
            com.qext2.primary.active.OptimalCadenceModel.CadenceStatus.NO_DATA      -> FieldColor.GRAY to "cadence_no_data"
        }
        return FieldOutput("CADENCE", cad.toInt().toString(), color, FieldStatus.OK, reason, mapOf("age_s" to age))
    }

    fun gear(state: RideState, context: RideContext = RideContext()): FieldOutput {
        val front = state.gearFront
        val rear = state.gearRear
        if (front == null || rear == null) {
            return FieldOutput("GEAR", "WAIT", FieldColor.GRAY, FieldStatus.NO_DATA, "missing_gear")
        }
        val age = state.sensorAgeSec("gear")
        if (age != null && age > config.sensorStaleSec) {
            return FieldOutput("GEAR", "${front}×${rear}", FieldColor.GRAY, FieldStatus.STALE, "gear_stale", mapOf("age_s" to age))
        }
        val cadRpm = state.cadenceRpm?.toInt() ?: 0
        val powerW = state.powerW?.toInt() ?: 0
        if (cadRpm > 0 && powerW > 0 && context.effectiveLtp > 0f) {
            val range = com.qext2.primary.active.OptimalCadenceModel.compute(
                powerW = powerW,
                effectiveFtp = context.effectiveLtp,
                gradePercent = state.gradeDisplayPct ?: 0.0,
                surface = context.surface,
                todayFactor = context.todayFactor,
                decouplingPct = context.decouplingPct,
            )
            val rawGearColor = when {
                cadRpm < range.low - 10 -> FieldColor.RED     // zrzuć ≥2
                cadRpm < range.low - 5  -> FieldColor.AMBER   // zrzuć 1
                cadRpm > range.high + 10 -> FieldColor.GREEN  // wrzuć ≥2
                cadRpm > range.high + 5  -> FieldColor.GREEN  // wrzuć 1
                else -> FieldColor.NEUTRAL                     // optimum
            }
            val gearColor = gearColorWithHysteresis(rawGearColor, state.tSec)
            val gearReason = if (gearColor == rawGearColor) "gear_cadence_advisory" else "gear_cadence_advisory_held"
            return FieldOutput("GEAR", "${front}×${rear}", gearColor, FieldStatus.OK, gearReason)
        }
        return FieldOutput("GEAR", "${front}×${rear}", FieldColor.NEUTRAL, FieldStatus.OK, "gear_present_no_advice_model")
    }

    fun wPrimeNoModel(): FieldOutput =
        FieldOutput("WPRIME", "WAIT", FieldColor.GRAY, FieldStatus.NO_MODEL, "missing_cp_or_wprime")

    fun tssNoModel(): FieldOutput =
        FieldOutput("TSS", "WAIT", FieldColor.GRAY, FieldStatus.NO_MODEL, "missing_ftp")

    fun batteryHead(state: RideState): FieldOutput =
        battery("BAT_HEAD", state.batteryHeadunitPct)

    fun batterySensors(state: RideState): FieldOutput =
        battery("BAT_SENS", state.batterySensorsPct)

    private val GEAR_COLOR_HOLD_SEC = 4.0

    fun mvp(state: RideState, context: RideContext = RideContext()): List<FieldOutput> =
        listOf(speed(state), power(state), hr(state), cadence(state, context), grade(state), gear(state, context))

    fun all(state: RideState): List<FieldOutput> =
        listOf(
            speed(state),
            avgGross(state),
            avgMoving(state),
            grade(state),
            power(state),
            hr(state),
            cadence(state),
            gear(state),
            wPrimeNoModel(),
            tssNoModel(),
            batteryHead(state),
            batterySensors(state)
        )

    // Histereza koloru GEAR: kandydat musi utrzymac sie GEAR_COLOR_HOLD_SEC
    // zanim kolor sie zmieni — koniec migotania na progu +-5 rpm. Czas z probek
    // (state.tSec), wiec zachowanie jest deterministyczne w testach replay.
    private var gearHystShown: FieldColor = FieldColor.NEUTRAL
    private var gearHystCandidate: FieldColor = FieldColor.NEUTRAL
    private var gearHystCandidateSinceSec: Double = 0.0
    private var gearHystLastSeenSec: Double = -1.0

    private fun gearColorWithHysteresis(raw: FieldColor, tSec: Double): FieldColor {
        if (tSec < gearHystLastSeenSec) {
            // czas cofniety = nowy przejazd/replay — reset stanu
            gearHystShown = raw
            gearHystCandidate = raw
            gearHystCandidateSinceSec = tSec
        }
        gearHystLastSeenSec = tSec
        if (raw == gearHystShown) {
            gearHystCandidate = raw
            gearHystCandidateSinceSec = tSec
            return gearHystShown
        }
        if (raw != gearHystCandidate) {
            gearHystCandidate = raw
            gearHystCandidateSinceSec = tSec
            return gearHystShown
        }
        if (tSec - gearHystCandidateSinceSec >= GEAR_COLOR_HOLD_SEC) {
            gearHystShown = raw
        }
        return gearHystShown
    }

    private fun battery(name: String, value: Double?): FieldOutput {
        if (value == null) return FieldOutput(name, "WAIT", FieldColor.GRAY, FieldStatus.NO_DATA, "missing_battery_source")
        val color = when {
            value < 15.0 -> FieldColor.RED
            value < 35.0 -> FieldColor.AMBER
            else -> FieldColor.GREEN
        }
        return FieldOutput(name, "${value.toInt()}%", color, FieldStatus.OK, "battery_source_present")
    }

    private fun oneDecimal(v: Double): String =
        String.format(Locale.US, "%.1f", v)
}
