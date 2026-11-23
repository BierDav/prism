// The phone metrics on a plain JVM: the batterystats checkin parser on a
// sample in AOSP's format, the series built from it, and a remote-write body
// that tests/remote_write_decode.py decodes with the reference snappy and a
// protobuf reader.
//
//   java -cp <core classes>:kotlin-stdlib.jar MetricsTestKt <out.bin>

import at.quickme.prism.core.Checkin
import at.quickme.prism.core.PhoneMetrics
import at.quickme.prism.core.Procs
import at.quickme.prism.core.RemoteWrite
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

private val CHECKIN = listOf(
    "9,0,i,vers,36,214,BP2A.250605.031,BP2A.250605.031",
    "9,h,0:RESET:TIME:1800000000000",
    "9,h,1000,+r,wl=1,Bl=80,Bt=300",
    "9,0,i,uid,10123,com.example.chat",
    "9,0,i,uid,10200,com.example.maps",
    "9,0,i,uid,10300,com.shared.a",
    "9,0,i,uid,10300,com.shared.b",
    "9,0,l,bt,12,3600000,3500000,7200000,7000000,1800000000000,3000000,2900000,4800,4700,4900,1000",
    "9,0,l,gwfl,1200000,1100000,0,0,0",
    "9,0,l,m,600000,0,1000,90000,300000,250000,610000,0,4,2000000,3,100,2,40,0,500000,7,10,3,1000,2000",
    "9,10123,l,pwi,uid,123.45,0,10.5,0",
    "9,10200,l,pwi,uid,45.5,0,1.0,0",
    "9,1000,l,pwi,uid,30,1,0,0",
    "9,10123,l,cpu,60000,15000,0",
    "9,10123,l,wl,*job*/sync,0,f,0,0,0,0,25000,p,12,0,9000,26000,20000,bp,10,0,9000,21000,0,w,0,0,0,0",
    "9,10123,l,wl,push,0,f,0,0,0,0,5000,p,3,0,2000,5000,5000,bp,3,0,2000,5000,0,w,0,0,0,0",
    "9,10123,l,wua,*walarm*:com.example.chat.SYNC,17",
    "9,10123,l,wua,*walarm*:other,3",
    "9,10123,l,nt,1000,2000,3000,4000,10,20,30,40,5000000,6,0,0,7,8,0,0,0,0,0,0,0,0",
    "9,10123,l,wfl,0,700000,900000,2,0,0,0,1,700,0",
    "9,10123,l,fg,120000,5",
    "9,10123,l,fgs,60000,1",
    "9,10123,l,st,100,20,30,400,5,0,600",
    "9,10123,l,jb,com.example.chat/.SyncJob,42000,14",
    "9,0,l,kwl,\"PowerManagerService.WakeLocks\",81000,120,0,0,0",
    "9,0,l,kwl,\"wlan_rx_wake\",4000,300,0,0,0",
    "9,0,l,wr,\"200 qcom,smp2p-modem, ipcc\",9000,77,0,0",
    "9,0,l,wr,\"Abort:wlan busy\",100,3,0,0",
    "9,0,l,sgt,1000,2000,3000,4000,5000",
    "9,0,l,dct,0,0,0,0,0,0,0,0,0,0,0,0,0,7000,0,0,0,0,0,0,9000,0,0",
    "9,10300,l,cpu,1000,1000,0",
    "9,1010300,l,cpu,500,0,0",
    "9,10200,c,cpu,99999,99999,0",
    "garbage line",
    "9,10200,l,cpu,notanumber,1,0",
).joinToString("\n")

