// Starts and stops the phone's Wi-Fi hotspot as the shell user (uid 2000),
// for Hotspot.kt (docs/hotspot.md). Prism runs it from its own APK through the
// shell server:
//
//   app_process -Djava.class.path=<Prism's APK> /system/bin \
//     at.quickme.prism.shell.HotspotTool start instant|start normal|stop|status
//
// It prints one line ("started instant", "already normal", "stopped",
// "off", "error: …") and exits non-zero on failure.
//
// Why the shell: the SoftApConfiguration setters it needs (band, bandwidth,
// idle timeout, client limit) are system APIs, and starting tethering with a
// configuration takes TETHER_PRIVILEGED or NETWORK_SETTINGS, which the shell
// holds. It calls as the package com.android.shell, so TetheringService's
// check that the package belongs to the calling uid passes.
//
// Why a process that exits: the tethering module keeps a Wi-Fi tethering
// request without watching who made it. Tethering.startTethering queues the
// request in its RequestTracker and asks Wi-Fi for the hotspot with a
// callback of its own (Tethering's StartTetheringSoftApCallback, with
// Binder.clearCallingIdentity, so Wi-Fi sees the tethering module as the
// caller); nothing in Tethering.java, TetheringService.java or
// WifiServiceImpl.startTetheredHotspotRequest links to the caller's binder or
// process. Our listener only receives the result. So the hotspot stays up
// after this process has exited, and nothing long-lived is needed.
//
// The persisted hotspot configuration (Settings) is never written: every
// start carries its own copy (TetheringRequest.setSoftApConfiguration,
// Android 16).

package at.quickme.prism.shell

import android.content.Context
import android.content.ContextWrapper
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

object HotspotTool {
    private const val TAG = "PrismHotspot"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val TETHERING_SERVICE = "tethering"

    /** Instant mode's idle timeout: off 120 s after the last client left. */
    const val INSTANT_TIMEOUT_MS = 120_000L

    // WifiManager's AP states (system API).
    private const val AP_DISABLING = 10
    private const val AP_DISABLED = 11
    private const val AP_FAILED = 14

    // SoftApInfo.CHANNEL_WIDTH_80MHZ (system API), if the field can't be read.
    private const val CHANNEL_WIDTH_80MHZ = 4

    private val executor = Executors.newSingleThreadExecutor()

