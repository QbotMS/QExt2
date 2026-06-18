package com.qext2.primary.model

/**
 * Klasyfikacja nawierzchni trasy.
 * Spójna z RouteGraph surfacetype stream:
 *   0.0 = PAVED, 1.0 = GRAVEL, 2.0 = LOOSE
 * i z QBot pipeline nawierzchni (paved/gravel/loose).
 */
enum class SurfaceType(val routeGraphValue: Float) {
    PAVED(0.0f),
    GRAVEL(1.0f),
    LOOSE(2.0f);

    companion object {
        fun fromRouteGraph(value: Float): SurfaceType = when {
            value >= 1.5f -> LOOSE
            value >= 0.5f -> GRAVEL
            else -> PAVED
        }

        fun fromQBot(raw: String): SurfaceType = when (raw.lowercase()) {
            "gravel", "unpaved", "dirt", "compacted", "fine_gravel" -> GRAVEL
            "loose", "sand", "grass", "mud", "snow", "ice" -> LOOSE
            else -> PAVED
        }
    }
}
