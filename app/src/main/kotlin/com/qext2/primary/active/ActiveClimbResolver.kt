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
                    isWithinClimbBounds = effectiveGrade > 2.0,
                    climbIndex = 0,
                    nowMs = nowMs,
                ),
                reason = "fake_synthetic",
            )
        }

        if (navClimbs.isEmpty()) {
            return ActiveClimbResolution(state = null, reason = "no_sdk_climbs")
        }

        // E4.2: najpierw podjazd zawierajacy pozycje, potem najblizszy PRZED toba
        val inside = navClimbs.firstOrNull { distanceMeters >= it.startDistance && distanceMeters <= it.startDistance + it.length }
        val candidate = inside
            ?: navClimbs.filter { it.startDistance > distanceMeters }.minByOrNull { it.startDistance }
            ?: return ActiveClimbResolution(state = null, reason = "no_active_sdk_climb")
        val isWithinBounds = inside != null

        return ActiveClimbResolution(
            state = ClimbState(
                hasRoute = true,
                distanceToClimbM = (candidate.startDistance - distanceMeters).coerceAtLeast(0.0),
                climbElevationM = candidate.totalElevation.toInt().coerceAtLeast(0),   // E4.2: przewyzszenie TEGO podjazdu
                avgGradePercent = candidate.grade,
                isWithinClimbBounds = isWithinBounds,
                climbIndex = candidate.index,
                nowMs = nowMs,
            ),
            reason = "sdk_climb",
        )
    }
}
