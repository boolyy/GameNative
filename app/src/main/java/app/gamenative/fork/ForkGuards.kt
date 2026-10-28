package app.gamenative.fork

import app.gamenative.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * GameNative Perf (see FORK.md): a modified build must not feed GameNative's community
 * database or analytics, and their API needs a Play Integrity token tied to upstream's signing
 * key anyway. Every upload path asks here first.
 */
object ForkGuards {
    private val logged = AtomicBoolean(false)

    /** True when this is the fork build; logs "upload skipped" once per process. */
    fun uploadsDisabled(what: String): Boolean {
        if (!BuildConfig.PERF_FORK) return false
        if (logged.compareAndSet(false, true)) {
            Timber.tag("FrameBoost").i("upload skipped (fork): $what")
        } else {
            Timber.tag("FrameBoost").d("upload skipped (fork): $what")
        }
        return true
    }
}
