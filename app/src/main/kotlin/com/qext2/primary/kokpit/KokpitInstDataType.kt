package com.qext2.primary.kokpit

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.model.PrimaryRideSnapshot
import com.qext2.primary.model.StatsRideSnapshot
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "QExt2KokpitInst"

/**
 * KOKPIT instrumenty (test) — dolne pole ekranu z mapa: polkole mocy (strefy wg CP, CPe5) i predkosci
 * (srednia), tetno ze strefa i srednia, W' bal, kadencja ze strefa optymalna i srednia, bieg z kaseta
 * i zalecana koronka. Rysowane jako obrazek. Demo: przelacznik "dane demo".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
class KokpitInstDataType : DataTypeImpl("qext2", "qext2-kokpit-inst") {

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        QExt2PrimaryExtension.instance?.onFieldVisible()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        AthleteDataStore.init(context)
        val w = config.viewSize.first.coerceAtLeast(200)
        val h = config.viewSize.second.coerceAtLeast(80)
        Log.i(TAG, "QEXT_KOKPIT_INST_VIEW size=${w}x$h")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emitter.updateView(RemoteViews(context.packageName, R.layout.field_stats_v2))

        fun emit(data: KokpitInstData) {
            val bmp = try { KokpitInstRenderer.render(w, h, data) } catch (e: Exception) {
                Log.w(TAG, "QEXT_KOKPIT_INST_RENDER_FAIL msg=${e.message}", e); null
            } ?: return
            val rv = RemoteViews(context.packageName, R.layout.field_stats_v2)
            rv.setImageViewBitmap(R.id.iv_stats_v2, bmp)
            emitter.updateView(rv)
        }

        // srednie tetno liczone lokalnie (tylko w ruchu)
        var hrSum = 0L
        var hrN = 0L

        scope.launch {
            if (AthleteDataStore.loadStatsV2Demo()) {
                while (isActive) {
                    emit(KokpitInstDemo.at(System.currentTimeMillis()))
                    delay(1000L)
                }
                return@launch
            }
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            var last: KokpitInstData? = null
            var lastMs = 0L
            ext.aggregatorFlow
                .flatMapLatest { agg ->
                    if (agg == null) flowOf(Triple<RideDataAggregator?, PrimaryRideSnapshot, StatsRideSnapshot>(null, PrimaryRideSnapshot(), StatsRideSnapshot()))
                    else combine(agg.snapshot, agg.statsSnapshot) { p, s -> Triple<RideDataAggregator?, PrimaryRideSnapshot, StatsRideSnapshot>(agg, p, s) }
                }
                .collect { (agg, p, s) ->
                    if (p.hrFreshnessMs < 12_000L && p.hr > 40 && p.speedKmh > 3.0) { hrSum += p.hr; hrN++ }
                    val d = try { toData(agg, p, s, if (hrN > 30) (hrSum / hrN).toInt() else null) } catch (e: Exception) {
                        Log.w(TAG, "QEXT_KOKPIT_INST_DATA_FAIL msg=${e.message}"); null
                    } ?: return@collect
                    val now = System.currentTimeMillis()
                    if (d == last || now - lastMs < 500L) return@collect
                    last = d; lastMs = now
                    emit(d)
                }
        }
        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
        }
    }

    private fun hrZone(hr: Int): Int? {
        val lthr = AthleteDataStore.loadLthrBpm()
        if (hr <= 0 || lthr <= 0) return null
        val pct = hr.toFloat() / lthr
        return when { pct < 0.81f -> 1; pct < 0.90f -> 2; pct < 0.95f -> 3; pct < 1.06f -> 4; else -> 5 }
    }

    private fun toData(agg: RideDataAggregator?, p: PrimaryRideSnapshot, s: StatsRideSnapshot, hrAvg: Int?): KokpitInstData {
        val power = if (p.powerFreshnessMs < 8_000L) p.power3s else null
        val cp = s.cpEffW.takeIf { it > 0f } ?: agg?.getEffectiveLtpWatts()?.takeIf { it > 0f }
        val speed = if (p.speedFreshnessMs < 12_000L) p.speedKmh.toFloat() else null
        val hr = if (p.hrFreshnessMs < 12_000L && p.hr > 0) p.hr else null
        val cad = if (p.cadenceFreshnessMs < 8_000L) p.cadence else null
        val pc = agg?.getPacingContext()
        val low = pc?.optCadenceLow ?: 80
        val high = pc?.optCadenceHigh ?: 95
        val front = if (p.gearFreshnessMs < 15_000L && p.gearFront > 0) p.gearFront else null
        val rear = if (p.gearFreshnessMs < 15_000L && p.gearRear > 0) p.gearRear else null
        val cogs = (agg?.getCassetteCogs() ?: emptyList()).sorted()
        // zalecana koronka: przy tej samej predkosci kadencja ~ liczba zebow tylnej koronki
        val rec = if (cad != null && cad > 30 && rear != null && cogs.isNotEmpty()) {
            val target = (low + high) / 2f
            val want = rear * target / cad
            cogs.minByOrNull { abs(it - want) }
        } else null
        return KokpitInstData(
            powerW = power,
            cpW = cp,
            cpe5W = s.cpEffLinW.takeIf { it > 0f },
            powerColor = p.powerColor,
            speedKmh = speed,
            avgSpeedKmh = if (s.movingElapsedSec > 60L) s.distanceKm / (s.movingElapsedSec / 3600f) else null,
            speedColor = p.speedColor,
            hr = hr,
            hrAvg = hrAvg,
            hrZone = hr?.let { hrZone(it) },
            wbalPct = s.wBalancePercent.takeIf { it >= 0 },
            cadence = cad,
            cadenceAvg = s.cadenceAvg.takeIf { it > 0 },
            optCadLow = low, optCadHigh = high,
            gearFront = front, gearRear = rear,
            cogs = cogs, recCog = rec,
        )
    }
}

/** Dane symulacyjne dla KOKPIT instrumenty (cykl 60 s). */
object KokpitInstDemo {
    private val cogs = listOf(10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 52)
    fun at(now: Long): KokpitInstData {
        val t = ((now / 1000L) % 60L).toFloat()
        val f = t / 60f
        val pw = (120 + 260 * kotlin.math.abs(kotlin.math.sin(t / 9f))).toInt()
        val sp = 14f + 22f * kotlin.math.abs(kotlin.math.sin(t / 13f))
        val hr = 110 + (60 * f).toInt()
        val cad = 70 + (25 * kotlin.math.abs(kotlin.math.sin(t / 5f))).toInt()
        val rear = cogs[(3 + (t / 6f).toInt()) % cogs.size]
        val ratio = hr / 165f
        val z = when { ratio < 0.81f -> 1; ratio < 0.90f -> 2; ratio < 0.95f -> 3; ratio < 1.06f -> 4; else -> 5 }
        return KokpitInstData(
            powerW = pw, cpW = 250f, cpe5W = 214f, powerColor = if (pw > 300) android.graphics.Color.parseColor("#F87171") else android.graphics.Color.parseColor("#4ADE80"),
            speedKmh = sp, avgSpeedKmh = 17.5f, speedColor = android.graphics.Color.parseColor("#F2C230"),
            hr = hr, hrAvg = 128, hrZone = z, wbalPct = (100 - 90 * f).toInt(),
            cadence = cad, cadenceAvg = 82, optCadLow = 80, optCadHigh = 95,
            gearFront = 36, gearRear = rear, cogs = cogs, recCog = cogs.minByOrNull { kotlin.math.abs(it - rear * 87.5f / cad) },
            demo = true,
        )
    }
}
