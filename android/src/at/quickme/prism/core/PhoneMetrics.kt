// The phone's metrics as Prometheus series (android/METRICS.md lists them):
// battery gauges sampled every few minutes, and the batterystats counters
// (Checkin) at push time, per app for the top apps only, so the label sets
// stay bounded. job="prism/phone", instance=<device name>.

package at.quickme.prism.core

object PhoneMetrics {
    const val JOB = "prism/phone"
    const val TOP_PROCESSES = 30
    const val TOP_THREADS = 20

    /** Named timers (kernel wakelocks, wakeup reasons, wakelocks, jobs, alarms): the top ones of each. */
    const val TOP_NAMED = 20

    /**
     * What a push adds besides the battery: CPU seconds per process (all of
     * the phone's, through the shell) and per thread group of Prism's own
     * process, and Prism's build ("app" -> version).
     */
    class Extra(
        @JvmField val processes: Map<String, Double> = emptyMap(),
        @JvmField val threads: Map<String, Double> = emptyMap(),
        @JvmField val info: Map<String, String> = emptyMap(),
        /** BackgroundPolicy's apps: package -> may run in the background now. */
        @JvmField val background: Map<String, Boolean> = emptyMap(),
        /** The hotspot (Hotspot.kt); null leaves its series out. */
        @JvmField val hotspot: Hotspot? = null,
    )

    /** The hotspot's state at push time (docs/hotspot.md). */
    class Hotspot(
        /** "instant" or "normal". */
        @JvmField val mode: String,
        @JvmField val on: Boolean,
        @JvmField val bleScanning: Boolean,
        /** Starts by reason ("ble", "normal"), counted since Prism's data was cleared. */
        @JvmField val starts: Map<String, Long>,
    )

    /** Both modes, so the one not in use reads 0 rather than going stale. */
    @JvmField
    val HOTSPOT_MODES = listOf("instant", "normal")

    /**
     * The `n` largest. No "other" for the rest: which names make the top
     * changes, and a sum of the rest would then go down like a reset.
     */
    private fun top(m: Map<String, Double>, n: Int): List<Pair<String, Double>> =
        m.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    /** One reading of the battery (BatteryManager), every few minutes. */
    class Gauges {
        @JvmField var timeMs = 0L
        @JvmField var levelRatio = Double.NaN          // 0..1
        @JvmField var currentAmperes = Double.NaN      // as the device reports it (Pixels: negative while discharging)
        @JvmField var voltageVolts = Double.NaN
        @JvmField var temperatureCelsius = Double.NaN
        @JvmField var chargeAmpereHours = Double.NaN   // charge counter
        @JvmField var plugged = -1                     // 0/1
        @JvmField var charging = -1                    // 0/1
        @JvmField var screenOn = -1                    // 0/1
        @JvmField var powerSave = -1                   // 0/1
        @JvmField var deviceIdle = -1                  // 0/1 (Doze)
        @JvmField var awakeMs = -1L                    // SystemClock.uptimeMillis: stops while the CPU sleeps
        @JvmField var bootMs = -1L                     // SystemClock.elapsedRealtime: runs on in sleep
        @JvmField var currentAverageAmperes = Double.NaN // the fuel gauge's average, where the device has one

        /** One line for the buffer file: "t,level,current,…" (the last three were added later). */
        fun encode(): String = listOf(
            timeMs, levelRatio, currentAmperes, voltageVolts, temperatureCelsius, chargeAmpereHours,
            plugged, charging, screenOn, powerSave, deviceIdle, awakeMs, bootMs, currentAverageAmperes,
        ).joinToString(",")

        companion object {
            @JvmStatic
            fun decode(line: String): Gauges {
                val f = line.trim().split(",")
                return Gauges().apply {
                    timeMs = f[0].toLong()
                    levelRatio = f[1].toDouble()
                    currentAmperes = f[2].toDouble()
                    voltageVolts = f[3].toDouble()
                    temperatureCelsius = f[4].toDouble()
                    chargeAmpereHours = f[5].toDouble()
                    plugged = f[6].toInt()
                    charging = f[7].toInt()
                    screenOn = f[8].toInt()
                    powerSave = f[9].toInt()
                    deviceIdle = f[10].toInt()
                    if (f.size >= 14) {
                        awakeMs = f[11].toLong()
                        bootMs = f[12].toLong()
                        currentAverageAmperes = f[13].toDouble()
                    }
                }
            }
        }
    }

