// The service that keeps Prism's push stream open (PrismPush: the ntfy topic
// prism-events on the relay, read in a coroutine scope that lives as long as
// the service), the way the ntfy app keeps its instant
// delivery, so alerts and agent questions reach the phone within seconds.
// While NetBird's tunnel is up, Android's binding of the VPN service keeps
// Prism's process running, so the service needs no notification of its own;
// while it is down, it is a foreground service with one ("Connected to
// Prism"), so pushes keep arriving.

package at.quickme.prism

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import at.quickme.prism.netbird.Netbird
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PushService : Service() {
    companion object {
        private const val CHANNEL = "relay"
        private const val ONGOING_ID = 1

        @JvmStatic
        fun start(c: Context) {
            c.startForegroundService(Intent(c, PushService::class.java))
        }
    }

    @Volatile private var running = false
    private var push: CoroutineScope? = null
    private var heartbeat: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settingsChanged: BroadcastReceiver? = null
    private var power: BroadcastReceiver? = null
    private var screen: BroadcastReceiver? = null

    /** The shell server attached (again): apply the background policy now (BackgroundPolicy), and the hotspot's mode. */
    private val shellChanged = Runnable {
        BackgroundPolicy.applyAsync(this)
        Hotspot.apply(this)
        Calls.watch(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_MIN)
        channel.description = "Keeps the connection that brings Prism's alerts to this phone"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // Started as a foreground service, it must call startForeground; with
        // the tunnel up it lets go of it at once.
        promote()
        if (Netbird.tunnelUp()) stopForeground(STOP_FOREGROUND_REMOVE)
        if (!running) {
            running = true
            Netbird.listen(tunnelChanged)
            push = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { PrismPush.run(this, it) }
            // The phone is awake anyway: a battery reading every 5 minutes.
            heartbeat = Thread({
                while (running) {
                    try {
                        Thread.sleep(60_000)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    Metrics.sampleIfDue(this)
                }
            }, "metrics-heartbeat").apply { start() }
            // A new network (Wi-Fi to mobile, the VPN coming up): reconnect at once.
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    PrismPush.reconnect()
                    // On or off Wi-Fi: the hotspot's BLE scan follows (Hotspot.kt).
                    Hotspot.apply(this@PushService)
                }
            }
            networkCallback = callback
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            // New settings (provisioned, or from an MDM): reconnect with
            // them. Android sends these only to receivers registered at run time.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = PrismPush.reconnect()
            }
            settingsChanged = receiver
            registerReceiver(receiver, Settings.changedFilter(), Context.RECEIVER_NOT_EXPORTED)
            // Plugged in or unplugged: the apps that run in the background only
            // while charging are let go or restricted (BackgroundPolicy).
            val powerReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val plugged = intent.action == Intent.ACTION_POWER_CONNECTED
                    BackgroundPolicy.applyAsync(context, plugged)
                    if (plugged) PrismApi.warm(context)
                }
            }
            power = powerReceiver
            registerReceiver(
                powerReceiver,
                IntentFilter(Intent.ACTION_POWER_CONNECTED).apply { addAction(Intent.ACTION_POWER_DISCONNECTED) },
                Context.RECEIVER_NOT_EXPORTED,
            )
            // The screen came on: the replica opens and catches up, so Prism is
            // current when opened (PrismApi closes it again once the screen is off).
            val screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = PrismApi.warm(context)
            }
            screen = screenReceiver
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            at.quickme.prism.shell.Shell.listen(shellChanged)
            BackgroundPolicy.applyAsync(this)
            Hotspot.apply(this)
            // Calls on the Mac (docs/calls.md).
            Calls.watch(this)
        }
        return START_STICKY
    }

    /** Foreground with the notification only while NetBird's tunnel is down. */
    private val tunnelChanged = Runnable {
        if (!running) return@Runnable
        if (Netbird.tunnelUp()) stopForeground(STOP_FOREGROUND_REMOVE) else promote()
    }

    private fun promote() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ongoing = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_prism)
            .setContentTitle("Connected to Prism")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(ONGOING_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: RuntimeException) {
            // Not allowed from the background right now (a Device Owner and a
            // VPN app usually are): the service keeps running while it may.
            android.util.Log.w("PushService", "no foreground service: $e")
        }
    }

    override fun onDestroy() {
        Netbird.unlisten(tunnelChanged)
        running = false
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        settingsChanged?.let { unregisterReceiver(it) }
        power?.let { unregisterReceiver(it) }
        screen?.let { unregisterReceiver(it) }
        at.quickme.prism.shell.Shell.unlisten(shellChanged)
        heartbeat?.interrupt()
        push?.cancel()
        super.onDestroy()
    }
}
