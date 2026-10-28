package app.gamenative.frameboost

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import timber.log.Timber

/**
 * logcat tag `FrameBoost` for events, plus one JSONL file per game session under
 * `Android/data/<package>/files/frameboost/` (readable over adb) for the device A/B tests.
 */
class FrameBoostLog(context: Context) {
    private val dir: File = context.getExternalFilesDir("frameboost") ?: File(context.filesDir, "frameboost")
    val file: File
    private var writer: BufferedWriter?

    init {
        dir.mkdirs()
        prune()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "frameboost-$stamp.jsonl")
        writer = runCatching { BufferedWriter(FileWriter(file, true)) }.getOrNull()
    }

    @Synchronized
    fun write(json: JSONObject) {
        val w = writer ?: return
        try {
            w.write(json.toString())
            w.newLine()
            w.flush()
        } catch (_: Exception) {
        }
    }

    fun event(message: String) {
        Timber.tag(TAG).i(message)
        write(JSONObject().put("t", System.currentTimeMillis()).put("event", message))
    }

    @Synchronized
    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.startsWith("frameboost-") && f.name.endsWith(".jsonl") } ?: return
        files.sortedBy { it.name }.dropLast(KEEP_FILES - 1).forEach { it.delete() }
    }

    companion object {
        const val TAG = "FrameBoost"
        private const val KEEP_FILES = 20
    }
}
