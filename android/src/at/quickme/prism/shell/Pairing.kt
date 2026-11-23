// Pairing the cluster's adb key from the phone, as Shizuku's manager pairs on
// Android 11+ (its AdbPairingService), except that the cluster pairs, not
// Prism: "Pair with the cluster" on the Shell access screen
//
//   1. turns Wireless debugging on (WRITE_SECURE_SETTINGS) and opens
//      Developer options at it;
//   2. posts a notification with a reply field for the six-digit code, so the
//      owner types the code without leaving Settings' "Pair device with
//      pairing code" dialog (leaving it closes it, and the code with it);
//   3. finds the dialog's pairing port with mDNS (_adb-tls-pairing._tcp, this
//      phone's own addresses only) and alerts again when it appears;
//   4. sends port and code to the cluster in a `pair` message on the relay
//      (Signal.pair); the cluster's shizuku-bootstrap pairs, connects and
//      starts the shell server;
//   5. counts the next server that attaches as success, or gives up after
//      RESULT_MS with "Try again".
//
// The code lives only in the reply's Intent and in memory until it is sent:
// never in logcat, preferences or a file. Nothing comes back from the
// cluster but the server's start, so a failure can't say more than that.

package at.quickme.prism.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import at.quickme.prism.R
import at.quickme.prism.core.ShellPairing
import java.text.DateFormat
import java.util.Date

internal object Pairing {
    private const val TAG = "PrismShell"
    private const val CHANNEL = "shell-pairing"
    private const val NOTIFICATION = 0x50414952 // "PAIR"
    private const val SERVICE = "_adb-tls-pairing._tcp"
    private const val KEY_CODE = "code"
    private const val ACTION_CODE = "at.quickme.prism.shell.PAIR_CODE"
    private const val ACTION_CANCEL = "at.quickme.prism.shell.PAIR_CANCEL"

    /** ShellActivity starts pairing when its Intent has this (the notification's "Try again"). */
    const val EXTRA_PAIR = "at.quickme.prism.shell.PAIR"

    /** Without a code for this long, the notification and the search end. */
    private const val WAIT_MS = 10 * 60_000L

    /** A code typed before the pairing service was found waits this long for it. */
    private const val PORT_WAIT_MS = 15_000L

    /** After the code went out, the server must attach within this. */
    const val RESULT_MS = 90_000L

    enum class Phase { IDLE, WAITING, SENDING, SENT, PAIRED, FAILED }

    /** What the screen shows: the phase, one line about it, and the pairing port if found. */
    data class State(val phase: Phase, val text: String = "", val port: Int? = null)

    @Volatile var state = State(Phase.IDLE)
        private set

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    // Main thread only.
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayList<NsdServiceInfo>()
    private var resolving = false
    private var port: Int? = null
    private var service: String? = null
    private var pendingCode: String? = null // typed before the port was found; memory only

    private val GIVE_UP = Runnable { if (state.phase == Phase.WAITING) stop(Phase.IDLE, "") }
    private val NO_RESULT = Runnable {
        if (state.phase == Phase.SENT || state.phase == Phase.SENDING) {
            fail(
                "The cluster didn't start the shell server within ${RESULT_MS / 1000} s. The code may have been " +
                    "mistyped or the dialog closed; Wi-Fi and NetBird must be on.",
            )
        }
    }
    private val NO_PORT = Runnable {
        if (pendingCode != null && port == null) {
            pendingCode = null
            set(Phase.WAITING, "Prism didn't find the pairing service. Keep “Pair device with pairing code” open and type the code again.")
            notifyWaiting(alert = true)
        }
    }

