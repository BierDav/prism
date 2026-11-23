// The phone in the owner's NetBird network, from inside the app: NetbirdVpn
// is a VpnService that runs NetBird's client library (android/netbird, the
// Go engine behind io.netbird.gomobile.android) and hands it the tun device,
// so the Prism server and T3 Code's web app on the NetBird peers are
// reachable without the NetBird app, and *.netbird.cloud names resolve
// through the tunnel.
//
// For the rest of the app:
//
//   val consent = Netbird.needsConsent(activity)   // null once allowed
//   if (consent != null) launcher.launch(consent) else Netbird.start(activity)
//   // on RESULT_OK: Netbird.start(activity)
//
//   Netbird.start(context)     starts the VPN when it is allowed and either
//                              enrolled or a setup key is configured; safe to
//                              call any time (BootReceiver, every app start)
//   Netbird.stop(context)      disconnects until the next start
//   Netbird.needsConsent(c)    the system's VPN consent dialog to launch once
//                              (VpnService.prepare), or null
//   Netbird.enrolled(c)        this phone is a NetBird peer (state on disk)
//   Netbird.connected()        the engine reports Connected
//   Netbird.statusText()       one line for the screen ("Connected, 3 peers")
//   Netbird.ip()               this phone's NetBird address, or ""
//   Netbird.fqdn()             this phone's NetBird name, or ""
//   Netbird.exitNodes()        the exit nodes the network offers this peer
//                              (routes for 0.0.0.0/0 or ::/0), as last seen
//   Netbird.exitNode(c)        the owner's pick (its id), or null for none
//   Netbird.selectExitNode(c, id)
//                              picks one (null: none) and applies it now
//   Netbird.resources()        the other routes the network offers this peer
//                              (networks and domains), as last seen
//   Netbird.enabledResources(c) the ids the owner switched on (none by default)
//   Netbird.enableResource(c, id, on)
//                              switches one on or off and applies it now
//   Netbird.listen(r) / unlisten(r)
//                              r runs on the main thread after every change,
//                              like PrismPush.listen
//
// Enrollment: the setting netbird_setup_key (Settings.kt) is
// used once, against netbird_management_url (default NetBird's hosted
// service). After a successful login the engine's config (with the peer's
// WireGuard key) and state stay in the app's private files
// (files/netbird/), and later starts need no key. Only a key that differs from
// the one already used logs in again; the SHA-256 of the used key is kept,
// never the key itself.
//
// Exit node: by default the tunnel carries only the NetBird peers and the
// resources switched on (below). The
// owner may pick one exit node (PhoneTab); then the engine selects that route
// (and its ::/0 pair), deselects every other exit node, and NetbirdVpn adds
// the default route to the tun device, so all of the phone's traffic leaves
// through that peer. NetBird's own sockets and its forwarding to the outside
// network's DNS servers are protected from the tunnel, so they keep working.
// The pick is kept in the app's private preferences ("netbird", exit_node)
// and applied again whenever the engine connects or its routes change, since
// the engine may restart without it; "none" is the default.
//
// Resources: NetBird's engine routes every network and domain the management
// service offers this peer unless told otherwise. Here none is routed until
// the owner switches it on (the Phone tab's "Resources" page): whenever the
// engine connects or its routes change, every offered route that is not in
// the owner's list is deselected and every one in it selected. The list is
// kept in the app's private preferences ("netbird", resources). A route the
// network newly offers is deselected within a moment of the engine learning of
// it. Peers themselves (the Prism server, T3 Code's web app) need no route and
// stay reachable either way.

// Only the release app runs the VPN: NetBird's socket directory is compiled
// for its package (netbird/), and a debug build's VPN would replace the
// release app's tunnel. Other package ids say so and never ask for consent.

package at.quickme.prism.netbird

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import at.quickme.prism.Settings
import java.io.File

object Netbird {
    private const val RELEASE_PACKAGE = "at.quickme.prism"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>()

