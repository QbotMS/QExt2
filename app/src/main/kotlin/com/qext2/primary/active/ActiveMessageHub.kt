package com.qext2.primary.active

import android.util.Log
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.util.QExt2DebugConfig
import io.hammerhead.karooext.models.PlayBeepPattern

private const val TAG = "QExt2MsgHub"

/**
 * Wspolna kolejka komunikatow QExt2 (wczesniej wewnatrz pola ACTIVE).
 * Producenci (czujniki, podjazdy, pacing W', gotowosc, jedzenie, pogoda) dzialaja co 1 s
 * niezaleznie od tego, ktore pole jest na ekranie. ACTIVE i KOKPIT nawigacja wyswietlaja
 * ten sam biezacy komunikat. Logika producentow przeniesiona 1:1 z CompositeActiveDataType.
 */
object ActiveMessageHub {
    val manager: ActiveMessageManager = ActiveMessageManager(logger = { msg -> Log.i("QEXT_ACTIVE_ENGINE", msg) })
        .also { ActiveMessageBus.manager = it }

    private val sensorProducer = SensorMessageProducer(logger = { msg -> Log.i("QEXT_SENSOR_MSG", msg) })
    private val climbProducer = ClimbAnnouncementProducer(logger = { msg -> Log.i("QEXT_CLIMB_MSG", msg) })
    private val climbPacingProducer = ClimbPacingProducer(logger = { msg -> Log.d(TAG, "QEXT_PACING $msg") })
    private val weatherProducer = WeatherMessageProducer(logger = { msg -> Log.i("QEXT_WEATHER_MSG", msg) })
    private val beepCooldown = BeepCooldownTracker()
    private val noSdkClimbLogGate = NoSdkClimbLogGate()

    /** Rosnie przy kazdej zmianie biezacego komunikatu - pola odswiezaja sie wtedy natychmiast. */
    @Volatile var version: Long = 0L
        private set
    private var lastCurId: String? = null

    @Synchronized
    fun reset() {
        manager.clear()
        climbProducer.reset()
        climbPacingProducer.reset()
        lastCurId = null
        version++
    }

    fun current(now: Long): ActiveMessage? = manager.getCurrent(now)

