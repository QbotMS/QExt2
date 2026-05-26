package com.qext2.primary.field

import org.junit.Assert.assertTrue
import org.junit.Test
import pl.qbot.karoo.core.FieldStatus
import java.io.File

class StatsSdkFirstGateTest {

    @Test
    fun statsSdkFirstGate() {
        val problems = mutableListOf<String>()

        val tssMissing = StatsAdvancedFieldPolicy.sdkTss(0f)
        checkDecision(tssMissing, expectedValue = "WAIT", expectedStatus = FieldStatus.NO_DATA, expectedReason = "sdk_field_not_available", name = "TSS_missing", problems = problems)

        val tssReady = StatsAdvancedFieldPolicy.sdkTss(156.4f)
        checkDecision(tssReady, expectedValue = "156", expectedStatus = FieldStatus.OK, expectedReason = "sdk_training_stress_score", name = "TSS_ready", problems = problems)

        val kcalMissing = StatsAdvancedFieldPolicy.sdkCalories(0)
        checkDecision(kcalMissing, expectedValue = "WAIT", expectedStatus = FieldStatus.NO_DATA, expectedReason = "sdk_field_not_available", name = "KCAL_missing", problems = problems)

        val kcalReady = StatsAdvancedFieldPolicy.sdkCalories(842)
        checkDecision(kcalReady, expectedValue = "842", expectedStatus = FieldStatus.OK, expectedReason = "sdk_calories", name = "KCAL_ready", problems = problems)

        val upNoRoute = StatsAdvancedFieldPolicy.ascentDone(false, false, 0, 0)
        checkDecision(upNoRoute, expectedValue = "WAIT", expectedStatus = FieldStatus.NO_DATA, expectedReason = "route_not_loaded", name = "UP_no_route", problems = problems)

        val leftFlat = StatsAdvancedFieldPolicy.ascentLeft(true, true, 0, 0)
        checkDecision(leftFlat, expectedValue = "0", expectedStatus = FieldStatus.OK, expectedReason = "flat_route_or_zero_ascent", name = "LEFT_flat", problems = problems)

        val batNoSource = StatsAdvancedFieldPolicy.batteryDrain(false, false, null, null)
        checkDecision(batNoSource, expectedValue = "WAIT", expectedStatus = FieldStatus.NO_DATA, expectedReason = "battery_source_not_connected", name = "BAT_DRAIN_no_source", problems = problems)

        val batDrainReady = StatsAdvancedFieldPolicy.batteryDrain(true, true, 4.2f, "headunit_polling")
        checkDecision(batDrainReady, expectedValue = "4.2", expectedStatus = FieldStatus.OK, expectedReason = "battery_drain_from_headunit", name = "BAT_DRAIN_ready", problems = problems)

        val batLeftReady = StatsAdvancedFieldPolicy.batteryLeft(true, true, 3 * 3600L + 5 * 60L, "headunit_polling")
        checkDecision(batLeftReady, expectedValue = "3:05", expectedStatus = FieldStatus.OK, expectedReason = "battery_runtime_from_headunit", name = "BAT_LEFT_ready", problems = problems)

        val nanTss = StatsAdvancedFieldPolicy.sdkTss(Float.NaN)
        if (nanTss.value != "WAIT") problems += "TSS_nan_should_not_render"
        val infTss = StatsAdvancedFieldPolicy.sdkTss(Float.POSITIVE_INFINITY)
        if (infTss.value != "WAIT") problems += "TSS_inf_should_not_render"
        val negKcal = StatsAdvancedFieldPolicy.sdkCalories(-1)
        if (negKcal.value != "WAIT") problems += "KCAL_negative_should_not_render"

        val blocked = listOf(
            "ETA" to StatsAdvancedFieldPolicy.waitNoData("sdk_field_not_available"),
            "CARB" to StatsAdvancedFieldPolicy.waitNoData("sdk_field_not_available"),
            "CARB_BALANCE" to StatsAdvancedFieldPolicy.waitNoData("sdk_field_not_available"),
            "FLUID" to StatsAdvancedFieldPolicy.waitNoData("sdk_field_not_available"),
            "WPRIME" to StatsAdvancedFieldPolicy.waitNoData("sdk_source_missing"),
            "RSRV" to StatsAdvancedFieldPolicy.waitNoData("sdk_source_missing"),
        )
        blocked.forEach { (name, d) ->
            if (d.value != "WAIT") problems += "$name must remain WAIT"
            if (d.reason.isBlank()) problems += "$name WAIT must include reason"
        }

        val pass = problems.isEmpty()
        writeAudit(pass, problems, blocked, tssReady, kcalReady, batDrainReady, batLeftReady, leftFlat)
        assertTrue("StatsSdkFirstGateTest FAIL. Szczegoly: reports/stats_sdk_first_audit.txt", pass)
    }

    private fun checkDecision(
        decision: AdvancedFieldDecision,
        expectedValue: String,
        expectedStatus: FieldStatus,
        expectedReason: String,
        name: String,
        problems: MutableList<String>,
    ) {
        if (decision.value != expectedValue) problems += "$name value=${decision.value} expected=$expectedValue"
        if (decision.status != expectedStatus) problems += "$name status=${decision.status} expected=$expectedStatus"
        if (decision.reason != expectedReason) problems += "$name reason=${decision.reason} expected=$expectedReason"
        if (decision.value == "NaN" || decision.value == "Infinity" || decision.value == "-Infinity") {
            problems += "$name rendered_non_finite=${decision.value}"
        }
    }

    private fun writeAudit(
        pass: Boolean,
        problems: List<String>,
        blocked: List<Pair<String, AdvancedFieldDecision>>,
        tss: AdvancedFieldDecision,
        kcal: AdvancedFieldDecision,
        batDrain: AdvancedFieldDecision,
        batLeft: AdvancedFieldDecision,
        leftFlat: AdvancedFieldDecision,
    ) {
        val root = resolveProjectRoot()
        val reports = File(root, "reports").apply { mkdirs() }
        val out = File(reports, "stats_sdk_first_audit.txt")
        val content = buildString {
            appendLine("Stats SDK-first audit")
            appendLine("TSS=${tss.value}/${tss.status}/${tss.reason}/${tss.source}")
            appendLine("KCAL=${kcal.value}/${kcal.status}/${kcal.reason}/${kcal.source}")
            appendLine("UP_LEFT_FLAT=${leftFlat.value}/${leftFlat.status}/${leftFlat.reason}/${leftFlat.source}")
            appendLine("BAT_DRAIN=${batDrain.value}/${batDrain.status}/${batDrain.reason}/${batDrain.source}")
            appendLine("BAT_LEFT=${batLeft.value}/${batLeft.status}/${batLeft.reason}/${batLeft.source}")
            appendLine("BLOCKED_FIELDS:")
            blocked.forEach { (name, d) -> appendLine("$name=${d.value}/${d.status}/${d.reason}") }
            appendLine("PROBLEMS:")
            if (problems.isEmpty()) appendLine("(none)") else problems.forEach { appendLine(it) }
            appendLine("PASS=${if (pass) "YES" else "NO"}")
        }
        out.writeText(content)
    }

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (cwd.name == "app") cwd.parentFile else cwd
    }
}