    @Volatile private var status = "not started"
    @Volatile private var connected = false
    @Volatile private var tunnel = false
    @Volatile private var ip = ""
    @Volatile private var fqdn = ""
    @Volatile private var peers = 0
    @Volatile private var exitNodes: List<ExitNode> = emptyList()
    @Volatile private var resources: List<Resource> = emptyList()

    /** Set by the running NetbirdVpn: applies the owner's exit node and resource picks soon. */
    @Volatile internal var applyRoutes: Runnable? = null

    private const val PREFS = "netbird"
    private const val EXIT_NODE = "exit_node"
    private const val RESOURCES = "resources"
    private const val TURNED_OFF = "turned_off"

    /**
     * An exit node the NetBird network offers this peer: a route for
     * 0.0.0.0/0 or ::/0 (an IPv4 one stands for its IPv6 pair as well).
     */
    data class ExitNode(
        /** The route's network id in NetBird, which selects it. */
        val id: String,
        val name: String,
        /** The routing peer's NetBird name, or "". */
        val peer: String,
        /** The routing peer is connected. */
        val online: Boolean,
        /** The engine routes through it right now. */
        val selected: Boolean,
    )

    /**
     * A network or domain resource the NetBird network offers this peer: any
     * route but an exit node.
     */
    data class Resource(
        /** The route's network id in NetBird, which selects it. */
        val id: String,
        /** What it routes: "10.0.0.0/24", or domains ("git.example.com, *.lan"). */
        val network: String,
        /** The routing peer's NetBird name, or "". */
        val peer: String,
        /** The routing peer is connected. */
        val online: Boolean,
        /** The engine routes it right now. */
        val selected: Boolean,
    )

    // --- For the screens ---

    /** Starts the VPN if the owner has allowed it and there is a peer or a setup key; nothing while its tunnel is up. */
    @JvmStatic
    fun start(c: Context) {
        if (tunnel) return
        val app = c.applicationContext
        if (!on(app)) {
            setStatus("turned off")
            return
        }
        if (app.packageName != RELEASE_PACKAGE) {
            setStatus("off in this build: only the release app runs NetBird")
            return
        }
        if (VpnService.prepare(app) != null) {
            setStatus("needs the owner's VPN consent")
            return
        }
        if (!enrolled(app) && Settings.netbirdSetupKey(app).isEmpty()) {
            setStatus("not enrolled: no netbird_setup_key provisioned")
            return
        }
        try {
            app.startForegroundService(Intent(app, NetbirdVpn::class.java))
        } catch (e: IllegalStateException) {
            setStatus("could not start: " + e.message)
        }
    }

    /** Whether the owner keeps NetBird on (the default); off, nothing starts it. */
    @JvmStatic
    fun on(c: Context): Boolean = !prefs(c).getBoolean(TURNED_OFF, false)

