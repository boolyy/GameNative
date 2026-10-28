package app.gamenative.frameboost

/**
 * Closed loop on the prime cluster's maximum frequency for the root backend: "only as much CPU
 * as the frame-time target needs". Pure logic, driven once per window (about 2 s).
 *
 * - p90 work time below 0.80 × target for 3 windows in a row: one step down (never below the
 *   cluster's scaling_min_freq, which the vendor's performance mode holds up).
 * - p90 above 0.95 × target, or more than 5 % of frames over the target: two steps up at once.
 * - At the floor with p90 below 0.6 × target for 10 s: release (unpin, give core_ctl back).
 *   The first window with misses re-pins.
 * - Never more than one change per 6 s, so it cannot hunt.
 */
class ClockCapController(
    stepsKhz: List<Long>,
    floorKhz: Long,
    private val minChangeIntervalNs: Long = 6_000_000_000L,
    private val releaseAfterNs: Long = 10_000_000_000L,
) {
    sealed class Decision {
        object None : Decision()
        data class SetCap(val khz: Long) : Decision()
        object Release : Decision()
        data class Repin(val khz: Long) : Decision()
    }

    /** Usable cap values, ascending; the floor is included so there is always a step. */
    val steps: List<Long> = run {
        val above = stepsKhz.filter { it >= floorKhz }.distinct().sorted()
        when {
            above.isEmpty() -> listOf(stepsKhz.maxOrNull() ?: floorKhz)
            else -> above
        }
    }

    private var index = steps.lastIndex
    private var lowStreak = 0
    private var lastChangeNs = Long.MIN_VALUE / 2
    private var floorLowSinceNs = -1L

    var released = false
        private set

    val currentCapKhz: Long get() = steps[index]
    val atMax: Boolean get() = index == steps.lastIndex
    val atFloor: Boolean get() = index == 0

    fun onWindow(p90WorkNs: Long, missPct: Float, targetNs: Long, nowNs: Long): Decision {
        if (targetNs <= 0L) return Decision.None
        val high = p90WorkNs > targetNs * 0.95 || missPct > 5f
        if (released) {
            if (!high) return Decision.None
            released = false
            index = steps.lastIndex
            lowStreak = 0
            lastChangeNs = nowNs
            return Decision.Repin(steps[index])
        }
        if (high) {
            lowStreak = 0
            floorLowSinceNs = -1L
            val next = minOf(index + 2, steps.lastIndex)
            if (next == index) return Decision.None
            index = next
            lastChangeNs = nowNs
            return Decision.SetCap(steps[index])
        }
        if (p90WorkNs < targetNs * 0.80) lowStreak++ else lowStreak = 0

        if (index == 0 && p90WorkNs < targetNs * 0.60) {
            if (floorLowSinceNs < 0L) floorLowSinceNs = nowNs
            if (nowNs - floorLowSinceNs >= releaseAfterNs) {
                released = true
                floorLowSinceNs = -1L
                lowStreak = 0
                lastChangeNs = nowNs
                return Decision.Release
            }
        } else {
            floorLowSinceNs = -1L
        }

        if (lowStreak >= 3 && index > 0 && nowNs - lastChangeNs >= minChangeIntervalNs) {
            index--
            lowStreak = 0
            lastChangeNs = nowNs
            return Decision.SetCap(steps[index])
        }
        return Decision.None
    }
}
