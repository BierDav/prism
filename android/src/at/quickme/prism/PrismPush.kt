// Prism's push: the ntfy topic prism-events on the relay (docs/prism-api.md,
// "Push"), read with the relay token this phone has. The stream itself
// (reconnects, catch-up, parsing) is shared/'s RelayStream; this keeps where
// it resumes and the keys it has handled in the "prism-push" preferences, and
// turns its messages into Android notifications. PushService keeps it
// running. Push only notifies and wakes the app; the phone's replica
// (PrismApi) is the source of truth and syncs on its own, and screens also
// reload when it changes, so a lost message is repaired by the next sync.

package at.quickme.prism

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import at.quickme.prism.shared.PushMessage
import at.quickme.prism.shared.RelayStream
import at.quickme.prism.shared.SeenKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
object PrismPush {
    /** The replica's tables the screens show (rocky-prism's schema). */
    private val REFRESH_TABLES = listOf(
        "alerts", "alert_actions", "agents", "agent_activity", "questions", "approvals", "machines", "projects",
        "profiles", "profile_entries", "actions", "policy", "settings", "call_requests",
    )

    const val CHANNEL_CRITICAL = "alerts-critical"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_AGENTS = "agents"
    const val CHANNEL_QUIET = "prism-quiet"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>()

    @Volatile
    private var stream: RelayStream? = null

    /** The stream's state for the Phone tab: "connected", "reconnecting: …", "not started". */
    @JvmStatic
    fun statusText(): String = stream?.status?.value?.text ?: "not started"

    @JvmStatic
    fun connected(): Boolean = stream?.status?.value?.connected == true

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

    /** Something changed on the server: screens reload. */
    @JvmStatic
    fun changed() {
        val copy = synchronized(this) { ArrayList(listeners) }
        for (r in copy) main.post(r)
    }

    @JvmStatic
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val critical = NotificationChannel(CHANNEL_CRITICAL, "Critical alerts", NotificationManager.IMPORTANCE_HIGH)
        critical.description = "Critical cluster alerts and failed machine checks"
        val alerts = NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        alerts.description = "Warnings and other alerts"
        val agents = NotificationChannel(CHANNEL_AGENTS, "Agent questions", NotificationManager.IMPORTANCE_HIGH)
        agents.description = "An agent waits for your answer in T3"
        val quiet = NotificationChannel(CHANNEL_QUIET, "Agent results and notices", NotificationManager.IMPORTANCE_LOW)
        quiet.description = "Finished agent turns and things to do sometime"
        nm.createNotificationChannels(listOf(critical, alerts, agents, quiet))
    }

    /** Drops the connection to make a new one at once (new network, new settings). */
    @JvmStatic
    fun reconnect() {
        stream?.reconnect()
    }

    /** Reads the stream in [scope]; PushService runs it while the service runs and cancels the scope on stop. */
    fun run(c: Context, scope: CoroutineScope) {
        channels(c)
        val prefs = c.getSharedPreferences("prism-push", Context.MODE_PRIVATE)
        val seen = SeenKeys((prefs.getString("keys", "") ?: "").split("\n"), 300) {
            prefs.edit().putString("keys", it.joinToString("\n")).apply()
        }
        val relay = RelayStream(
            base = { Settings.relayUrl(c).takeIf { Settings.relayAllowed(it) }.orEmpty() },
            token = { Settings.relayToken(c) },
            since = prefs.getString("since", "") ?: "",
            onSince = { prefs.edit().putString("since", it).apply() },
        )
        stream = relay
        scope.launch {
            // Catch up with whatever happened while away.
            // (Approvals only while the replica is wanted anyway: a pending one
            // also comes as a message, which opens it.)
            relay.status.collect { if (it.connected) { changed(); if (PrismApi.wanted(c)) Approver.load(c) } }
        }
        scope.launch {
            // What the push announced arrives by Rocky's sync, a moment later
            // or before it: screens reload when the replica changes, too.
            PrismApi.changes(REFRESH_TABLES).debounce(300).collect { tables ->
                changed()
                if ("approvals" in tables) Approver.load(c)
                if ("settings" in tables) Updater.checkSoon(c) // published settings, maybe this phone's
                if ("call_requests" in tables) Calls.handleDialRequests(c)
            }
        }
        scope.launch {
            relay.messages.collect { m ->
                // An approval is read and signed from the replica: open it for a
                // while, so the approval syncs in. Everything else carries its
                // text, and the screens catch up when the screen comes on
                // (PrismApi), so it doesn't open the replica (tens of pushes
                // an hour while agents work).
                if (m.type.startsWith("approval.")) PrismApi.warm(c, 2 * PrismApi.CALL_HOLD_MS)
                if (seen.firstTime(m.key)) {
                    handle(c, m)
                    if (m.type == "approval.pending" || m.type == "approval.closed") Approver.load(c)
                }
            }
        }
    }

    /** One prism-events message (docs/prism-api.md, "Messages"), not seen before. */
    private fun handle(c: Context, m: PushMessage) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val tag = m.notificationTag
        val title = m.title.ifEmpty { "Prism" }
        val text = m.body
        val home = PendingIntent.getActivity(
            c, 0, MainActivity.intent(c, MainActivity.TAB_ALERTS), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        when (m.type) {
            "alert.firing" -> {
                val critical = m.severity == "critical"
                val open = MainActivity.alertIntent(c, m.alertId)
                nm.notify(
                    tag, 0,
                    builder(
                        c, if (critical) CHANNEL_CRITICAL else CHANNEL_ALERTS, title, text,
                        PendingIntent.getActivity(c, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    ).setCategory(Notification.CATEGORY_ERROR)
                        .setColor(if (critical) 0xFFBA1A1A.toInt() else 0xFF8C5000.toInt())
                        .build(),
                )
            }
            "agent.question", "agent.finished" -> {
                val question = m.type == "agent.question"
                // A question opens the inbox at it, to answer there; a result opens the chat.
                val open = if (question) {
                    MainActivity.inboxIntent(c, InboxItem.questionKey(m.questionId.ifEmpty { m.key.removePrefix("agent.question:") }))
                } else {
                    T3Activity.intent(c, m.host, m.environmentId, m.threadId)
                }
                val tap = if (open != null) {
                    PendingIntent.getActivity(c, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                } else {
                    home
                }
                nm.notify(
                    tag, 0,
                    builder(c, if (question) CHANNEL_AGENTS else CHANNEL_QUIET, title, text, tap)
                        .setCategory(if (question) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS).build(),
                )
            }
            // Ended, or answered here, in T3 or elsewhere: its notification goes.
            "alert.ended", "question.closed" -> nm.cancel(tag, 0)
            "notice" -> nm.notify(tag, 0, builder(c, CHANNEL_QUIET, title, text, home).build())
            "app.release" -> {
                // fleet published a new version of this app: fetch it now, quietly.
                if (m.packageName.ifEmpty { c.packageName } == c.packageName) Updater.checkSoon(c)
                return
            }
            "app.settings" -> {
                // New settings for this phone: apply them soon (ServerSettings).
                if (m.device == Settings.deviceName(c)) Updater.checkSoon(c)
                return
            }
            "placement.changed" -> {} // refresh open screens, don't notify
            else -> {} // agent.started and anything new: just refresh
        }
        changed()
    }

    private fun builder(c: Context, channel: String, title: String, text: String, tap: PendingIntent): Notification.Builder =
        Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_prism)
            .setColor(0xFF4553C4.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
}
