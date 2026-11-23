// Approvals on the phone: the shared queue (shared/…/Approvals.kt) with its
// history in the app's preferences, a notification for each pending approval
// that brings up the approval screen (ApproveActivity), also over the lock
// screen, and the answers: Approve signs with the device key after a
// fingerprint, Deny just says no. Nothing feeds the queue yet: an integration
// offers its approvals with its own ApprovalChannel (queue(c).offer).

package at.quickme.prism

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import at.quickme.prism.shared.Approval
import at.quickme.prism.shared.ApprovalChannel
import at.quickme.prism.shared.ApprovalQueue
import at.quickme.prism.shared.Approvals
import at.quickme.prism.shared.Outcome
import at.quickme.prism.shared.ThreadLink
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object Approver {
    const val CHANNEL = "approvals"
    private const val PREFS = "approvals"
    private const val HISTORY = "history"

    private val scope = MainScope()
    private var queue: ApprovalQueue? = null
    private val remoteIDs = mutableSetOf<String>()

    /** The server remains authoritative; a failed answer is offered again on reload. */
    private val loadLock = Mutex()

    suspend fun load(c: Context) = withContext(Dispatchers.Main) {
        loadLock.withLock { loadLocked(c.applicationContext) }
    }

    private suspend fun loadLocked(c: Context) {
        if (!PrismApi.enrolled(c) || Sample.on(c)) return
        val fresh = try { PrismApi.call(c) { approvals() } } catch (_: at.quickme.prism.shared.PrismError) { return }
        val q = queue(c)
        val ids = fresh.map { it.id }.toSet()
        for (id in remoteIDs - ids) q.withdraw(id)
        remoteIDs.clear()
        remoteIDs.addAll(ids)
        for (approval in fresh) q.offer(approval, object : ApprovalChannel {
            override suspend fun answer(approval: Approval, outcome: Outcome, signature: String?) {
                if (outcome == Outcome.EXPIRED || outcome == Outcome.WITHDRAWN) return
                val answer = if (outcome == Outcome.APPROVED) approval else {
                    val decision = approval.options.firstOrNull { it.decision == "decline" || it.decision == "cancel" }?.decision ?: return
                    Approvals.choosing(approval, decision)
                }
                try {
                    PrismApi.call(c) { answerApproval(answer, signature) }
                } finally {
                    scope.launch { load(c) }
                }
            }
        })
    }

    /** The app's one queue, with the stored history; notifications follow it from the first call on. */
    @JvmStatic
    @Synchronized
    fun queue(c: Context): ApprovalQueue {
        queue?.let { return it }
        val app = c.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val q = ApprovalQueue(Approvals.decodeHistory(prefs.getString(HISTORY, "") ?: "")) { history ->
            prefs.edit().putString(HISTORY, Approvals.encodeHistory(history)).apply()
        }
        queue = q
        follow(app, q)
        return q
    }

    /** One notification per pending approval, gone once it is decided; expiry every two seconds while any waits. */
    private fun follow(c: Context, q: ApprovalQueue) {
        createChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        val shown = HashSet<String>()
        scope.launch {
            q.pending.collect { list ->
                val ids = list.map { it.id }.toSet()
                for (id in shown - ids) nm.cancel(notificationId(id))
                for (a in list) if (a.id !in shown) notify(c, a)
                shown.clear()
                shown.addAll(ids)
            }
        }
        scope.launch {
            q.pending.collectLatest { list ->
                while (list.isNotEmpty()) {
                    q.expire()
                    delay(2000)
                }
            }
        }
    }

    private fun notificationId(id: String): Int = ("approval:$id").hashCode()

    @JvmStatic
    fun createChannel(c: Context) {
        val channel = NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Something waits for your approval and fingerprint"
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Whether the approval screen or the main screen (its inbox) is in front: then notifications don't pop up over it. */
    @Volatile
    private var inFront = false

    /**
     * A screen that shows approvals itself came to the front or left it. In
     * front, their notifications go quiet (no heads-up, no full-screen
     * intent) and stay in the shade.
     */
    @JvmStatic
    fun setInFront(c: Context, front: Boolean) {
        inFront = front
        if (!front) return
        val nm = c.getSystemService(NotificationManager::class.java)
        for (a in queue(c).pending.value) {
            nm.cancel(notificationId(a.id))
            notify(c, a)
        }
    }

    private fun notify(c: Context, a: Approval) {
        val open = Intent(c, ApproveActivity::class.java)
            .putExtra(ApproveActivity.EXTRA_ID, a.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            c, notificationId(a.id), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_prism)
            .setColor(0xFF4553C4.toInt())
            .setContentTitle(shorten(a.title, 80))
            .setContentText(shorten(listOf(a.source, a.summary).filter { it.isNotBlank() }.joinToString(" · "), 120))
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
        if (a.expiresAt != 0L) builder.setTimeoutAfter(maxOf(1000, a.expiresAt * 1000 - System.currentTimeMillis()))
        if (inFront) {
            // Silent, as NotificationCompat.setSilent does it: in a group that
            // only alerts for its summary (there is none).
            builder.setGroup("approvals-on-screen").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        } else {
            builder.setFullScreenIntent(pi, true)
        }
        c.getSystemService(NotificationManager::class.java).notify(notificationId(a.id), builder.build())
    }

    fun deny(c: Context, a: Approval) {
        queue(c).decide(a.id, Outcome.DENIED)
    }

    /** T3 Code's web app opens a thread at /<environment>/<thread> (its router decodes the path segments). */
    fun openThread(c: Context, t: ThreadLink) {
        val url = Settings.t3Url(c)
        if (url.isEmpty()) return
        val uri = Uri.parse(url).buildUpon().appendPath(t.environmentId).appendPath(t.threadId).build()
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No browser.
        }
    }

    /**
     * Asks for the fingerprint, signs the approval with the device key and
     * decides it. [onNotice] gets what went wrong; [onDone] runs once it is decided.
     */
    fun approve(a: ComponentActivity, approval: Approval, onNotice: (String) -> Unit, onDone: () -> Unit) {
        queue(a).choose(approval.id, approval.decision) ?: return
        val can = a.getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            onNotice("No fingerprint is available ($can).")
            return
        }
        val key: DeviceKey
        val signature: java.security.Signature
        try {
            key = DeviceKey.loadOrCreate(a)
            signature = key.signature()
        } catch (e: Exception) {
            onNotice("The signing key is not usable: ${e.message}")
            return
        }
        val prompt = BiometricPrompt.Builder(a)
            .setTitle(shorten(approval.title, 60))
            .setSubtitle(shorten(approval.source, 80))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", a.mainExecutor) { _, _ -> }
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(signature), CancellationSignal(), a.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.signature ?: signature
                    try {
                        unlocked.update(Approvals.signedData(approval))
                        val armored = Approvals.signature(key.point, unlocked.sign())
                        if (!queue(a).decide(approval.id, Outcome.APPROVED, armored)) {
                            onNotice("This approval is no longer pending.")
                        } else {
                            onDone()
                        }
                    } catch (e: Exception) {
                        onNotice("Signing failed: ${e.message}")
                    }
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onNotice(message.toString())
                }
            },
        )
    }

    /** The debug build's sample approval, answered into the log (the Approvals tab's button). */
    fun raiseSample(c: Context) {
        queue(c).offer(Sample.approval(), object : ApprovalChannel {
            override suspend fun answer(approval: Approval, outcome: Outcome, signature: String?) {
                android.util.Log.i("PrismSample", "sample approval ${approval.id}: $outcome\n${signature.orEmpty()}")
            }
        })
    }

    @JvmStatic
    fun shorten(text: String, limit: Int): String = if (text.length <= limit) text else text.substring(0, limit - 1) + "…"
}
