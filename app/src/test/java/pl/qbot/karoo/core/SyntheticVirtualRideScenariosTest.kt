package pl.qbot.karoo.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyntheticVirtualRideScenariosTest {

    @Test
    fun start_idle() {
        val samples = mutableListOf<RideSample>()
        var hr = 80.0
        for (t in 0..119) {
            hr = if (t % 10 == 0) (hr + 1).coerceAtMost(90.0) else hr
            samples += RideSample(
                tSec = t.toDouble(),
                speedKmh = 0.0,
                powerW = 0.0,
                cadenceRpm = 0.0,
                hrBpm = hr,
                distanceM = 0.0,
                gradePct = null,
            )
        }
        runScenario("start_idle", samples) { s, out, ctx ->
            if (s.speedKmh ?: 0.0 < 3.0) {
                val grade = out.getValue("GRADE").value
                if (grade.startsWith("+") && grade != "+0.0" && grade != "0") {
                    ctx.problems += "t=${s.tSec}s grade_spike_idle=$grade"
                }
            }
        }
    }

    @Test
    fun crank_start() {
        val samples = mutableListOf<RideSample>()
        var dist = 0.0
        for (t in 0..29) {
            samples += RideSample(tSec = t.toDouble(), speedKmh = 0.0, powerW = 0.0, cadenceRpm = 0.0, hrBpm = 85.0, distanceM = dist, gradePct = 0.0)
        }
        for (i in 0..119) {
            val t = 30 + i
            val speed = 22.0 * (i / 119.0)
            val power = 160.0 * (i / 119.0)
            val cad = 65.0 * (i / 119.0)
            val hr = 85.0 + 35.0 * (i / 119.0)
            dist += speed / 3.6
            samples += RideSample(tSec = t.toDouble(), speedKmh = speed, powerW = power, cadenceRpm = cad, hrBpm = hr, distanceM = dist, gradePct = 0.0)
        }
        var speed = 22.0
        for (i in 0..29) {
            val t = 150 + i
            speed = (speed - 0.6).coerceAtLeast(4.0)
            dist += speed / 3.6
            samples += RideSample(tSec = t.toDouble(), speedKmh = speed, powerW = 0.0, cadenceRpm = 0.0, hrBpm = 120.0 - i * 0.5, distanceM = dist, gradePct = 0.0)
        }
        runScenario("crank_start", samples, movingFromSec = 30.0)
    }

    @Test
    fun flat_ride() {
        val samples = mutableListOf<RideSample>()
        var dist = 0.0
        for (t in 0..1200) {
            val speed = 24.0 + (t % 20) / 20.0
            val power = 140.0 + (t % 31)
            val cad = 60.0 + (t % 11)
            val hr = 120.0 + (t % 21)
            dist += speed / 3.6
            samples += RideSample(tSec = t.toDouble(), speedKmh = speed, powerW = power, cadenceRpm = cad, hrBpm = hr, distanceM = dist, gradePct = 0.0)
        }
        runScenario("flat_ride", samples) { _, out, ctx ->
            val speed = out.getValue("SPEED")
            if (speed.reason.isBlank()) ctx.problems += "speed_reason_blank"
        }
    }

    @Test
    fun climb_descent() {
        val samples = mutableListOf<RideSample>()
        var dist = 0.0
        var firstDescentRawSec: Double? = null
        for (t in 0..899) {
            val grade = when {
                t < 300 -> 0.0
                t < 600 -> 6.0
                else -> -6.0
            }
            val speed = when {
                t < 300 -> 24.0
                t < 600 -> 14.0
                else -> 30.0
            }
            if (firstDescentRawSec == null && grade < -1.0) firstDescentRawSec = t.toDouble()
            dist += speed / 3.6
            samples += RideSample(tSec = t.toDouble(), speedKmh = speed, powerW = 170.0, cadenceRpm = 65.0, hrBpm = 135.0, distanceM = dist, gradePct = grade)
        }
        runScenario("climb_descent", samples) { s, out, ctx ->
            val start = firstDescentRawSec
            if (start != null && s.tSec - start > 8.0 && !ctx.flags.containsKey("descent_seen")) {
                val gradeOut = out.getValue("GRADE")
                val isDescent = gradeOut.reason == "descent" || gradeOut.value.startsWith("-")
                if (isDescent) ctx.flags["descent_seen"] = true
            }
        }.also { result ->
            if (result.flags["descent_seen"] != true) {
                result.problems += "descent_not_seen_within_8s"
                result.pass = false
            }
        }
    }

    @Test
    fun sensor_dropouts() {
        val samples = mutableListOf<RideSample>()
        var dist = 0.0
        for (t in 0..599) {
            val speed: Double? = if (t in 120..149) null else 24.0
            val power: Double? = if (t in 220..249) null else 160.0
            val hr: Double? = if (t in 320..349) null else 130.0
            val cad: Double? = if (t in 420..449) null else 65.0
            dist += (speed ?: 24.0) / 3.6
            samples += RideSample(tSec = t.toDouble(), speedKmh = speed, powerW = power, cadenceRpm = cad, hrBpm = hr, distanceM = dist, gradePct = 0.0)
        }
        runScenario("sensor_dropouts", samples)
    }

    @Test
    fun ui_recreate_pause_resume() {
        val samples = mutableListOf<RideSample>()
        var dist = 0.0
        for (t in 0..600) {
            dist += 24.0 / 3.6
            val event = if (t == 600) RideEvent.UI_RECREATE else RideEvent.NONE
            samples += RideSample(tSec = t.toDouble(), speedKmh = 24.0, powerW = 160.0, cadenceRpm = 65.0, hrBpm = 130.0, distanceM = dist, gradePct = 0.0, event = event)
        }
        samples += RideSample(tSec = 601.0, speedKmh = 0.0, powerW = 0.0, cadenceRpm = 0.0, hrBpm = 120.0, distanceM = dist, gradePct = 0.0, event = RideEvent.PAUSE)
        for (t in 602..721) {
            samples += RideSample(tSec = t.toDouble(), speedKmh = 0.0, powerW = 0.0, cadenceRpm = 0.0, hrBpm = 118.0, distanceM = dist, gradePct = 0.0)
        }
        samples += RideSample(tSec = 722.0, speedKmh = 24.0, powerW = 160.0, cadenceRpm = 65.0, hrBpm = 130.0, distanceM = dist, gradePct = 0.0, event = RideEvent.RESUME)
        for (t in 723..1322) {
            dist += 24.0 / 3.6
            samples += RideSample(tSec = t.toDouble(), speedKmh = 24.0, powerW = 160.0, cadenceRpm = 65.0, hrBpm = 130.0, distanceM = dist, gradePct = 0.0)
        }
        runScenario("ui_recreate_pause_resume", samples) { s, _, ctx ->
            if (s.tSec == 599.0) ctx.flags["avg_before"] = ctx.state.avgMovingKmh ?: 0.0
            if (s.tSec == 723.0) {
                val before = ctx.flags["avg_before"] as? Double ?: 0.0
                val after = ctx.state.avgMovingKmh ?: 0.0
                if (after < before - 2.0) ctx.problems += "avg_reset_after_ui_recreate_or_pause before=$before after=$after"
                if ((ctx.state.avgMovingKmh ?: 0.0) <= (ctx.state.avgGrossKmh ?: 0.0)) {
                    ctx.problems += "avg_moving_not_higher_than_gross_after_pause"
                }
            }
        }
    }

    @Test
    fun route_loaded_no_motion() {
        val samples = mutableListOf<RideSample>()
        for (t in 0..299) {
            val hr = if (t % 15 == 0) null else 80.0 + (t % 11)
            samples += RideSample(
                tSec = t.toDouble(),
                speedKmh = 0.0,
                powerW = 0.0,
                cadenceRpm = 0.0,
                hrBpm = hr,
                altitudeM = 120.0 + (t % 5) * 0.1,
                distanceM = 0.0,
                gradePct = null,
            )
        }
        runScenario(
            name = "route_loaded_no_motion",
            samples = samples,
            csvFileName = "virtual_route_loaded_no_motion.csv",
            auditFileName = "route_loaded_no_motion_audit.txt",
        ) { s, out, ctx ->
            val avgGross = FieldComputers().avgGross(ctx.state)
            val avgMoving = FieldComputers().avgMoving(ctx.state)
            if (ctx.state.distanceM > 0.0) {
                ctx.problems += "t=${s.tSec}s distance_gt_zero=${ctx.state.distanceM}"
            }
            if (avgGross.value != "WAIT") {
                ctx.problems += "t=${s.tSec}s avg_gross_not_wait=${avgGross.value}"
            }
            if (avgMoving.value != "WAIT") {
                ctx.problems += "t=${s.tSec}s avg_moving_not_wait=${avgMoving.value}"
            }
            val grade = out.getValue("GRADE")
            if (grade.reason in setOf("climb", "light_climb", "steep_climb")) {
                ctx.problems += "t=${s.tSec}s grade_climb_from_idle reason=${grade.reason} value=${grade.value}"
            }
        }
    }

    private data class ScenarioResult(
        var pass: Boolean,
        val statusCounts: Map<String, Map<String, Int>>,
        val flags: MutableMap<String, Any?>,
        val problems: MutableList<String>,
    )

    private data class ScenarioContext(
        val state: RideState,
        val flags: MutableMap<String, Any?>,
        val problems: MutableList<String>,
    )

    private fun runScenario(
        name: String,
        samples: List<RideSample>,
        movingFromSec: Double? = null,
        csvFileName: String = "virtual_${name}.csv",
        auditFileName: String = "virtual_${name}_audit.txt",
        extraChecks: ((RideSample, Map<String, FieldOutput>, ScenarioContext) -> Unit)? = null,
    ): ScenarioResult {
        val root = resolveProjectRoot()
        val reportsDir = File(root, "reports").apply { mkdirs() }
        val csv = StringBuilder().appendLine("t,field,value,color,status,reason,raw")

        val state = RideState()
        val fc = FieldComputers()
        val names = listOf("SPEED", "POWER", "HR", "CADENCE", "GRADE", "GEAR")
        val statusCounts = names.associateWith { mutableMapOf<String, Int>() }.toMutableMap()
        val problems = mutableListOf<String>()
        val flags = mutableMapOf<String, Any?>()

        var lastDistance = Double.NEGATIVE_INFINITY
        var lastAvgGross = Double.NEGATIVE_INFINITY
        var lastAvgMoving = Double.NEGATIVE_INFINITY

        samples.forEach { s ->
            state.update(s)
            val outs = fc.mvp(state).associateBy { it.name }
            val ctx = ScenarioContext(state, flags, problems)

            names.forEach { n ->
                val o = outs.getValue(n)
                statusCounts[n]!!.merge(o.status.name, 1, Int::plus)
                csv.appendLine(listOf(s.tSec.toString(), n, o.value, o.color.name, o.status.name, o.reason, o.raw.toString()).joinToString(",") { it.csvEscape() })

                if (o.status == FieldStatus.INVALID) problems += "t=${s.tSec}s $n INVALID"
                if (o.reason.isBlank()) problems += "t=${s.tSec}s $n reason_blank"
            }

            if ((s.speedKmh ?: 0.0) > 90.0) problems += "t=${s.tSec}s speed_gt_90"
            if (state.distanceM + 1e-6 < lastDistance) problems += "t=${s.tSec}s distance_reset"
            lastDistance = state.distanceM

            state.avgGrossKmh?.let { a -> if (lastAvgGross.isFinite() && a < lastAvgGross - 2.0) problems += "t=${s.tSec}s avg_gross_reset"; lastAvgGross = a }
            state.avgMovingKmh?.let { a -> if (lastAvgMoving.isFinite() && a < lastAvgMoving - 2.0) problems += "t=${s.tSec}s avg_moving_reset"; lastAvgMoving = a }

            if ((s.speedKmh ?: 0.0) < 3.0) {
                val g = outs.getValue("GRADE").value
                if (g.startsWith("+") && g != "+0.0" && g != "0") problems += "t=${s.tSec}s grade_spike_idle=$g"
            }

            if (movingFromSec != null && s.tSec >= movingFromSec) {
                listOf("SPEED", "POWER", "HR", "CADENCE").forEach { n ->
                    if (outs.getValue(n).status == FieldStatus.NO_DATA) problems += "t=${s.tSec}s $n NO_DATA_after_move"
                }
            }

            extraChecks?.invoke(s, outs, ctx)
        }

        File(reportsDir, csvFileName).writeText(csv.toString())

        val pass = problems.isEmpty()
        val audit = buildString {
            appendLine("scenario=$name")
            appendLine("ticks=${samples.size}")
            appendLine("final_distance_m=${"%.2f".format(state.distanceM)}")
            appendLine("final_avg_gross=${"%.4f".format(state.avgGrossKmh ?: Double.NaN)}")
            appendLine("final_avg_moving=${"%.4f".format(state.avgMovingKmh ?: Double.NaN)}")
            appendLine("status_counts:")
            names.forEach { appendLine("$it: ${statusCounts[it]!!.toSortedMap()}") }
            appendLine("problems:")
            if (problems.isEmpty()) appendLine("(none)") else problems.take(20).forEach { appendLine(it) }
            appendLine("PASS=${if (pass) "YES" else "NO"}")
        }
        File(reportsDir, auditFileName).writeText(audit)

        assertTrue("Scenariusz $name FAIL. Szczegoly: reports/$auditFileName", pass)
        return ScenarioResult(pass, statusCounts.mapValues { it.value.toMap() }, flags, problems)
    }

    private fun String.csvEscape(): String {
        if (!contains(",") && !contains("\"") && !contains("\n")) return this
        return "\"${replace("\"", "\"\"")}\""
    }

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (cwd.name == "app") cwd.parentFile else cwd
    }
}
