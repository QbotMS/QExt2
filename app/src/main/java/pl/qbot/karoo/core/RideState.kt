package pl.qbot.karoo.core

import kotlin.math.max

class RideState(
    private val config: LabConfig = LabConfig()
) {
    var started: Boolean = false
        private set
    var paused: Boolean = false
        private set

    var tSec: Double = 0.0
        private set
    private var firstTSec: Double? = null
    private var lastTSec: Double? = null

    var elapsedTotalSec: Double = 0.0
        private set
    var elapsedMovingSec: Double = 0.0
        private set

    var distanceM: Double = 0.0
        private set
    private var lastDistanceM: Double? = null

    var speedKmh: Double? = null
        private set
    var powerW: Double? = null
        private set
    var hrBpm: Double? = null
        private set
    var cadenceRpm: Double? = null
        private set
    var altitudeM: Double? = null
        private set

    var gradeRawPct: Double? = null
        private set
    var gradeDisplayPct: Double? = null
        private set

    var gearFront: Int? = null
        private set
    var gearRear: Int? = null
        private set

    private val lastSensorUpdateSec: MutableMap<String, Double> = mutableMapOf()
    var uiRecreateCount: Int = 0
        private set
    var dropoutActive: Boolean = false
        private set

    fun update(sample: RideSample) {
        if (firstTSec == null) {
            firstTSec = sample.tSec
            lastTSec = sample.tSec
            started = true
        }

        val previousT = lastTSec ?: sample.tSec
        val dt = max(0.0, sample.tSec - previousT)
        tSec = sample.tSec
        elapsedTotalSec = sample.tSec - (firstTSec ?: sample.tSec)

        when (sample.event) {
            RideEvent.PAUSE -> paused = true
            RideEvent.RESUME -> paused = false
            RideEvent.SENSOR_DROPOUT -> dropoutActive = true
            RideEvent.SENSOR_RESTORE -> dropoutActive = false
            RideEvent.UI_RECREATE -> uiRecreateCount += 1
            RideEvent.NONE -> Unit
        }

        val prevDistance = lastDistanceM
        val prevAltitude = altitudeM

        sample.distanceM?.let { distance ->
            lastDistanceM?.let { prev ->
                val dd = max(0.0, distance - prev)
                if (!paused && dd > 0.0) elapsedMovingSec += dt
            }
            distanceM = max(distanceM, distance)
            lastDistanceM = distance
        } ?: run {
            val sp = sample.speedKmh
            if (!paused && sp != null && sp > 1.0) {
                elapsedMovingSec += dt
                distanceM += sp / 3.6 * dt
            }
        }

        if (!dropoutActive) {
            setSensor("speedKmh", sample.speedKmh) { speedKmh = it }
            setSensor("powerW", sample.powerW) { powerW = it }
            setSensor("hrBpm", sample.hrBpm) { hrBpm = it }
            setSensor("cadenceRpm", sample.cadenceRpm) { cadenceRpm = it }
        }


        sample.gearFront?.let { gearFront = it }
        sample.gearRear?.let { gearRear = it }
        if (sample.gearFront != null && sample.gearRear != null) {
            lastSensorUpdateSec["gear"] = tSec
        }

        val speedReliable = (sample.speedKmh ?: speedKmh ?: 0.0) >= 3.0
        val distanceDelta = if (sample.distanceM != null && prevDistance != null) sample.distanceM - prevDistance else null
        val distanceReliable = distanceDelta != null && distanceDelta >= 5.0
        val canUpdateGrade = speedReliable && distanceReliable

        var rawGrade = if (canUpdateGrade) sample.gradePct else null
        if (
            rawGrade == null &&
            canUpdateGrade &&
            sample.altitudeM != null &&
            prevAltitude != null &&
            sample.distanceM != null &&
            prevDistance != null
        ) {
            val dd = sample.distanceM - prevDistance
            if (dd >= 5.0) rawGrade = 100.0 * (sample.altitudeM - prevAltitude) / dd
        }

        sample.altitudeM?.let { altitudeM = it }

        if (rawGrade != null && rawGrade in -35.0..35.0) {
            gradeRawPct = rawGrade
            gradeDisplayPct = rawGrade
            lastSensorUpdateSec["gradePct"] = tSec
        }

        lastTSec = sample.tSec
    }

    private inline fun setSensor(name: String, value: Double?, setter: (Double) -> Unit) {
        if (value != null) {
            setter(value)
            lastSensorUpdateSec[name] = tSec
        }
    }

    fun sensorAgeSec(sensorName: String): Double? {
        val last = lastSensorUpdateSec[sensorName] ?: return null
        return tSec - last
    }

    val avgGrossKmh: Double?
        get() {
            if (elapsedTotalSec < 60.0 || distanceM < 100.0) return null
            return (distanceM / 1000.0) / (elapsedTotalSec / 3600.0)
        }

    val avgMovingKmh: Double?
        get() {
            if (elapsedMovingSec < 60.0 || distanceM < 100.0) return null
            return (distanceM / 1000.0) / (elapsedMovingSec / 3600.0)
        }
}
