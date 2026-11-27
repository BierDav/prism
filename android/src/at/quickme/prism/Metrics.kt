// Phone metrics for battery life (android/METRICS.md), pushed as Prometheus
// remote write to the Prism workers (job="prism/phone"), which pass it on to
// the cluster's telemetry (POST /v1/telemetry/write, worker/src/telemetry.rs).
// Not to telemetry.quickme.at any more: that is reachable only over NetBird,
// and a push every 15 minutes kept the phone's NetBird peer from ever idling
// (NetBird idles one after 15 minutes without data), so WireGuard's
// keepalives woke the modem every 25 s.
//
// Cheap on battery by design, with no alarms of its own:
//  - Every 5 minutes, a reading of the battery (BatteryManager) is appended to
//    a small file. It is taken from the relay service's heartbeat, which runs
//    anyway while the phone is awake for push's keepalives (PushService).
//  - Every 15 minutes (the shortest period Android allows), MetricsJob, a
//    JobScheduler job that waits for a network, adds one more reading, reads
//    `dumpsys batterystats --checkin` (with DUMP, which enrollment grants),
//    every process's CPU time (through the shell server, when it runs) and
//    Prism's own threads' CPU time, and pushes everything in one request. The
//    buffer is cleared only after the endpoint accepted it.
//  - While the phone is unplugged with the screen off, the job pushes at most
//    every hour and otherwise only takes its reading: each push wakes the
//    radio, and the readings wait on the phone anyway.

package at.quickme.prism

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import at.quickme.prism.core.Checkin
import at.quickme.prism.core.PhoneMetrics
import at.quickme.prism.core.Procs
import at.quickme.prism.core.RemoteWrite
import at.quickme.prism.shell.Shell
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

object Metrics {
    const val JOB_ID = 4201
    const val SAMPLE_MS = 5 * 60_000L
    const val TOP_APPS = 25

    /** At most a day of readings waits for a push; older ones are dropped. */
    const val MAX_BUFFERED = 288

    /**
     * How often the job pushes while the phone is unplugged with the screen
     * off; 5 minutes less, as a periodic job runs anywhere in its last 5.
     */
    const val IDLE_PUSH_MS = 60 * 60_000L

    @Volatile
    private var status = "not pushed yet"
    private var lastSampleMs = 0L

    @JvmStatic
    fun statusText(): String = status

