package app.gamenative.frameboost

import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PServerDriver.CpuCluster
import java.io.File

/**
 * Root backend for devices whose power HAL has no hint-session support (the Retroid Pocket Nova
 * and the AYN Thor ship `PServerBinder` instead). Everything goes through upstream's
 * [PServerDriver], so its sysfs baseline, babysitter and dirty-session recovery cover what is
 * changed here. Clusters are discovered by the driver; nothing is hardcoded to a device.
 *
 * 1. Keeps the prime cluster online (`core_ctl/min_cpus=1` on the prime core).
 * 2. Pins the game's main thread to the prime cluster, moves game threads that inherited
 *    "prime only" to the performance cluster, and moves wineserver off the efficiency cluster.
 * 3. Runs [ClockCapController] on the prime cluster's max frequency every window.
 * 4. On stop: core_ctl back to what it was, the main thread back to every core, and the
 *    driver's baseline restores cpufreq.
 */
class RootPinBackend(
    private val pserver: PServerDriver,
    private val scanner: ProcScanner,
    private val log: FrameBoostLog,
) : BoostBackend {
    override val name = "root"

    private val prime = pserver.getCpuCoresByCluster(CpuCluster.PRIME)
    private val perf = pserver.getCpuCoresByCluster(CpuCluster.PERFORMANCE)
    private val eff = pserver.getCpuCoresByCluster(CpuCluster.EFFICIENCY)
    private val primeCore = prime.firstOrNull()
    private val coreCtlPath = primeCore?.let { "/sys/devices/system/cpu/cpu$it/core_ctl/min_cpus" }
    private val primePolicyDir = primeCore?.let { "/sys/devices/system/cpu/cpufreq/policy$it" }
    private val primeSet = prime.toSet()
    private val effSet = eff.toSet()

    private var controller: ClockCapController? = null
    private var coreCtlOriginal: String? = null
    private var selection: ThreadSelection? = null
    private var started = false

    @Volatile
    private var capKhz: Long? = null

    @Volatile
    private var pinned = false

    @Volatile
    private var pinCommands = 0L

    val available: Boolean get() = primeCore != null && perf.isNotEmpty()

    override fun start(selection: ThreadSelection, targetNs: Long): Boolean {
        if (primeCore == null || primePolicyDir == null) {
            log.event("root: no prime cluster discovered, backend unavailable")
            return false
        }
        // Arms the driver's sysfs baseline and babysitter; PowerManager.stop() / stop() restore it.
        pserver.start()
        started = true
        val steps = pserver.getAvailableCpuFrequenciesForCluster(CpuCluster.PRIME)
        val floor = read("$primePolicyDir/scaling_min_freq")?.toLongOrNull() ?: steps.firstOrNull() ?: 0L
        val c = ClockCapController(steps, floor)
        controller = c
        capKhz = c.currentCapKhz
        coreCtlOriginal = coreCtlPath?.let { read(it) }
        log.event(
            "root: prime=$prime perf=$perf eff=$eff steps=${c.steps.size} " +
                "(${c.steps.firstOrNull()}..${c.steps.lastOrNull()} kHz, floor=$floor) " +
                "core_ctl=${coreCtlOriginal ?: "absent"}",
        )
        this.selection = selection
        holdPrimeOnline(true)
        applyPins(selection, force = true)
        return true
    }

    override fun onThreadsChanged(selection: ThreadSelection) {
        this.selection = selection
        if (pinned) applyPins(selection, force = false)
    }

    override fun onTargetChanged(targetNs: Long) {}

    override fun onWindow(stats: WorkStats, targetNs: Long, nowNs: Long) {
        val c = controller ?: return
        val sel = selection
        if (stats.frames < 10) return // paused, loading or backgrounded: no decision
        when (val d = c.onWindow((stats.p90WorkMs * 1_000_000f).toLong(), stats.missPct, targetNs, nowNs)) {
            is ClockCapController.Decision.SetCap -> setCap(d.khz)
            ClockCapController.Decision.Release -> {
                sel?.let { unpinMain(it) }
                holdPrimeOnline(false)
                setCap(c.steps.last())
                pinned = false
                log.event("root: released the pin and core_ctl (idle at the floor for 10 s)")
            }
            is ClockCapController.Decision.Repin -> {
                holdPrimeOnline(true)
                sel?.let { applyPins(it, force = true) }
                setCap(d.khz)
                log.event("root: re-pinned after misses")
            }
            ClockCapController.Decision.None -> {}
        }
        // GameNative's winhandler and the game itself can reset affinities after launch; re-apply drift.
        if (pinned && sel != null) applyPins(sel, force = false)
    }

    override fun stop() {
        if (!started) return
        started = false
        selection?.let { unpinMain(it) }
        holdPrimeOnline(false)
        pinned = false
        // Restores every sysfs value recorded at start() (the prime max frequency included) and
        // kills the babysitter. Asynchronous inside the driver.
        pserver.stop()
    }

    override fun status(): Map<String, Any?> = mapOf(
        "primeCapKhz" to capKhz,
        "pinned" to pinned,
        "released" to (controller?.released ?: false),
        "coreCtl" to coreCtlOriginal,
        "pinCommands" to pinCommands,
    )

    private fun setCap(khz: Long) {
        if (capKhz == khz) return
        if (pserver.setMaxCpuValueForCluster(CpuCluster.PRIME, khz)) {
            capKhz = khz
            log.event("root: prime max frequency -> $khz kHz")
        } else {
            log.event("root: failed to set prime max frequency $khz kHz")
        }
    }

    private fun holdPrimeOnline(on: Boolean) {
        val path = coreCtlPath ?: return
        val value = if (on) "1" else (coreCtlOriginal ?: "0")
        pserver.executeRootCommand("echo $value > '$path'")
    }

    private fun maskOf(cores: Collection<Int>): String =
        pserver.getTasksetMask(cores.fold(0) { acc, c -> acc or (1 shl c) })

    /**
     * Main thread -> prime cluster; game threads whose affinity is prime-only -> performance
     * cluster; wineserver threads on the efficiency cluster only -> performance cluster.
     * One root command for all of it. With [force] every mask is written regardless of state.
     */
    private fun applyPins(sel: ThreadSelection, force: Boolean) {
        val cmds = ArrayList<String>()
        val primeMask = maskOf(prime)
        val perfMask = maskOf(perf)
        val main = sel.mainTid
        val mainAllowed = scanner.allowedCpus(sel.gamePid, main)
        if (force || mainAllowed == null || mainAllowed != primeSet) {
            cmds.add("taskset -p $primeMask $main")
        }
        for (tid in scanner.threadStats(sel.gamePid).keys) {
            if (tid == main) continue
            val allowed = scanner.allowedCpus(sel.gamePid, tid) ?: continue
            if (allowed == primeSet) cmds.add("taskset -p $perfMask $tid")
        }
        sel.wineserverPid?.let { ws ->
            for (tid in scanner.threadStats(ws).keys) {
                val allowed = scanner.allowedCpus(ws, tid) ?: continue
                if (force || (effSet.isNotEmpty() && allowed.all { it in effSet })) {
                    cmds.add("taskset -p $perfMask $tid")
                }
            }
        }
        if (cmds.isEmpty()) return
        pserver.executeRootCommand(cmds.joinToString(" >/dev/null 2>&1; ", postfix = " >/dev/null 2>&1"))
        pinCommands++
        pinned = true
    }

    private fun unpinMain(sel: ThreadSelection) {
        val all = (eff + perf + prime).toSet()
        if (all.isEmpty()) return
        pserver.executeRootCommand("taskset -p ${maskOf(all)} ${sel.mainTid} >/dev/null 2>&1")
    }

    /** sysfs values are usually world-readable; falls back to root when not. */
    private fun read(path: String): String? {
        runCatching { File(path).readText().trim() }.getOrNull()?.let { return it }
        return pserver.executeRootCommand("cat '$path'").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
