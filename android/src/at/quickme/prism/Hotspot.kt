// The phone's Wi-Fi hotspot in one of two modes (docs/hotspot.md), switched
// with the Quick Settings tile (HotspotTileService):
//
//  - instant (the default): off; Prism listens for the owner's MacBook over
//    Bluetooth LE and starts it for one client when the Mac asks, and Android
//    turns it off again 120 s after the last client left. When the Mac leaves
//    for a Wi-Fi where it had working internet before, it sends a release,
//    and Prism switches the hotspot off at once.
//  - normal: on, and kept on: Prism starts it again 10 s after it stopped,
//    for whatever reason (a reboot, someone switching it off).
//
// Starting and stopping run as the shell user (shell/HotspotTool.kt), since
// the configuration they set is a system API; Prism's own process only
// watches: whether Wi-Fi tethering runs (the public TetheringEventCallback),
// Bluetooth, location, and the settings. apply()
// reconciles the mode with what it sees; it runs at app start, at boot, from
// PushService's hooks, and whenever one of those changes. Without the shell
// server nothing can change: apply() waits for it to attach (Shell.listen).
//
// The scan is hardware-filtered (the request's 4-byte UUID prefix, masked)
// and delivered to a PendingIntent, so Prism's process needn't run for it,
// and runs only while it could matter: instant mode, hotspot_key set,
// Bluetooth on, location on, and either the hotspot off (for a request) or
// on as Prism's instant hotspot (for a release). Also while the phone is on
// Wi-Fi: the Mac may not know that network, and its menu asks whatever the
// path. A low-power filtered scan costs next to nothing, since Android keeps
// one running for itself anyway.
//
// Android 16 (API 36) and newer: the tethering callback and a start with its
// own configuration are public there; on older versions the tile still
// switches modes, but nothing else happens.

package at.quickme.prism

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import at.quickme.prism.core.HotspotToken
import at.quickme.prism.core.PhoneMetrics
import at.quickme.prism.shell.HotspotTool
import at.quickme.prism.shell.Shell
import java.io.IOException
import java.util.concurrent.Executors

object Hotspot {
    private const val TAG = "PrismHotspot"

    const val INSTANT = "instant"
    const val NORMAL = "normal"

    /** In normal mode, how long after the hotspot stopped Prism starts it again. */
    private const val RESTART_DELAY_MS = 10_000L

    /** At most one start per this for the Mac's requests (docs/hotspot.md). */
    private const val BLE_INTERVAL_MS = 30_000L

