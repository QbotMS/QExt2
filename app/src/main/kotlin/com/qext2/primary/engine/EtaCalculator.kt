package com.qext2.primary.engine

class EtaCalculator {
    private val speedHistory10m = ArrayDeque<Pair<Long, Float>>()
    private val speedHistory30m = ArrayDeque<Pair<Long, Float>>()
    private val speedHistory30s = ArrayDeque<Pair<Long, Float>>()
    private var totalStoppedMs: Long = 0L

    fun update(nowMs: Long, speedKph: Float, isMoving: Boolean, moveOnlyMs: Long, elapsedMs: Long) {
        if (isMoving) {
            val entry = Pair(nowMs, speedKph)
            speedHistory10m.addLast(entry)
            speedHistory30m.addLast(entry)
            speedHistory30s.addLast(entry)
        }
        val cut10m = nowMs - 10 * 60_000L
        val cut30m = nowMs - 30 * 60_000L
        val cut30s = nowMs - 30_000L
        while (speedHistory10m.isNotEmpty() && speedHistory10m.first().first < cut10m) speedHistory10m.removeFirst()
        while (speedHistory30m.isNotEmpty() && speedHistory30m.first().first < cut30m) speedHistory30m.removeFirst()
        while (speedHistory30s.isNotEmpty() && speedHistory30s.first().first < cut30s) speedHistory30s.removeFirst()
        totalStoppedMs = elapsedMs - moveOnlyMs
    }

    fun smartAvgKph(wholeRideMovingAvgKph: Float): Float {
        val avg10m = speedHistory10m.map { it.second }.average().toFloat()
        val avg30m = speedHistory30m.map { it.second }.average().toFloat()
        return when {
            speedHistory30m.size < 10 -> wholeRideMovingAvgKph
            speedHistory10m.size < 5 -> 0.5f * avg30m + 0.5f * wholeRideMovingAvgKph
            else -> 0.30f * avg10m + 0.40f * avg30m + 0.30f * wholeRideMovingAvgKph
        }
    }

    fun calculateEtaMs(nowMs: Long, remainingKm: Float, distanceKm: Float, predictedSpeedKph: Float): Long {
        if (remainingKm <= 0f || predictedSpeedKph <= 0f) return 0L
        val movingHours = remainingKm / predictedSpeedKph
        val totalKm = distanceKm + remainingKm
        val progress = if (totalKm > 0) distanceKm / totalKm else 0f
        val stopRateSecPerKm = if (distanceKm > 1f) (totalStoppedMs / 1000f) / distanceKm else 0f
        val endDecay = if (progress > 0.85f) (1f - progress) / 0.15f else 1f
        val predictedStopsSec = stopRateSecPerKm * remainingKm * endDecay
        return nowMs + ((movingHours * 3600f + predictedStopsSec) * 1000f).toLong()
    }

    fun requiredSpeedKph(nowMs: Long, remainingKm: Float, deadlineMs: Long, predictedStopsSec: Float): Float {
        val budgetMs = deadlineMs - nowMs
        if (budgetMs <= 0L || remainingKm <= 0f) return 0f
        val movingBudgetH = (budgetMs / 1000f - predictedStopsSec) / 3600f
        if (movingBudgetH <= 0f) return 999f
        return remainingKm / movingBudgetH
    }

    fun predictedStopsSec(remainingKm: Float, distanceKm: Float): Float {
        if (distanceKm < 1f) return 0f
        val totalKm = distanceKm + remainingKm
        val progress = distanceKm / totalKm
        val stopRateSecPerKm = (totalStoppedMs / 1000f) / distanceKm
        val endDecay = if (progress > 0.85f) (1f - progress) / 0.15f else 1f
        return stopRateSecPerKm * remainingKm * endDecay
    }
}