    @Suppress("DEPRECATION") // prepareMainLooper: a process of our own
    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val (line, code) = try {
            run(args.toList())
        } catch (e: Throwable) {
            Log.e(TAG, "hotspot ${args.joinToString(" ")}", e)
            "error: $e" to 1
        }
        println(line)
        System.out.flush()
        System.exit(code)
    }

    private fun run(args: List<String>): Pair<String, Int> {
        val context = Hidden.packageContext(Hidden.systemContext(), "com.android.shell", android.os.Process.SHELL_UID)
        val tm = tetheringManager(context) ?: return "error: no TetheringManager" to 1
        val wm = context.getSystemService(WifiManager::class.java) ?: return "error: no WifiManager" to 1
        return when (args.getOrNull(0)) {
            "status" -> running(tm) to 0
            "stop" -> if (stop(tm, wm)) "stopped" to 0 else "error: still on" to 1
            "start" -> when (val mode = args.getOrNull(1)) {
                "instant", "normal" -> start(context, tm, wm, mode)
                else -> "error: start instant|normal" to 2
            }
            else -> "error: usage: start instant|start normal|stop|status" to 2
        }
    }

    /**
     * A TetheringManager that calls as com.android.shell. The one
     * getSystemService hands out in this process names the system context's
     * package ("android") as the caller, which TetheringService refuses for
     * uid 2000 ("Package name android does not match UID 2000", error 14):
     * build one on a context that names the shell's package, with the
     * tethering service's binder.
     */
    private fun tetheringManager(context: Context): TetheringManager? {
        val shell = object : ContextWrapper(context) {
            override fun getOpPackageName(): String = SHELL_PACKAGE
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getAttributionTag(): String? = null
        }
        return try {
            TetheringManager::class.java
                .getDeclaredConstructor(Context::class.java, Supplier::class.java)
                .apply { isAccessible = true }
                .newInstance(shell, Supplier<IBinder?> { Hidden.service(TETHERING_SERVICE) })
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "TetheringManager(Context, Supplier): $e; using the system's")
            context.getSystemService(TetheringManager::class.java)
        }
    }

    // --- What runs ---

    /**
     * Which configuration Wi-Fi tethering runs with: "off", "instant",
     * "normal", or "other" (started elsewhere, e.g. from Quick Settings with
     * the persisted configuration). The tethering module tells the requesting
     * uid, or a caller with NETWORK_SETTINGS, the request's configuration.
     */
    private fun running(tm: TetheringManager): String {
        val wifi = AtomicReference<TetheringInterface?>()
        val got = CountDownLatch(1)
        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                wifi.set(interfaces.firstOrNull { it.type == TETHERING_WIFI })
                got.countDown()
            }
        }
        tm.registerTetheringEventCallback(executor, callback)
        try {
            // The callback reports the current state at once on registration.
            if (!got.await(5, TimeUnit.SECONDS)) Log.w(TAG, "no tethering state within 5 s")
        } finally {
            tm.unregisterTetheringEventCallback(callback)
        }
        val iface = wifi.get() ?: return "off"
        val cfg = iface.softApConfiguration ?: return "other"
        return classify(cfg)
    }

    private fun classify(cfg: SoftApConfiguration): String {
        val auto = getter(cfg, "isAutoShutdownEnabled") as Boolean?
        val timeout = (getter(cfg, "getShutdownTimeoutMillis") as Number?)?.toLong()
        val clients = (getter(cfg, "getMaxNumberOfClients") as Number?)?.toInt()
        return when {
            auto == true && timeout == INSTANT_TIMEOUT_MS && clients == 1 -> "instant"
            auto == false && (clients == null || clients == 0) -> "normal"
            else -> "other"
        }
    }

    private fun getter(target: Any, name: String): Any? = try {
        Hidden.call(target, name)
    } catch (e: Throwable) {
        Log.w(TAG, "$name: $e")
        null
    }

    private val TETHERING_WIFI: Int by lazy {
        try {
            TetheringManager::class.java.getField("TETHERING_WIFI").getInt(null)
        } catch (e: ReflectiveOperationException) {
            0
        }
    }

    // --- Starting ---

    private fun start(context: Context, tm: TetheringManager, wm: WifiManager, mode: String): Pair<String, Int> {
        val now = running(tm)
        if (now == mode) return "already $mode" to 0
        // Another mode's hotspot (or one started elsewhere): Wi-Fi starts a
        // tethered hotspot only from idle, so it goes off first.
        if (now != "off" || !idle(wm)) {
            Log.i(TAG, "running as $now; stopping it before starting $mode")
            if (!stop(tm, wm)) return "error: could not stop the running hotspot ($now)" to 1
        }
        val persisted = Hidden.call(wm, "getSoftApConfiguration") as SoftApConfiguration?
            ?: return "error: no persisted hotspot configuration" to 1
        val cfg = configure(persisted, mode)
        val request = TetheringManager.TetheringRequest.Builder(TETHERING_WIFI).setSoftApConfiguration(cfg).build()
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        tm.startTethering(request, executor, object : TetheringManager.StartTetheringCallback {
            override fun onTetheringStarted() {
                result.set(0)
                done.countDown()
            }

            override fun onTetheringFailed(error: Int) {
                result.set(error)
                done.countDown()
            }
        })
        if (!done.await(30, TimeUnit.SECONDS)) return "error: no answer from tethering in 30 s" to 1
        val error = result.get()
        if (error != 0) return "error: tethering failed with $error" to 1
        Log.i(TAG, "started $mode")
        return "started $mode" to 0
    }

    /**
     * A copy of the persisted configuration for `mode`, through the system
     * API's builder. A setter this Android doesn't have is logged and
     * skipped: the hotspot still starts, only less tuned.
     */
    private fun configure(persisted: SoftApConfiguration, mode: String): SoftApConfiguration {
        val builder = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            .getConstructor(SoftApConfiguration::class.java).newInstance(persisted)
        fun set(name: String, vararg args: Any) {
            try {
                Hidden.call(builder, name, *args)
            } catch (e: Throwable) {
                Log.w(TAG, "skipping $name(${args.joinToString()}): $e")
            }
        }
        if (mode == "instant") {
            // One AP, not bridged (setBand replaces the channels, and with
            // them bridged bands), on 5 GHz where it can be: Android picks the
            // highest band of the set it can use. 5 GHz alone fails while the
            // phone's own Wi-Fi sits on a channel the AP can't share (a DFS
            // channel such as 5500 MHz: "Wifi connected to unavailable freq"),
            // so 2.4 GHz stays allowed. 80 MHz is what a MacBook uses on 5 GHz
            // anyway, and finishes transfers sooner.
            set("setBand", SoftApConfiguration.BAND_5GHZ or SoftApConfiguration.BAND_2GHZ)
            set("setMaxChannelBandwidth", channelWidth80())
            set("setAutoShutdownEnabled", true)
            set("setShutdownTimeoutMillis", INSTANT_TIMEOUT_MS)
            set("setMaxNumberOfClients", 1)
        } else {
            set("setAutoShutdownEnabled", false)
            set("setMaxNumberOfClients", 0) // no limit
        }
        return Hidden.call(builder, "build") as SoftApConfiguration
    }

    private fun channelWidth80(): Int = try {
        Class.forName("android.net.wifi.SoftApInfo").getField("CHANNEL_WIDTH_80MHZ").getInt(null)
    } catch (e: ReflectiveOperationException) {
        CHANNEL_WIDTH_80MHZ
    }

    // --- Stopping ---

    private fun apState(wm: WifiManager): Int = try {
        Hidden.call(wm, "getWifiApState") as Int
    } catch (e: Throwable) {
        Log.w(TAG, "getWifiApState: $e")
        -1
    }

    private fun idle(wm: WifiManager): Boolean = apState(wm).let { it == AP_DISABLED || it == AP_FAILED }

    /** Stops Wi-Fi tethering and waits (at most 15 s) until the AP is idle. */
    private fun stop(tm: TetheringManager, wm: WifiManager): Boolean {
        if (idle(wm)) return true
        if (apState(wm) != AP_DISABLING) {
            try {
                // The system API by type stops whatever Wi-Fi tethering runs,
                // whoever started it.
                Hidden.call(tm, "stopTethering", TETHERING_WIFI)
            } catch (e: Throwable) {
                Log.w(TAG, "stopTethering(int): $e; trying the request API")
                tm.stopTethering(TetheringManager.TetheringRequest.Builder(TETHERING_WIFI).build(), executor,
                    object : TetheringManager.StopTetheringCallback {})
            }
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (idle(wm)) return true
            Thread.sleep(200)
        }
        return idle(wm)
    }
}
