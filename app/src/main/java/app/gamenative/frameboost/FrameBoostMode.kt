package app.gamenative.frameboost

/** Settings → Performance → FrameBoost, and the per-container `GN_FRAMEBOOST` override. */
enum class FrameBoostMode(val key: String) {
    OFF("off"),
    AUTO("auto"),
    ADPF("adpf"),
    ROOT("root"),
    ;

    companion object {
        const val ENV_OVERRIDE = "GN_FRAMEBOOST"

        /** Accepts the keys above plus a few aliases; null for blank or unknown text. */
        fun fromKey(value: String?): FrameBoostMode? {
            val k = value?.trim()?.lowercase() ?: return null
            return when (k) {
                "" -> null
                "hint", "hints", "hintapi", "hint_api" -> ADPF
                "on", "default" -> AUTO
                "0", "false", "none" -> OFF
                else -> entries.firstOrNull { it.key == k }
            }
        }
    }
}