    /** Collects samples per series, so each series goes out once with all its samples. */
    private class Builder(val instance: String) {
        val series = LinkedHashMap<String, RemoteWrite.Series>()

        fun add(name: String, value: Double, timeMs: Long, vararg labels: String) {
            if (value.isNaN() || value.isInfinite()) return
            val s = RemoteWrite.Series(name).label("job", JOB).label("instance", instance)
            var i = 0
            while (i + 1 < labels.size) {
                s.label(labels[i], labels[i + 1])
                i += 2
            }
            series.getOrPut(s.key()) { s }.sample(value, timeMs)
        }

        fun add(name: String, value: Long, timeMs: Long, vararg labels: String) = add(name, value.toDouble(), timeMs, *labels)

        fun flag(name: String, value: Int, timeMs: Long) {
            if (value >= 0) add(name, value.toDouble(), timeMs)
        }
    }

    /**
     * Every series for one push. `gauges` are the buffered battery readings,
     * oldest first; `checkin` may be null when batterystats was not readable
     * (no shell), and `topApps` bounds the per-app series.
     */
    @JvmStatic
    fun build(
        instance: String,
        gauges: List<Gauges>,
        checkin: Checkin?,
        nowMs: Long,
        topApps: Int,
        self: Map<String, Double>,
        extra: Extra = Extra(),
    ): List<RemoteWrite.Series> {
        val b = Builder(instance)
        for (g in gauges) {
            b.add("phone_battery_level_ratio", g.levelRatio, g.timeMs)
            b.add("phone_battery_current_amperes", g.currentAmperes, g.timeMs)
            b.add("phone_battery_voltage_volts", g.voltageVolts, g.timeMs)
            b.add("phone_battery_temperature_celsius", g.temperatureCelsius, g.timeMs)
            b.add("phone_battery_charge_ampere_hours", g.chargeAmpereHours, g.timeMs)
            b.flag("phone_battery_plugged", g.plugged, g.timeMs)
            b.flag("phone_battery_charging", g.charging, g.timeMs)
            b.flag("phone_screen_on", g.screenOn, g.timeMs)
            b.flag("phone_power_save_mode", g.powerSave, g.timeMs)
            b.flag("phone_device_idle", g.deviceIdle, g.timeMs)
            if (g.awakeMs >= 0) b.add("phone_awake_seconds_total", g.awakeMs / 1000.0, g.timeMs)
            if (g.bootMs >= 0) b.add("phone_since_boot_seconds_total", g.bootMs / 1000.0, g.timeMs)
            b.add("phone_battery_current_average_amperes", g.currentAverageAmperes, g.timeMs)
        }
        for ((name, value) in self) b.add(name, value, nowMs)
        for ((k, v) in extra.info) b.add("phone_prism_build_info", 1.0, nowMs, "version", v, "component", k)
        for ((pkg, allowed) in extra.background) {
            b.add("phone_app_background_allowed", if (allowed) 1.0 else 0.0, nowMs, "package", pkg)
        }
        extra.hotspot?.let { h ->
            for (m in HOTSPOT_MODES) b.add("phone_hotspot_mode", if (m == h.mode) 1.0 else 0.0, nowMs, "mode", m)
            b.add("phone_hotspot_on", if (h.on) 1.0 else 0.0, nowMs)
            b.add("phone_hotspot_ble_scanning", if (h.bleScanning) 1.0 else 0.0, nowMs)
            for ((reason, n) in h.starts) b.add("phone_hotspot_starts_total", n, nowMs, "reason", reason)
        }
        top(extra.processes, TOP_PROCESSES).forEach { (name, sec) ->
            b.add("phone_process_cpu_seconds_total", sec, nowMs, "process", name)
        }
        top(extra.threads, TOP_THREADS).forEach { (name, sec) ->
            b.add("phone_prism_thread_cpu_seconds_total", sec, nowMs, "thread", name)
        }
        if (checkin == null || checkin.statLines == 0) return ArrayList(b.series.values)

        val t = nowMs
        b.add("phone_batterystats_reset_timestamp_seconds", checkin.statsStartClockMs / 1000.0, t)
        b.add("phone_battery_capacity_estimated_ampere_hours", checkin.estimatedCapacityMah / 1000.0, t)
        b.add("phone_on_battery_seconds_total", checkin.onBatteryMs / 1000.0, t)
        b.add("phone_on_battery_screen_off_seconds_total", checkin.onBatteryScreenOffMs / 1000.0, t)
        b.add("phone_screen_on_seconds_total", checkin.screenOnMs / 1000.0, t)
        b.add("phone_interactive_seconds_total", checkin.interactiveMs / 1000.0, t)
        b.add("phone_mobile_radio_active_seconds_total", checkin.mobileRadioActiveMs / 1000.0, t)
        b.add("phone_wifi_on_seconds_total", checkin.wifiOnMs / 1000.0, t)
        b.add("phone_wifi_running_seconds_total", checkin.wifiRunningMs / 1000.0, t)
        b.add("phone_wakelock_seconds_total", checkin.partialWakelockMs / 1000.0, t, "type", "partial")
        b.add("phone_wakelock_seconds_total", checkin.fullWakelockMs / 1000.0, t, "type", "full")
        b.add("phone_device_idle_seconds_total", checkin.deviceIdleDeepMs / 1000.0, t, "mode", "deep")
        b.add("phone_device_idle_seconds_total", checkin.deviceIdleLightMs / 1000.0, t, "mode", "light")
        b.add("phone_power_save_seconds_total", checkin.powerSaveMs / 1000.0, t)
        checkin.signalStrengthMs?.forEachIndexed { i, ms ->
            b.add("phone_mobile_signal_seconds_total", ms / 1000.0, t, "level", Checkin.SIGNAL_STRENGTHS.getOrElse(i) { "level$it" })
        }
        checkin.dataConnectionMs?.forEachIndexed { i, ms ->
            // Most types never occur on this phone: only the ones that did.
            if (ms > 0) b.add("phone_data_connection_seconds_total", ms / 1000.0, t, "type", Checkin.DATA_CONNECTIONS.getOrElse(i) { "type$it" })
        }
        for (k in checkin.kernelWakelocks.values.sortedByDescending { it.ms }.take(TOP_NAMED)) {
            b.add("phone_kernel_wakelock_seconds_total", k.ms / 1000.0, t, "name", k.name)
            b.add("phone_kernel_wakelocks_total", k.count, t, "name", k.name)
        }
        for (w in checkin.wakeupReasons.values.sortedByDescending { it.count }.take(TOP_NAMED)) {
            b.add("phone_wakeup_reason_total", w.count, t, "reason", w.name)
            b.add("phone_wakeup_reason_seconds_total", w.ms / 1000.0, t, "reason", w.name)
        }
        for (w in checkin.wakelocks.values.sortedByDescending { it.ms }.take(TOP_NAMED)) {
            b.add("phone_wakelock_name_seconds_total", w.ms / 1000.0, t, "package", checkin.nameOf(w.uid), "name", w.name)
            b.add("phone_wakelock_name_total", w.count, t, "package", checkin.nameOf(w.uid), "name", w.name)
        }
        for (j in checkin.jobs.values.sortedByDescending { it.ms }.take(TOP_NAMED)) {
            b.add("phone_job_seconds_total", j.ms / 1000.0, t, "package", checkin.nameOf(j.uid), "name", j.name)
            b.add("phone_jobs_total", j.count, t, "package", checkin.nameOf(j.uid), "name", j.name)
        }
        for (a in checkin.alarms.values.sortedByDescending { it.count }.take(TOP_NAMED)) {
            b.add("phone_wakeup_alarm_name_total", a.count, t, "package", checkin.nameOf(a.uid), "name", a.name)
        }

        // Apps under the same package name (other users, shared uids) count as one.
        val byName = LinkedHashMap<String, Checkin.App>()
        for (a in checkin.apps.values) {
            val name = checkin.nameOf(a.uid)
            val sum = byName[name]
            byName[name] = if (sum == null) a else merge(sum, a)
        }
        val ranked = byName.entries.sortedByDescending { it.value.weight() }
        var otherMah = 0.0
        ranked.forEachIndexed { i, (pkg, a) ->
            if (i >= topApps) {
                otherMah += a.mah
                return@forEachIndexed
            }
            b.add("phone_app_estimated_charge_ampere_hours_total", a.mah / 1000.0, t, "package", pkg)
            b.add("phone_app_cpu_seconds_total", a.cpuUserMs / 1000.0, t, "package", pkg, "mode", "user")
            b.add("phone_app_cpu_seconds_total", a.cpuSystemMs / 1000.0, t, "package", pkg, "mode", "system")
            b.add("phone_app_wakelock_seconds_total", a.wakelockPartialMs / 1000.0, t, "package", pkg, "type", "partial")
            b.add("phone_app_wakelock_seconds_total", a.wakelockFullMs / 1000.0, t, "package", pkg, "type", "full")
            b.add("phone_app_wakelock_seconds_total", a.wakelockWindowMs / 1000.0, t, "package", pkg, "type", "window")
            b.add("phone_app_wakelocks_total", a.wakelockPartialCount, t, "package", pkg, "type", "partial")
            b.add("phone_app_wakeup_alarms_total", a.wakeupAlarms, t, "package", pkg)
            b.add("phone_app_radio_wakeups_total", a.mobileWakeups, t, "package", pkg, "network", "mobile")
            b.add("phone_app_radio_wakeups_total", a.wifiWakeups, t, "package", pkg, "network", "wifi")
            b.add("phone_app_mobile_radio_active_seconds_total", a.mobileActiveUs / 1e6, t, "package", pkg)
            b.add("phone_app_wifi_running_seconds_total", a.wifiRunningUs / 1e6, t, "package", pkg)
            b.add("phone_app_wifi_scan_seconds_total", a.wifiScanUs / 1e6, t, "package", pkg)
            b.add("phone_app_network_bytes_total", a.mobileRxBytes, t, "package", pkg, "network", "mobile", "direction", "rx")
            b.add("phone_app_network_bytes_total", a.mobileTxBytes, t, "package", pkg, "network", "mobile", "direction", "tx")
            b.add("phone_app_network_bytes_total", a.wifiRxBytes, t, "package", pkg, "network", "wifi", "direction", "rx")
            b.add("phone_app_network_bytes_total", a.wifiTxBytes, t, "package", pkg, "network", "wifi", "direction", "tx")
            b.add("phone_app_foreground_seconds_total", a.foregroundMs / 1000.0, t, "package", pkg)
            b.add("phone_app_foreground_service_seconds_total", a.foregroundServiceMs / 1000.0, t, "package", pkg)
            a.stateMillis?.forEachIndexed { s, millis ->
                // Android's checkin prints st in milliseconds, despite the field name in AOSP's dumps.
                b.add("phone_app_process_state_seconds_total", millis / 1000.0, t, "package", pkg, "state", Checkin.PROCESS_STATES[s])
            }
        }
        // The rest, so the per-app charge still adds up to the whole.
        b.add("phone_app_estimated_charge_ampere_hours_total", otherMah / 1000.0, t, "package", "other")
        b.add("phone_apps_tracked", minOf(topApps, ranked.size).toLong(), t)
        return ArrayList(b.series.values)
    }

