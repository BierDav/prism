// Tells the cluster whether the shell server runs, on the relay topic
// `prism-shell` (android/shizuku/README.md has the contract; the cluster's
// shizuku-bootstrap reads it and starts the server over wireless debugging).
//
// Down: when the server dies, when no server reached Prism in its first 20 s
// (after a boot or an update), when the phone joins a Wi-Fi network, when
// NetBird connects, when the wireless-debugging port is found or changes,
// when Prism comes to the front (at most every 2 min), and every 5 min while
// down and the phone has a network. Up: once, when the server hands over its
// binder. Nobody has to ask: it keeps asking until the server runs.
//
// With every "down", Prism first sends one UDP datagram through NetBird to
// each cluster node (the A records of telemetry.quickme.at, NetBird addresses
// only): NetBird's lazy connections wake an idle tunnel only from the side
// that sends, so this opens the way for the cluster's adb connection.
//
// Android turns Wireless debugging off at every boot and when the phone leaves
// a Wi-Fi network. Like Shizuku's start on boot, Prism turns it back on when
// it is down and on Wi-Fi, with WRITE_SECURE_SETTINGS, which enrollment
// grants (enroll.py, `pm grant`; the network must have been allowed once with
// "Always allow on this network"). It also turns off the expiry of adb
// authorizations, as Shizuku does, so the cluster's key stays valid however
// long the phone runs without a reboot.
//
// Each message also says whether adbd listens on TCP 5555 (`adb_tcp`): the
// cluster runs `adb tcpip 5555` after it started the server, which holds
// until the next reboot.
//
// Pair: once, when the owner pairs the cluster's adb key from the Shell
// access screen (Pairing.kt), a `pair` message with the pairing port and the
// six-digit code. It is sent with ntfy's "Cache: no", so the relay hands it
// only to the subscriber that is connected and keeps no copy; the code never
// goes to logcat.

package at.quickme.prism.shell

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit
import at.quickme.prism.core.ShellPairing
import at.quickme.prism.Settings as PrismSettings

internal object Signal {
    private const val TAG = "PrismShell"
    const val TOPIC = "prism-shell"
    private const val ADB_SERVICE = "_adb-tls-connect._tcp"
    private const val FIRST_CHECK_MS = 20_000L
    private const val KEEPALIVE_MS = 5 * 60_000L
    private const val FRONT_MIN_MS = 2 * 60_000L
    private var lastAsked = 0L // handler thread only
    private const val DISCOVERY_MS = 2 * 60_000L
    private const val ALARM = "at.quickme.prism.shell.KEEPALIVE"

    /** The cluster nodes, by their NetBird addresses; the fixed ones if DNS has none. */
    private const val WAKE_HOST = "telemetry.quickme.at"
    private val WAKE_FALLBACK = listOf("100.123.47.63", "100.123.155.242", "100.123.98.20")

    /** discard: nothing listens, the node drops it. */
    private const val WAKE_PORT = 9

    private lateinit var app: Context
    private var handler: Handler? = null
    private val wifi: MutableSet<Network> = Collections.synchronizedSet(HashSet())
    private val networks: MutableSet<Network> = Collections.synchronizedSet(HashSet())
    private var down = false // handler thread only
    private var adbPort: Int? = null // handler thread only
    private var discovery: NsdManager.DiscoveryListener? = null // handler thread only
    private val toResolve = ArrayList<NsdServiceInfo>() // handler thread only
    private var resolving = false // handler thread only
    private var keepalive: PendingIntent? = null

    // Fixed instances, so removeCallbacks finds them.
    private val WIFI_JOINED = Runnable { wifiJoined() }
    private val STOP_DISCOVERY = Runnable { stopDiscovery() }
    private var retryReason: String? = null // handler thread only
    private val RETRY = Runnable {
        val reason = retryReason
        if (down && !Shell.available() && reason != null) publishDown(reason)
    }

    private val h: Handler get() = handler!!

    fun init(c: Context) {
        app = c
        val t = HandlerThread("prism-shell-signal")
        t.start()
        val handler = Handler(t.looper)
        this.handler = handler

        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    wifi.add(n)
                    // Give NetBird a moment to come up on the new network.
                    handler.removeCallbacks(WIFI_JOINED)
                    handler.postDelayed(WIFI_JOINED, 10_000)
                    Shell.changed()
                }

