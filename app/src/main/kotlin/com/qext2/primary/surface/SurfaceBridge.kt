package com.qext2.primary.surface

import com.qext2.primary.model.SurfaceType

/**
 * Singleton bridge miedzy RideDataAggregator a SurfaceProfileCache.
 * Rozwiazuje circular dependency:
 *   RideDataAggregator -> SurfaceBridge <- QExt2PrimaryExtension -> SurfaceProfileCache
 */
object SurfaceBridge {
    @Volatile private var cache: SurfaceProfileCache? = null

    fun init(c: SurfaceProfileCache) { cache = c }

    fun onNavigationState(state: io.hammerhead.karooext.models.OnNavigationState, routeName: String?) {
        cache?.onNavigationState(state, routeName)
    }

    fun onRouteGraphSurface(value: Float) {
        cache?.onRouteGraphSurface(value)
    }

    fun currentSurface(kmAlongRoute: Float): SurfaceType =
        cache?.surfaceAt(kmAlongRoute) ?: SurfaceType.PAVED
}
