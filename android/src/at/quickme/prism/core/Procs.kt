// CPU time per process and per thread from /proc (android/METRICS.md):
// every process's /proc/<pid>/stat as the shell user reads them, and
// Prism's own threads (/proc/self/task/<tid>/stat), so a process or a
// thread that keeps the phone busy shows by name. Counters are summed over
// the processes (threads) of the same name from snapshot to snapshot
// (Accumulator), so a process that restarts doesn't reset its name's count.

package at.quickme.prism.core

object Procs {
    /** One /proc/<pid>/stat: its comm, CPU time (utime + stime) and start, in clock ticks. */
    class Stat internal constructor(
        @JvmField val pid: Int,
        @JvmField val comm: String,
        @JvmField val ticks: Long,
        @JvmField val startTicks: Long,
    )

    /**
     * "pid (comm) state ppid …": comm may hold spaces and parentheses, so the
     * fields after it are counted from its last ')'. utime and stime are
     * fields 14 and 15, starttime 22 (proc(5)).
     */
    @JvmStatic
    fun parseStat(line: String): Stat? {
        val open = line.indexOf(" (")
        val close = line.lastIndexOf(')')
        if (open <= 0 || close < open) return null
        val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val rest = line.substring(close + 1).trim().split(' ')
        // rest[0] is field 3 (state).
        if (rest.size < 20) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        val start = rest[19].toLongOrNull() ?: return null
        return Stat(pid, line.substring(open + 2, close), utime + stime, start)
    }

    /** Every stat line in `text`; lines that don't parse are skipped. */
    @JvmStatic
    fun parseStats(text: String): List<Stat> = text.lineSequence().mapNotNull { parseStat(it) }.toList()

    /**
     * `ps -A -o PID,NAME` (toybox): pid -> process name, argv[0] without its
     * directory (an app's process is its package name, maybe with ":service").
     */
    @JvmStatic
    fun parsePs(text: String): Map<Int, String> {
        val names = HashMap<Int, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            val space = t.indexOf(' ')
            if (space <= 0) continue
            val pid = t.substring(0, space).toIntOrNull() ?: continue
            val name = t.substring(space + 1).trim().substringAfterLast('/')
            if (name.isNotEmpty()) names[pid] = name
        }
        return names
    }

    /** A thread name with its number dropped ("pool-3-thread-12" -> "pool-N-thread-N"), so pools add up. */
    @JvmStatic
    fun threadGroup(comm: String): String = comm.replace(Regex("[0-9]+"), "N").ifEmpty { "?" }

    /**
     * CPU time per name, summed from snapshot to snapshot. A process (thread)
     * is known by its pid and start time; one that started since the last
     * snapshot counts in full, one that ended keeps what it had. The first
     * snapshot only sets the baseline, so the totals count from then on.
     */
    class Accumulator {
        private var last: Map<String, Long>? = null
        private val totals = HashMap<String, Long>()

        /** Adds a snapshot of (name, stat) and returns the totals per name, in ticks. */
        @Synchronized
        fun add(snapshot: List<Pair<String, Stat>>): Map<String, Long> {
            val prev = last
            val now = HashMap<String, Long>()
            for ((name, s) in snapshot) {
                val key = s.pid.toString() + "@" + s.startTicks
                now[key] = s.ticks
                val before = prev?.get(key)
                val delta = when {
                    prev == null -> 0L
                    before == null -> s.ticks
                    else -> maxOf(0L, s.ticks - before)
                }
                totals[name] = (totals[name] ?: 0L) + delta
            }
            last = now
            return HashMap(totals)
        }
    }
}
