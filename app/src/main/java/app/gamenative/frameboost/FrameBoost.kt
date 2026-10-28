package app.gamenative.frameboost

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager as AndroidPowerManager
import android.provider.Settings
import android.view.Display
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.drivers.PServerDriver
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.json.JSONObject
import timber.log.Timber

/**
 * GameNative Perf's frame-time-driven CPU boost (see FORK.md). Facade and lifecycle.
 *
 * Hooks from upstream code:
 * - [onFrame] from FrameRating.update on every presented frame (render path: allocation-free).
 * - [onInjectedWait] from ShmFramePacer and PresentExtension when the FPS limiter delays a client.
 * - [onLimiterChanged] from XServerScreen whenever the limiter target is applied.
 * - [start] where PowerManager.autoStart runs, [stop] where PowerManager.stop runs.
 *
 * All real work happens on one worker thread every 250 ms; windows are 2 s, thread rescans 5 s.
 */
object FrameBoost {
    const val TAG = FrameBoostLog.TAG
    private const val TICK_MS = 250L
    private const val WINDOW_NS = 2_000_000_000L
    private const val RESCAN_NS = 5_000_000_000L
    private const val LOCATE_NS = 1_000_000_000L
    private const val LOGCAT_EVERY_WINDOWS = 5

    @Volatile
    private var session: Session? = null

    @Volatile
    private var frameBackend: BoostBackend? = null

    @Volatile
    private var limiterFps: Int = 0

    // Render path only.
    private var lastFrameNs = 0L

    /** Render path. Records the frame and feeds the ADPF backend its work time. */
    @JvmStatic
    fun onFrame(nowNs: Long) {
        if (!FrameClock.enabled) return
        val wait = FrameClock.record(nowNs)
        val prev = lastFrameNs
        lastFrameNs = nowNs
        val backend = frameBackend ?: return
        if (prev == 0L) return
        val interval = nowNs - prev
        if (interval <= 0L || interval > WorkWindow.MAX_INTERVAL_NS) return
        backend.onFrame((interval - wait).coerceAtLeast(0L), nowNs)
    }

    /** The FPS limiter is about to make the client wait [ns] before its next frame. */
    @JvmStatic
    fun onInjectedWait(ns: Long) = FrameClock.onInjectedWait(ns)

    /** Limiter target as applied to the engines: FPS, or 0 when off. */
    @JvmStatic
    fun onLimiterChanged(fps: Int) {
        limiterFps = fps
        session?.onLimiterChanged(fps)
    }

    @JvmStatic
    fun start(context: Context, container: Container) {
        stop()
        val s = Session(context.applicationContext, container, limiterFps)
        session = s
        s.start()
    }

    @JvmStatic
    fun stop() {
        val s = session ?: return
        session = null
        s.stop()
    }

    @JvmStatic
    fun isActive(): Boolean = frameBackend != null

    private class Session(
        private val context: Context,
        private val container: Container,
        initialLimiterFps: Int,
    ) : Runnable {
        private val thread = Thread(this, "FrameBoost")

        @Volatile
        private var running = true

        @Volatile
        private var pendingLimiterFps = initialLimiterFps

        private val log = FrameBoostLog(context)
        private val scanner = ProcScanner()
        private val exeName = exeNameOf(container)
        private var backend: BoostBackend? = null
        private var backendStarted = false
        private var threadSet: ThreadSet? = null
        private var selection: ThreadSelection? = null
        private var limiterFps = initialLimiterFps
        private var displayHz = 60f
        private var targetNs = 0L
        private var choice: BackendChoice = BackendChoice(BackendKind.OFF, "not started")

        private val times = LongArray(4096)
        private val waits = LongArray(4096)
        private val workScratch = LongArray(4096)
        private val intervalScratch = LongArray(4096)
        private var readIndex = 0L
        private var prevTimeNs = 0L
        private var lastWindowNs = 0L
        private var lastRescanNs = 0L
        private var lastLocateNs = 0L
        private var windows = 0L

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            running = false
            thread.interrupt()
            runCatching { thread.join(3_000) }
        }

        fun onLimiterChanged(fps: Int) {
            pendingLimiterFps = fps
        }

        override fun run() {
            try {
                setup()
                if (backend !is NoopBackend) loop()
            } catch (e: InterruptedException) {
                // stop()
            } catch (e: Throwable) {
                log.event("FrameBoost worker failed: $e")
                Timber.tag(TAG).e(e, "FrameBoost worker failed")
            } finally {
                teardown()
            }
        }

