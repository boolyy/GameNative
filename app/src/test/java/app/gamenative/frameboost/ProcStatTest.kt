package app.gamenative.frameboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatTest {
    @Test
    fun `stat line with a plain comm`() {
        val line = "12345 (SiNiSistar2.exe) S 1 12345 12345 0 -1 4194560 1000 0 0 0 4321 123 0 0 20 0 60 0 100 0 0 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 7 0 0 0 0 0"
        val e = ProcStat.parseStat(line)!!
        assertEquals("SiNiSistar2.exe", e.comm)
        assertEquals(4321L + 123L, e.cpuTicks)
    }

    @Test
    fun `wine thread names with spaces and parentheses`() {
        val line = "777 (Dispatch Thread (x)) R 1 777 777 0 -1 4194560 1 0 0 0 50 7 0 0 20 0 1 0 100 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 6 0 0 0 0 0"
        val e = ProcStat.parseStat(line)!!
        assertEquals("Dispatch Thread (x)", e.comm)
        assertEquals(57L, e.cpuTicks)
    }

    @Test
    fun `truncated or garbage lines are null`() {
        assertNull(ProcStat.parseStat("12 (x) S 1 2"))
        assertNull(ProcStat.parseStat(""))
        assertNull(ProcStat.parseStat("no parens here at all"))
    }

    @Test
    fun `cmdline splits on NUL and drops the trailing one`() {
        val bytes = "Z:\\home\\xuser\\Game.exe\u0000-windowed\u0000".toByteArray()
        assertEquals(listOf("Z:\\home\\xuser\\Game.exe", "-windowed"), ProcStat.parseCmdline(bytes))
        assertEquals(emptyList<String>(), ProcStat.parseCmdline(ByteArray(0)))
    }

    @Test
    fun `cpu lists with ranges`() {
        assertEquals(setOf(0, 1, 2, 3, 7), ProcStat.parseCpuList("0-3,7"))
        assertEquals(setOf(7), ProcStat.parseCpuList("7\n"))
        assertEquals(emptySet<Int>(), ProcStat.parseCpuList(""))
    }

    @Test
    fun `allowed cpus from a status file`() {
        val status = "Name:\tGame.exe\nUmask:\t0022\nCpus_allowed:\t80\nCpus_allowed_list:\t7\nMems_allowed_list:\t0\n"
        assertEquals(setOf(7), ProcStat.parseAllowedCpus(status))
        assertNull(ProcStat.parseAllowedCpus("Name:\tx\n"))
    }
}