    /** Turns NetBird on (and starts it) or off (and stops it) until turned on again. */
    @JvmStatic
    fun setOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(TURNED_OFF, !on).apply()
        if (on) start(c) else stop(c)
        changed()
    }

    /** Disconnects; the next start connects again. */
    @JvmStatic
    fun stop(c: Context) {
        val app = c.applicationContext
        try {
            app.startService(Intent(app, NetbirdVpn::class.java).setAction(NetbirdVpn.ACTION_STOP))
        } catch (e: IllegalStateException) {
            // In the background with the VPN not running: nothing to stop.
            setStatus("stopped")
        }
    }

    /**
     * The system's VPN consent dialog, for an Activity to start for a result;
     * null when this app may already run its VPN (also when it is the
     * always-on VPN), and always in a build that never runs it.
     */
    @JvmStatic
    fun needsConsent(c: Context): Intent? {
        val app = c.applicationContext
        if (app.packageName != RELEASE_PACKAGE) return null
        return VpnService.prepare(app)
    }

    /** Whether this phone has logged in to NetBird before (its config is on disk). */
    @JvmStatic
    fun enrolled(c: Context): Boolean = File(dir(c), NetbirdVpn.ENROLLED).exists()

    @JvmStatic
    fun connected(): Boolean = connected

    @JvmStatic
    fun statusText(): String {
        if (connected) return if (peers == 1) "Connected, 1 peer" else "Connected, $peers peers"
        return status
    }

    /** This phone's NetBird address (without the prefix length), or "". */
    @JvmStatic
    fun ip(): String = ip

    /** This phone's NetBird name, e.g. pixel.netbird.cloud, or "". */
    @JvmStatic
    fun fqdn(): String = fqdn

    /** The exit nodes the network offers this peer, as of the last check while connected. */
    @JvmStatic
    fun exitNodes(): List<ExitNode> = exitNodes

    /** The id of the exit node the owner picked, or null for none (NetBird's networks only). */
    @JvmStatic
    fun exitNode(c: Context): String? = prefs(c).getString(EXIT_NODE, null)

    /**
     * Sends all traffic through the exit node `id`, or (null) only NetBird's
     * networks; kept for later starts and applied now when the VPN runs.
     */
    @JvmStatic
    fun selectExitNode(c: Context, id: String?) {
        val edit = prefs(c).edit()
        if (id == null) edit.remove(EXIT_NODE) else edit.putString(EXIT_NODE, id)
        edit.apply()
        exitNodes = exitNodes.map { it.copy(selected = it.id == id) }
        changed()
        applyRoutes?.run()
    }

    /** The resources the network offers this peer, as of the last check while connected. */
    @JvmStatic
    fun resources(): List<Resource> = resources

    /** The ids of the resources the owner switched on; none by default. */
    @JvmStatic
    fun enabledResources(c: Context): Set<String> = prefs(c).getStringSet(RESOURCES, null).orEmpty()

    /** Routes the resource `id` through the tunnel or not; kept for later starts and applied now when the VPN runs. */
    @JvmStatic
    fun enableResource(c: Context, id: String, on: Boolean) {
        val now = enabledResources(c).toMutableSet()
        if (on) now.add(id) else now.remove(id)
        prefs(c).edit().putStringSet(RESOURCES, now).apply()
        resources = resources.map { if (it.id == id) it.copy(selected = on) else it }
        changed()
        applyRoutes?.run()
    }

    @JvmStatic
    @Synchronized
    fun listen(r: Runnable) {
        listeners.add(r)
    }

    @JvmStatic
    @Synchronized
    fun unlisten(r: Runnable) {
        listeners.remove(r)
    }

    // --- For NetbirdVpn ---

    internal fun dir(c: Context) = File(c.applicationContext.filesDir, "netbird")

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun setStatus(s: String) {
        status = s
        changed()
    }

    /**
     * Whether the VPN's tunnel is established. Android then binds the VPN
     * service as a foreground service, which keeps Prism's process running:
     * the VPN and the relay need no notifications of their own meanwhile.
     */
    @JvmStatic
    fun tunnelUp(): Boolean = tunnel

    internal fun setTunnel(up: Boolean) {
        if (tunnel == up) return
        tunnel = up
        changed()
    }

    internal fun setConnected(on: Boolean, s: String) {
        connected = on
        status = s
        changed()
    }

    internal fun setAddress(newFqdn: String?, newIp: String?) {
        if (!newFqdn.isNullOrEmpty()) fqdn = newFqdn
        if (newIp != null) ip = newIp.substringBefore('/')
        changed()
    }

    internal fun setPeers(n: Int) {
        peers = n
        changed()
    }

    internal fun setRoutes(exits: List<ExitNode>, others: List<Resource>) {
        if (exits == exitNodes && others == resources) return
        exitNodes = exits
        resources = others
        changed()
    }

    private fun changed() {
        val copy = synchronized(this) { ArrayList(listeners) }
        for (r in copy) main.post(r)
    }
}
