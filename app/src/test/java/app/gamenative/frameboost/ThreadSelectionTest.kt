package app.gamenative.frameboost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadSelectionTest {
    @Test
    fun `main thread always, then threads above 30 percent of a core, busiest first, capped at 4`() {
        // 5 s window at 100 Hz = 500 ticks per core; 30 % = 150 ticks
        val deltas = mapOf(100 to 10L, 101 to 400L, 102 to 160L, 103 to 149L, 104 to 300L, 105 to 200L)
        val tids = ThreadSelection.selectBusy(mainTid = 100, deltaTicks = deltas, elapsedSec = 5.0)
        // busiest three: 101 (400), 104 (300), 105 (200); 102 (160) is cut by the cap, 103 under threshold
        assertArrayEquals(intArrayOf(100, 101, 104, 105), tids)
    }

    @Test
    fun `no elapsed time means main thread only`() {
        assertArrayEquals(intArrayOf(5), ThreadSelection.selectBusy(5, mapOf(6 to 1000L), 0.0))
    }

    @Test
    fun `selection equality ignores names`() {
        val a = ThreadSelection(1, 9, intArrayOf(1, 2), mapOf(1 to "a"))
        val b = ThreadSelection(1, 9, intArrayOf(1, 2), mapOf(1 to "b"))
        val c = ThreadSelection(1, null, intArrayOf(1, 2), emptyMap())
        assertEquals(a, b)
        assertTrue(a != c)
        assertArrayEquals(intArrayOf(1, 2, 9), a.allTids)
        assertArrayEquals(intArrayOf(1, 2), c.allTids)
    }

    /** A fake /proc: pid → (cmdline, tid → (comm, ticks)); mutable so threads can vanish. */
    private class FakeProc : ProcScanner() {
        val procs = HashMap<Int, Pair<List<String>, MutableMap<Int, StatEntry>>>()
        override fun listPids(): List<Int> = procs.keys.sorted()
        override fun exists(pid: Int): Boolean = procs.containsKey(pid)
        override fun cmdline(pid: Int): List<String> = procs[pid]?.first ?: emptyList()
        override fun threadStats(pid: Int): Map<Int, StatEntry> = procs[pid]?.second?.toMap() ?: emptyMap()
        override fun allowedCpus(pid: Int, tid: Int): Set<Int>? = null
    }

    @Test
    fun `locates the game by exe name but not the winhandler, and wineserver by argv0`() {
        val proc = FakeProc()
        proc.procs[10] = listOf("/usr/bin/wineserver", "-p0") to mutableMapOf(10 to StatEntry("wineserver", 0))
        proc.procs[11] = listOf("Z:\\opt\\winhandler.exe", "SiNiSistar2.exe") to mutableMapOf(11 to StatEntry("winhandler.exe", 0))
        proc.procs[12] = listOf("Z:\\home\\xuser\\SiNiSistar2.exe") to mutableMapOf(12 to StatEntry("SiNiSistar2.exe", 0))
        val ts = ThreadSet(proc, "sinisistar2.exe")
        assertTrue(ts.locate())
        assertEquals(12, ts.gamePid)
        assertEquals(10, ts.wineserverPid)
        assertTrue(ts.gameAlive())
    }

    @Test
    fun `first sample is the main thread, the second picks busy threads, vanished threads are ignored`() {
        val proc = FakeProc()
        val threads = mutableMapOf(
            20 to StatEntry("Game.exe", 100),
            21 to StatEntry("Dispatch Thread", 100),
            22 to StatEntry("idle", 100),
            23 to StatEntry("gone soon", 100),
        )
        proc.procs[20] = listOf("C:\\Game.exe") to threads
        val ts = ThreadSet(proc, "Game.exe")
        assertTrue(ts.locate())
        val first = ts.sample(0L)!!
        assertArrayEquals(intArrayOf(20), first.gameTids)
        // 5 s later: main +50 ticks, dispatch +400, idle +5, "gone soon" vanished, new thread 24 appears
        threads[20] = StatEntry("Game.exe", 150)
        threads[21] = StatEntry("Dispatch Thread", 500)
        threads[22] = StatEntry("idle", 105)
        threads.remove(23)
        threads[24] = StatEntry("new", 9_000)
        val second = ts.sample(5_000_000_000L)!!
        assertArrayEquals(intArrayOf(20, 21), second.gameTids)
        assertEquals("Dispatch Thread", second.names[21])
        proc.procs.remove(20)
        assertNull(ts.sample(6_000_000_000L))
        assertTrue(!ts.gameAlive())
    }

    @Test
    fun `game not running yet`() {
        val proc = FakeProc()
        val ts = ThreadSet(proc, "Game.exe")
        assertTrue(!ts.locate())
        assertNull(ts.gamePid)
        assertNotNull(ts)
    }
}