    /** A request that came while the shell server was down still counts this long once it attaches. */
    private const val BLE_PENDING_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())

    /** The shell calls block and must not overlap: one thread, in order. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hotspot") }

    private var app: Context? = null // guarded by this
    private val listeners = ArrayList<Runnable>()

    /** Whether Wi-Fi tethering runs, as the tethering callback last said. */
    @Volatile
    var on = false
        private set

    /** Whether the tethering callback has reported yet in this process. */
    @Volatile private var known = false

    /** Whether Prism's BLE scan is registered now. */
    @Volatile
    var scanning = false
        private set

    /** Whether the scan's state was set once in this process (a scan from before may still be registered). */
    @Volatile private var scanSet = false

    /** The running hotspot's configuration was checked against the mode since it came on. */
    @Volatile private var checked = false

    /** The running hotspot is Prism's instant one (started for the Mac): a release may stop it. */
    @Volatile private var instantOn = false

    /** A shell call was needed while the server was down: apply() again once it attaches. */
    @Volatile private var shellWaiting = false

    @Volatile private var lastBleStart = 0L // elapsedRealtime
    @Volatile private var bleWaitingUntil = 0L // elapsedRealtime

    @JvmStatic
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 36

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("hotspot", Context.MODE_PRIVATE)

    @JvmStatic
    fun mode(c: Context): String = if (prefs(c).getString("mode", INSTANT) == NORMAL) NORMAL else INSTANT

    /** Switches the mode (the tile) and applies it. */
    @JvmStatic
    fun setMode(c: Context, mode: String) {
        prefs(c).edit().putString("mode", mode).apply()
        Log.i(TAG, "mode $mode")
        // A running hotspot was checked against the old mode.
        checked = false
        apply(c)
        changed()
    }

    /** The key from setting hotspot_key (64 hex characters), or null. */
    private fun key(c: Context): ByteArray? = HotspotToken.parseKey(Settings.value(c, "hotspot_key"))

    /** For the tile: `r` runs on the main thread when the mode, the hotspot or the scan changes. */
    @JvmStatic
    fun listen(r: Runnable) {
        synchronized(listeners) { listeners.add(r) }
    }

    @JvmStatic
    fun unlisten(r: Runnable) {
        synchronized(listeners) { listeners.remove(r) }
    }

    private fun changed() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (r in copy) main.post(r)
    }

    /**
     * Brings the hotspot and the scan in line with the mode. Returns at once:
     * the shell calls run on a thread of their own.
     */
    @JvmStatic
    fun apply(c: Context) {
        val ctx = c.applicationContext
        init(ctx)
        if (!supported()) return
        main.post { updateScan(ctx) }
        worker.execute { reconcile(ctx) }
    }

    /** Starts watching the hotspot in this process (the tile), without changing anything. */
    @JvmStatic
    fun watch(c: Context) = init(c.applicationContext)

    /** What the metrics push reports (android/METRICS.md). */
    @JvmStatic
    fun metrics(c: Context): PhoneMetrics.Hotspot {
        val p = prefs(c)
        return PhoneMetrics.Hotspot(
            mode(c), on, scanning,
            mapOf("ble" to p.getLong("starts_ble", 0), "normal" to p.getLong("starts_normal", 0)),
        )
    }

    private fun counted(c: Context, reason: String) {
        val p = prefs(c)
        p.edit().putLong("starts_$reason", p.getLong("starts_$reason", 0) + 1).apply()
    }

    // --- Watching ---

    /** Once per process: the callbacks that feed apply(). */
    @Synchronized
    private fun init(ctx: Context) {
        if (app != null) return
        app = ctx
        if (!supported()) return
        Shell.listen(shellChanged)
        try {
            ctx.getSystemService(TetheringManager::class.java)?.registerTetheringEventCallback(ctx.mainExecutor, tethering)
        } catch (e: RuntimeException) {
            Log.w(TAG, "tethering callback: $e")
        }
        // Bluetooth and location switched, new settings (hotspot_key): only
        // to receivers registered at run time.
        val filter = Settings.changedFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = updateScan(ctx)
        }, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private val tethering = object : TetheringManager.TetheringEventCallback {
        override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
            val c = synchronized(this@Hotspot) { app } ?: return
            val now = interfaces.any { it.type == TetheringManager.TETHERING_WIFI }
            val was = on
            val first = !known
            on = now
            known = true
            if (now == was && !first) return
            Log.i(TAG, "hotspot " + if (now) "on" else "off")
            if (!now) {
                checked = false
                instantOn = false
            }
            main.removeCallbacks(restart)
            if (!now && mode(c) == NORMAL) {
                // Wait a little: Wi-Fi restarts the hotspot by itself at times
                // (a country code change), and someone switching it off sees it
                // go off first.
                main.postDelayed(restart, RESTART_DELAY_MS)
            }
            if (now && !checked) worker.execute { reconcile(c) }
            updateScan(c)
            changed()
        }
    }

    private val restart = Runnable { synchronized(this) { app }?.let { apply(it) } }

    private val shellChanged = Runnable {
        val c = synchronized(this) { app } ?: return@Runnable
        if (shellWaiting && Shell.available()) {
            Log.i(TAG, "the shell server attached; applying the hotspot mode")
            apply(c)
        }
    }

    // --- The hotspot ---

    /** On the worker thread. */
    private fun reconcile(c: Context) {
        if (mode(c) == NORMAL) {
            if (on && checked) return
            // The tool checks what runs: a normal hotspot stays as it is, any
            // other is restarted with normal mode's configuration.
            val r = tool(c, "start", NORMAL) ?: return
            if (r.startsWith("started")) counted(c, "normal")
            if (r.startsWith("started") || r.startsWith("already")) checked = true
            return
        }
        if (bleWaitingUntil > SystemClock.elapsedRealtime() && !on) {
            bleWaitingUntil = 0
            startForMac(c)
            return
        }
        // Instant: a hotspot left from normal mode goes off; one the owner
        // switched on by hand stays.
        if (!on || checked) return
        val r = tool(c, "status") ?: return
        if (r == NORMAL) tool(c, "stop")
        // One Prism started for the Mac before this process: a release may stop it.
        instantOn = r == INSTANT
        checked = true
        main.post { updateScan(c) }
    }

    /** On the worker thread: the Mac asked. */
    private fun startForMac(c: Context) {
        if (mode(c) != INSTANT) return
        val r = tool(c, "start", INSTANT)
        if (r == null) {
            bleWaitingUntil = SystemClock.elapsedRealtime() + BLE_PENDING_MS
            return
        }
        if (r.startsWith("started")) counted(c, "ble")
        if (r.startsWith("started") || r.startsWith("already")) {
            checked = true
            instantOn = true
            main.post { updateScan(c) }
        }
    }

    /** On the worker thread: the Mac left for a proven Wi-Fi. */
    private fun releaseForMac(c: Context) {
        if (mode(c) != INSTANT || !on || !instantOn) return
        tool(c, "stop")
    }

    /**
     * Runs HotspotTool from Prism's APK as the shell user; its one line, or
     * null when the shell server isn't there (apply() runs again when it is).
     */
    private fun tool(c: Context, vararg args: String): String? {
        if (!Shell.available()) {
            Log.i(TAG, "no shell server for hotspot ${args.joinToString(" ")}; waiting for it")
            shellWaiting = true
            return null
        }
        val apk = c.applicationInfo.sourceDir
        return try {
            val r = Shell.run(
                60_000, "/system/bin/env", "CLASSPATH=$apk",
                "/system/bin/app_process", "-Djava.class.path=$apk", "/system/bin", "--nice-name=prism_hotspot",
                HotspotTool::class.java.name, *args,
            )
            shellWaiting = false
            val line = r.stdout.trim().lineSequence().lastOrNull()?.trim() ?: ""
            if (r.ok()) {
                Log.i(TAG, "hotspot ${args.joinToString(" ")}: $line")
            } else {
                Log.w(TAG, "hotspot ${args.joinToString(" ")}: $r $line ${r.stderr.take(300)}")
            }
            line
        } catch (e: IOException) {
            Log.w(TAG, "hotspot ${args.joinToString(" ")}: ${e.message}")
            shellWaiting = true
            null
        }
    }

    // --- The Mac's request (Bluetooth LE) ---

    private fun scanIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 0, Intent(c, ScanReceiver::class.java),
            // Mutable: the system adds the results to it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun granted(c: Context, permission: String) = c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun scanner(c: Context): BluetoothLeScanner? {
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return if (adapter.isEnabled) adapter.bluetoothLeScanner else null
    }

    /** Starts or stops the scan as the conditions say; any thread. */
    @Synchronized
    private fun updateScan(c: Context) {
        if (!supported()) return
        val scanner = scanner(c)
        val want = mode(c) == INSTANT && key(c) != null && scanner != null &&
            c.getSystemService(LocationManager::class.java)?.isLocationEnabled == true &&
            known && (!on || instantOn) &&
            granted(c, Manifest.permission.BLUETOOTH_SCAN) && granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
        // With Bluetooth off the system dropped the scan by itself.
        if (scanner == null) {
            if (scanning) changed()
            scanning = false
            return
        }
        if (want == scanning && scanSet) return
        val pi = scanIntent(c)
        try {
            // A scan of an earlier process may still be registered with the same intent.
            scanner.stopScan(pi)
            scanning = false
            if (want) {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(HotspotToken.FILTER_UUID), ParcelUuid(HotspotToken.FILTER_MASK))
                    .build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
                val rc = scanner.startScan(listOf(filter), settings, pi)
                scanning = rc == 0
                if (rc != 0) Log.w(TAG, "BLE scan failed to start: $rc")
            }
            scanSet = true
            Log.i(TAG, "BLE scan " + if (scanning) "on" else "off")
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE scan: $e")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "BLE scan: $e") // Bluetooth went off meanwhile
        }
        changed()
    }

    /** Results of the scan (the PendingIntent). */
    private fun scanned(c: Context, results: List<ScanResult>, done: () -> Unit) {
        val key = key(c)
        if (key == null || mode(c) != INSTANT) return done()
        val now = System.currentTimeMillis()
        val uuids = results.flatMap { r -> r.scanRecord?.serviceUuids.orEmpty().map { it.uuid } }
        val release = on && instantOn && uuids.any { HotspotToken.accepts(key, it, now, HotspotToken.RELEASE) }
        val asked = !on && uuids.any { HotspotToken.accepts(key, it, now) }
        if (!asked && !release) return done()
        synchronized(this) {
            val t = SystemClock.elapsedRealtime()
            if (lastBleStart != 0L && t - lastBleStart < BLE_INTERVAL_MS) return done()
            lastBleStart = t
        }
        Log.i(TAG, if (release) "the Mac left for a proven Wi-Fi: hotspot off" else "the Mac asks for the hotspot")
        worker.execute {
            try {
                if (release) releaseForMac(c) else if (!on) startForMac(c)
            } finally {
                done()
            }
        }
    }

    class ScanReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = context.applicationContext
            init(c)
            val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
            if (error != 0) {
                Log.w(TAG, "BLE scan error $error")
                scanning = false
                scanSet = false
                return
            }
            if (!supported()) return
            val results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            if (results.isNullOrEmpty()) return
            // The shell call may take seconds: keep the broadcast alive for it.
            val pending = goAsync()
            scanned(c, results) { pending.finish() }
        }
    }
}