    /** Accumulates `b` into `a` (only used for this build). */
    private fun merge(a: Checkin.App, b: Checkin.App): Checkin.App {
        a.mah += b.mah
        a.cpuUserMs += b.cpuUserMs
        a.cpuSystemMs += b.cpuSystemMs
        a.wakelockPartialMs += b.wakelockPartialMs
        a.wakelockFullMs += b.wakelockFullMs
        a.wakelockWindowMs += b.wakelockWindowMs
        a.wakelockPartialCount += b.wakelockPartialCount
        a.wakelockFullCount += b.wakelockFullCount
        a.wakelockWindowCount += b.wakelockWindowCount
        a.wakeupAlarms += b.wakeupAlarms
        a.mobileRxBytes += b.mobileRxBytes
        a.mobileTxBytes += b.mobileTxBytes
        a.wifiRxBytes += b.wifiRxBytes
        a.wifiTxBytes += b.wifiTxBytes
        a.mobileActiveUs += b.mobileActiveUs
        a.mobileActiveCount += b.mobileActiveCount
        a.mobileWakeups += b.mobileWakeups
        a.wifiWakeups += b.wifiWakeups
        a.wifiRunningUs += b.wifiRunningUs
        a.wifiScanUs += b.wifiScanUs
        a.foregroundMs += b.foregroundMs
        a.foregroundServiceMs += b.foregroundServiceMs
        b.stateMillis?.let { bs ->
            val ms = a.stateMillis ?: LongArray(bs.size).also { a.stateMillis = it }
            for (i in bs.indices) ms[i] += bs[i]
        }
        return a
    }
}
