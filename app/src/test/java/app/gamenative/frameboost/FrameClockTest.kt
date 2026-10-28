package app.gamenative.frameboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameClockTest {
    @After
    fun tearDown() {
        FrameClock.enabled = false
        FrameClock.reset()
    }

    @Test
    fun `injected waits attach to the next frame and add up`() {
        FrameClock.reset()
        FrameClock.enabled = true
        FrameClock.onInjectedWait(3_000_000L)
        FrameClock.onInjectedWait(4_000_000L)
        assertEquals(7_000_000L, FrameClock.record(100L))
        assertEquals(0L, FrameClock.record(200L))
        val times = LongArray(8)
        val waits = LongArray(8)
        assertEquals(2, FrameClock.copyFrom(0L, times, waits))
        assertEquals(100L, times[0])
        assertEquals(7_000_000L, waits[0])
        assertEquals(200L, times[1])
        assertEquals(0L, waits[1])
        assertEquals(2L, FrameClock.writeIndex())
        assertEquals(0, FrameClock.copyFrom(2L, times, waits))
    }

    @Test
    fun `disabled clock records nothing`() {
        FrameClock.reset()
        FrameClock.enabled = false
        FrameClock.onInjectedWait(1L)
        assertEquals(0L, FrameClock.record(1L))
        assertEquals(0L, FrameClock.writeIndex())
    }
}
