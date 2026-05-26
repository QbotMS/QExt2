package com.qext2.primary.active

class NoSdkClimbLogGate {
    private var lastNoSdkClimbsRouteKey: String? = null

    fun shouldLogNoSdkClimbs(routeKey: String): Boolean {
        val normalized = routeKey.ifBlank { "route:unknown" }
        if (lastNoSdkClimbsRouteKey == normalized) return false
        lastNoSdkClimbsRouteKey = normalized
        return true
    }

    fun onSdkClimbsAvailable(routeKey: String) {
        val normalized = routeKey.ifBlank { "route:unknown" }
        if (lastNoSdkClimbsRouteKey == normalized) {
            lastNoSdkClimbsRouteKey = null
        }
    }
}
