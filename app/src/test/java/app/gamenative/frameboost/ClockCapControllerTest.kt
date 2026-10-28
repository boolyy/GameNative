package app.gamenative.frameboost

import app.gamenative.frameboost.ClockCapController.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockCapControllerTest {
    private val sec = 1_000_000_000L
    private val target = 16_666_666L
    private val steps = listOf(1_000_000L, 1_400_000L, 1_800_000L, 2_200_000L, 2_600_000L, 3_000_000L, 3_200_000L)

    /** Feeds windows 2 s apart and collects the decisions. */
    private fun run(c: ClockCapController, windows: List<Pair<Long, Float>>, startNs: Long = 0L): List<Decision> {
        var now = startNs
        return windows.map { (p90, miss) ->
            now += 2 * sec
            c.onWindow(p90, miss, target, now)
        }
    }

    private fun low() = (target * 0.5).toLong() to 0f
    private fun mid() = (target * 0.9).toLong() to 0f
    private fun high() = (target * 0.99).toLong() to 0f
    private fun missy() = (target * 0.7).toLong() to 10f

    @Test
    fun `starts at the maximum step and ignores steps below the floor`() {
        val c = ClockCapController(steps, floorKhz = 1_800_000L)
        assertEquals(listOf(1_800_000L, 2_200_000L, 2_600_000L, 3_000_000L, 3_200_000L), c.steps)
        assertEquals(3_200_000L, c.currentCapKhz)
        assertTrue(c.atMax)
    }

    @Test
    fun `steady headroom steps down one step every three windows until the floor`() {
        val c = ClockCapController(steps, floorKhz = 1_800_000L)
        val d = run(c, List(18) { low() })
        val caps = d.filterIsInstance<Decision.SetCap>().map { it.khz }
        assertEquals(listOf(3_000_000L, 2_600_000L, 2_200_000L, 1_800_000L), caps)
        assertTrue(c.atFloor)
        // 3 windows per step: decisions at windows 3, 6, 9, 12
        assertEquals(listOf(2, 5, 8, 11), d.withIndex().filter { it.value is Decision.SetCap }.map { it.index })
    }

    @Test
    fun `a spike raises the cap two steps immediately`() {
        val c = ClockCapController(steps, floorKhz = 1_000_000L)
        run(c, List(9) { low() }) // 3 steps down: 3.2 -> 3.0 -> 2.6 -> 2.2
        assertEquals(2_200_000L, c.currentCapKhz)
        val d = run(c, listOf(high()), startNs = 18 * sec)
        assertEquals(Decision.SetCap(3_000_000L), d.single())
        val d2 = run(c, listOf(missy()), startNs = 20 * sec)
        assertEquals(Decision.SetCap(3_200_000L), d2.single())
        assertEquals(Decision.None, run(c, listOf(high()), startNs = 22 * sec).single())
    }

    @Test
    fun `oscillating load never hunts faster than one step per 6 s`() {
        val c = ClockCapController(steps, floorKhz = 1_000_000L)
        val pattern = List(12) { i -> if (i % 2 == 0) low() else high() }
        val d = run(c, pattern)
        assertTrue("no step-down with alternating windows", d.none { it is Decision.SetCap && it.khz < 3_200_000L })
        // and after settling into steady low, no two changes closer than 6 s
        val d2 = run(c, List(12) { low() }, startNs = 24 * sec)
        val changeIdx = d2.withIndex().filter { it.value is Decision.SetCap }.map { it.index }
        changeIdx.zipWithNext().forEach { (a, b) -> assertTrue((b - a) * 2 >= 6) }
    }

    @Test
    fun `at the floor with lots of headroom for 10 s it releases, and misses re-pin`() {
        val c = ClockCapController(listOf(1_000_000L, 2_000_000L), floorKhz = 1_000_000L)
        var now = 0L
        fun step(p: Pair<Long, Float>): Decision { now += 2 * sec; return c.onWindow(p.first, p.second, target, now) }
        repeat(3) { step(low()) }
        assertEquals(1_000_000L, c.currentCapKhz)
        assertTrue(c.atFloor)
        // 10 s at the floor with p90 < 0.6 x target
        val decisions = (1..6).map { step(low()) }
        assertTrue(decisions.contains(Decision.Release))
        assertTrue(c.released)
        assertEquals(Decision.None, step(low()))
        val repin = step(missy())
        assertEquals(Decision.Repin(2_000_000L), repin)
        assertFalse(c.released)
        assertTrue(c.atMax)
    }

    @Test
    fun `mid headroom holds the cap`() {
        val c = ClockCapController(steps, floorKhz = 1_000_000L)
        val d = run(c, List(10) { mid() })
        assertTrue(d.all { it == Decision.None })
    }

    @Test
    fun `no usable steps above the floor falls back to the maximum`() {
        val c = ClockCapController(listOf(500_000L, 800_000L), floorKhz = 900_000L)
        assertEquals(listOf(800_000L), c.steps)
        assertEquals(Decision.None, c.onWindow(1L, 0f, target, 2 * sec))
    }
}
