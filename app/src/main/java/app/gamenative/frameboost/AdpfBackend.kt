package app.gamenative.frameboost

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi

data class AdpfProbe(val supported: Boolean, val preferredRateNs: Long, val detail: String)

/** API-level-safe entry points; [AdpfBackend] itself needs API 31. */
object Adpf {
    /** Whether this device can run hint sessions at all. Cheap; creates and closes one test session. */
    fun probe(context: Context): AdpfProbe {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return AdpfProbe(false, -1L, "Android API < 31")
        return probeS(context)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun probeS(context: Context): AdpfProbe {
        val manager = context.getSystemService(PerformanceHintManager::class.java)
            ?: return AdpfProbe(false, -1L, "no PerformanceHintManager service")
        val rate = try {
            manager.preferredUpdateRateNanos
        } catch (e: Exception) {
            -1L
        }
        if (rate < 0L) return AdpfProbe(false, rate, "preferredUpdateRateNanos < 0: the power HAL has no hint-session support")
        val test = try {
            manager.createHintSession(intArrayOf(Process.myTid()), 16_666_666L)
        } catch (e: Exception) {
            null
        } ?: return AdpfProbe(false, rate, "createHintSession returned null")
        try {
            test.close()
        } catch (_: Exception) {
        }
        return AdpfProbe(true, rate, "ok")
    }

    fun create(context: Context): BoostBackend? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return createS(context)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createS(context: Context): BoostBackend? {
        val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
        val rate = try {
            manager.preferredUpdateRateNanos
        } catch (e: Exception) {
            -1L
        }
        if (rate < 0L) return null
        return AdpfBackend(manager, rate)
    }
}

/**
 * Android Dynamic Performance Framework backend: a [PerformanceHintManager] hint session over the
 * game's busy threads and wineserver. Every frame's CPU work (present interval minus the FPS
 * limiter's injected wait) is reported against the target, batched to the HAL's preferred update
 * rate, and Android raises or lowers clocks and placement on its own. No root needed.
 *
 * Android 13 (both target devices) has no [PerformanceHintManager.Session.setThreads]; the
 * session is closed and recreated when the thread set changes. API 34+ uses setThreads.
 */
@RequiresApi(Build.VERSION_CODES.S)
class AdpfBackend internal constructor(
    private val manager: PerformanceHintManager,
    val preferredRateNs: Long,
) : BoostBackend {
    override val name = "adpf"

    @Volatile
    private var session: PerformanceHintManager.Session? = null

    @Volatile
    private var targetNs = 0L

    @Volatile
    private var tids: IntArray = IntArray(0)

    private val reportEveryNs = preferredRateNs.coerceAtLeast(0L)

    // Render-path batching state; touched only by onFrame.
    private var batchWorkNs = 0L
    private var batchCount = 0
    private var lastReportNs = 0L

    @Volatile
    private var lastReportedNs = 0L

    @Volatile
    private var reports = 0L

    @Volatile
    private var sessionsCreated = 0

    override fun start(selection: ThreadSelection, targetNs: Long): Boolean {
        this.targetNs = targetNs
        return openSession(selection.allTids)
    }

    private fun openSession(newTids: IntArray): Boolean {
        closeSession()
        val s = try {
            manager.createHintSession(newTids, targetNs)
        } catch (e: Exception) {
            null
        }
        session = s
        tids = newTids
        if (s != null) sessionsCreated++
        return s != null
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    override fun onThreadsChanged(selection: ThreadSelection) {
        val newTids = selection.allTids
        if (newTids.contentEquals(tids)) return
        val s = session
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                s.setThreads(newTids)
                tids = newTids
                return
            } catch (_: Exception) {
                // fall through to recreate
            }
        }
        openSession(newTids)
    }

    override fun onTargetChanged(targetNs: Long) {
        this.targetNs = targetNs
        try {
            session?.updateTargetWorkDuration(targetNs)
        } catch (_: Exception) {
        }
    }

    override fun onFrame(workNs: Long, nowNs: Long) {
        val s = session ?: return
        batchWorkNs += workNs
        batchCount++
        if (nowNs - lastReportNs < reportEveryNs) return
        val mean = batchWorkNs / batchCount
        batchWorkNs = 0L
        batchCount = 0
        lastReportNs = nowNs
        try {
            s.reportActualWorkDuration(mean)
            lastReportedNs = mean
            reports++
        } catch (_: Exception) {
            // session closed under us; the worker recreates it
        }
    }

    override fun stop() {
        closeSession()
    }

    override fun status(): Map<String, Any?> = mapOf(
        "hintSession" to (session != null),
        "sessions" to sessionsCreated,
        "reports" to reports,
        "lastReportMs" to lastReportedNs / 1_000_000f,
        "preferredRateNs" to preferredRateNs,
        "tids" to tids.size,
    )
}
