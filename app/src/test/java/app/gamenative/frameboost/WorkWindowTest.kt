package app.gamenative.frameboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkWindowTest {
    private val ms = 1_000_000L
    private val target60 = 16_666_666L

    private fun compute(prev: Long, times: LongArray, waits: LongArray, targetNs: Long = target60): WorkStats =
        WorkWindow.compute(prev, times, waits, times.size, targetNs, LongArray(times.size), LongArray(times.size))

    @Test
    fun `capped 60 fps game that finishes in 9 ms reports about 9 ms of work, not 16_6`() {
        // Present every 16.67 ms; the limiter injected ~7.67 ms before each frame.
        val n = 120
        val times = LongArray(n) { i -> (i + 1) * target60 }
        val waits = LongArray(n) { target60 - 9 * ms }
        val s = compute(0L, times, waits)
        assertEquals(n - 1, s.frames)
        assertEquals(9f, s.p50WorkMs, 0.05f)
        assertEquals(9f, s.p90WorkMs, 0.05f)
        assertEquals(9f, s.meanWorkMs, 0.05f)
        assertEquals(16.67f, s.p90IntervalMs, 0.05f)
        assertEquals(60f, s.fps, 0.5f)
        assertEquals(0f, s.missPct, 0f)
        assertEquals(7.67f, s.meanInjectedWaitMs, 0.05f)
    }

    @Test
    fun `without a limiter work equals the interval`() {
        val times = LongArray(61) { i -> i * 20 * ms }
        val s = compute(0L, times, LongArray(61))
        assertEquals(60, s.frames)
        assertEquals(20f, s.p50WorkMs, 0.01f)
        assertEquals(50f, s.fps, 0.1f)
    }

    @Test
    fun `misses are intervals over 1_05 x target`() {
        val times = LongArray(11)
        var t = 0L
        for (i in 0 until 11) {
            t += if (i % 5 == 0) 25 * ms else target60
            times[i] = t
        }
        val s = compute(0L, times, LongArray(11))
        assertEquals(10, s.frames)
        // frames 1..10 relative to prev: i=5 and i=10 are 25 ms
        assertEquals(20f, s.missPct, 0.01f)
        assertEquals(25f, s.p90WorkMs, 0.01f)
    }

    @Test
    fun `previous window's last frame supplies the first interval`() {
        val times = longArrayOf(100 * ms, 116 * ms)
        val s = compute(84 * ms, times, LongArray(2))
        assertEquals(2, s.frames)
        assertEquals(16f, s.p50WorkMs, 0.01f)
    }

    @Test
    fun `pauses longer than a second are not frames`() {
        val times = longArrayOf(0, 16 * ms, 3_000 * ms, 3_016 * ms)
        val s = compute(0L, times, LongArray(4))
        assertEquals(2, s.frames)
        assertTrue(s.p90WorkMs < 17f)
    }

    @Test
    fun `empty window is empty`() {
        assertEquals(WorkStats.EMPTY, compute(0L, LongArray(0), LongArray(0)))
        assertEquals(WorkStats.EMPTY, compute(0L, longArrayOf(5 * ms), LongArray(1)))
    }

    @Test
    fun `work never goes negative when the wait exceeds the interval`() {
        val times = longArrayOf(0, 10 * ms, 20 * ms)
        val waits = longArrayOf(0, 15 * ms, 15 * ms)
        val s = compute(0L, times, waits)
        assertEquals(0f, s.p90WorkMs, 0f)
    }
}