    /** Schedules the 15-minute push (idempotent; also after boot and updates). */
    @JvmStatic
    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, MetricsJob::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15), TimeUnit.MINUTES.toMillis(5))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build(),
        )
    }

    /** A battery reading if the last one is 5 minutes old; called from the relay's heartbeat. */
    @JvmStatic
    @Synchronized
    fun sampleIfDue(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSampleMs < SAMPLE_MS - 10_000) return
        lastSampleMs = now
        try {
            append(c, read(c))
        } catch (e: IOException) {
            // The next one will do.
        }
    }

    @JvmStatic
    fun read(c: Context): PhoneMetrics.Gauges {
        val g = PhoneMetrics.Gauges()
        g.timeMs = System.currentTimeMillis()
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) g.levelRatio = level / 100.0
        val current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Long.MIN_VALUE) g.currentAmperes = current / 1e6
        // A reading is taken only while the phone is awake, so the current now
        // says little about its sleep; the average and the two clocks do.
        val average = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        if (average != Long.MIN_VALUE && average != 0L) g.currentAverageAmperes = average / 1e6
        g.awakeMs = SystemClock.uptimeMillis()
        g.bootMs = SystemClock.elapsedRealtime()
        val charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (charge != Long.MIN_VALUE && charge > 0) g.chargeAmpereHours = charge / 1e6
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (mv > 0) g.voltageVolts = mv / 1000.0
            val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) g.temperatureCelsius = tenths / 10.0
            g.plugged = if (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) 1 else 0
        }
        g.charging = if (bm.isCharging) 1 else 0
        val pm = c.getSystemService(PowerManager::class.java)
        g.screenOn = if (pm.isInteractive) 1 else 0
        g.powerSave = if (pm.isPowerSaveMode) 1 else 0
        g.deviceIdle = if (pm.isDeviceIdleMode) 1 else 0
        return g
    }

    private fun buffer(c: Context) = File(c.filesDir, "metrics-gauges.csv")

    private fun append(c: Context, g: PhoneMetrics.Gauges) {
        val f = buffer(c)
        var lines = if (f.exists()) f.readLines(Charsets.UTF_8) else emptyList()
        lines = lines + g.encode()
        if (lines.size > MAX_BUFFERED) lines = lines.subList(lines.size - MAX_BUFFERED, lines.size)
        f.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun buffered(c: Context): List<PhoneMetrics.Gauges> = try {
        val f = buffer(c)
        if (!f.exists()) {
            emptyList()
        } else {
            f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull {
                try {
                    PhoneMetrics.Gauges.decode(it)
                } catch (e: RuntimeException) {
                    null // a torn line: drop it
                }
            }
        }
    } catch (e: IOException) {
        emptyList()
    }

    /** What the last batterystats read gave: lines read, seconds it took. */
    @Volatile private var batterystatsLines = 0
    @Volatile private var batterystatsSeconds = 0.0
    @Volatile private var batterystatsMode = ""

    /**
     * batterystats since the last charge, through the shell server (or with
     * the DUMP permission enrollment grants, which Android 17 no longer
     * honours for an app's own dumpsys); null when neither works.
     *
     * `--checkin`, not `-c`: `-c` also prints the whole battery history, which
     * on Android 14+ is kept across charges and grows to megabytes, so dumpsys
     * gave up after its 10 seconds before it reached the stats (they were empty
     * from 2026-11-20 on). `--checkin` prints no history, but first the stats
     * Android saved at the last reset (full charge), once, deleting them: after
     * a charge the first read can be the old period, so then it reads again
     * and keeps the one that started last.
     */
    @JvmStatic
    fun batterystats(c: Context, charged: Boolean): Checkin? {
        val start = SystemClock.elapsedRealtime()
        var first = batterystatsOnce(c, "--checkin")
        if (first != null && first.statLines == 0 && Shell.available()) {
            // Not what this Android prints for --checkin: -c, history and all,
            // with time enough to get past it.
            first = batterystatsOnce(c, "-c")
            batterystatsMode = "-c"
        } else {
            batterystatsMode = "--checkin"
        }
        var best = first
        if (first != null && first.statLines > 0 && batterystatsMode == "--checkin") {
            val prefs = c.getSharedPreferences("metrics", Context.MODE_PRIVATE)
            val known = prefs.getLong("stats_start", 0)
            var kept: Checkin = first
            if (charged || known == 0L || first.statsStartClockMs < known) {
                val second = batterystatsOnce(c, "--checkin")
                if (second != null && second.statLines > 0 && second.statsStartClockMs >= first.statsStartClockMs) kept = second
            }
            if (kept.statsStartClockMs > known) prefs.edit().putLong("stats_start", kept.statsStartClockMs).apply()
            best = kept
        }
        batterystatsLines = best?.lines ?: 0
        batterystatsSeconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        return best
    }

    private fun batterystatsOnce(c: Context, mode: String): Checkin? {
        // dumpsys's own limit is 10 s per service; the stats alone take well under one.
        val argv = arrayOf("dumpsys", "-t", "60", "batterystats", mode)
        try {
            // The shell server first: on Android 17 an app's own dumpsys gets no
            // output at all, DUMP or not (Prism's batterystats were empty from
            // its reinstall as at.quickme.prism on 2026-11-20 on).
            if (Shell.available()) {
                val r = Shell.run(90_000, *argv)
                if (r.ok()) return Checkin.parse(r.stdout)
                status = "batterystats through the shell: $r"
            }
            if (c.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED) {
                val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
                try {
                    p.outputStream.close()
                    return p.inputStream.bufferedReader(Charsets.UTF_8).use { Checkin.parse(it) }
                } finally {
                    p.destroy()
                }
            }
        } catch (e: Exception) {
            status = "batterystats: ${e.message}"
        }
        return null
    }

    private val processes = Procs.Accumulator()
    private val threads = Procs.Accumulator()

    private val ticksPerSecond: Double by lazy {
        try {
            Os.sysconf(OsConstants._SC_CLK_TCK).toDouble().takeIf { it > 0 } ?: 100.0
        } catch (e: Exception) {
            100.0
        }
    }

    /**
     * CPU seconds per process name over all of the phone's processes, since
     * this process started (Procs.Accumulator); empty without the shell
     * server, as only the shell user sees every process.
     */
    @JvmStatic
    fun processCpu(): Map<String, Double> {
        if (!Shell.available()) return emptyMap()
        return try {
            val r = Shell.run(30_000, "sh", "-c", "cat /proc/[0-9]*/stat 2>/dev/null; echo @@ps@@; ps -A -o PID,NAME")
            if (!r.ok() && r.stdout.isEmpty()) return emptyMap()
            val stats = Procs.parseStats(r.stdout.substringBefore("@@ps@@"))
            val names = Procs.parsePs(r.stdout.substringAfter("@@ps@@", ""))
            val totals = processes.add(stats.map { (names[it.pid] ?: it.comm) to it })
            totals.mapValues { it.value / ticksPerSecond }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** CPU seconds per thread group of this process (Procs.threadGroup), since it started. */
    @JvmStatic
    fun threadCpu(): Map<String, Double> = try {
        val snapshot = File("/proc/self/task").listFiles().orEmpty().mapNotNull { dir ->
            try {
                Procs.parseStat(File(dir, "stat").readText())?.let { Procs.threadGroup(it.comm) to it }
            } catch (e: IOException) {
                null // the thread ended meanwhile
            }
        }
        threads.add(snapshot).mapValues { it.value / ticksPerSecond }
    } catch (e: Exception) {
        emptyMap()
    }

    /** This process's own CPU seconds (all threads, the ended ones too). */
    private fun selfCpuSeconds(): Double = try {
        Procs.parseStat(File("/proc/self/stat").readText())?.ticks?.div(ticksPerSecond) ?: Double.NaN
    } catch (e: IOException) {
        Double.NaN
    }

    /**
     * Whether this run of the job should only take a reading: the phone is
     * unplugged with the screen off and its last push is less than an hour old.
     */
    private fun idleSkip(c: Context, prefs: android.content.SharedPreferences): Boolean {
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged || c.getSystemService(PowerManager::class.java).isInteractive) return false
        val since = System.currentTimeMillis() - prefs.getLong("pushed_at", 0)
        return since in 0 until IDLE_PUSH_MS - 5 * 60_000L
    }

    /** Collects and pushes; true when the endpoint accepted it (MetricsJob). */
    @JvmStatic
    fun push(c: Context): Boolean {
        val password = Settings.telemetryPassword(c)
        if (password.isEmpty()) {
            status = "not configured: enrollment sets telemetry_password"
            return true // nothing to retry
        }
        val prefs = c.getSharedPreferences("metrics", Context.MODE_PRIVATE)
        if (idleSkip(c, prefs)) {
            sampleIfDue(c)
            return true
        }
        synchronized(this) {
            try {
                append(c, read(c))
                lastSampleMs = System.currentTimeMillis()
            } catch (e: IOException) {
                // Push what there is.
            }
        }
        val collectStart = SystemClock.elapsedRealtime()
        val gauges = buffered(c)
        // Plugged in since the last push: a reset (full charge) may have saved the old period.
        val charged = gauges.any { it.plugged == 1 }
        val checkin = batterystats(c, charged)
        val background = try {
            BackgroundPolicy.apply(c)
        } catch (e: RuntimeException) {
            emptyMap()
        }
        val processCpu = processCpu()
        val threadCpu = threadCpu()
        val self = linkedMapOf(
            "phone_metrics_push_failures_total" to prefs.getLong("failures", 0).toDouble(),
            // 1 only when the stats themselves came, not just some output.
            "phone_metrics_batterystats_available" to if (checkin != null && checkin.statLines > 0) 1.0 else 0.0,
            "phone_metrics_batterystats_lines" to batterystatsLines.toDouble(),
            "phone_metrics_batterystats_duration_seconds" to batterystatsSeconds,
            // 1 when --checkin gave no stats and the push fell back to -c (history and all).
            "phone_metrics_batterystats_fallback" to if (batterystatsMode == "-c") 1.0 else 0.0,
            "phone_metrics_shell_available" to if (Shell.available()) 1.0 else 0.0,
            "phone_prism_cpu_seconds_total" to selfCpuSeconds(),
            "phone_prism_replica_open" to if (PrismApi.isOpen()) 1.0 else 0.0,
            "phone_prism_replica_opens_total" to PrismApi.opens.toDouble(),
            "phone_prism_replica_closes_total" to PrismApi.closes.toDouble(),
            "phone_metrics_collect_duration_seconds" to (SystemClock.elapsedRealtime() - collectStart) / 1000.0,
        )
        val info = try {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            mapOf("app" to "${pi.versionName} (${pi.longVersionCode})")
        } catch (e: PackageManager.NameNotFoundException) {
            emptyMap()
        }
        val now = System.currentTimeMillis()
        val series = PhoneMetrics.build(
            Settings.deviceName(c), gauges, checkin, now, TOP_APPS, self,
            PhoneMetrics.Extra(processCpu, threadCpu, info, background, Hotspot.metrics(c)),
        )
        val body = RemoteWrite.snappy(RemoteWrite.encode(series))
        return try {
            val http = URL(Settings.telemetryUrl(c)).openConnection() as HttpURLConnection
            http.connectTimeout = 20_000
            http.readTimeout = 30_000
            http.requestMethod = "POST"
            http.doOutput = true
            val auth = Settings.telemetryUser(c) + ":" + password
            http.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray(Charsets.UTF_8)))
            http.setRequestProperty("Content-Type", "application/x-protobuf")
            http.setRequestProperty("Content-Encoding", "snappy")
            http.setRequestProperty("X-Prometheus-Remote-Write-Version", "0.1.0")
            http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
            val code = http.responseCode
            http.disconnect()
            if (code / 100 == 2) {
                // Readings taken while this push ran stay for the next one.
                synchronized(this) {
                    val sent = gauges.lastOrNull()?.timeMs ?: 0
                    val keep = buffered(c).filter { it.timeMs > sent }.map { it.encode() }
                    buffer(c).writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                }
                prefs.edit().putLong("pushed_at", now).apply()
                status = "pushed ${series.size} series at " + android.text.format.DateFormat.format("HH:mm", now) +
                    when {
                        checkin == null -> " (no batterystats: DUMP is not granted)"
                        checkin.statLines == 0 -> " (batterystats: no stats in ${checkin.lines} lines)"
                        else -> " (batterystats $batterystatsMode)"
                    }
                true
            } else {
                // 4xx other than 429: the request itself is wrong; retrying won't help.
                status = "push refused: HTTP $code"
                prefs.edit().putLong("failures", prefs.getLong("failures", 0) + 1).apply()
                code / 100 == 4 && code != 429
            }
        } catch (e: IOException) {
            status = "push failed: ${e.message}"
            prefs.edit().putLong("failures", prefs.getLong("failures", 0) + 1).apply()
            false
        }
    }
}

/** The 15-minute metrics push (Metrics; hourly while unplugged with the screen off), a JobScheduler job: Android batches it with other work, so it costs no wakeup of its own. */
class MetricsJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = jobThread(params, "metrics-push", "PrismMetrics") { Metrics.push(this) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true // try again later
    }
}
