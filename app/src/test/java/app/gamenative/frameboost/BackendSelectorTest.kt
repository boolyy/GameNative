package app.gamenative.frameboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectorTest {
    private fun caps(adpf: Boolean, pserver: Boolean, powerControl: Boolean = false, sdk: Int = 33) =
        Capabilities(sdk, adpf, pserver, powerControl)

    @Test
    fun `auto prefers the hint API, then root, then off`() {
        assertEquals(BackendKind.ADPF, BackendSelector.select(FrameBoostMode.AUTO, null, caps(true, true)).kind)
        assertEquals(BackendKind.ROOT, BackendSelector.select(FrameBoostMode.AUTO, null, caps(false, true)).kind)
        assertEquals(BackendKind.OFF, BackendSelector.select(FrameBoostMode.AUTO, null, caps(false, false)).kind)
    }

    @Test
    fun `forced modes do not fall back`() {
        assertEquals(BackendKind.OFF, BackendSelector.select(FrameBoostMode.ADPF, null, caps(false, true)).kind)
        assertEquals(BackendKind.OFF, BackendSelector.select(FrameBoostMode.ROOT, null, caps(true, false)).kind)
        assertEquals(BackendKind.ROOT, BackendSelector.select(FrameBoostMode.ROOT, null, caps(true, true)).kind)
    }

    @Test
    fun `power control wins over everything except off`() {
        for (mode in FrameBoostMode.entries) {
            val c = BackendSelector.select(mode, null, caps(true, true, powerControl = true))
            assertEquals(BackendKind.OFF, c.kind)
            if (mode != FrameBoostMode.OFF) assertTrue(c.reason.contains("Power Control"))
        }
    }

    @Test
    fun `the container env override replaces the global setting`() {
        val c = BackendSelector.select(FrameBoostMode.OFF, FrameBoostMode.ROOT, caps(true, true))
        assertEquals(BackendKind.ROOT, c.kind)
        assertTrue(c.reason.contains("GN_FRAMEBOOST=root"))
        assertEquals(BackendKind.OFF, BackendSelector.select(FrameBoostMode.AUTO, FrameBoostMode.OFF, caps(true, true)).kind)
    }

    @Test
    fun `mode keys and aliases`() {
        assertEquals(FrameBoostMode.ADPF, FrameBoostMode.fromKey(" Hint "))
        assertEquals(FrameBoostMode.ROOT, FrameBoostMode.fromKey("root"))
        assertEquals(FrameBoostMode.AUTO, FrameBoostMode.fromKey("on"))
        assertEquals(FrameBoostMode.OFF, FrameBoostMode.fromKey("0"))
        assertNull(FrameBoostMode.fromKey(""))
        assertNull(FrameBoostMode.fromKey(null))
        assertNull(FrameBoostMode.fromKey("turbo"))
    }

    @Test
    fun `target frame time comes from the limiter, else the panel`() {
        assertEquals(16_666_666L, FrameBoost.targetNsFor(60, 120f))
        assertEquals(8_333_333L, FrameBoost.targetNsFor(0, 120f))
        assertEquals(16_666_666L, FrameBoost.targetNsFor(0, 0f))
    }
}