                override fun onLost(n: Network) {
                    wifi.remove(n)
                    Shell.changed()
                }
            },
            handler,
        )
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    networks.add(n)
                }

                override fun onLost(n: Network) {
                    networks.remove(n)
                }
            },
            handler,
        )

        // The keepalive is an alarm, not a handler delay: those stop while the
        // phone sleeps.
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handler.post { keepaliveDue() }
                }
            },
            IntentFilter(ALARM), null, handler, Context.RECEIVER_NOT_EXPORTED,
        )
        keepalive = PendingIntent.getBroadcast(
            app, 0, Intent(ALARM).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // NetBird up: the cluster can reach the phone now, so ask again.
        var netbird = false
        at.quickme.prism.netbird.Netbird.listen(Runnable {
            val now = at.quickme.prism.netbird.Netbird.connected()
            if (now && !netbird) handler.post { if (down && !Shell.available()) publishDown("netbird") }
            netbird = now
        })

        handler.post { keepAuthorizations() }
        handler.postDelayed({
            if (Shell.available()) return@postDelayed
            val reason = firstReason()
            Shell.noServer(reason)
            goDown(reason)
        }, FIRST_CHECK_MS)
    }

    fun hasWifi(): Boolean = wifi.isNotEmpty()

    // --- State changes (from Shell) ---

    fun up() {
        val handler = handler ?: return
        handler.post {
            down = false
            handler.removeCallbacks(RETRY)
            stopDiscovery()
            keepalive?.let { app.getSystemService(AlarmManager::class.java).cancel(it) }
            publish("up", "started")
            keepAuthorizations()
        }
    }

    /** The owner tapped "Ask the cluster now": while the server is down, tell it at once. */
    fun request() {
        val handler = handler ?: return
        handler.post {
            if (Shell.available()) return@post
            down = true
            ensureWirelessDebugging()
            publishDown("request")
            scheduleKeepalive()
            if (hasWifi()) startDiscovery()
        }
    }

    fun down(reason: String) {
        val handler = handler ?: return
        handler.post { goDown(reason) }
    }

    /** Prism came to the front: while the server is down, ask the cluster again (not too often). */
    fun front() {
        val handler = handler ?: return
        handler.post {
            if (!down || Shell.available() || SystemClock.elapsedRealtime() - lastAsked < FRONT_MIN_MS) return@post
            ensureWirelessDebugging()
            publishDown("front")
        }
    }

    /**
     * Pairing (Pairing.kt): Wireless debugging on, if it is off, we are on
     * Wi-Fi and may; the pairing dialog is part of it.
     */
    fun wirelessDebuggingForPairing() {
        val handler = handler ?: return
        handler.post { ensureWirelessDebugging() }
    }

    /**
     * Sends the `pair` message: the cluster pairs its key against [port] with
     * [code] and then starts the server. [done] gets null once the relay took
     * it, else why not; it runs on the signal thread.
     */
    fun pair(port: Int, code: String, done: (String?) -> Unit) {
        val handler = handler ?: return done("Prism's shell signal isn't running")
        val fields = try {
            ShellPairing.fields(port, code)
        } catch (e: IllegalArgumentException) {
            return done(e.message)
        }
        handler.post {
            if (!PrismSettings.relayAllowed(PrismSettings.relayUrl(app))) return@post done("no relay is configured")
            // The cluster connects to the pairing port through NetBird: open the tunnels first.
            wakeNodes()
            done(if (publish("pair", "pair", fields, cache = false)) null else "the relay can't be reached")
        }
    }

    private fun goDown(reason: String) {
        if (Shell.available()) return
        down = true
        ensureWirelessDebugging()
        publishDown(reason)
        scheduleKeepalive()
        if (hasWifi()) startDiscovery()
    }

    private fun wifiJoined() {
        if (!down || Shell.available()) return
        ensureWirelessDebugging()
        publishDown("wifi")
        scheduleKeepalive()
        startDiscovery()
    }

    private fun keepaliveDue() {
        if (!down || Shell.available()) return
        ensureWirelessDebugging()
        if (networks.isNotEmpty()) publishDown("keepalive")
        scheduleKeepalive()
        if (hasWifi()) startDiscovery()
    }

    private fun scheduleKeepalive() {
        val pi = keepalive ?: return
        app.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + KEEPALIVE_MS, pi)
    }

    // --- Wireless debugging ---

    private fun maySetSecureSettings(): Boolean =
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** adb authorizations don't expire (Developer options › "Disable adb authorization timeout"). */
    private fun keepAuthorizations() {
        if (!maySetSecureSettings()) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS (enrollment grants it): Wireless debugging stays as it is")
            return
        }
        try {
            if (Settings.Global.getLong(app.contentResolver, "adb_allowed_connection_time", -1L) != 0L) {
                Settings.Global.putLong(app.contentResolver, "adb_allowed_connection_time", 0L)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "adb_allowed_connection_time", e)
        }
    }

    /** Wireless debugging on, if it is off, we are on Wi-Fi and may. */
    private fun ensureWirelessDebugging() {
        if (!hasWifi() || !maySetSecureSettings()) return
        try {
            if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) != 1) {
                Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
                Log.i(TAG, "turned Wireless debugging on")
                // adbd announces its new port by mDNS in a few seconds.
                h.postDelayed({
                    stopDiscovery()
                    startDiscovery()
                }, 3_000)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "can't turn Wireless debugging on", e)
        }
    }

    /** Why there's no server when Prism's process has just started. */
    private fun firstReason(): String {
        if (SystemClock.elapsedRealtime() < 10 * 60_000L) return "boot"
        try {
            val p = app.packageManager.getPackageInfo(app.packageName, 0)
            if (System.currentTimeMillis() - p.lastUpdateTime < 10 * 60_000L) return "updated"
        } catch (e: PackageManager.NameNotFoundException) {
            // We are installed.
        }
        return "no-server"
    }

    // --- The message ---

    /** "down"; if the relay can't be reached (e.g. just after a boot), again every minute until it can. */
    private fun publishDown(reason: String) {
        lastAsked = SystemClock.elapsedRealtime()
        h.removeCallbacks(RETRY)
        retryReason = null
        wakeNodes()
        if (!publish("down", reason)) {
            retryReason = reason
            h.postDelayed(RETRY, 60_000)
        }
    }

    /**
     * POSTs one message to the topic. [extra] are more fields; a message with
     * [cache] false is not kept by the relay and its body is not logged.
     */
    private fun publish(state: String, reason: String, extra: String = "", cache: Boolean = true): Boolean {
        val base = PrismSettings.relayUrl(app)
        val token = PrismSettings.relayToken(app)
        val phone = PrismSettings.deviceName(app)
        if (!PrismSettings.relayAllowed(base)) {
            Log.w(TAG, "no relay configured; can't tell the cluster the shell server is $state")
            return true // nothing to retry until the relay is configured
        }
        val port = if (state != "up") currentPort() else null
        val body = "{\"v\":1,\"phone\":" + quote(phone.trim()) +
            ",\"state\":\"" + state + "\",\"reason\":\"" + reason + "\"" +
            ",\"wifi\":" + hasWifi() +
            ",\"netbird\":" + netbird() +
            ",\"adb_wifi\":" + adbWifi() +
            ",\"adb_port\":" + port +
            ",\"adb_tcp\":" + adbTcp() +
            ",\"app_version\":" + appVersion() + extra +
            ",\"time\":" + System.currentTimeMillis() / 1000 + "}"
        val url = base.trim().trimEnd('/') + "/" + TOPIC
        for (attempt in 0 until 3) {
            try {
                val http = URL(url).openConnection() as HttpURLConnection
                http.connectTimeout = 15_000
                http.readTimeout = 15_000
                http.requestMethod = "POST"
                http.doOutput = true
                if (token.isNotBlank()) http.setRequestProperty("Authorization", "Bearer " + token.trim())
                if (!cache) http.setRequestProperty("Cache", "no")
                http.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = http.responseCode
                http.disconnect()
                if (code / 100 == 2) {
                    Log.i(TAG, if (cache) "told the cluster: $body" else "told the cluster: $state ($reason)")
                    return true
                }
                Log.w(TAG, "relay answered HTTP $code on $TOPIC")
            } catch (e: IOException) {
                Log.w(TAG, "can't reach the relay: " + e.message)
            }
            SystemClock.sleep(2000L shl attempt)
        }
        return false
    }

    /** One datagram to each cluster node through NetBird, so the tunnels to them are up. */
    private fun wakeNodes() {
        val nodes = ArrayList<InetAddress>()
        try {
            InetAddress.getAllByName(WAKE_HOST).filterTo(nodes) { isNetbird(it) }
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            // No DNS yet: the fixed addresses.
        }
        if (nodes.isEmpty()) {
            for (ip in WAKE_FALLBACK) {
                try {
                    nodes.add(InetAddress.getByName(ip)) // a literal: no lookup
                } catch (e: IOException) {
                    // Can't happen for a literal.
                }
            }
        }
        try {
            DatagramSocket().use { socket ->
                val payload = "prism".toByteArray(Charsets.US_ASCII)
                for (node in nodes) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, node, WAKE_PORT))
                    } catch (e: IOException) {
                        Log.w(TAG, "wake " + node.hostAddress + ": " + e.message)
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            Log.w(TAG, "wake the cluster nodes: " + e.message)
        }
    }

    /** An address in NetBird's range, 100.64.0.0/10. */
    private fun isNetbird(a: InetAddress): Boolean {
        val b = a.address
        return a is Inet4Address && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun appVersion(): Long = try {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }

    /** Settings.Global adb_wifi_enabled, or null if an app may not read it. */
    private fun adbWifi(): Boolean? = try {
        Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) != 0
    } catch (e: RuntimeException) {
        null
    }

    /** Whether an interface has a NetBird address (100.64.0.0/10), or null if we can't tell. */
    private fun netbird(): Boolean? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .any { i -> i.inetAddresses.toList().any { isNetbird(it) } }
    } catch (e: Exception) {
        if (e !is IOException && e !is RuntimeException) throw e
        null
    }

    /** 5555 when adbd listens on TCP 5555 (`adb tcpip 5555`, until the next reboot), else null. */
    fun adbTcp(): Int? = if (property("service.adb.tcp.port")?.trim() == "5555") 5555 else null

    /**
     * A system property: through the shell server when it runs (uid 2000 may
     * read adbd's), else SystemProperties or getprop, if SELinux lets an app
     * read it; null if not readable.
     */
    fun property(name: String): String? {
        Shell.property(name)?.let { if (it.isNotEmpty()) return it }
        try {
            val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String?
            if (!v.isNullOrEmpty()) return v
        } catch (e: ReflectiveOperationException) {
            // Hidden from apps.
        } catch (e: RuntimeException) {
            // Hidden from apps.
        }
        return try {
            val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine() }
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            line?.trim()?.ifEmpty { null }
        } catch (e: IOException) {
            null
        } catch (e: InterruptedException) {
            null
        }
    }

    // --- The wireless-debugging port ---

    /** The port adbd listens on for wireless debugging, if we know it. */
    private fun currentPort(): Int? = portFromProperty() ?: adbPort

    /** service.adb.tls.port, if readable (property). */
    private fun portFromProperty(): Int? {
        val port = property("service.adb.tls.port")?.toIntOrNull() ?: return null
        return if (port in 1024..65535) port else null
    }

    /**
     * Looks for this phone's own wireless-debugging service with mDNS, as
     * Shizuku's manager does (AdbMdns); stops after DISCOVERY_MS or when the
     * server is up.
     */
    private fun startDiscovery() {
        if (discovery != null) return
        val nsd = app.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}

            override fun onDiscoveryStopped(type: String) {}

            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.w(TAG, "mDNS discovery failed: $error")
                h.post { discovery = null }
            }

            override fun onStopDiscoveryFailed(type: String, error: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                h.post {
                    toResolve.add(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discovery = listener
        try {
            nsd.discoverServices(ADB_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "mDNS discovery", e)
            discovery = null
            return
        }
        h.postDelayed(STOP_DISCOVERY, DISCOVERY_MS)
    }

    private fun stopDiscovery() {
        h.removeCallbacks(STOP_DISCOVERY)
        toResolve.clear()
        val listener = discovery ?: return
        try {
            app.getSystemService(NsdManager::class.java).stopServiceDiscovery(listener)
        } catch (e: RuntimeException) {
            // Already stopped.
        }
        discovery = null
    }

    @Suppress("DEPRECATION") // resolveService: its successor needs API 34
    private fun resolveNext() {
        if (resolving || toResolve.isEmpty() || discovery == null) return
        resolving = true
        val info = toResolve.removeAt(0)
        app.getSystemService(NsdManager::class.java).resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, error: Int) {
                    h.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    h.post {
                        resolving = false
                        if (isOwn(i)) found(i.port)
                        resolveNext()
                    }
                }
            },
        )
    }

    /** Whether a resolved service is this phone's own (other phones may debug on the same Wi-Fi). */
    @Suppress("DEPRECATION") // host: hostAddresses needs API 34
    fun isOwn(info: NsdServiceInfo): Boolean {
        val hosts = ArrayList<InetAddress>()
        if (Build.VERSION.SDK_INT >= 34) hosts.addAll(info.hostAddresses) else info.host?.let { hosts.add(it) }
        return try {
            NetworkInterface.getNetworkInterfaces().toList().any { i -> i.inetAddresses.toList().any { it in hosts } }
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            false
        }
    }

    private fun found(port: Int) {
        if (port < 1024 || port > 65535 || port == adbPort) return
        Log.i(TAG, "wireless debugging listens on port $port")
        adbPort = port
        if (down && !Shell.available()) publishDown("port")
    }
}