    /** "Pair with the cluster" (the screen, or "Try again"): from an Activity, on the main thread. */
    fun start(c: Context) {
        app = c.applicationContext
        createChannel()
        val nm = app.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            fail("Prism's notifications are off; the code is typed into one. Turn them on in Settings › Apps › Prism.", notify = false)
            return
        }
        if (!Signal.hasWifi()) {
            fail("Wireless debugging needs Wi-Fi: connect to a Wi-Fi network first.", notify = false)
            return
        }
        main.removeCallbacks(NO_RESULT)
        main.removeCallbacks(NO_PORT)
        pendingCode = null
        Signal.wirelessDebuggingForPairing()
        startDiscovery()
        set(Phase.WAITING, waitingText())
        notifyWaiting(alert = true)
        main.removeCallbacks(GIVE_UP)
        main.postDelayed(GIVE_UP, WAIT_MS)
        try {
            // Developer options at Wireless debugging, as Shizuku's manager opens it.
            c.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            stop(Phase.FAILED, "Developer options are off: Settings › About phone › tap Build number seven times.")
        }
    }

    /** The owner cancelled from the notification. */
    fun cancel() = stop(Phase.IDLE, "")

    /** A shell server attached (Shell.attach): after the code went out, that is the cluster's doing. */
    fun serverUp() {
        main.post {
            if (state.phase != Phase.SENT && state.phase != Phase.SENDING) return@post
            main.removeCallbacks(NO_RESULT)
            set(Phase.PAIRED, "Paired at ${time()}: the cluster started the shell server.")
            val n = builder()
                .setContentTitle("Paired with the cluster")
                .setContentText("The shell server runs as uid 2000.")
                .setContentIntent(openScreen(false))
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
        }
    }

    // --- The code ---

    private fun typed(c: Context, text: CharSequence?) {
        if (!::app.isInitialized) app = c.applicationContext
        when (state.phase) {
            Phase.SENDING, Phase.SENT -> return notifySent() // a second reply: the first is on its way
            Phase.PAIRED -> return
            else -> {}
        }
        val code = ShellPairing.code(text)
        if (code == null) {
            set(Phase.WAITING, "That wasn't six digits. Type the code from the dialog again.")
            return notifyWaiting(alert = false)
        }
        if (discovery == null) startDiscovery() // Prism's process was restarted meanwhile
        val p = port
        if (p == null) {
            pendingCode = code
            set(Phase.SENDING, "Looking for the pairing service…")
            notifySent()
            main.removeCallbacks(NO_PORT)
            main.postDelayed(NO_PORT, PORT_WAIT_MS)
            return
        }
        send(p, code)
    }

    private fun send(p: Int, code: String) {
        pendingCode = null
        main.removeCallbacks(NO_PORT)
        main.removeCallbacks(GIVE_UP)
        stopDiscovery()
        set(Phase.SENDING, "Sending the code to the cluster…", p)
        notifySent()
        Signal.pair(p, code) { error ->
            main.post {
                if (state.phase != Phase.SENDING) return@post
                if (error != null) return@post fail("The code didn't reach the cluster: $error.")
                Log.i(TAG, "pairing: sent the code for port $p to the cluster")
                set(Phase.SENT, "Code sent at ${time()}; the cluster pairs and starts the shell server.", p)
                notifySent()
                main.removeCallbacks(NO_RESULT)
                main.postDelayed(NO_RESULT, RESULT_MS)
            }
        }
    }

    // --- The pairing service ---

    private fun startDiscovery() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}

            override fun onDiscoveryStopped(type: String) {}

            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.w(TAG, "pairing: mDNS discovery failed: $error")
                main.post { if (discovery === this) discovery = null }
            }

            override fun onStopDiscoveryFailed(type: String, error: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (discovery !== this) return@post
                    toResolve.add(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (discovery === this && info.serviceName == service) lost() }
            }
        }
        discovery = listener
        try {
            app.getSystemService(NsdManager::class.java).discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "pairing: mDNS discovery", e)
            discovery = null
        }
    }

    private fun stopDiscovery() {
        toResolve.clear()
        port = null
        service = null
        val listener = discovery ?: return
        discovery = null
        try {
            app.getSystemService(NsdManager::class.java).stopServiceDiscovery(listener)
        } catch (e: RuntimeException) {
            // Already stopped.
        }
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
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        if (discovery != null && ShellPairing.portOk(i.port) && Signal.isOwn(i)) found(i.serviceName, i.port)
                        resolveNext()
                    }
                }
            },
        )
    }

    private fun found(name: String, p: Int) {
        if (p == port) return
        Log.i(TAG, "pairing: the pairing service listens on port $p")
        port = p
        service = name
        val code = pendingCode
        if (code != null) return send(p, code)
        if (state.phase == Phase.WAITING || state.phase == Phase.FAILED) {
            set(Phase.WAITING, "The pairing dialog is open: type its six-digit code into Prism's notification.", p)
            notifyWaiting(alert = true) // over the dialog, as a heads-up
        }
    }

    /** The dialog closed. */
    private fun lost() {
        port = null
        service = null
        if (state.phase == Phase.WAITING) {
            set(Phase.WAITING, waitingText())
            notifyWaiting(alert = false)
        }
    }

    // --- State and notifications ---

    private fun set(phase: Phase, text: String, p: Int? = port) {
        state = State(phase, text, p)
        Shell.changed()
    }

    private fun fail(text: String, notify: Boolean = true) {
        stop(Phase.FAILED, text)
        if (!notify) return
        val n = builder()
            .setContentTitle("Pairing with the cluster didn't finish")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(false))
            .addAction(Notification.Action.Builder(null, "Try again", openScreen(true)).build())
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
    }

    private fun stop(phase: Phase, text: String) {
        main.removeCallbacks(GIVE_UP)
        main.removeCallbacks(NO_RESULT)
        main.removeCallbacks(NO_PORT)
        pendingCode = null
        if (::app.isInitialized) {
            stopDiscovery()
            app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        }
        set(phase, text, null)
    }

    private fun waitingText() =
        "In Wireless debugging, tap “Pair device with pairing code”, then type the code into Prism's notification."

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Pairing with the cluster", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "The pairing code for the cluster's adb key, typed while Settings shows it"
        channel.setSound(null, null)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun builder(): Notification.Builder =
        Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_prism)
            .setColor(0xFF4553C4.toInt())
            .setCategory(Notification.CATEGORY_STATUS)
            .setLocalOnly(true)

    private fun notifyWaiting(alert: Boolean) {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Six-digit pairing code").build()
        val reply = PendingIntent.getBroadcast(
            app, 1, Intent(app, Receiver::class.java).setAction(ACTION_CODE),
            // RemoteInput fills in the Intent: it must be mutable (explicit, so only Prism gets it).
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val p = port
        val n = builder()
            .setContentTitle(if (p != null) "Type the pairing code" else "Pair with the cluster")
            .setContentText(state.text)
            .setStyle(Notification.BigTextStyle().bigText(state.text))
            .setContentIntent(openScreen(false))
            .addAction(
                Notification.Action.Builder(null, "Enter code", reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            )
            .addAction(Notification.Action.Builder(null, "Cancel", cancelIntent()).build())
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
    }

    /** Sending or sent: no reply field any more (this also ends the reply's spinner). */
    private fun notifySent() {
        val n = builder()
            .setContentTitle("Pairing with the cluster")
            .setContentText(state.text)
            .setStyle(Notification.BigTextStyle().bigText(state.text))
            .setContentIntent(openScreen(false))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancelIntent()).build())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 2, Intent(app, Receiver::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openScreen(pair: Boolean): PendingIntent {
        val i = Intent(app, ShellActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (pair) i.putExtra(EXTRA_PAIR, true)
        return PendingIntent.getActivity(app, if (pair) 4 else 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun time(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

    /** The notification's reply and Cancel. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CODE -> {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)
                    main.post { typed(context, text) }
                }
                ACTION_CANCEL -> main.post {
                    if (!::app.isInitialized) app = context.applicationContext
                    cancel()
                }
            }
        }
    }
}
