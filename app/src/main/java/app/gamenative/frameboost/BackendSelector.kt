package app.gamenative.frameboost

/** What the device offers, gathered at session start. */
data class Capabilities(
    val sdk: Int,
    val adpfSupported: Boolean,
    val pserverAvailable: Boolean,
    val powerControlActive: Boolean,
)

enum class BackendKind { OFF, ADPF, ROOT }

data class BackendChoice(val kind: BackendKind, val reason: String)

/** Pure: global setting × per-game override × device capabilities → backend. */
object BackendSelector {
    fun select(global: FrameBoostMode, override: FrameBoostMode?, caps: Capabilities): BackendChoice {
        val mode = override ?: global
        val source = if (override != null) "${FrameBoostMode.ENV_OVERRIDE}=${override.key}" else "setting=${global.key}"
        if (mode == FrameBoostMode.OFF) return BackendChoice(BackendKind.OFF, "off ($source)")
        if (caps.powerControlActive) {
            return BackendChoice(BackendKind.OFF, "Power Control is active for this game; FrameBoost never runs alongside it ($source)")
        }
        return when (mode) {
            FrameBoostMode.ADPF -> if (caps.adpfSupported) {
                BackendChoice(BackendKind.ADPF, "hint API ($source)")
            } else {
                BackendChoice(BackendKind.OFF, "hint API only, but the device has no hint-session support ($source)")
            }
            FrameBoostMode.ROOT -> if (caps.pserverAvailable) {
                BackendChoice(BackendKind.ROOT, "root ($source)")
            } else {
                BackendChoice(BackendKind.OFF, "root only, but PServer is not available ($source)")
            }
            FrameBoostMode.AUTO -> when {
                caps.adpfSupported -> BackendChoice(BackendKind.ADPF, "auto: hint API supported ($source)")
                caps.pserverAvailable -> BackendChoice(BackendKind.ROOT, "auto: no hint-session support, PServer available ($source)")
                else -> BackendChoice(BackendKind.OFF, "auto: neither hint sessions nor PServer available ($source)")
            }
            FrameBoostMode.OFF -> BackendChoice(BackendKind.OFF, "off ($source)")
        }
    }
}
