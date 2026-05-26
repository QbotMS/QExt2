package com.qext2.primary.engine.hrdecoupling

data class HrSample(
    val timestampMs: Long,
    val hr: Int,
    val power: Int,
    val cadence: Int,
    val speedKmh: Double,
    val elapsedSec: Long,
)
