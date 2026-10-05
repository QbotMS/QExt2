package com.qext2.primary.kokpit

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.model.StatsRideSnapshot
import com.qext2.primary.model.SurfaceType
import com.qext2.primary.surface.SurfaceBridge
import com.qext2.primary.weather.RainForecastClient
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QExt2KokpitNav"

/**
 * KOKPIT nawigacja (test) — gorne pole ekranu z mapa: KOMUNIKATY, km/zostalo/zmrok/ETA, pasek trasy
 * z nawierzchnia przed toba, nachylenie, przewyzszenie, temp+opad, wiatr. Rysowane jako obrazek.
 * Demo: ten sam przelacznik co STATS v2 ("dane demo").
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
class KokpitNavDataType : DataTypeImpl("qext2", "qext2-kokpit-nav") {

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        QExt2PrimaryExtension.instance?.onFieldVisible()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        AthleteDataStore.init(context)
        val w = config.viewSize.first.coerceAtLeast(160)
        val h = config.viewSize.second.coerceAtLeast(60)
        Log.i(TAG, "QEXT_KOKPIT_NAV_VIEW size=${w}x$h")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emitter.updateView(RemoteViews(context.packageName, R.layout.field_stats_v2))

        val rotator = RouteMessageRotator()

        fun emit(data: KokpitNavData) {
            val bmp = try { KokpitNavRenderer.render(w, h, data) } catch (e: Exception) {
                Log.w(TAG, "QEXT_KOKPIT_NAV_RENDER_FAIL msg=${e.message}", e); null
            } ?: return
            val rv = RemoteViews(context.packageName, R.layout.field_stats_v2)
            rv.setImageViewBitmap(R.id.iv_stats_v2, bmp)
            emitter.updateView(rv)
        }

        scope.launch {
            if (AthleteDataStore.loadStatsV2Demo()) {
                while (isActive) {
                    val d = withContext(Dispatchers.Default) { KokpitNavDemo.at(System.currentTimeMillis()) }
                    emit(d)
                    delay(2000L)
                }
                return@launch
            }
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            var last: KokpitNavData? = null
            var lastMs = 0L
            ext.aggregatorFlow
                .flatMapLatest { agg -> (agg?.statsSnapshot ?: flowOf(StatsRideSnapshot())).map { agg to it } }
                .collect { (agg, snap) ->
                    val d = try { toData(agg, snap, rotator) } catch (e: Exception) {
                        Log.w(TAG, "QEXT_KOKPIT_NAV_DATA_FAIL msg=${e.message}"); null
                    } ?: return@collect
                    val now = System.currentTimeMillis()
                    if (d == last || now - lastMs < 1000L) return@collect
                    last = d; lastMs = now
                    emit(d)
                }
        }
        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
        }
    }

    private fun cls(t: SurfaceType) = when (t) { SurfaceType.PAVED -> SurfClass.PAVED; SurfaceType.GRAVEL -> SurfClass.GRAVEL; SurfaceType.LOOSE -> SurfClass.LOOSE }

    private fun toData(agg: RideDataAggregator?, s: StatsRideSnapshot, rotator: RouteMessageRotator): KokpitNavData {
        val now = System.currentTimeMillis()
        val pos = s.distanceKm.coerceAtLeast(0f)
        val dtdKm = ((agg?.getDistanceToDestinationMeters() ?: 0.0) / 1000.0).toFloat()
        val total = if (s.hasRoute && dtdKm > 0.05f) pos + dtdKm else null
        val segs = SurfaceBridge.segmentsSnapshot().map { SurfSeg(it.kmStart, it.kmEnd, cls(it.surface)) }
        val climbs = (agg?.getNavClimbs() ?: emptyList()).map { ClimbInfo((it.startDistance / 1000.0).toFloat(), (it.length / 1000.0).toFloat(), it.grade.toFloat()) }
        val descent = agg?.getSteepDescentAhead()?.let { DescentInfo((it.first / 1000.0).toFloat(), it.second.toFloat()) }
        val fresh = s.weatherFresh
        val cond = (s.weatherCondition ?: "").lowercase()
        val rainNow = if (!fresh) null else s.weatherRain1hMm?.takeIf { it > 0f }
            ?: if (cond.contains("rain") || cond.contains("drizzle")) 0.2f else null
        val rf = agg?.getRainForecast()
        val rfMin = rf?.minutes
        val rainSoon = if (rf != null && rfMin != null && RainForecastClient.isFresh(rf)) RainSoon(rfMin, rf.probPct, rf.mmPerH) else null
        // nastepne zdarzenie: swit przed wschodem, zmrok w dzien, po zmroku swit nastepnego dnia (SDK: CIVIL_DAWN / CIVIL_DUSK)
        val duskRaw = agg?.getCivilDuskMs() ?: 0L
        val dawnRaw = agg?.getCivilDawnMs() ?: 0L
        val (twMs, twLabel) = when {
            dawnRaw > now -> dawnRaw to "świt"
            duskRaw > now -> duskRaw to "zmrok"
            dawnRaw > 0L -> (dawnRaw + 86_400_000L) to "świt"
            else -> 0L to "zmrok"
        }
        val dusk = if (twLabel == "zmrok" && twMs > now) twMs else null
        val eta = if (s.etaModelReady && s.etaTimestamp > 0L) s.etaTimestamp else null
        val pois = SurfaceBridge.poisSnapshot().map { PoiInfo(it.km, it.cat, it.name, it.today) }
        val cands = RouteMessageEngine.candidates(
            RouteMsgInput(
                nowMs = now, hasRoute = s.hasRoute, posKm = pos, surfaces = segs, climbs = climbs, descent = descent,
                rainNowMmH = rainNow, rainSoon = rainSoon, carbBalanceG = if (s.carbModelReady) s.carbBalanceG else null,
                duskMs = dusk ?: 0L, etaMs = eta ?: 0L, pois = pois,
            )
        )
        val msg = rotator.next(now, cands)
        val hw = agg?.getHeadwindRel()
        val ahead = if (segs.isNotEmpty() && total != null) segs.sortedBy { it.kmStart }.filter { it.kmEnd > pos }
            .map { (it.kmEnd - maxOf(it.kmStart, pos)) to RouteMessageEngine.surfColor(it.surface) } else null
        return KokpitNavData(
            msg = msg, doneKm = pos, totalKm = total, leftKm = if (total != null) dtdKm else null,
            duskMs = if (twMs > now) twMs else null, twilightLabel = twLabel, etaMs = eta, ahead = ahead,
            stopsKm = agg?.getLongStopsKm()?.map { it.toFloat() } ?: emptyList(),
            gradePct = agg?.getEffectiveGrade()?.toFloat(),
            ascDone = if (s.routeClimbSourceReady) s.ascentDoneM else null,
            ascLeft = if (s.routeClimbSourceReady) s.ascentLeftM else null,
            tempC = if (fresh) s.weatherTemperatureC else null,
            rainNowMmH = rainNow, rainSoon = rainSoon,
            windMps = hw?.second ?: if (fresh) s.weatherWindSpeedMps else null,
            windDirDeg = if (fresh) agg?.getWeatherWindDirDeg() else null,
            windRelDeg = hw?.first,
        )
    }
}

/** Dane symulacyjne dla KOKPIT nawigacja: co 8 s inny komunikat. */
object KokpitNavDemo {
    private val msgs = listOf(
        RouteMsg(MsgKind.SURFACE, "za 1,2 km:", "szuter 3,4 km", "#D9A04E"),
        RouteMsg(MsgKind.CLIMB, "za 2,1 km: podjazd", "1,8 km · 6%", "#FB923C"),
        RouteMsg(MsgKind.DESCENT, "za 0,8 km: zjazd", "-9% szuter", "#F87171"),
        RouteMsg(MsgKind.RAIN, "deszcz za 20 min:", "60%", "#60A5FA"),
        RouteMsg(MsgKind.FUEL, "zjedz:", "-35 g", "#E9A23B"),
        RouteMsg(MsgKind.DUSK, "meta po zmroku:", "zmrok 18:42", "#F87171"),
        RouteMsg(MsgKind.POI, "za 1,4 km: sklep", "Biedronka · 05:00–23:00", "#4ADE80"),
    )
    fun at(now: Long): KokpitNavData {
        val t = ((now / 1000L) % 120L).toFloat(); val f = t / 120f
        val total = 164f; val done = total * f
        val segs = listOf(92f to "#C9D2DC", 61f to "#D9A04E", 11f to "#E0563B")
        var rem = done
        val ahead = segs.mapNotNull { (len, col) -> val r = len - rem; rem = maxOf(0f, rem - len); if (r > 0f) r to col else null }
        val m = msgs[((now / 8000L) % msgs.size).toInt()]
        return KokpitNavData(
            msg = m, doneKm = done, totalKm = total, leftKm = total - done,
            duskMs = now + 95 * 60_000L, etaMs = now + (((total - done) / 19f) * 3600_000f).toLong(),
            ahead = ahead, gradePct = (kotlin.math.sin(t / 6f) * 9f), ascDone = (1280 * f).toInt(), ascLeft = (1280 * (1 - f)).toInt(),
            tempC = 24f, rainNowMmH = null, rainSoon = RainSoon(40, 60, 1.2f), windMps = 4f, windDirDeg = 300,
            windRelDeg = ((now / 1000L) * 6 % 360).toInt(), stopsKm = listOf(22f, 41.5f).filter { it < done }, demo = true,
        )
    }
}
