// `dumpsys batterystats -c`: Android's comma-separated battery stats
// (BatteryStats.dumpCheckinLocked in AOSP). Every line is
//   <version>,<uid>,<category>,<type>,<values…>
// Category "l" is "since the last full charge"; only those lines are read, so
// every number here is a counter that restarts at a full charge. Units as
// AOSP prints them: times in ms unless noted, charge in mAh.

package at.quickme.prism.core

import java.io.BufferedReader
import java.io.StringReader

class Checkin {
    /** Per uid, everything the metrics use. */
    class App internal constructor(@JvmField val uid: Int) {
        @JvmField var mah = 0.0                       // pwi: estimated charge
        @JvmField var cpuUserMs = 0L                  // cpu
        @JvmField var cpuSystemMs = 0L
        @JvmField var wakelockPartialMs = 0L          // wl, summed over its wakelocks
        @JvmField var wakelockFullMs = 0L
        @JvmField var wakelockWindowMs = 0L
        @JvmField var wakelockPartialCount = 0L
        @JvmField var wakelockFullCount = 0L
        @JvmField var wakelockWindowCount = 0L
        @JvmField var wakeupAlarms = 0L               // wua, summed
        @JvmField var mobileRxBytes = 0L              // nt
        @JvmField var mobileTxBytes = 0L
        @JvmField var wifiRxBytes = 0L
        @JvmField var wifiTxBytes = 0L
        @JvmField var mobileActiveUs = 0L
        @JvmField var mobileActiveCount = 0L
        @JvmField var mobileWakeups = 0L
        @JvmField var wifiWakeups = 0L
        @JvmField var wifiRunningUs = 0L              // wfl
        @JvmField var wifiScanUs = 0L
        @JvmField var foregroundMs = 0L               // fg, fgs
        @JvmField var foregroundServiceMs = 0L
        @JvmField var stateMillis: LongArray? = null // st: per process state, milliseconds

        /** A rough weight for picking the top apps when there is no charge estimate. */
        fun weight(): Double = mah * 1e9 + wakelockPartialMs + cpuUserMs + cpuSystemMs + mobileActiveUs / 1000.0 +
            (mobileRxBytes + mobileTxBytes + wifiRxBytes + wifiTxBytes) / 1e6
    }

    @JvmField val apps = HashMap<Int, App>()

    /** app id (uid modulo 100000) -> its packages, from the "uid" lines. */
    @JvmField val packages = HashMap<Int, MutableList<String>>()

    // Device-wide, since the last charge.
    @JvmField var onBatteryMs = 0L
    @JvmField var onBatteryScreenOffMs = 0L
    @JvmField var statsStartClockMs = 0L
    @JvmField var estimatedCapacityMah = 0.0
    @JvmField var screenOnMs = 0L
    @JvmField var interactiveMs = 0L
    @JvmField var mobileRadioActiveMs = 0L
    @JvmField var fullWakelockMs = 0L
    @JvmField var partialWakelockMs = 0L
    @JvmField var deviceIdleDeepMs = 0L
    @JvmField var deviceIdleLightMs = 0L
    @JvmField var powerSaveMs = 0L
    @JvmField var wifiOnMs = 0L
    @JvmField var wifiRunningMs = 0L
    @JvmField var lines = 0

    /** Lines of category "l" (since the last charge); 0 means the stats themselves were missing. */
    @JvmField var statLines = 0

    /** A named timer: a kernel wakelock, a wakeup reason, an app's wakelock or job. */
    class Timer internal constructor(@JvmField val uid: Int, @JvmField val name: String) {
        @JvmField var ms = 0L
        @JvmField var count = 0L
    }

    /** kwl: kernel wakelocks (wakeup sources), by name. */
    @JvmField val kernelWakelocks = LinkedHashMap<String, Timer>()

    /** wr: why the SoC woke up (the kernel's wakeup reasons), by name. */
    @JvmField val wakeupReasons = LinkedHashMap<String, Timer>()

