package app.gamenative.frameboost

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free, allocation-free record of presented frames, written on the render path
 * ([FrameBoost.onFrame]) and drained by the FrameBoost worker.
 *
 * Each entry is the frame's timestamp and the wait the FPS limiter injected before it
 * ([onInjectedWait], reported by ShmFramePacer and the Present extension). Actual CPU work for a
 * frame is its present interval minus that wait: a game capped at 60 FPS that finishes in 9 ms
 * still presents every 16.6 ms, and reporting 16.6 ms would tell Android to keep boosting.
 *
 * Same shape as upstream's FrameTimeRing: one writer, readers may lose the oldest entries if
 * they stall for thousands of frames.
 */
object FrameClock {
    private const val CAPACITY = 4096
    private const val MASK = (CAPACITY - 1).toLong()

    private val timesNs = LongArray(CAPACITY)
    private val waitsNs = LongArray(CAPACITY)
    private val writeIndex = AtomicLong(0L)
    private val pendingWaitNs = AtomicLong(0L)

    @Volatile
    var enabled: Boolean = false

    /** Limiter wait injected before the next presented frame; several waits add up. */
    @JvmStatic
    fun onInjectedWait(ns: Long) {
        if (ns > 0L && enabled) pendingWaitNs.addAndGet(ns)
    }

    /** Records a presented frame. Returns the limiter wait that preceded it. */
    @JvmStatic
    fun record(nowNs: Long): Long {
        if (!enabled) return 0L
        val wait = pendingWaitNs.getAndSet(0L)
        val index = writeIndex.getAndIncrement()
        val slot = (index and MASK).toInt()
        timesNs[slot] = nowNs
        waitsNs[slot] = wait
        return wait
    }

    fun reset() {
        writeIndex.set(0L)
        pendingWaitNs.set(0L)
    }

    fun writeIndex(): Long = writeIndex.get()

    /**
     * Copies entries from index [from] (or the oldest retained one, if [from] was overwritten)
     * up to the current write index into [outTimes] / [outWaits]. Returns the count.
     */
    fun copyFrom(from: Long, outTimes: LongArray, outWaits: LongArray): Int {
        val end = writeIndex.get()
        val oldest = if (end > CAPACITY) end - CAPACITY else 0L
        var index = maxOf(from, oldest)
        var count = 0
        val limit = minOf(outTimes.size, outWaits.size)
        while (index < end && count < limit) {
            val slot = (index and MASK).toInt()
            outTimes[count] = timesNs[slot]
            outWaits[count] = waitsNs[slot]
            count++
            index++
        }
        return count
    }
}
