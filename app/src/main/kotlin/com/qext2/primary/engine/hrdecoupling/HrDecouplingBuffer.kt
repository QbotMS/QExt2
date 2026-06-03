package com.qext2.primary.engine.hrdecoupling

private const val MAX_SAMPLES = 7200

class HrDecouplingBuffer {

    private val samples = ArrayDeque<HrSample>(MAX_SAMPLES)
    @Volatile
    private var cachedSnapshot: List<HrSample>? = null

    fun add(sample: HrSample) {
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        cachedSnapshot = null
    }

    fun snapshotWindow(fromMs: Long, toMs: Long): List<HrSample> {
        return samples.filter { it.timestampMs in fromMs..toMs }
    }

    fun snapshotAll(): List<HrSample> {
        val cached = cachedSnapshot
        if (cached != null) return cached
        val snap = samples.toList()
        cachedSnapshot = snap
        return snap
    }

    fun size(): Int = samples.size

    fun clear() {
        samples.clear()
        cachedSnapshot = null
    }
}
