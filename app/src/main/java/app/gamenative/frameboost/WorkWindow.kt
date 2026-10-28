package app.gamenative.frameboost

/** Frame statistics over one FrameBoost window (about 2 s). Times in milliseconds. */
data class WorkStats(
    val frames: Int,
    val fps: Float,
    val p50WorkMs: Float,
    val p90WorkMs: Float,
    val meanWorkMs: Float,
    val p90IntervalMs: Float,
    val missPct: Float,
    val meanInjectedWaitMs: Float,
) {
    companion object {
        val EMPTY = WorkStats(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

object WorkWindow {
    /** Gaps longer than this are a pause (menu, loading, backgrounded), not a frame. */
    const val MAX_INTERVAL_NS = 1_000_000_000L

    /** A frame "misses" when its present interval exceeds the target by this factor. */
    const val MISS_FACTOR = 1.05

    /**
     * Pure computation over [count] frames: timestamps in [timesNs] (ascending, as recorded) and
     * the limiter wait injected before each in [waitsNs]. [prevTimeNs] is the last frame of the
     * previous window (0 when unknown) so the first interval is not lost between windows.
     *
     * work = interval - injected wait, clamped at 0. Percentiles use nearest-rank.
     * [workScratch] and [intervalScratch] must hold at least [count] entries; both are sorted in place.
     */
    fun compute(
        prevTimeNs: Long,
        timesNs: LongArray,
        waitsNs: LongArray,
        count: Int,
        targetNs: Long,
        workScratch: LongArray,
        intervalScratch: LongArray,
    ): WorkStats {
        if (count <= 0) return WorkStats.EMPTY
        var n = 0
        var misses = 0
        var workSum = 0L
        var waitSum = 0L
        var prev = prevTimeNs
        val missThreshold = if (targetNs > 0L) (targetNs * MISS_FACTOR).toLong() else Long.MAX_VALUE
        var first = -1L
        var last = -1L
        for (i in 0 until count) {
            val t = timesNs[i]
            if (prev != 0L) {
                val interval = t - prev
                if (interval in 1..MAX_INTERVAL_NS) {
                    val work = (interval - waitsNs[i]).coerceAtLeast(0L)
                    workScratch[n] = work
                    intervalScratch[n] = interval
                    n++
                    workSum += work
                    waitSum += waitsNs[i].coerceAtLeast(0L)
                    if (interval > missThreshold) misses++
                    if (first < 0) first = prev
                    last = t
                }
            }
            prev = t
        }
        if (n == 0) return WorkStats.EMPTY
        java.util.Arrays.sort(workScratch, 0, n)
        java.util.Arrays.sort(intervalScratch, 0, n)
        val spanNs = last - first
        val fps = if (spanNs > 0L) (n.toDouble() * 1e9 / spanNs).toFloat() else 0f
        return WorkStats(
            frames = n,
            fps = fps,
            p50WorkMs = percentileMs(workScratch, n, 0.50),
            p90WorkMs = percentileMs(workScratch, n, 0.90),
            meanWorkMs = (workSum.toDouble() / n / 1e6).toFloat(),
            p90IntervalMs = percentileMs(intervalScratch, n, 0.90),
            missPct = misses * 100f / n,
            meanInjectedWaitMs = (waitSum.toDouble() / n / 1e6).toFloat(),
        )
    }

    private fun percentileMs(sorted: LongArray, size: Int, p: Double): Float {
        val index = (Math.ceil(p * size).toInt() - 1).coerceIn(0, size - 1)
        return sorted[index] / 1_000_000f
    }
}
