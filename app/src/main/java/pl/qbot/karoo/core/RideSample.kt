package pl.qbot.karoo.core

data class RideSample(
    val tSec: Double,
    val speedKmh: Double? = null,
    val powerW: Double? = null,
    val hrBpm: Double? = null,
    val cadenceRpm: Double? = null,
    val altitudeM: Double? = null,
    val distanceM: Double? = null,
    val gradePct: Double? = null,
    val gearFront: Int? = null,
    val gearRear: Int? = null,
    val event: RideEvent = RideEvent.NONE
)

enum class RideEvent {
    NONE,
    PAUSE,
    RESUME,
    SENSOR_DROPOUT,
    SENSOR_RESTORE,
    UI_RECREATE
}
