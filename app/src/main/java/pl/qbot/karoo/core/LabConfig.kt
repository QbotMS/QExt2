package pl.qbot.karoo.core

data class LabConfig(
    val targetAvgKmh: Double = 24.0,
    val maxRealisticSpeedKmh: Double = 90.0,
    val maxRealisticAvgKmh: Double = 80.0,
    val gradeDeadbandPct: Double = 0.7,
    val gradeDescentThresholdPct: Double = -1.0,
    val gradeLightClimbPct: Double = 4.0,
    val gradeClimbPct: Double = 8.0,
    val speedStaleSec: Double = 5.0,
    val sensorStaleSec: Double = 5.0
)
