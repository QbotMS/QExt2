package com.qext2.primary.active

import java.util.Locale

data class ClimbState(
    val hasRoute: Boolean,
    val distanceToClimbM: Double,
    val climbElevationM: Int,
    val avgGradePercent: Double,
    val isWithinClimbBounds: Boolean,
    val climbIndex: Int,
    val nowMs: Long,
)

class ClimbAnnouncementProducer(private val logger: (String) -> Unit = {}) {

    private var announcedApproachKey: Int = -1
    private var announcedActive = false
    private var announcedFinish = false
    private var onClimb = false
    private var lastResolvedIndex: Int = -1
    private var lastRejectMs = 0L

    private companion object {
        const val CLIMB_GRADE_THRESHOLD = 2.0
    }

    fun checkAndProduce(state: ClimbState): ActiveMessage? {
        if (!state.hasRoute) {
            rejectLog("REJECT reason=missingRoute")
            return null
        }
        return checkPreClimb(state) ?: checkClimbActive(state) ?: checkClimbFinish(state) ?: run {
            rejectLog("REJECT reason=noCandidate dist=${state.distanceToClimbM}m grade=${state.avgGradePercent}%")
            null
        }
    }

    private fun checkPreClimb(s: ClimbState): ActiveMessage? {
        if (s.distanceToClimbM <= 0.0) { rejectLog("REJECT reason=distanceZero"); return null }
        if (s.distanceToClimbM > 500.0) { rejectLog("REJECT reason=distanceTooFar dist=${s.distanceToClimbM}"); return null }
        if (s.climbIndex == announcedApproachKey) { rejectLog("REJECT reason=alreadyAnnouncedPre"); return null }
        announcedApproachKey = s.climbIndex
        announcedActive = false
        announcedFinish = false
        lastResolvedIndex = s.climbIndex
        logger("TRIGGER type=climb_pre dist=${s.distanceToClimbM}m")
        return ActiveMessage(
            id = "climb_pre_${s.nowMs}",
            title = "PODJAZD",
            line1 = formatLine1(s),
            line2 = null,
            severity = ActiveMessageSeverity.INFO,
            priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.RESUME_IF_STILL_VALID,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 8_000L,
        )
    }

    private fun checkClimbActive(s: ClimbState): ActiveMessage? {
        if (!s.isWithinClimbBounds) {
            onClimb = false
            rejectLog("REJECT reason=notWithinClimbBounds grade=${s.avgGradePercent}")
            return null
        }
        if (!onClimb) {
            onClimb = true
            announcedActive = false
            announcedFinish = false
        }
        announcedActive = true
        return null  // in-climb messaging handled by ClimbPacingProducer
    }

    private fun checkClimbFinish(s: ClimbState): ActiveMessage? {
        if (announcedFinish) { rejectLog("REJECT reason=alreadyAnnouncedFinish"); return null }
        if (!onClimb && !announcedActive) { rejectLog("REJECT reason=notOnClimb"); return null }
        if (s.isWithinClimbBounds) { rejectLog("REJECT reason=stillWithinClimbBounds"); return null }
        announcedFinish = true
        onClimb = false
        logger("TRIGGER type=climb_finish elev=${s.climbElevationM}m")
        return ActiveMessage(
            id = "climb_finish_${s.nowMs}",
            title = "PODJAZD DONE",
            line1 = "↑${s.climbElevationM}m",
            line2 = null,
            severity = ActiveMessageSeverity.INFO,
            priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 8_000L,
        )
    }

    private fun formatLine1(state: ClimbState): String {
        val dist = if (state.distanceToClimbM < 1000) {
            String.format(Locale.US, "%.0f m", state.distanceToClimbM)
        } else {
            String.format(Locale.US, "%.1f km", state.distanceToClimbM / 1000.0)
        }
        val grade = state.avgGradePercent.toInt()
        return "$dist ↑${state.climbElevationM}m ${if (grade >= 0) "+$grade" else "$grade"}%"
    }

    private fun formatActiveLine1(state: ClimbState): String {
        val remaining = state.climbElevationM
        val dist = if (state.distanceToClimbM < 1000) {
            String.format(Locale.US, "%.0f m", state.distanceToClimbM)
        } else {
            String.format(Locale.US, "%.1f km", state.distanceToClimbM / 1000.0)
        }
        return "$dist ↑${remaining}m"
    }

    fun reset() {
        announcedApproachKey = -1
        announcedActive = false
        announcedFinish = false
        onClimb = false
        lastResolvedIndex = -1
    }

    private fun rejectLog(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastRejectMs < 15_000L) return
        lastRejectMs = now
        logger(msg)
    }
}
