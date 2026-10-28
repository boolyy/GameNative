package app.gamenative.frameboost

/**
 * One way of getting the game the CPU it needs. Chosen once per session by [BackendSelector].
 * All methods except [onFrame] run on the FrameBoost worker thread.
 */
interface BoostBackend {
    val name: String

    /** Called once the game process is known. False means the backend could not be set up. */
    fun start(selection: ThreadSelection, targetNs: Long): Boolean

    fun onThreadsChanged(selection: ThreadSelection)

    fun onTargetChanged(targetNs: Long)

    /** Render path: one presented frame's CPU work. Must be cheap, allocation-free and lock-free. */
    fun onFrame(workNs: Long, nowNs: Long) {}

    /** Every window (about 2 s) with that window's statistics. */
    fun onWindow(stats: WorkStats, targetNs: Long, nowNs: Long) {}

    fun stop()

    /** Backend-specific fields for the JSONL log line. */
    fun status(): Map<String, Any?> = emptyMap()
}

/** Nothing active; the reason is in the log. */
class NoopBackend(val reason: String) : BoostBackend {
    override val name = "off"
    override fun start(selection: ThreadSelection, targetNs: Long): Boolean = true
    override fun onThreadsChanged(selection: ThreadSelection) {}
    override fun onTargetChanged(targetNs: Long) {}
    override fun stop() {}
    override fun status(): Map<String, Any?> = mapOf("reason" to reason)
}
