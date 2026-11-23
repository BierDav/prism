// The phone's side of Prism's API (docs/prism-api.md): the calls, their JSON
// and their errors are shared/'s PrismClient, answered by this phone's own
// replica of Prism's Rocky database (PrismReplica, rocky-prism-ffi) instead
// of a server. Reads come from the replica; an action is written there for
// the worker leading the cluster, which runs it and syncs back the outcome.
//
// The replica is enrolled with the settings rocky_provisioning (this
// phone's `prism-device-v1:` provisioning, minted by fleet with
// `prism-worker provision david-pixel`) and rocky_peers (the workers'
// contacts, `prism-worker peer`, one per line); prism-phone-enroll writes
// both. It syncs over wss://prism.quickme.at/rocky/<pod> on its own while it
// is open; ntfy's push (PrismPush) wakes the app.
//
// It is open only while something wants it, for battery life: while the
// screen is on, while the phone charges, and for a while after a push or a
// call (hold). A minute after nothing wants it any more it closes, and with
// it Rocky's connections to the workers; the next call opens it again and it
// catches up. Always open, its sync kept the CPU from sleeping (on mobile
// data it reconnected every half minute) and took most of the battery.
//
// No token and no sign-in: the replica's provisioning is this phone's
// identity, and the workers check approval signatures against the device
// key in their config (devices.david-pixel.key).
//
// In sample mode (Sample.on) the same client answers from Sample instead.

package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismError
import at.quickme.prism.shared.PrismReplica
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object PrismApi {
    private const val TAG = "PrismApi"
    private val lock = Mutex()

    /** How long a call keeps the replica open after it (and a push: see [hold]). */
    const val CALL_HOLD_MS = 60_000L

    /** How often an open replica checks whether anything still wants it. */
    private const val CHECK_MS = 30_000L

    /** The replica open now, and the settings it was opened with. */
    @Volatile
    private var opened: Pair<String, PrismReplica>? = null

    /** The open replica, for [changes]: null while closed. */
    private val current = MutableStateFlow<PrismReplica?>(null)

    /** Until when (SystemClock.elapsedRealtime) a hold keeps it open. */
    @Volatile
    private var heldUntil = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closer: Job? = null

    /** How often it was opened and closed since the process started (the metrics). */
    @Volatile var opens = 0L
        private set
    @Volatile var closes = 0L
        private set

    /** Whether the replica is open now (the metrics). */
    fun isOpen(): Boolean = opened != null

    /** Keeps the replica open (and syncing) for at least `ms` from now. */
    @JvmStatic
    fun hold(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        if (until > heldUntil) heldUntil = until
    }

    /** Opens the replica now and holds it `ms` (a push arrived, the screen came on). */
    @JvmStatic
    fun warm(c: Context, ms: Long = CALL_HOLD_MS) {
        if (!enrolled(c) || Sample.on(c)) return
        hold(ms)
        val app = c.applicationContext
        scope.launch {
            try {
                replica(app)
            } catch (e: Exception) {
                Log.w(TAG, "opening the replica: " + e.message)
            }
        }
    }

    /** Whether something wants the replica open: the screen is on, it charges, or a hold. */
    @JvmStatic
    fun wanted(c: Context): Boolean {
        if (SystemClock.elapsedRealtime() < heldUntil) return true
        if (c.getSystemService(PowerManager::class.java).isInteractive) return true
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** While it is open: closes it once nothing has wanted it for a check. */
    private fun watch(app: Context) {
        if (closer?.isActive == true) return
        closer = scope.launch {
            while (opened != null) {
                delay(CHECK_MS)
                if (!wanted(app)) {
                    close("nothing wants it")
                    return@launch
                }
            }
        }
    }

    /** Closes the replica and its connections; the next call opens it again. */
    private suspend fun close(why: String) {
        lock.withLock {
            val (_, r) = opened ?: return
            opened = null
            // The change streams end first, so nothing holds the replica.
            current.value = null
            delay(200)
            try {
                withContext(Dispatchers.IO) { r.close() }
            } catch (e: Exception) {
                Log.w(TAG, "closing the replica: " + e.message)
            }
            closes++
            Log.i(TAG, "replica closed: $why")
        }
    }

    @Volatile
    private var failure: String? = null

    /** The Prism API on this phone's replica, opened (again) when its settings changed. */
    private suspend fun client(app: Context): PrismClient {
        if (Sample.on(app)) return Sample.client
        val replica = replica(app) ?: throw PrismError(
            401, "unauthorized",
            failure ?: "This phone has no Rocky provisioning yet (rocky_provisioning). Enroll it with prism-phone-enroll.",
        )
        return replica.client(Settings.deviceName(app), prismUrl = Settings.prismUrl(app))
    }

    /**
     * This phone's replica, or null while it is not provisioned or does not
     * open (the reason is in [status]). Shared by the screens, PushService and
     * the jobs; open while something wants it ([wanted]), opened again on use.
     */
    suspend fun replica(c: Context): PrismReplica? {
        val app = c.applicationContext
        val provisioning = Settings.rockyProvisioning(app)
        if (provisioning.isEmpty()) return null
        val peers = PrismReplica.peers(Settings.rockyPeers(app))
        val key = provisioning + "\n" + peers.joinToString("\n")
        opened?.takeIf { it.first == key }?.let { return it.second }
        return lock.withLock {
            opened?.takeIf { it.first == key }?.let { return@withLock it.second }
            opened?.let { (_, old) ->
                opened = null
                current.value = null
                try {
                    old.close()
                } catch (e: Exception) {
                    Log.w(TAG, "closing the replica: " + e.message)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    PrismReplica.openProvisioned(directory(app).path, provisioning, peers)
                }.also {
                    opened = key to it
                    current.value = it
                    opens++
                    failure = null
                    Log.i(TAG, "replica opened")
                    watch(app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "The replica did not open: ${e.message}"
                Log.w(TAG, failure!!)
                null
            }
        }
    }

    /** Where the replica's database lives: no backup, no device transfer. */
    private fun directory(c: Context): File = File(c.noBackupFilesDir, "rocky")

    /**
     * The tables each change of the replica touched (a sync or a write here),
     * while it is open; it doesn't open the replica, and follows it when it
     * closes and opens again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changes(tables: List<String>? = null): Flow<List<String>> =
        current.flatMapLatest { it?.changes(tables) ?: emptyFlow() }

    /** For the Phone tab: whether the replica is open, or why not. */
    @JvmStatic
    fun status(c: Context): String = when {
        Sample.on(c) -> "sample mode"
        Settings.rockyProvisioning(c).isEmpty() -> "not provisioned (rocky_provisioning)"
        opened != null -> "open, syncing with ${PrismReplica.peers(Settings.rockyPeers(c)).size} worker(s)"
        failure == null -> "closed while nothing needs it (screen off, not charging); opens again on use"
        else -> failure ?: "not open yet"
    }

    /**
     * Runs [block] against the replica, off the main thread. It throws
     * [PrismError], whose message is for the owner.
     */
    suspend fun <T> call(c: Context, block: suspend PrismClient.() -> T): T {
        val app = c.applicationContext
        hold(CALL_HOLD_MS)
        return withContext(Dispatchers.IO) { client(app).block() }
    }

    /** Whether the API can answer: sample mode, or a provisioned replica. */
    @JvmStatic
    fun enrolled(c: Context): Boolean = Sample.on(c) || Settings.rockyProvisioning(c).isNotEmpty()
}
