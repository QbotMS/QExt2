package com.qext2.primary.active

import com.qext2.primary.engine.KarooClimb
import kotlin.math.abs

data class ActiveClimbResolution(
    val state: ClimbState?,
    val reason: String,
)

object ActiveClimbResolver {

    fun resolve(
        nowMs: Long,
        fakeMode: Boolean,
        hasRoute: Boolean,
        navClimbs: List<KarooClimb>,
        distanceMeters: Double,
        distanceToDestinationMeters: Double,
        ascentLeftM: Int,
        effectiveGrade: Double,
    ): ActiveClimbResolution {
        if (!hasRoute) {
            return ActiveClimbResolution(state = null, reason = "no_route")
        }

        if (fakeMode) {
            val fakeDistance = if (effectiveGrade > 2.0) 0.0 else distanceToDestinationMeters
            return ActiveClimbResolution(
                state = ClimbState(
                    hasRoute = true,
                    distanceToClimbM = fakeDistance,
                    climbElevationM = ascentLeftM,
                    avgGradePercent = effectiveGrade,
                    nowMs = nowMs,
                ),
                reason = "fake_synthetic",
            )
        }

        if (navClimbs.isEmpty()) {
            return ActiveClimbResolution(state = null, reason = "no_sdk_climbs")
        }

        val candidate = navClimbs
            .filter { it.startDistance + it.length >= distanceMeters - 25.0 }
            .minByOrNull { abs(it.startDistance - distanceMeters) }
            ?: return ActiveClimbResolution(state = null, reason = "no_active_sdk_climb")

        return ActiveClimbResolution(
            state = ClimbState(
                hasRoute = true,
                distanceToClimbM = candidate.startDistance - distanceMeters,
                climbElevationM = candidate.totalElevation.toInt().coerceAtLeast(0),
                avgGradePercent = effectiveGrade,
                nowMs = nowMs,
            ),
            reason = "sdk_climb",
        )
    }
}
