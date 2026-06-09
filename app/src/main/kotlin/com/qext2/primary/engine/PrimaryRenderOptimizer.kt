package com.qext2.primary.engine

import android.graphics.Color
import android.util.Log
import com.qext2.primary.model.PrimaryRideSnapshot
import com.qext2.primary.util.QExt2DebugConfig
import java.io.File

private const val TAG = "QExt2Render"

class PrimaryRenderOptimizer {

    var enabled = true
    var fileLoggingEnabled = true

    private companion object {
        const val MIN_INTERVAL_MOVING_MS = 300L
        const val MIN_INTERVAL_STATIONARY_MS = 500L
        const val REPORT_INTERVAL_MS = 60_000L
    }

    private var totalRenders = 0L
    private var dedupeRejects = 0L
    private var throttleRejects = 0L
    private var lastReportMs = 0L
    private val renderTimestamps = ArrayDeque<Long>(60)
    private var lastSignature = ""
    private var lastRenderMs = 0L
    private var isFirstRender = true
    private var metricsFile: File? = null

    private var lastHrText = "NO"; private var lastCadText = "NO"
    private var lastPwrText = "NO"; private var lastSpdText = "NO"
    private var lastGearText = "NO"; private var lastGradeText = "NO"
    private var lastHrColor = Color.WHITE; private var lastCadColor = Color.WHITE
    private var lastPwrColor = Color.WHITE; private var lastSpdColor = Color.WHITE
    private var lastGearColor = Color.WHITE; private var lastGradeColor = Color.WHITE

    data class RenderDecision(val shouldRender: Boolean, val reason: String)

    fun initializeMetricsFile(baseDir: File) {
        if (!fileLoggingEnabled) return
        val dir = File(baseDir, "qext2")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "primary_render_metrics.csv")
        val isNew = !file.exists()
        metricsFile = file
        if (isNew) {
            file.appendText("ts_ms,total_renders,dedupe_rejects,throttle_rejects,renders_per_min,enabled\n")
        }
    }

    fun decide(snap: PrimaryRideSnapshot, speedKmh: Double): RenderDecision {
        val now = System.currentTimeMillis()

        if (!enabled) {
            processRender(now, snap)
            return RenderDecision(true, "legacy")
        }

        if (isFirstRender) {
            processRender(now, snap)
            return RenderDecision(true, "first")
        }

        val signature = computeSignature(snap)

        if (signature == lastSignature) {
            dedupeRejects++
            return RenderDecision(false, "dedupe")
        }

        if (isFastPath(snap)) {
            processRender(now, snap)
            logAggregate(now)
            return RenderDecision(true, "fastpath")
        }

        val isMoving = speedKmh > 1.0
        val minInterval = if (isMoving) MIN_INTERVAL_MOVING_MS else MIN_INTERVAL_STATIONARY_MS
        if (now - lastRenderMs < minInterval) {
            throttleRejects++
            return RenderDecision(false, "throttle")
        }

        processRender(now, snap)
        logAggregate(now)
        return RenderDecision(true, "normal")
    }

    private fun processRender(now: Long, snap: PrimaryRideSnapshot) {
        isFirstRender = false
        lastRenderMs = now
        lastSignature = computeSignature(snap)
        lastHrText = snap.hrDisplay; lastCadText = snap.cadenceDisplay
        lastPwrText = snap.powerDisplay; lastSpdText = snap.speedDisplay
        lastGearText = snap.gearDisplay; lastGradeText = snap.gradeDisplay
        lastHrColor = snap.hrColor; lastCadColor = snap.cadenceColor
        lastPwrColor = snap.powerColor; lastSpdColor = snap.speedColor
        lastGearColor = snap.gearColor; lastGradeColor = snap.gradeColor
        totalRenders++
        renderTimestamps.addLast(now)
        while (renderTimestamps.size > 60) renderTimestamps.removeFirst()
    }

    private fun computeSignature(snap: PrimaryRideSnapshot): String {
        return "${snap.hrDisplay}|${snap.hrColor}|${snap.cadenceDisplay}|${snap.cadenceColor}|" +
                "${snap.powerDisplay}|${snap.powerColor}|${snap.speedDisplay}|${snap.speedColor}|" +
                "${snap.gearDisplay}|${snap.gearColor}|${snap.gradeDisplay}|${snap.gradeColor}|${snap.gradeBgColor}"
    }

    private fun isFastPath(snap: PrimaryRideSnapshot): Boolean {
        if (noTransition(snap.hrDisplay, lastHrText)) return true
        if (noTransition(snap.cadenceDisplay, lastCadText)) return true
        if (noTransition(snap.powerDisplay, lastPwrText)) return true
        if (noTransition(snap.speedDisplay, lastSpdText)) return true
        if (noTransition(snap.gearDisplay, lastGearText)) return true
        if (noTransition(snap.gradeDisplay, lastGradeText)) return true
        if (snap.hrColor != lastHrColor || snap.cadenceColor != lastCadColor ||
            snap.powerColor != lastPwrColor || snap.speedColor != lastSpdColor ||
            snap.gearColor != lastGearColor || snap.gradeColor != lastGradeColor) return true
        if (powerViewBin(snap.powerDisplay.length) != powerViewBin(lastPwrText.length)) return true
        if (speedViewBin(snap.speedDisplay.length) != speedViewBin(lastSpdText.length)) return true
        return false
    }

    private fun noTransition(current: String, previous: String): Boolean =
        (current == "NO") != (previous == "NO")

    private fun powerViewBin(len: Int): Int = when {
        len <= 3 -> 0
        len == 4 -> 1
        else -> 2
    }

    private fun speedViewBin(len: Int): Int = when {
        len <= 4 -> 0
        len == 5 -> 1
        else -> 2
    }

    private fun logAggregate(now: Long) {
        if (lastReportMs == 0L) { lastReportMs = now; return }
        if (now - lastReportMs < REPORT_INTERVAL_MS) return
        lastReportMs = now
        val rpm = if (renderTimestamps.size >= 2) {
            val durationMs = now - renderTimestamps.first()
            if (durationMs > 0) totalRenders.toDouble() / (durationMs / 60_000.0) else 0.0
        } else 0.0
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "STATS renders=$totalRenders dedupe=$dedupeRejects throttle=$throttleRejects " +
                "r/min=${String.format("%.1f", rpm)} enabled=$enabled")
        if (fileLoggingEnabled) {
            metricsFile?.appendText(
                "$now,$totalRenders,$dedupeRejects,$throttleRejects,${String.format("%.1f", rpm)},$enabled\n"
            )
        }
    }

    fun reset() {
        totalRenders = 0; dedupeRejects = 0; throttleRejects = 0; lastReportMs = 0L
        renderTimestamps.clear(); lastSignature = ""; lastRenderMs = 0L; isFirstRender = true
        lastHrText = "NO"; lastCadText = "NO"; lastPwrText = "NO"; lastSpdText = "NO"
        lastGearText = "NO"; lastGradeText = "NO"
        lastHrColor = Color.WHITE; lastCadColor = Color.WHITE; lastPwrColor = Color.WHITE
        lastSpdColor = Color.WHITE; lastGearColor = Color.WHITE; lastGradeColor = Color.WHITE
    }
}