    /** wl: every app's wakelocks by (uid, name), partial time and count only. */
    @JvmField val wakelocks = LinkedHashMap<String, Timer>()

    /** jb: scheduled jobs by (uid, name). */
    @JvmField val jobs = LinkedHashMap<String, Timer>()

    /** wua: alarms that woke the device, by (uid, name); count only. */
    @JvmField val alarms = LinkedHashMap<String, Timer>()

    /** sgt: time per mobile signal strength bin (SIGNAL_STRENGTH_NAMES). */
    @JvmField var signalStrengthMs: LongArray? = null

    /** dct: time per data connection type (DATA_CONNECTION_NAMES). */
    @JvmField var dataConnectionMs: LongArray? = null

    private fun timer(map: MutableMap<String, Timer>, uid: Int, name: String): Timer =
        map.getOrPut(uid.toString() + "/" + name) { Timer(uid, name) }

    private fun app(uid: Int): App = apps.getOrPut(uid) { App(uid) }

    private fun line(f: List<String>) {
        val uid = f[1].toInt()
        val category = f[2]
        val type = f[3]
        if (category == "i" && type == "uid" && f.size >= 6) {
            val list = packages.getOrPut(f[4].toInt()) { ArrayList() }
            if (f[5] !in list) list.add(f[5])
            lines++
            return
        }
        if (category != "l") return
        lines++
        statLines++
        when (type) {
            "bt" -> {
                onBatteryMs = l(f, 5)
                statsStartClockMs = l(f, 9)
                onBatteryScreenOffMs = l(f, 10)
                estimatedCapacityMah = d(f, 12)
            }
            "m" -> {
                screenOnMs = l(f, 4)
                fullWakelockMs = l(f, 6)
                partialWakelockMs = l(f, 7)
                mobileRadioActiveMs = l(f, 8)
                interactiveMs = l(f, 10)
                powerSaveMs = l(f, 11)
                deviceIdleDeepMs = l(f, 13)
                deviceIdleLightMs = l(f, 19)
            }
            "gwfl" -> {
                wifiOnMs = l(f, 4)
                wifiRunningMs = l(f, 5)
            }
            "pwi" -> if (f[4] == "uid") app(uid).mah += d(f, 5)
            "cpu" -> {
                app(uid).cpuUserMs += l(f, 4)
                app(uid).cpuSystemMs += l(f, 5)
            }
            "wl" -> {
                // name, then groups of: time ms, kind (f/p/bp/w), count, current, max, total duration.
                val a = app(uid)
                val named = timer(wakelocks, uid, f[4])
                var i = 5
                while (i + 2 < f.size) {
                    val time = l(f, i)
                    val count = l(f, i + 2)
                    when (f[i + 1]) {
                        "p" -> {
                            a.wakelockPartialMs += time
                            a.wakelockPartialCount += count
                            named.ms += time
                            named.count += count
                        }
                        "f" -> { a.wakelockFullMs += time; a.wakelockFullCount += count }
                        "w" -> { a.wakelockWindowMs += time; a.wakelockWindowCount += count }
                        else -> {} // "bp" (background partial) is part of "p"
                    }
                    i += 6
                }
            }
            "wua" -> {
                app(uid).wakeupAlarms += l(f, 5)
                timer(alarms, uid, f[4]).count += l(f, 5)
            }
            "jb" -> timer(jobs, uid, f[4]).also { it.ms += l(f, 5); it.count += l(f, 6) }
            "kwl", "wr" -> {
                // A quoted name, which may itself hold commas, then time ms and count.
                var end = 4
                while (end < f.size - 1 && !(f[end].length > 1 && f[end].endsWith("\""))) end++
                val name = f.subList(4, end + 1).joinToString(",").removeSurrounding("\"")
                val t = timer(if (type == "kwl") kernelWakelocks else wakeupReasons, 0, name)
                t.ms += l(f, end + 1)
                t.count += l(f, end + 2)
            }
            "sgt" -> signalStrengthMs = LongArray(f.size - 4) { l(f, 4 + it) }
            "dct" -> dataConnectionMs = LongArray(f.size - 4) { l(f, 4 + it) }
            "nt" -> {
                val a = app(uid)
                a.mobileRxBytes += l(f, 4)
                a.mobileTxBytes += l(f, 5)
                a.wifiRxBytes += l(f, 6)
                a.wifiTxBytes += l(f, 7)
                a.mobileActiveUs += l(f, 12)
                a.mobileActiveCount += l(f, 13)
                a.mobileWakeups += l(f, 16)
                a.wifiWakeups += l(f, 17)
            }
            "wfl" -> {
                app(uid).wifiScanUs += l(f, 5)
                app(uid).wifiRunningUs += l(f, 6)
            }
            "fg" -> app(uid).foregroundMs += l(f, 4)
            "fgs" -> app(uid).foregroundServiceMs += l(f, 4)
            "st" -> app(uid).stateMillis = LongArray(PROCESS_STATES.size) { l(f, 4 + it) }
        }
    }

