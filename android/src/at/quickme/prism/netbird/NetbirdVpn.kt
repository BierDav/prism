// The VpnService that runs NetBird's engine (Netbird.kt has the overview).
// One thread enrolls with the setup key when needed and then runs the engine,
// which blocks until it stops; the engine calls back here for the tun device
// (configureInterface, and again after its routes change), to protect its own
// sockets from the tunnel, for the phone's interfaces, and with its state.
// The owner's exit node and resource picks are applied on the tun thread
// whenever the engine connects or its routes change.
// The phone's networks outside the VPN are watched so the engine learns of
// network changes and its DNS can forward to the network's own servers.
//
// A watchdog looks after an engine without a connection while the phone has
// a network: after NUDGE_MS it tells the engine there is a network and makes
// it redial; after RESTART_MS it restarts the engine with a fresh client
// (then after twice as long, up to RESTART_MAX_MS, until it connects). The
// engine's reconnect loops pause silently while it thinks there is no
// network, and the VPN's DNS server is the engine itself, so an engine that
// never resumes after a long standby takes the device's DNS down with it.

package at.quickme.prism.netbird

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import at.quickme.prism.R
import at.quickme.prism.Settings
import io.netbird.gomobile.android.Android
import io.netbird.gomobile.android.Client
import io.netbird.gomobile.android.ConnectionListener
import io.netbird.gomobile.android.DNSList
import io.netbird.gomobile.android.EnvList
import io.netbird.gomobile.android.ErrListener
import io.netbird.gomobile.android.IFaceDiscover
import io.netbird.gomobile.android.NetworkChangeListener
import io.netbird.gomobile.android.PlatformFiles
import io.netbird.gomobile.android.TunAdapter
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NetbirdVpn : VpnService() {
    companion object {
        const val ACTION_STOP = "at.quickme.prism.netbird.STOP"
        /** Apps a TV keeps out of the VPN (establish). */
        private val TV_OUTSIDE_VPN = listOf("com.google.android.tungsten.setupwraith")

        /**
         * Android Auto refuses to start ("Communication error 21") while its
         * network is a VPN, and its wireless link to the car is a local Wi-Fi
         * network that an exit node's default route would swallow.
         */
        private val OUTSIDE_VPN = listOf("com.google.android.projection.gearhead")

        /** In files/netbird: the SHA-256 (hex) of the setup key this peer enrolled with. */
        const val ENROLLED = "enrolled"
        private const val TAG = "Netbird"
        private const val CHANNEL = "netbird"
        private const val ONGOING_ID = 0x6e6274
        private const val LOGIN_TIMEOUT_S = 120L
        /** Without a connection this long (with a network): tell the engine there is a network. */
        private const val NUDGE_MS = 3 * 60_000L
        /** Without a connection this long: restart the engine; doubled after each restart that doesn't connect. */
        private const val RESTART_MS = 10 * 60_000L
        private const val RESTART_MAX_MS = 60 * 60_000L
        private const val WATCHDOG_MS = 30_000L
        private const val V4_DEFAULT = "0.0.0.0/0"
        private const val V6_DEFAULT = "::/0"

        private fun dnsList(servers: List<String>): DNSList {
            val list = DNSList()
            for (s in servers) {
                try {
                    list.add(s)
                } catch (e: Exception) {
                    // A scoped or odd address.
                }
            }
            return list
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun addAddress(b: Builder, cidr: String) {
            val host = cidr.substringBefore('/')
            val bits = if ('/' in cidr) cidr.substringAfter('/').toInt() else if (':' in host) 128 else 32
            b.addAddress(host, bits)
        }

        /** addRoute with the host bits cleared, which the Builder insists on. */
        private fun addRoute(b: Builder, cidr: String) {
            require('/' in cidr) { "no prefix length" }
            val bits = cidr.substringAfter('/').toInt()
            val host = cidr.substringBefore('/')
            require(host.matches(Regex("[0-9A-Fa-f.:]+"))) { "not an address" }
            val a = try {
                InetAddress.getByName(host).address // a literal: no lookup
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }
            for (i in a.indices) {
                val keep = (bits - 8 * i).coerceIn(0, 8)
                a[i] = (a[i].toInt() and (0xff shl (8 - keep))).toByte()
            }
            try {
                b.addRoute(InetAddress.getByAddress(a), bits)
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }
        }
    }

    /** Guards the engine loop's waits and the network callback. */
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()

    @Volatile private var running = false
    @Volatile private var restart = false
    /** The watchdog stopped the engine: run it again with a fresh client. */
    @Volatile private var stuck = false
    /** The engine runs (runWithoutLogin has not returned). */
    @Volatile private var engineRunning = false
    /**
     * uptimeMillis since the running engine has had no connection, or 0 while
     * it has one: time asleep doesn't count, so the engine gets its chance to
     * reconnect after a standby before the watchdog steps in.
     */
    @Volatile private var unconnectedSince = 0L
    /** The watchdog nudged the engine since unconnectedSince (tun thread). */
    private var nudged = false
    /** The watchdog's restart threshold (tun thread; reset on connecting). */
    private var restartAfter = RESTART_MS
    /** Android blocks the app's own network access on underlying (Doze and the like). */
    @Volatile private var blocked = false
    @Volatile private var client: Client? = null
    private var engine: Thread? = null
    /** Rebuilds the tun device and applies the route picks, off the engine's own threads. */
    private val tunWork = Executors.newSingleThreadScheduledExecutor()
    private val routesHook = Runnable { routesSoon() }

    /** A setup key that failed this run, so a bad key isn't retried in a loop. */
    @Volatile private var failedKeyHash: String? = null

    /** The interface as the engine last asked for it; the base for renewing it. */
    @Volatile private var tun: Tun? = null

    /** DNS servers of the network outside the VPN: NetBird forwards other names to them. */
    @Volatile private var hostDns: List<String> = emptyList()
    private val firstNetwork = CountDownLatch(1)

    /** Written on the main thread only. */
    @Volatile private var underlying: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settingsChanged: BroadcastReceiver? = null
    private var idleChanged: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Turned off: also when Android starts the always-on VPN (Dpc).
        val off = !Netbird.on(this)
        if (intent?.action == ACTION_STOP || off) {
            shutdown()
            Netbird.setConnected(false, if (off) "turned off" else "stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // A foreground start must call startForeground; with the tunnel up it is let go at once.
        goForeground()
        if (Netbird.tunnelUp()) stopForeground(STOP_FOREGROUND_REMOVE)
        if (!running) {
            running = true
            engine = Thread(::run, "netbird").apply { start() }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = settingsChanged()
            }
            settingsChanged = receiver
            registerReceiver(receiver, Settings.changedFilter(), Context.RECEIVER_NOT_EXPORTED)
            val idle = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val pm = getSystemService(PowerManager::class.java)
                    Log.i(TAG, "device idle: " + pm.isDeviceIdleMode)
                    if (!pm.isDeviceIdleMode) awake()
                }
            }
            idleChanged = idle
            registerReceiver(idle, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            Netbird.applyRoutes = routesHook
            onTunThread(WATCHDOG_MS) { watchdog() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val channel = NotificationChannel(CHANNEL, "NetBird", NotificationManager.IMPORTANCE_MIN)
        channel.description = "Keeps this phone in the owner's NetBird network"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val ongoing = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_prism)
            .setContentTitle("NetBird")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        try {
            startForeground(ONGOING_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: RuntimeException) {
            // Started by the system (always-on VPN, a sticky restart) while the
            // app may not start foreground services: the system's own binding
            // keeps an established VPN running anyway.
            Log.w(TAG, "no foreground service: $e")
        }
    }

    private fun openApp(): PendingIntent {
        val open = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onRevoke() {
        // The owner switched the VPN off, or another app's VPN took over.
        shutdown()
        Netbird.setConnected(false, "VPN switched off in the system settings")
        super.onRevoke()
    }

    override fun onDestroy() {
        if (Netbird.applyRoutes === routesHook) Netbird.applyRoutes = null
        shutdown()
        tunWork.shutdownNow()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        Netbird.setTunnel(false)
        client?.stop()
        lock.withLock {
            wake.signalAll()
            networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            networkCallback = null
        }
        settingsChanged?.let { unregisterReceiver(it) }
        settingsChanged = null
        idleChanged?.let { unregisterReceiver(it) }
        idleChanged = null
    }

    // --- The engine thread ---

    private fun run() {
        val dir = Netbird.dir(this)
        val cache = File(cacheDir, "netbird")
        if (!dir.mkdirs() && !dir.isDirectory || !cache.mkdirs() && !cache.isDirectory) {
            Netbird.setStatus("cannot create $dir")
            return
        }
        val files = object : PlatformFiles {
            override fun configurationFilePath() = File(dir, "netbird.cfg").path
            override fun stateFilePath() = File(dir, "state.json").path
            override fun cacheDir() = cache.path
        }
        go.Seq.setContext(applicationContext)
        var c = newClient()
        // The engine starts with the outside network's DNS servers, so wait
        // briefly for the first report of them.
        watchNetworks()
        try {
            firstNetwork.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            return
        }

        var backoff = 5_000L
        while (running) {
            restart = false
            stuck = false
            var wait = backoff
            try {
                if (!login(files)) {
                    wait = 0 // until a (new) setup key is provisioned
                } else if (!restart) {
                    Netbird.setStatus("connecting")
                    val started = System.currentTimeMillis()
                    unconnectedSince = SystemClock.uptimeMillis()
                    engineRunning = true
                    try {
                        c.runWithoutLogin(files, dnsList(hostDns), { dnsReady() }, EnvList())
                    } finally {
                        engineRunning = false
                    }
                    if (System.currentTimeMillis() - started > 60_000) {
                        backoff = 5_000
                        wait = 5_000
                    }
                    if (stuck) {
                        // A fresh client: the stopped one's state (its idea
                        // that there is no network) stays behind.
                        backoff = 5_000
                        wait = 1_000
                        c.removeConnectionListener()
                        c = newClient()
                        Netbird.setConnected(false, "restarted: no connection for a long time")
                    } else if (running && c.status() == "NeedsLogin") {
                        // The management service dropped this peer: enroll again,
                        // with the managed key if it is one that works.
                        if (!File(dir, ENROLLED).delete()) Log.w(TAG, "could not forget the enrollment")
                        Netbird.setConnected(false, "NetBird wants a new login: needs a new netbird_setup_key")
                        // The client keeps reporting NeedsLogin until an
                        // interactive login; after enrolling, a fresh one.
                        c.removeConnectionListener()
                        c = newClient()
                    } else {
                        Netbird.setConnected(false, "stopped; again in ${wait / 1000} s")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "engine", e)
                Netbird.setConnected(false, "error: ${e.message}; again in ${wait / 1000} s")
            }
            lock.withLock {
                if (running && !restart) {
                    try {
                        // Without a wait (no key yet): until something wakes it.
                        if (wait > 0) wake.await(wait, TimeUnit.MILLISECONDS) else wake.await()
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
            if (wait > 0) backoff = minOf(backoff * 2, 300_000)
        }
    }

    /**
     * Every WATCHDOG_MS on the tun thread, for an engine without a connection
     * while the phone has a network: after NUDGE_MS tells it there is a
     * network; after restartAfter stops it, and the engine loop runs it
     * again with a fresh client.
     */
    private fun watchdog() {
        if (!running) return
        try {
            val since = unconnectedSince
            val c = client
            if (since == 0L) {
                nudged = false
                restartAfter = RESTART_MS
            } else if (engineRunning && c != null && !restart && !stuck && !blocked && outsideNetwork()) {
                val s = (SystemClock.uptimeMillis() - since) / 1000
                if (s * 1000 > restartAfter) {
                    Log.w(TAG, "no connection for $s s with a network: restarting the engine")
                    restartAfter = minOf(restartAfter * 2, RESTART_MAX_MS)
                    nudged = false
                    stuck = true
                    c.stop()
                } else if (s * 1000 > NUDGE_MS && !nudged) {
                    Log.w(TAG, "no connection for $s s with a network: telling the engine")
                    nudged = true
                    c.setNetworkAvailable(true)
                    c.notifyNetworkChange()
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "watchdog", e)
        } finally {
            onTunThread(WATCHDOG_MS) { watchdog() }
        }
    }

    /**
     * The device left idle or its network was unblocked: a disconnected engine
     * with a network learns it is there and redials now.
     */
    private fun awake() {
        if (!engineRunning || unconnectedSince == 0L || !outsideNetwork()) return
        val c = client ?: return
        Log.i(TAG, "awake while disconnected: redialing")
        c.setNetworkAvailable(true)
        c.notifyNetworkChange()
    }

    /**
     * Whether Android has a network outside the VPN, asked afresh rather than
     * taken from the callback's bookkeeping, which a missed callback would
     * leave wrong.
     */
    private fun outsideNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun newClient(): Client {
        val c = Android.newClient(Build.VERSION.SDK_INT.toLong(), deviceName(), "prism-android", Tunnel(), Interfaces(), Changes())
        c.setConnectionListener(State())
        client = c
        return c
    }

    /** The engine's DNS is up: give it the outside network's servers (again). */
    private fun dnsReady() {
        val c = client ?: return
        val servers = hostDns
        if (servers.isEmpty()) return
        try {
            c.onUpdatedHostDNS(dnsList(servers))
        } catch (e: Exception) {
            Log.i(TAG, "host DNS not updated: " + e.message)
        }
    }

    /**
     * Enrolls with the managed setup key unless this peer already enrolled
     * with it. False when there is nothing to run with yet.
     */
    private fun login(files: PlatformFiles): Boolean {
        val marker = File(Netbird.dir(this), ENROLLED)
        val used = if (marker.exists()) marker.readText(Charsets.UTF_8).trim() else null
        val key = Settings.netbirdSetupKey(this)
        val hash = if (key.isEmpty()) null else sha256(key)
        if (hash == null || hash == used || hash == failedKeyHash) {
            if (used != null) return true
            Netbird.setConnected(
                false,
                if (hash == null) "not enrolled: no netbird_setup_key provisioned" else "the setup key was refused; waiting for a new one",
            )
            return false
        }

        Netbird.setStatus("enrolling with the setup key")
        val auth = Android.newAuth(files.configurationFilePath(), Settings.netbirdManagementUrl(this), null)
        val done = CountDownLatch(1)
        var error: Exception? = null
        auth.loginWithSetupKeyAndSaveConfig(
            object : ErrListener {
                override fun onSuccess() = done.countDown()

                override fun onError(e: Exception) {
                    error = e
                    done.countDown()
                }
            },
            key, deviceName(),
        )
        if (!done.await(LOGIN_TIMEOUT_S, TimeUnit.SECONDS)) throw IOException("no answer from NetBird's management service")
        error?.let { e ->
            val message = e.message.toString()
            // A refused key stays refused; a network error is retried.
            if ("PermissionDenied" in message || "InvalidArgument" in message || "NotFound" in message ||
                "invalid setup key" in message
            ) {
                failedKeyHash = hash
                Log.w(TAG, "setup key refused: $message")
                if (used != null) return true // keep the enrollment we have
                Netbird.setConnected(false, "the setup key was refused: $message")
                return false
            }
            throw e
        }
        marker.writeText(hash + "\n", Charsets.UTF_8)
        Log.i(TAG, "enrolled")
        return true
    }

    /** A new or different setup key (provisioned or managed): enroll with it now. */
    private fun settingsChanged() {
        val key = Settings.netbirdSetupKey(this)
        if (key.isEmpty()) return
        val marker = File(Netbird.dir(this), ENROLLED)
        try {
            if (marker.exists() && sha256(key) == marker.readText(Charsets.UTF_8).trim()) return
        } catch (e: IOException) {
            Log.w(TAG, "reading $marker", e)
        }
        failedKeyHash = null
        restart = true
        client?.stop()
        lock.withLock { wake.signalAll() }
    }

    private fun deviceName(): String {
        val name = android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        return if (name.isNullOrBlank()) Build.MODEL else name.trim()
    }

    // --- The network outside the VPN (main thread) ---

    private fun watchNetworks() {
        val outside = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network $network available")
                val c = client ?: return
                c.setNetworkAvailable(true)
                if (underlying != null && underlying != network) c.notifyNetworkChange()
                if (underlying != network) blocked = false
                underlying = network
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val servers = properties.dnsServers.mapNotNull { it.hostAddress }
                val same = servers == hostDns
                hostDns = servers
                firstNetwork.countDown()
                if (!same) dnsReady()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "network $network lost")
                if (network != underlying) return
                underlying = null
                client?.setNetworkAvailable(false)
            }

            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
                // Doze or the like blocks the app's own network access.
                if (network != underlying || isBlocked == blocked) return
                Log.i(TAG, "network $network " + if (isBlocked) "blocked" else "unblocked")
                blocked = isBlocked
                if (!isBlocked) awake()
            }
        }
        lock.withLock {
            if (!running) return
            getSystemService(ConnectivityManager::class.java)
                .registerBestMatchingNetworkCallback(outside, callback, Handler(Looper.getMainLooper()))
            networkCallback = callback
        }
    }

    // --- Callbacks from the engine ---

    /** What configureInterface was asked for, with the latest routes. */
    private data class Tun(
        val address: String,
        val addressV6: String,
        val mtu: Int,
        val dns: String,
        val searchDomains: String,
        val routes: String,
    )

    /** Builds the tun device; the engine owns the returned fd. */
    private fun establish(t: Tun): Int {
        val b = Builder().setSession("NetBird").setMtu(t.mtu).setMetered(false).setConfigureIntent(openApp())
        addAddress(b, t.address)
        if (t.addressV6.isNotEmpty()) addAddress(b, t.addressV6)
        val routes = t.routes.split(";").map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        // An exit node: everything goes into the tunnel. One without an IPv6
        // pair still takes ::/0, so IPv6 is dropped in the tunnel instead of
        // leaving past the exit node (apps fall back to IPv4).
        if (V4_DEFAULT in routes && V6_DEFAULT !in routes) routes.add(V6_DEFAULT)
        if (t.dns.isNotEmpty()) {
            // NetBird's resolver answers *.netbird.cloud (and forwards the
            // rest to hostDns); it lives inside the tunnel.
            b.addDnsServer(t.dns)
            val host = t.dns + if (':' in t.dns) "/128" else "/32"
            if (host !in routes) routes.add(host)
        }
        for (r in routes) {
            try {
                addRoute(b, r)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "route $r skipped: " + e.message)
            }
        }
        for (d in t.searchDomains.split(";")) if (d.isNotBlank()) b.addSearchDomain(d.trim())
        // A TV's Google sign-in (its setup app) checks the internet bound to
        // its active network, which in the VPN is this split tunnel: it
        // reaches only NetBird, so the check says "no internet". Outside the
        // VPN its active network is the TV's own.
        val outside = if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) OUTSIDE_VPN + TV_OUTSIDE_VPN else OUTSIDE_VPN
        for (p in outside) {
            try {
                b.addDisallowedApplication(p)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        val fd = b.establish() ?: throw IllegalStateException("the VPN is not allowed (no consent, or it was revoked)")
        // Established: Android's own binding keeps the service (and Prism) running, so no notification.
        Netbird.setTunnel(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        return fd.detachFd()
    }

    /** Runs work on the tun thread after delayMs, unless the service is gone. */
    private fun onTunThread(delayMs: Long = 0, work: () -> Unit) {
        try {
            tunWork.schedule(Runnable(work), delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Destroyed meanwhile.
        }
    }

    private fun renew() {
        onTunThread {
            val c = client
            val t = tun
            if (c == null || t == null || !running) return@onTunThread
            try {
                val s = c.tunSettings
                val next = t.copy(searchDomains = s.searchDomains, routes = s.routes)
                c.renewTun(establish(next).toLong())
                tun = next
            } catch (e: Exception) {
                Log.w(TAG, "renewing the tun device", e)
            }
        }
    }

    // --- The exit node and the resources (tun thread) ---

    /**
     * Applies the owner's picks soon; after connecting again a little later
     * too, as the network map with the routes may come after the state.
     */
    private fun routesSoon(vararg delaysMs: Long) {
        for (d in if (delaysMs.isEmpty()) longArrayOf(0) else delaysMs) onTunThread(d) { applyRoutes() }
    }

    /**
     * Makes the engine route through the picked exit node only, or through
     * none (also while the picked one is not offered), and route only the
     * resources the owner switched on; then tells the screens. Never on an
     * engine callback's thread: the engine holds its route lock while it
     * calls back, and networks() takes it.
     */
    private fun applyRoutes() {
        val c = client
        if (c == null || !running) return
        val offered = routes(c) ?: return // the engine is not up
        val (exits, resources) = offered
        val want = Netbird.exitNode(this)
        try {
            if (want != null && exits.any { it.id == want }) {
                // Selecting one deselects every other exit node (the engine
                // keeps them exclusive) and takes its ::/0 pair along.
                if (exits.any { it.selected != (it.id == want) }) {
                    c.selectRoute(want)
                    Log.i(TAG, "exit node $want selected")
                }
            } else {
                for (n in exits.filter { it.selected }) {
                    c.deselectRoute(n.id)
                    Log.i(TAG, "exit node ${n.id} deselected")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applying the exit node: " + e.message)
        }
        val enabled = Netbird.enabledResources(this)
        for (r in resources) {
            val on = r.id in enabled
            if (r.selected == on) continue
            try {
                if (on) c.selectRoute(r.id) else c.deselectRoute(r.id)
                Log.i(TAG, "resource ${r.id} " + if (on) "selected" else "deselected")
            } catch (e: Exception) {
                Log.w(TAG, "resource ${r.id}: " + e.message)
            }
        }
        val now = routes(c) ?: offered
        Netbird.setRoutes(now.first, now.second)
    }

    /** The engine's exit nodes and other routes, or null while it has no route manager. */
    private fun routes(c: Client): Pair<List<Netbird.ExitNode>, List<Netbird.Resource>>? {
        val networks = c.networks() ?: return null
        val exits = ArrayList<Netbird.ExitNode>()
        val others = ArrayList<Netbird.Resource>()
        for (i in 0L until networks.size()) {
            val n = networks.get(i) ?: continue
            // "0.0.0.0/0", "::/0", or "0.0.0.0/0, ::/0" for a pair.
            val ranges = n.network.split(",").map { it.trim() }
            val online = n.status == "Connected"
            if (V4_DEFAULT in ranges || V6_DEFAULT in ranges) {
                exits.add(Netbird.ExitNode(n.name, n.name, n.peer.orEmpty(), online, n.isSelected))
            } else {
                others.add(Netbird.Resource(n.name, n.network.orEmpty(), n.peer.orEmpty(), online, n.isSelected))
            }
        }
        return exits.sortedBy { it.name.lowercase() } to others.sortedBy { it.id.lowercase() }
    }

    @Volatile private var protectedOnce = false

    private inner class Tunnel : TunAdapter {
        override fun configureInterface(
            address: String,
            addressV6: String?,
            mtu: Long,
            dns: String?,
            searchDomains: String?,
            routes: String?,
        ): Long {
            val t = Tun(address, addressV6.orEmpty(), mtu.toInt(), dns.orEmpty(), searchDomains.orEmpty(), routes.orEmpty())
            val fd = establish(t)
            tun = t
            Netbird.setAddress(null, address)
            return fd.toLong()
        }

        // NetBird protects every socket of its own (management, signal,
        // relays) from the tunnel. A refusal surfaces in NetBird only as a
        // dial timeout, so say it here; and once that protection works.
        override fun protectSocket(fd: Int): Boolean {
            val ok = protect(fd)
            if (!ok) {
                Log.w(TAG, "VpnService.protect refused socket $fd")
            } else if (!protectedOnce) {
                protectedOnce = true
                Log.i(TAG, "sockets are protected from the tunnel")
            }
            return ok
        }

        override fun updateAddr(address: String) {
            val t = tun
            if (t == null || address == t.address) return
            tun = t.copy(address = address)
            Netbird.setAddress(null, address)
            renew()
        }
    }

    /** The phone's interfaces, in the engine's format: "name index mtu up broadcast loopback p2p multicast|addr/len …". */
    private class Interfaces : IFaceDiscover {
        override fun iFaces(): String {
            val out = StringBuilder()
            val all = NetworkInterface.getNetworkInterfaces() ?: return ""
            for (i in all.toList()) {
                try {
                    var broadcast = false
                    val addresses = i.interfaceAddresses.joinToString(" ") { a ->
                        if (a.broadcast != null) broadcast = true
                        "${a.address.hostAddress}/${a.networkPrefixLength}"
                    }
                    out.append(i.name).append(' ').append(i.index).append(' ').append(i.mtu)
                        .append(' ').append(i.isUp).append(' ').append(broadcast)
                        .append(' ').append(i.isLoopback).append(' ').append(i.isPointToPoint)
                        .append(' ').append(i.supportsMulticast())
                        .append('|').append(addresses).append('\n')
                } catch (e: IOException) {
                    Log.i(TAG, "interface " + i.name + ": " + e.message)
                }
            }
            return out.toString()
        }
    }

    private inner class Changes : NetworkChangeListener {
        /** The engine's routes or DNS changed: build the tun device again. */
        override fun onNetworkChanged(ignored: String?) {
            renew()
            routesSoon()
        }

        override fun setInterfaceIP(ip: String?) = Netbird.setAddress(null, ip)

        override fun setInterfaceIPv6(ip: String?) {}
    }

    private inner class State : ConnectionListener {
        override fun onStateChanged(state: Long) = when (state) {
            Android.ClientStateConnected -> connected()
            Android.ClientStateConnecting -> unconnected("connecting")
            Android.ClientStateDisconnecting -> unconnected("disconnecting")
            Android.ClientStateNoNetwork -> unconnected("no network")
            else -> unconnected("disconnected")
        }

        override fun onConnected() = connected()
        override fun onConnecting() = unconnected("connecting")
        override fun onDisconnected() = unconnected("disconnected")
        override fun onDisconnecting() = unconnected("disconnecting")
        override fun onAddressChanged(fqdn: String?, ip: String?) = Netbird.setAddress(fqdn, ip)
        override fun onPeersListChanged(count: Long) {
            Netbird.setPeers(count.toInt())
            // A new peer may bring routes with the next network map.
            routesSoon(1_000)
        }

        private fun connected() {
            unconnectedSince = 0
            Netbird.setConnected(true, "connected")
            routesSoon(0, 3_000, 15_000)
        }

        private fun unconnected(s: String) {
            if (unconnectedSince == 0L) unconnectedSince = SystemClock.uptimeMillis()
            Netbird.setConnected(false, s)
        }
    }
}