        private fun setup() {
            val global = FrameBoostMode.fromKey(PrefManager.frameBoostMode) ?: FrameBoostMode.OFF
            val override = FrameBoostMode.fromKey(EnvVars(container.envVars).get(FrameBoostMode.ENV_OVERRIDE))
            val probe = Adpf.probe(context)
            val pserver = PowerManager.pserverDriver()
            val powerControl = PowerManager.isProfilePowerControlEnabled()
            val caps = Capabilities(
                sdk = Build.VERSION.SDK_INT,
                adpfSupported = probe.supported,
                pserverAvailable = pserver != null && pserver.isDriverSupported(),
                powerControlActive = powerControl,
            )
            choice = BackendSelector.select(global, override, caps)
            displayHz = displayRefreshHz(context)
            targetNs = targetNsFor(limiterFps, displayHz)
            val performanceMode = runCatching { Settings.System.getInt(context.contentResolver, "performance_mode", -1) }.getOrDefault(-1)

            val capLine = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("capability", true)
                .put("app", BuildConfig.VERSION_NAME)
                .put("package", BuildConfig.APPLICATION_ID)
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("game", exeName)
                .put("modeSetting", global.key)
                .put("modeOverride", override?.key)
                .put("adpfSupported", probe.supported)
                .put("preferredUpdateRateNanos", probe.preferredRateNs)
                .put("adpfDetail", probe.detail)
                .put("pserverAvailable", caps.pserverAvailable)
                .put("powerControlActive", powerControl)
                .put("powerDriver", runCatching { PowerManager.driverName() }.getOrDefault("uninitialized"))
                .put("performanceMode", performanceMode)
                .put("displayHz", displayHz)
                .put("limiterFps", limiterFps)
                .put("targetMs", targetNs / 1e6)
                .put("backend", choice.kind.name.lowercase())
                .put("reason", choice.reason)
            if (pserver != null) {
                val clusters = JSONObject()
                for (c in PServerDriver.CpuCluster.entries) {
                    val cores = pserver.getCpuCoresByCluster(c)
                    if (cores.isEmpty()) continue
                    val freqs = pserver.getAvailableCpuFrequenciesForCluster(c)
                    clusters.put(
                        c.name.lowercase(),
                        JSONObject().put("cores", cores.joinToString(","))
                            .put("minKhz", freqs.firstOrNull()).put("maxKhz", freqs.lastOrNull()).put("steps", freqs.size),
                    )
                }
                capLine.put("clusters", clusters)
                val primeCore = pserver.getCpuCoresByCluster(PServerDriver.CpuCluster.PRIME).firstOrNull()
                capLine.put("coreCtl", primeCore != null && java.io.File("/sys/devices/system/cpu/cpu$primeCore/core_ctl").exists())
            }
            log.write(capLine)
            Timber.tag(TAG).i(
                "capabilities: model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} adpf=${probe.supported} (${probe.detail}, rate=${probe.preferredRateNs}) " +
                    "pserver=${caps.pserverAvailable} powerControl=$powerControl performance_mode=$performanceMode " +
                    "display=${displayHz}Hz limiter=$limiterFps target=${"%.2f".format(targetNs / 1e6)}ms -> ${choice.kind} (${choice.reason})",
            )
            if (performanceMode == 2) {
                log.event("performance_mode=2 (High): the vendor holds the prime core's minimum clock up every 10 s, leaving FrameBoost nothing to scale down. Standard or Medium recommended with FrameBoost.")
            }

            backend = when (choice.kind) {
                BackendKind.ADPF -> Adpf.create(context) ?: NoopBackend("hint API backend could not be created")
                BackendKind.ROOT -> {
                    val root = pserver?.let { RootPinBackend(it, scanner, log) }
                    if (root != null && root.available) root else NoopBackend("root backend: no prime cluster")
                }
                BackendKind.OFF -> NoopBackend(choice.reason)
            }
            if (backend is NoopBackend) {
                log.event("FrameBoost off: ${(backend as NoopBackend).reason}")
            } else {
                log.event("FrameBoost ${backend!!.name}: waiting for $exeName (${choice.reason})")
                threadSet = ThreadSet(scanner, exeName)
            }
        }