    /** The name to show for a uid: its package, a shared one, or a system name. */
    fun nameOf(uid: Int): String {
        val appId = uid % 100000
        val list = packages[appId]
        if (!list.isNullOrEmpty()) {
            if (list.size == 1) return list[0]
            val sorted = list.sorted()
            return sorted[0] + "+" + (sorted.size - 1)
        }
        return when (appId) {
            0 -> "root"
            1000 -> "android.system"
            1001 -> "android.radio"
            1002 -> "android.bluetooth"
            1010 -> "android.wifi"
            1013 -> "android.media"
            1041 -> "android.audioserver"
            1047 -> "android.cameraserver"
            2000 -> "shell"
            else -> if (appId < 10000) "system.$appId" else "uid.$appId"
        }
    }

    companion object {
        /** Process states in the order of "st" (BatteryStats.Uid.PROCESS_STATE_*). */
        @JvmField
        val PROCESS_STATES = arrayOf("top", "foreground_service", "foreground", "background", "top_sleeping", "heavy_weight", "cached")

        /** Mobile signal strength bins in the order of "sgt" (CellSignalStrength.SIGNAL_STRENGTH_NAMES). */
        @JvmField
        val SIGNAL_STRENGTHS = arrayOf("none", "poor", "moderate", "good", "great")

        /** Data connection types in the order of "dct" (BatteryStats.DATA_CONNECTION_NAMES). */
        @JvmField
        val DATA_CONNECTIONS = arrayOf(
            "none", "gprs", "edge", "umts", "cdma", "evdo_0", "evdo_a", "1xrtt", "hsdpa", "hsupa", "hspa", "iden",
            "evdo_b", "lte", "ehrpd", "hspap", "gsm", "td_scdma", "iwlan", "lte_ca", "nr", "emergency", "other",
        )

        @JvmStatic
        fun parse(text: String): Checkin = parse(BufferedReader(StringReader(text)))

        /**
         * Reads `dumpsys batterystats -c` line by line. Not `--checkin`: that one
         * prints (and deletes) the stats saved at the last charge, not the current
         * ones. `-c` also prints the battery history ("<version>,h,…"), which can
         * be large; those lines are skipped without splitting them.
         */
        @JvmStatic
        fun parse(input: BufferedReader): Checkin {
            val c = Checkin()
            while (true) {
                val line = input.readLine() ?: break
                val comma = line.indexOf(',')
                if (comma < 0 || line.startsWith("h,", comma + 1)) continue
                val f = line.trim().split(",")
                if (f.size < 5) continue
                try {
                    c.line(f)
                } catch (e: RuntimeException) {
                    // A field this parser doesn't understand: skip the line, keep the rest.
                }
            }
            return c
        }

        private fun l(f: List<String>, i: Int): Long = if (i < f.size && f[i].isNotEmpty()) f[i].trim().toLong() else 0

        private fun d(f: List<String>, i: Int): Double = if (i < f.size && f[i].isNotEmpty()) f[i].trim().toDouble() else 0.0
    }
}