    @Synchronized
    fun tick(agg: RideDataAggregator, now: Long) {
        try {
            val sensorState = SensorState(
                speedKmh = agg.getEffectiveSpeedKmh(),
                cadence = agg.getEffectiveCadence(),
                hr = agg.getEffectiveHr(),
                power = agg.getEffectivePower(),
                powerFreshnessMs = agg.getPowerFreshnessMs(),
                cadenceFreshnessMs = agg.getCadenceFreshnessMs(),
                hrFreshnessMs = agg.getHrFreshnessMs(),
                hasRoute = agg.getEffectiveRoute(),
                elapsedSec = agg.getElapsedSec(),
                nowMs = now,
                athleteDataAgeH = agg.getAthleteDataAgeHours(now),
                effectiveTodayFactor = agg.statsSnapshot.value.readiness,
            )
            sensorProducer.checkAndProduce(sensorState)?.let { if (manager.show(it)) beep(it, "show") }

            val climbResolution = ActiveClimbResolver.resolve(
                nowMs = now,
                fakeMode = QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE,
                hasRoute = agg.getEffectiveRoute(),
                navClimbs = agg.getNavClimbs(),
                distanceMeters = agg.getDistanceMeters(),
                distanceToDestinationMeters = agg.getDistanceToDestinationMeters(),
                ascentLeftM = agg.getAscentLeftM(),
                effectiveGrade = agg.getEffectiveGrade(),
            )
            val routeKey = agg.getRouteKey().ifBlank { "route:unknown" }
            if (agg.getNavClimbs().isNotEmpty()) noSdkClimbLogGate.onSdkClimbsAvailable(routeKey)
            if (climbResolution.reason == "no_sdk_climbs" && noSdkClimbLogGate.shouldLogNoSdkClimbs(routeKey)) {
                Log.i(TAG, "QEXT_CLIMB_MSG reason=no_sdk_climbs route=$routeKey climbs=0")
            }
            climbResolution.state?.let { climbProducer.checkAndProduce(it) }?.let { if (manager.show(it)) beep(it, "show") }

            // pacing dziala zawsze, gdy mamy LTP - nie tylko na podjezdzie
            val climbState = climbResolution.state
            climbPacingProducer.checkAndProduce(
                power = agg.snapshot.value.power3s,
                wBalancePct = agg.statsSnapshot.value.wBalancePercent,
                effectiveLtpW = agg.getEffectiveLtpWatts(),
                cpEffW = agg.statsSnapshot.value.cpEffW,
                wPrimeEffKj = agg.statsSnapshot.value.wPrimeEffKj,
                isWithinBounds = climbState?.isWithinClimbBounds == true,
                ascentLeftM = climbState?.climbElevationM ?: 0,
                grade = climbState?.avgGradePercent ?: agg.getEffectiveGrade(),
                climbIndex = climbState?.climbIndex ?: -1,
                modeFactor = agg.getModeFactor(),
                nowMs = now,
            )?.let { if (manager.show(it)) beep(it, "pacing") }
            agg.consumePendingReadinessMessage()?.let { if (manager.show(it)) beep(it, "readiness") }
            agg.consumePendingFuelMessage()?.let { if (manager.show(it)) beep(it, "fuel") }

            weatherProducer.checkAndProduce(WeatherMsgState(
                weatherFresh = agg.statsSnapshot.value.weatherFresh,
                temperatureC = agg.statsSnapshot.value.weatherTemperatureC,
                windSpeedMps = agg.statsSnapshot.value.windSpeedMps,   // karoo-headwind
                rain1hMm = agg.statsSnapshot.value.weatherRain1hMm,
                condition = agg.statsSnapshot.value.weatherCondition,
                nowMs = now,
            ))?.let { if (manager.show(it)) beep(it, "weather") }

            when (val res = manager.hideExpired(now)) {
                is ExpiryResult.Expired -> Log.d(TAG, "QEXT_ACTIVE_MSG_HIDE id=${res.message.id} reason=expired")
                is ExpiryResult.Resumed -> Log.d(TAG, "QEXT_ACTIVE_MSG_RESUME id=${res.message.id}")
                is ExpiryResult.None -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "QEXT_MSG_HUB_TICK_FAIL msg=${e.message}")
        }
        val cur = manager.getCurrent(now)
        if (cur?.id != lastCurId) {
            lastCurId = cur?.id
            version++
            com.qext2.primary.util.RideFileLog.append(
                if (cur == null) "ACTIVE_MSG brak" else "ACTIVE_MSG ${cur.severity} ${cur.title} | ${cur.line1 ?: ""} ${cur.line2 ?: ""}"
            )
        }
    }

    private fun beep(msg: ActiveMessage, reason: String) {
        if (reason == "resume") return
        if (msg.severity != ActiveMessageSeverity.WARNING && msg.severity != ActiveMessageSeverity.CRITICAL) return
        val now = System.currentTimeMillis()
        if (beepCooldown.suppression(now) != null) return
        try {
            val system = QExt2PrimaryExtension.instance?.karooSystem
            if (system == null) { beepCooldown.onFailure(now); return }
            val tones = if (msg.severity == ActiveMessageSeverity.CRITICAL) {
                listOf(PlayBeepPattern.Tone(5000, 180), PlayBeepPattern.Tone(null, 60), PlayBeepPattern.Tone(5000, 220))
            } else {
                listOf(PlayBeepPattern.Tone(5000, 120))
            }
            if (system.dispatch(PlayBeepPattern(tones))) beepCooldown.onSuccess(now) else beepCooldown.onFailure(now)
        } catch (e: Exception) {
            beepCooldown.onFailure(now)
            Log.w(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} reason=dispatch_error error=${e.message}")
        }
    }
}