        private fun loop() {
            while (running) {
                Thread.sleep(TICK_MS)
                val now = System.nanoTime()
                val b = backend ?: return

                val fps = pendingLimiterFps
                if (fps != limiterFps) {
                    limiterFps = fps
                    targetNs = targetNsFor(fps, displayHz)
                    if (backendStarted) b.onTargetChanged(targetNs)
                    log.event("limiter -> $fps fps, target ${"%.2f".format(targetNs / 1e6)} ms")
                }

                if (PowerManager.isProfilePowerControlEnabled()) {
                    log.event("Power Control turned on for this game; FrameBoost off for the rest of the session")
                    return
                }

                val ts = threadSet ?: return
                if (!backendStarted) {
                    if (now - lastLocateNs < LOCATE_NS) continue
                    lastLocateNs = now
                    if (!ts.locate()) continue
                    val sel = ts.sample(now) ?: continue
                    if (!b.start(sel, targetNs)) {
                        log.event("${b.name} backend failed to start; FrameBoost off")
                        return
                    }
                    selection = sel
                    backendStarted = true
                    FrameClock.reset()
                    readIndex = 0L
                    prevTimeNs = 0L
                    FrameClock.enabled = true
                    frameBackend = b
                    lastWindowNs = now
                    lastRescanNs = now
                    log.event("${b.name} backend started: $sel")
                    continue
                }

                if (!ts.gameAlive()) {
                    log.event("game process ${ts.gamePid} exited")
                    stopBackend(b)
                    continue
                }

                if (now - lastRescanNs >= RESCAN_NS) {
                    lastRescanNs = now
                    val sel = ts.sample(now)
                    if (sel != null && sel != selection) {
                        selection = sel
                        b.onThreadsChanged(sel)
                        log.event("threads -> $sel")
                    }
                }

                if (now - lastWindowNs >= WINDOW_NS) {
                    lastWindowNs = now
                    window(b, now)
                }
            }
        }

        private fun window(b: BoostBackend, now: Long) {
            val count = FrameClock.copyFrom(readIndex, times, waits)
            readIndex = FrameClock.writeIndex()
            val stats = WorkWindow.compute(prevTimeNs, times, waits, count, targetNs, workScratch, intervalScratch)
            if (count > 0) prevTimeNs = times[count - 1]
            b.onWindow(stats, targetNs, now)
            windows++
            val headroom = thermalHeadroom()
            val sel = selection
            val line = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("game", exeName)
                .put("backend", b.name)
                .put("fps", round2(stats.fps))
                .put("p50Ms", round2(stats.p50WorkMs))
                .put("p90Ms", round2(stats.p90WorkMs))
                .put("meanMs", round2(stats.meanWorkMs))
                .put("p90IntervalMs", round2(stats.p90IntervalMs))
                .put("targetMs", round2((targetNs / 1e6).toFloat()))
                .put("missPct", round2(stats.missPct))
                .put("injectedWaitMs", round2(stats.meanInjectedWaitMs))
                .put("frames", stats.frames)
                .put("tids", sel?.allTids?.joinToString(","))
                .put("thermalHeadroom", headroom?.let { round2(it) })
            for ((k, v) in b.status()) line.put(k, v)
            log.write(line)
            if (windows % LOGCAT_EVERY_WINDOWS == 1L) {
                Timber.tag(TAG).i(
                    "${b.name}: fps=${round2(stats.fps)} p50=${round2(stats.p50WorkMs)} p90=${round2(stats.p90WorkMs)} " +
                        "target=${round2((targetNs / 1e6).toFloat())} miss=${round2(stats.missPct)}% wait=${round2(stats.meanInjectedWaitMs)} " +
                        "frames=${stats.frames} ${b.status()}",
                )
            }
        }

        private fun stopBackend(b: BoostBackend) {
            FrameClock.enabled = false
            frameBackend = null
            backendStarted = false
            selection = null
            runCatching { b.stop() }
        }

        private fun teardown() {
            backend?.let { if (backendStarted) stopBackend(it) }
            FrameClock.enabled = false
            frameBackend = null
            log.event("FrameBoost stopped")
            log.close()
        }

        private fun thermalHeadroom(): Float? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return runCatching {
                context.getSystemService(AndroidPowerManager::class.java)?.getThermalHeadroom(10)?.takeIf { it.isFinite() }
            }.getOrNull()
        }
    }


    /** Target frame time: the limiter's when on, else the panel's refresh rate. */
    internal fun targetNsFor(limiterFps: Int, displayHz: Float): Long {
        val hz = if (limiterFps > 0) limiterFps.toFloat() else displayHz.takeIf { it > 0f } ?: 60f
        return (1e9 / hz).toLong()
    }

    private fun displayRefreshHz(context: Context): Float {
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return 60f
        val hz = runCatching { display.mode.refreshRate }.getOrNull() ?: display.refreshRate
        return if (hz.isFinite() && hz > 0f) hz else 60f
    }

    private fun exeNameOf(container: Container): String {
        val name = container.executablePath.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        return if (name.isEmpty() || name.endsWith(".exe", ignoreCase = true)) name else "$name.exe"
    }

    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0
}