fun main(args: Array<String>) {
    val c = Checkin.parse(CHECKIN)
    check(c.estimatedCapacityMah == 4800.0, "bt: estimated capacity")
    check(c.onBatteryMs == 3600000L && c.statsStartClockMs == 1800000000000L, "bt: on battery, start clock")
    check(c.screenOnMs == 600000L && c.partialWakelockMs == 90000L && c.mobileRadioActiveMs == 300000L, "m: screen, wakelock, radio")
    check(c.deviceIdleDeepMs == 2000000L && c.deviceIdleLightMs == 500000L, "m: doze deep and light")
    check(c.wifiOnMs == 1200000L && c.wifiRunningMs == 1100000L, "gwfl")
    val chat = c.apps.getValue(10123)
    check(abs(chat.mah - 123.45) < 1e-9, "pwi: charge per uid")
    check(chat.cpuUserMs == 60000L && chat.cpuSystemMs == 15000L, "cpu")
    check(chat.wakelockPartialMs == 30000L && chat.wakelockPartialCount == 15L, "wl: partial summed over wakelocks, bp not added")
    check(chat.wakeupAlarms == 20L, "wua summed")
    check(chat.mobileRxBytes == 1000L && chat.wifiTxBytes == 4000L && chat.mobileActiveUs == 5000000L &&
        chat.mobileWakeups == 7L && chat.wifiWakeups == 8L, "nt")
    check(chat.wifiScanUs == 700000L && chat.wifiRunningUs == 900000L, "wfl")
    check(chat.foregroundMs == 120000L && chat.foregroundServiceMs == 60000L, "fg, fgs")
    check(chat.stateMillis?.get(0) == 100L && chat.stateMillis?.get(6) == 600L, "st")
    check(c.apps.getValue(10200).cpuUserMs == 0L, "category c and malformed lines ignored")
    check(c.statLines > 0 && Checkin.parse("9,0,i,vers,36\n9,0,i,uid,10123,a.b").statLines == 0, "statLines counts only the stats")
    check(c.kernelWakelocks.values.first().let { it.name == "PowerManagerService.WakeLocks" && it.ms == 81000L && it.count == 120L }, "kwl")
    val modem = c.wakeupReasons.values.first()
    check(modem.name == "200 qcom,smp2p-modem, ipcc" && modem.ms == 9000L && modem.count == 77L, "wr: quoted name with commas")
    check(c.wakelocks.values.any { it.uid == 10123 && it.name == "*job*/sync" && it.ms == 25000L && it.count == 12L }, "wl by name")
    check(c.jobs.values.single().let { it.name == "com.example.chat/.SyncJob" && it.ms == 42000L && it.count == 14L }, "jb")
    check(c.alarms.values.any { it.name == "*walarm*:com.example.chat.SYNC" && it.count == 17L }, "wua by name")
    check(c.signalStrengthMs?.toList() == listOf(1000L, 2000L, 3000L, 4000L, 5000L), "sgt")
    check(c.dataConnectionMs?.get(13) == 7000L && c.dataConnectionMs?.get(20) == 9000L, "dct: lte and nr")

    val stat = Procs.parseStat("1234 (Binder:1234_(2) x) S 1 1234 0 0 -1 4194624 1 0 0 0 250 50 0 0 20 0 30 0 98765 1 1 0")
    check(stat != null && stat.pid == 1234 && stat.comm == "Binder:1234_(2) x" && stat.ticks == 300L && stat.startTicks == 98765L, "proc stat")
    check(Procs.parseStat("garbage") == null, "proc stat: garbage")
    val ps = Procs.parsePs("  PID NAME\n 1234 at.quickme.sudoapprover\n   88 /system/bin/surfaceflinger\n")
    check(ps[1234] == "at.quickme.sudoapprover" && ps[88] == "surfaceflinger" && ps.size == 2, "ps names")
    check(Procs.threadGroup("tokio-runtime-w") == "tokio-runtime-w" && Procs.threadGroup("pool-3-thread-12") == "pool-N-thread-N", "thread groups")
    fun st(pid: Int, ticks: Long, start: Long) = Procs.Stat(pid, "x", ticks, start)
    val acc = Procs.Accumulator()
    check(acc.add(listOf("a" to st(1, 100, 5), "b" to st(2, 50, 5))) == mapOf("a" to 0L, "b" to 0L), "accumulator: baseline")
    // a runs 30 more; b ended; a second "a" started since (counts in full); pid 2 reused by c.
    check(acc.add(listOf("a" to st(1, 130, 5), "a" to st(3, 20, 900), "c" to st(2, 7, 950))) == mapOf("a" to 50L, "b" to 0L, "c" to 7L), "accumulator: deltas, new and reused pids")
    check(acc.add(listOf("a" to st(1, 135, 5)))["a"] == 55L, "accumulator: an ended process keeps its total")
    check(c.nameOf(10123) == "com.example.chat" && c.nameOf(1000) == "android.system" &&
        c.nameOf(10300) == "com.shared.a+1" && c.nameOf(1010300) == "com.shared.a+1", "names")

    val gauges = (0 until 3).map { i ->
        val g = PhoneMetrics.Gauges().apply {
            timeMs = 1_800_000_000_000L + i * 300_000L
            levelRatio = 0.8 - i * 0.01
            currentAmperes = -0.25
            voltageVolts = 3.9
            temperatureCelsius = 30.5
            chargeAmpereHours = 3.2
            plugged = 0
            charging = 0
            screenOn = i % 2
            powerSave = 0
            deviceIdle = 0
        }
        val back = PhoneMetrics.Gauges.decode(g.encode())
        check(back.timeMs == g.timeMs && back.levelRatio == g.levelRatio && back.screenOn == g.screenOn, "gauges round-trip $i")
        g
    }
    // The buffer file's format is unchanged from the Java app's (Double.toString, NaN as "NaN").
    check(PhoneMetrics.Gauges().apply { timeMs = 5 }.encode() == "5,NaN,NaN,NaN,NaN,NaN,-1,-1,-1,-1,-1,-1,-1,NaN", "gauges line format")
    val old = PhoneMetrics.Gauges.decode("5,0.5,NaN,NaN,NaN,NaN,0,0,1,0,1")
    check(old.levelRatio == 0.5 && old.deviceIdle == 1 && old.awakeMs == -1L, "gauges: lines from before the clocks still decode")
    gauges[0].awakeMs = 1000
    gauges[0].bootMs = 4000
    val extra = PhoneMetrics.Extra(
        processes = (1..40).associate { "proc$it" to it.toDouble() },
        threads = mapOf("tokio-runtime-w" to 12.5),
        info = mapOf("app" to "1.2 (34)"),
        hotspot = PhoneMetrics.Hotspot("normal", true, false, mapOf("ble" to 3L, "normal" to 1L)),
    )
    val series = PhoneMetrics.build("david-pixel", gauges, c, 1_800_000_900_000L, 2, mapOf("phone_metrics_push_failures_total" to 2.0), extra)
    val byKey = HashMap<String, RemoteWrite.Series>()
    for (s in series) {
        check(!byKey.containsKey(s.key()), "series unique: " + s.key())
        byKey[s.key()] = s
        check(s.name().startsWith("phone_"), "name prefix: " + s.name())
        check(s.labels["job"] == "prism/phone" && s.labels["instance"] == "david-pixel", "job and instance: " + s.name())
    }
    val level = series.firstOrNull { it.name() == "phone_battery_level_ratio" }
    check(level != null && level.samples.size == 3, "gauges: one series, three samples")
    val apps = series.mapNotNull { it.labels["package"] }.distinct().size
    check(apps == 3, "top 2 apps plus 'other' ($apps)")
    check(series.any {
        it.labels["package"] == "com.example.chat" && it.name() == "phone_app_estimated_charge_ampere_hours_total" &&
            abs(it.samples[0][0] - 0.12345) < 1e-12
    }, "charge in ampere hours")
    check(series.any {
        it.labels["package"] == "com.example.chat" && it.name() == "phone_app_process_state_seconds_total" &&
            it.labels["state"] == "top" && abs(it.samples[0][0] - 0.1) < 1e-12
    }, "process state time in seconds")

    check(series.single { it.name() == "phone_awake_seconds_total" }.samples.size == 1, "awake clock only where read")
    val procs = series.filter { it.name() == "phone_process_cpu_seconds_total" }
    check(procs.size == PhoneMetrics.TOP_PROCESSES && procs.none { it.labels["process"] == "proc1" }, "top processes only")
    check(series.any { it.name() == "phone_prism_thread_cpu_seconds_total" && it.labels["thread"] == "tokio-runtime-w" }, "thread cpu")
    check(series.any { it.name() == "phone_prism_build_info" && it.labels["version"] == "1.2 (34)" }, "build info")
    check(series.any { it.name() == "phone_wakeup_reason_total" && it.labels["reason"] == "200 qcom,smp2p-modem, ipcc" && it.samples[0][0] == 77.0 }, "wakeup reasons")
    check(series.any { it.name() == "phone_kernel_wakelock_seconds_total" && it.labels["name"] == "wlan_rx_wake" }, "kernel wakelocks")
    check(series.any { it.name() == "phone_mobile_signal_seconds_total" && it.labels["level"] == "poor" && it.samples[0][0] == 2.0 }, "signal strength")
    check(series.filter { it.name() == "phone_data_connection_seconds_total" }.map { it.labels["type"] }.toSet() == setOf("lte", "nr"), "data connection types that occurred")
    check(series.any { it.name() == "phone_job_seconds_total" && it.labels["package"] == "com.example.chat" }, "jobs")
    check(series.any { it.name() == "phone_wakelock_name_seconds_total" && it.labels["name"] == "push" && it.samples[0][0] == 5.0 }, "wakelocks by name")
    check(series.any { it.name() == "phone_hotspot_mode" && it.labels["mode"] == "normal" && it.samples[0][0] == 1.0 } &&
        series.any { it.name() == "phone_hotspot_mode" && it.labels["mode"] == "instant" && it.samples[0][0] == 0.0 }, "hotspot mode, both labels")
    check(series.any { it.name() == "phone_hotspot_on" && it.samples[0][0] == 1.0 } &&
        series.any { it.name() == "phone_hotspot_ble_scanning" && it.samples[0][0] == 0.0 }, "hotspot on, ble scanning")
    check(series.any { it.name() == "phone_hotspot_starts_total" && it.labels["reason"] == "ble" && it.samples[0][0] == 3.0 }, "hotspot starts by reason")

    File(args[0]).writeBytes(RemoteWrite.snappy(RemoteWrite.encode(series)))
    // A body over one snappy chunk (64 KiB) too.
    val big = (0 until 3000).map {
        RemoteWrite.Series("phone_test_series").label("job", "prism/phone").label("n", "value-$it").sample(it.toDouble(), 1000L + it)
    }
    File(args[0] + ".big").writeBytes(RemoteWrite.snappy(RemoteWrite.encode(big)))
    println(if (failed == 0) "all metrics checks passed" else "$failed metrics check(s) failed")
    exitProcess(if (failed == 0) 0 else 1)
}
