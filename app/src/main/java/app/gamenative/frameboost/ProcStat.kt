package app.gamenative.frameboost

import java.io.File

/** One thread's `/proc/<pid>/task/<tid>/stat`: its name and CPU time in clock ticks. */
data class StatEntry(val comm: String, val cpuTicks: Long)

/** Parsers for the `/proc` text formats FrameBoost reads. Pure functions, unit-tested. */
object ProcStat {
    /** Linux USER_HZ; `/proc/[pid]/stat` times are in these ticks on every Android kernel. */
    const val CLK_TCK = 100

    /**
     * Parses a `stat` line. The comm (field 2) is in parentheses and may contain spaces and
     * parentheses itself (Wine threads are named after DLLs and functions), so the fields are
     * taken from after the last ')'. utime and stime are fields 14 and 15.
     */
    fun parseStat(line: String): StatEntry? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val comm = line.substring(open + 1, close)
        val rest = line.substring(close + 1).trim().split(' ')
        // rest[0] = state (field 3), so field N is rest[N - 3]
        if (rest.size < 13) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return StatEntry(comm, utime + stime)
    }

    /** `/proc/<pid>/cmdline` is NUL-separated; a trailing NUL is normal. */
    fun parseCmdline(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        return String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    }

    /** Parses a CPU list like `0-3,7` (the `Cpus_allowed_list` line of `status`). */
    fun parseCpuList(text: String): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (part in text.trim().split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val dash = p.indexOf('-')
            if (dash > 0) {
                val a = p.substring(0, dash).toIntOrNull() ?: continue
                val b = p.substring(dash + 1).toIntOrNull() ?: continue
                for (c in minOf(a, b)..maxOf(a, b)) out.add(c)
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /** Finds `Cpus_allowed_list:` in a `status` file's text. */
    fun parseAllowedCpus(statusText: String): Set<Int>? {
        for (line in statusText.lineSequence()) {
            if (line.startsWith("Cpus_allowed_list:")) {
                return parseCpuList(line.substringAfter(':'))
            }
        }
        return null
    }
}

/**
 * Reads `/proc` for processes of this app's UID. Wine processes are children of GameNative under
 * the same UID, so they are visible without root even with `hidepid=2`.
 */
open class ProcScanner(private val procRoot: File = File("/proc")) {

    open fun listPids(): List<Int> =
        procRoot.list()?.mapNotNull { name -> if (name.all { it.isDigit() }) name.toIntOrNull() else null } ?: emptyList()

    open fun exists(pid: Int): Boolean = File(procRoot, pid.toString()).isDirectory

    open fun cmdline(pid: Int): List<String> = runCatching {
        ProcStat.parseCmdline(File(procRoot, "$pid/cmdline").readBytes())
    }.getOrDefault(emptyList())

    /** tid → stat entry for every thread of [pid]; threads that vanish mid-read are skipped. */
    open fun threadStats(pid: Int): Map<Int, StatEntry> {
        val taskDir = File(procRoot, "$pid/task")
        val tids = taskDir.list() ?: return emptyMap()
        val out = HashMap<Int, StatEntry>(tids.size * 2)
        for (name in tids) {
            val tid = name.toIntOrNull() ?: continue
            val entry = runCatching { ProcStat.parseStat(File(taskDir, "$name/stat").readText()) }.getOrNull() ?: continue
            out[tid] = entry
        }
        return out
    }

    open fun allowedCpus(pid: Int, tid: Int): Set<Int>? = runCatching {
        ProcStat.parseAllowedCpus(File(procRoot, "$pid/task/$tid/status").readText())
    }.getOrNull()

    /** First pid whose command line mentions [exeName] (case-insensitive) and is not the winhandler. */
    fun findPidByExe(exeName: String): Int? {
        val needle = exeName.lowercase()
        return listPids().firstOrNull { pid ->
            val args = cmdline(pid)
            args.isNotEmpty() &&
                args.none { it.lowercase().contains("winhandler.exe") } &&
                args.any { arg -> arg.lowercase().let { it.endsWith(needle) || it.contains("/$needle") || it.contains("\\$needle") || it == needle } }
        }
    }

    /** First pid whose argv[0] ends with [suffix] (e.g. `wineserver`). */
    fun findPidByArg0Suffix(suffix: String): Int? =
        listPids().firstOrNull { pid -> cmdline(pid).firstOrNull()?.endsWith(suffix) == true }
}
