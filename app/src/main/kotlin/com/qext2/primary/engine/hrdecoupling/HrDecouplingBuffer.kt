package com.qext2.primary.engine.hrdecoupling

private const val MAX_SAMPLES = 2400

class HrDecouplingBuffer {

    private val samples = ArrayDeque<HrSample>(MAX_SAMPLES)

    fun add(sample: HrSample) {
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    fun snapshotWindow(fromMs: Long, toMs: Long): List<HrSample> {
        return samples.filter { it.timestampMs in fromMs..toMs }
    }

    fun snapshotAll(): List<HrSample> = samples.toList()

    fun size(): Int = samples.size

    fun clear() {
        samples.clear()
    }
}
