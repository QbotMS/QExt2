package pl.qbot.karoo.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VirtualReplayGateTest {

    @Test
    fun virtualReplayGate_i151099215() {
        val rootDir = resolveProjectRoot()
        val input = File(rootDir, "data/qbot_replay_i151099215_2026-05-24.json")
        require(input.exists()) { "Brak pliku replay: ${input.absolutePath}" }

        val ticks = parseTicks(input)

        val reportsDir = File(rootDir, "reports").apply { mkdirs() }
        val csvFile = File(reportsDir, "virtual_field_replay_i151099215.csv")
        val txtFile = File(reportsDir, "virtual_field_audit_i151099215.txt")

        val state = RideState()
        val fields = FieldComputers()
        val mvpNames = listOf("SPEED", "POWER", "HR", "CADENCE", "GRADE", "GEAR")

        val statusCounts = mvpNames.associateWith { mutableMapOf<String, Int>() }.toMutableMap()
        val colorCounts = mvpNames.associateWith { mutableMapOf<String, Int>() }.toMutableMap()
        val noDataTotals = mutableMapOf("POWER" to 0, "HR" to 0, "CADENCE" to 0)

        var invalidSeen = false
        var speedOverLimitSeen = false
        var distanceDecreased = false
        var avgDecreased = false
        var emptyReasonSeen = false

        var lastDistance = Double.NEGATIVE_INFINITY
        var lastAvgGross = Double.NEGATIVE_INFINITY
        var lastAvgMoving = Double.NEGATIVE_INFINITY

        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val csv = StringBuilder()
        csv.appendLine("t,field,value,color,status,reason,raw")

        for (tick in ticks) {
            val sample = tick.toRideSample()
            state.update(sample)

            val mvp = fields.mvp(state)
            val avgGrossOut = fields.avgGross(state)
            val avgMovingOut = fields.avgMoving(state)

            val outputsForCsv = mvp + listOf(avgGrossOut, avgMovingOut)
            outputsForCsv.forEach { out ->
                csv.appendLine(
                    listOf(
                        sample.tSec.toString(),
                        out.name,
                        out.value,
                        out.color.name,
                        out.status.name,
                        out.reason,
                        out.raw.toString(),
                    ).joinToString(",") { it.csvEscape() }
                )
            }

            mvp.forEach { out ->
                statusCounts[out.name]!!.merge(out.status.name, 1, Int::plus)
                colorCounts[out.name]!!.merge(out.color.name, 1, Int::plus)

                if (out.status == FieldStatus.INVALID) {
                    invalidSeen = true
                    if (problems.size < 20) problems += "t=${sample.tSec}s ${out.name} INVALID reason=${out.reason}"
                }
                if (out.reason.isBlank()) {
                    emptyReasonSeen = true
                    if (problems.size < 20) problems += "t=${sample.tSec}s ${out.name} empty_reason"
                }
                if (out.name in noDataTotals.keys && out.status == FieldStatus.NO_DATA) {
                    noDataTotals[out.name] = noDataTotals.getValue(out.name) + 1
                }
            }

            val speedKmh = sample.speedKmh ?: 0.0
            if (speedKmh > 90.0) {
                speedOverLimitSeen = true
                if (problems.size < 20) problems += "t=${sample.tSec}s SPEED>90 (${"%.2f".format(speedKmh)})"
            }

            if (state.distanceM + 1e-6 < lastDistance) {
                distanceDecreased = true
                if (problems.size < 20) problems += "t=${sample.tSec}s distance_decrease ${state.distanceM} < $lastDistance"
            }
            lastDistance = state.distanceM

            state.avgGrossKmh?.let { avg ->
                if (lastAvgGross.isFinite() && avg < lastAvgGross - 2.0) {
                    avgDecreased = true
                    if (problems.size < 20) problems += "t=${sample.tSec}s avg_gross_decrease $avg < $lastAvgGross"
                }
                lastAvgGross = avg
            }
            state.avgMovingKmh?.let { avg ->
                if (lastAvgMoving.isFinite() && avg < lastAvgMoving - 2.0) {
                    avgDecreased = true
                    if (problems.size < 20) problems += "t=${sample.tSec}s avg_moving_decrease $avg < $lastAvgMoving"
                }
                lastAvgMoving = avg
            }

        }

        csvFile.writeText(csv.toString())

        val finalAvgGross = state.avgGrossKmh ?: Double.NaN
        val finalAvgMoving = state.avgMovingKmh ?: Double.NaN
        val avgGrossRangeOk = finalAvgGross in 17.2..18.0
        val avgMovingRangeOk = finalAvgMoving in 19.0..20.0

        noDataTotals.forEach { (field, count) ->
            if (count == ticks.size) {
                if (problems.size < 20) problems += "$field NO_DATA przez cala jazde"
            }
        }

        val gearAllNoData = statusCounts["GEAR"]?.let { it[FieldStatus.NO_DATA.name] == ticks.size } == true
        if (gearAllNoData) {
            warnings += "GEAR: no gear source in replay/runtime"
        }

        val gatePass =
            !invalidSeen &&
                !speedOverLimitSeen &&
                avgGrossRangeOk &&
                avgMovingRangeOk &&
                noDataTotals.values.none { it == ticks.size } &&
                !emptyReasonSeen &&
                !distanceDecreased &&
                !avgDecreased

        val statusBlock = mvpNames.joinToString("\n") { field -> "$field: ${statusCounts[field]!!.toSortedMap()}" }
        val colorBlock = mvpNames.joinToString("\n") { field -> "$field: ${colorCounts[field]!!.toSortedMap()}" }
        val problemBlock = if (problems.isEmpty()) "(brak)" else problems.joinToString("\n")

        val audit = buildString {
            appendLine("ticks=${ticks.size}")
            appendLine("final_distance_m=${"%.2f".format(state.distanceM)}")
            appendLine("final_avg_gross=${"%.4f".format(finalAvgGross)}")
            appendLine("final_avg_moving=${"%.4f".format(finalAvgMoving)}")
            appendLine("status_counts:")
            appendLine(statusBlock)
            appendLine("color_counts:")
            appendLine(colorBlock)
            appendLine("first_20_problems:")
            appendLine(problemBlock)
            appendLine("KNOWN MISSING SOURCES:")
            if (warnings.isEmpty()) appendLine("(none)") else warnings.forEach { appendLine("- $it") }
            appendLine("PASS=${if (gatePass) "YES" else "NO"}")
        }
        txtFile.writeText(audit)

        assertTrue("Virtual replay gate FAIL. Szczegoly w ${txtFile.path}", gatePass)
    }

    private fun parseTicks(input: File): List<Map<String, Double?>> {
        val out = mutableListOf<Map<String, Double?>>()
        val keyRegex = Regex("\"([a-zA-Z0-9_]+)\"\\s*:\\s*(.+?)(,)?$")

        var inTicks = false
        var inTick = false
        var current = mutableMapOf<String, Double?>()

        input.forEachLine { line ->
            val t = line.trim()
            if (!inTicks) {
                if (t.startsWith("\"ticks\": [")) inTicks = true
                return@forEachLine
            }

            if (!inTick) {
                if (t == "{") {
                    inTick = true
                    current = mutableMapOf()
                }
                if (t == "]" || t == "],") {
                    inTicks = false
                }
                return@forEachLine
            }

            if (t == "}," || t == "}") {
                inTick = false
                out += current.toMap()
                return@forEachLine
            }

            val m = keyRegex.find(t) ?: return@forEachLine
            val key = m.groupValues[1]
            val raw = m.groupValues[2].trim()
            current[key] = parseNullableNumber(raw)
        }

        return out
    }

    private fun parseNullableNumber(raw: String): Double? {
        if (raw == "null") return null
        if (raw.startsWith("\"")) return null
        return raw.toDoubleOrNull()
    }

    private fun Map<String, Double?>.toRideSample(): RideSample {
        return RideSample(
            tSec = this["t_s"] ?: 0.0,
            speedKmh = this["speed_mps"]?.times(3.6),
            powerW = this["power_w"],
            hrBpm = this["heart_rate_bpm"],
            cadenceRpm = this["cadence_rpm"],
            altitudeM = this["altitude_m"],
            distanceM = this["distance_m"],
            gradePct = this["grade"],
        )
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
