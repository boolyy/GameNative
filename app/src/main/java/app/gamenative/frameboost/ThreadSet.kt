package app.gamenative.frameboost

/** The threads FrameBoost acts on: the game's main thread, its busiest others, and wineserver. */
class ThreadSelection(
    val gamePid: Int,
    val wineserverPid: Int?,
    /** Game TIDs, main thread first, the rest ascending. */
    val gameTids: IntArray,
    /** tid → thread name, for the log. */
    val names: Map<Int, String>,
) {
    /** Every TID a hint session should cover. wineserver's main thread has TID == PID. */
    val allTids: IntArray
        get() = if (wineserverPid != null) gameTids + wineserverPid else gameTids

    val mainTid: Int get() = gamePid

    override fun equals(other: Any?): Boolean =
        other is ThreadSelection && other.gamePid == gamePid && other.wineserverPid == wineserverPid &&
            other.gameTids.contentEquals(gameTids)

    override fun hashCode(): Int = 31 * (31 * gamePid + (wineserverPid ?: 0)) + gameTids.contentHashCode()

    override fun toString(): String =
        "game=$gamePid tids=${gameTids.joinToString(",") { "$it:${names[it] ?: "?"}" }} wineserver=$wineserverPid"

    companion object {
        const val BUSY_THRESHOLD = 0.30
        const val MAX_GAME_THREADS = 4

        /**
         * Picks the main thread plus every other thread above [threshold] of one core over the
         * window, busiest first, capped at [cap] threads in total.
         */
        fun selectBusy(
            mainTid: Int,
            deltaTicks: Map<Int, Long>,
            elapsedSec: Double,
            clkTck: Int = ProcStat.CLK_TCK,
            threshold: Double = BUSY_THRESHOLD,
            cap: Int = MAX_GAME_THREADS,
        ): IntArray {
            if (elapsedSec <= 0.0 || cap <= 1) return intArrayOf(mainTid)
            val coreTicks = elapsedSec * clkTck
            val busy = deltaTicks.entries
                .filter { (tid, d) -> tid != mainTid && d > 0 && d / coreTicks >= threshold }
                .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
                .take(cap - 1)
                .map { it.key }
                .sorted()
            return intArrayOf(mainTid) + busy.toIntArray()
        }
    }
}

/**
 * Finds the game and wineserver processes and samples the game's threads' CPU time so the
 * busiest ones can be selected. Re-run [sample] every few seconds; the first call only knows
 * the main thread.
 */
class ThreadSet(private val scanner: ProcScanner, private val exeName: String) {
    var gamePid: Int? = null
        private set
    var wineserverPid: Int? = null
        private set
    private var lastTicks: Map<Int, Long> = emptyMap()
    private var lastSampleNs = 0L
    private var hasSample = false

    /** Looks the processes up. True when the game process was found. */
    fun locate(): Boolean {
        gamePid = scanner.findPidByExe(exeName)
        wineserverPid = scanner.findPidByArg0Suffix("wineserver")
        lastTicks = emptyMap()
        lastSampleNs = 0L
        hasSample = false
        return gamePid != null
    }

    fun gameAlive(): Boolean = gamePid?.let { scanner.exists(it) } == true

    fun sample(nowNs: Long): ThreadSelection? {
        val pid = gamePid ?: return null
        val entries = scanner.threadStats(pid)
        if (entries.isEmpty()) return null
        val ticks = HashMap<Int, Long>(entries.size * 2)
        val names = HashMap<Int, String>(entries.size * 2)
        for ((tid, e) in entries) {
            ticks[tid] = e.cpuTicks
            names[tid] = e.comm
        }
        val prevTicks = lastTicks
        val prevNs = lastSampleNs
        val first = !hasSample
        lastTicks = ticks
        lastSampleNs = nowNs
        hasSample = true
        val tids = if (first) {
            intArrayOf(pid)
        } else {
            val elapsed = (nowNs - prevNs) / 1e9
            val deltas = HashMap<Int, Long>()
            for ((tid, t) in ticks) {
                val p = prevTicks[tid] ?: continue
                deltas[tid] = t - p
            }
            ThreadSelection.selectBusy(pid, deltas, elapsed)
        }
        return ThreadSelection(pid, wineserverPid, tids, names)
    }
}
