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
/** forceLive = wersja produkcyjna: zawsze dane z jazdy (bez demo). */
class KokpitInstDataType(typeId: String = "qext2-kokpit-inst", private val forceLive: Boolean = false) : DataTypeImpl("qext2", typeId) {

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

        // trendy srednich (zmiana w ~10 min)
        val trCp = Trend(3f); val trSpd = Trend(0.3f); val trHr = Trend(2f); val trCad = Trend(2f)
        // srednie tetno liczone lokalnie (tylko w ruchu)
        var hrSum = 0L
        var hrN = 0L

        scope.launch {
            if (!forceLive && AthleteDataStore.loadStatsV2Demo()) {
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
                    val d = try { toData(agg, p, s, if (hrN > 30) (hrSum / hrN).toInt() else null).let { dd ->
                        val now = System.currentTimeMillis()
                        dd.copy(cpTrend = trCp.push(now, dd.cpe5W), avgSpeedTrend = trSpd.push(now, dd.avgSpeedKmh),
                            hrAvgTrend = trHr.push(now, dd.hrAvg?.toFloat()), cadAvgTrend = trCad.push(now, dd.cadenceAvg?.toFloat()))
                    } } catch (e: Exception) {
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
            avgSpeedKmh = (if (s.movingElapsedSec > 60L) s.distanceKm / (s.movingElapsedSec / 3600f) else null)?.takeIf { it >= 1f },
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
            hrShowZone = AthleteDataStore.loadHrZoneMode(),
            powerCeilingW = pc?.takeIf { it.isActive }?.ceilingW,
            hrDriftLevel = (agg?.getHrDecouplingPct() ?: 0f).let { if (it >= 10f) 2 else if (it >= 6f) 1 else 0 },
        )
    }
}

/** Trend wartosci: porownanie z probka sprzed ~10 min (probki co 30 s); prog = minimalna zmiana. */
class Trend(private val threshold: Float) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    fun push(now: Long, v: Float?): Int {
        if (v == null) return 0
        if (samples.isEmpty() || now - samples.last().first >= 30_000L) samples.addLast(now to v)
        while (samples.size > 1 && now - samples.first().first > 11 * 60_000L) samples.removeFirst()
        val old = samples.firstOrNull() ?: return 0
        if (now - old.first < 5 * 60_000L) return 0
        val diff = v - old.second
        return when { diff > threshold -> 1; diff < -threshold -> -1; else -> 0 }
    }
}

/** Dane symulacyjne: zwykla jazda w normie; co minute 10 s stanu alarmowego (moc nad pulapem, Z5, niskie W'). */
object KokpitInstDemo {
    private val cogs = listOf(10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 52)
    fun at(now: Long): KokpitInstData {
        val t = ((now / 1000L) % 60L).toFloat()
        val alarm = t >= 50f
        val wave = kotlin.math.sin(t / 7f)
        val pw = if (alarm) 330 else (195 + 25 * wave).toInt()
        val sp = 24f + 4f * kotlin.math.sin(t / 11f)
        val hr = if (alarm) 172 else (136 + 6 * wave).toInt()
        val cad = (84 + 5 * kotlin.math.sin(t / 5f)).toInt()
        val rear = cogs[(4 + (t / 15f).toInt()) % cogs.size]
        val ratio = hr / 165f
        val z = when { ratio < 0.81f -> 1; ratio < 0.90f -> 2; ratio < 0.95f -> 3; ratio < 1.06f -> 4; else -> 5 }
        return KokpitInstData(
            powerW = pw, cpW = 250f, cpe5W = 214f, powerColor = android.graphics.Color.WHITE,
            speedKmh = sp, avgSpeedKmh = 23.4f, speedColor = android.graphics.Color.WHITE,
            hr = hr, hrAvg = 134, hrZone = z, wbalPct = if (alarm) 14 else 88,
            cadence = cad, cadenceAvg = 85, optCadLow = 80, optCadHigh = 95,
            gearFront = 36, gearRear = rear, cogs = cogs, recCog = null,
            // demo: trend sredniej predkosci zmienia sie w cyklu 3 min: rosnie / bez zmian / spada
            cpTrend = if (alarm) -1 else 0, avgSpeedTrend = when (((now / 60000L) % 3L).toInt()) { 0 -> 1; 1 -> 0; else -> -1 }, hrAvgTrend = 0, cadAvgTrend = 0,
            powerCeilingW = 285,
            hrDriftLevel = if (alarm) 2 else if (t >= 40f) 1 else 0,
            demo = true,
        )
    }
}
