// Prism's open alerts and machine events, grouped by category.
// Tapping an alert opens it (AlertScreen): beside the list where there is room.

package at.quickme.prism

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.quickme.prism.ui.CodeFamily
import at.quickme.prism.ui.EmptyState
import at.quickme.prism.ui.dpadStop
import at.quickme.prism.ui.listPadding
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.Severity
import at.quickme.prism.ui.SeverityBadge
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.ago
import at.quickme.prism.ui.prism
import at.quickme.prism.ui.tone
import at.quickme.prism.shared.Agent
import at.quickme.prism.shared.Alert
import at.quickme.prism.shared.PrismError
import at.quickme.prism.shared.groupAlerts
import kotlinx.coroutines.launch
import at.quickme.prism.shared.Severity as AlertLevel

/** What the Alerts tab shows: loaded from the API, kept across tab switches. */
class AlertsState {
    var alerts by mutableStateOf<List<Alert>?>(null)

    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    fun criticalCount() = alerts?.count { it.level == AlertLevel.CRITICAL } ?: 0

    /** Loads the open alerts. */
    suspend fun load(c: Context) {
        if (loading || !PrismApi.enrolled(c)) return
        loading = true
        try {
            alerts = PrismApi.call(c) { alerts("open") }
            error = null
        } catch (e: PrismError) {
            error = e.message
        } finally {
            loading = false
        }
    }
}

@Composable
fun AlertsTab(
    state: AlertsState,
    padding: PaddingValues,
    show: (String) -> Unit,
    onOpen: (Long) -> Unit,
    /** The alert open beside the list, if any. */
    selected: Long? = null,
    enrolled: Boolean = PrismApi.enrolled(LocalContext.current),
) {
    // No pull to refresh: Home reloads on resume, on every push and every 30 s.
    Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = listPadding(padding, top = 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!enrolled) {
                item { EnrollCard() }
                return@LazyColumn
            }
            state.error?.let { err -> item { ErrorCard(err) } }
            val alerts = state.alerts
            when {
                alerts == null && state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator(Modifier.size(72.dp))
                    }
                }
                alerts != null && alerts.isEmpty() -> item {
                    EmptyState(PrismIcons.Shield, "All quiet", "No open alerts on the cluster or the machines.")
                }
                alerts != null -> {
                    // Groups with a critical alert first, then in the contract's order;
                    // within a group the most severe, then the newest first.
                    val groups = groupAlerts(alerts, System.currentTimeMillis() / 1000)
                        .sortedBy { g -> if (g.alerts.any { it.level == AlertLevel.CRITICAL }) 0 else 1 }
                    for ((category, list) in groups) {
                        item(key = "h-$category") {
                            SectionHeader(category, Modifier.padding(top = 12.dp, start = 4.dp)) {
                                Text("${list.size}", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        itemsIndexed(list, key = { _, a -> "a-" + a.id }) { i, a ->
                            AlertCard(a, segmentShape(i, list.size), onClick = { onOpen(a.id) }, selected = a.id == selected)
                        }
                    }
                }
            }

        }
    }
}

/** Expressive list grouping: big corners outside a group, small ones between its items. */
fun segmentShape(index: Int, count: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

/** Shown until the phone has its Rocky provisioning (PrismApi). */
@Composable
private fun EnrollCard() {
    EmptyState(
        PrismIcons.Fingerprint,
        "Not connected to Prism",
        "This phone needs its replica of Prism's database: fleet's prism-phone-enroll sets rocky_provisioning " +
            "(prism-worker provision with this phone's name) and rocky_peers over USB.",
    )
}

/** What went wrong with the last load; the next load (push, poll, resume) clears it. */
@Composable
fun ErrorCard(error: String) {
    val tone = MaterialTheme.prism.critical
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).dpadStop(), shape = MaterialTheme.shapes.large, color = tone.container) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PrismIcons.Octagon, null, tint = tone.onContainer)
            Text(error, style = MaterialTheme.typography.bodyMedium, color = tone.onContainer, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun AlertCard(alert: Alert, shape: Shape, onClick: (() -> Unit)?, selected: Boolean = false) {
    val severity = Severity.of(alert.level)
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SeverityBadge(severity, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#" + alert.id, style = MaterialTheme.typography.labelLarge.copy(fontFamily = CodeFamily),
                        color = severity.tone().color)
                    Text(alert.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (alert.summary.isNotEmpty()) Text(alert.summary, style = MaterialTheme.typography.bodyMedium)
                if (alert.brief.isNotEmpty()) {
                    Text(alert.brief, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (alert.host.isNotEmpty()) HostBadge(alert.host)
                    val now = System.currentTimeMillis() / 1000
                    val meta = listOfNotNull(
                        if (alert.machineEvent) "machine check" else null,
                        ago(alert.since(now), now).takeIf { it.isNotEmpty() },
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                alert.agents.firstOrNull()?.let { AgentChip(it) }
            }
        }
    }
    val color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick != null) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, color = color) { content() }
    } else {
        Surface(Modifier.fillMaxWidth().dpadStop(shape), shape = shape, color = color) { content() }
    }
}

/** The newest agent on an alert, in one line. */
@Composable
private fun AgentChip(agent: Agent) {
    val asks = agent.status == "waiting"
    val label = if (agent.runStatus == "interrupted" && agent.status == "failed") "stopped" else agent.status
    val tone = if (asks) MaterialTheme.prism.warning else null
    Surface(
        shape = RoundedCornerShape(50),
        color = tone?.container ?: MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(PrismIcons.Sparkle, null, Modifier.size(14.dp), tint = tone?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                if (asks) "Agent asks you" else "Agent $label",
                style = MaterialTheme.typography.labelMedium,
                color = tone?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** A card for one agent; tapping it opens the chat. */
@Composable
fun AgentCard(agent: Agent, shape: Shape, stopping: Boolean, onStop: () -> Unit) {
    val context = LocalContext.current
    val chat = T3Activity.intent(context, agent)
    val state = agent.status
    val asks = state == "waiting"
    val stopped = state == "failed" && agent.runStatus == "interrupted"
    val tone = when {
        asks -> MaterialTheme.prism.warning
        state == "failed" && !stopped -> MaterialTheme.prism.critical
        state == "finished" -> MaterialTheme.prism.success
        else -> null
    }
    val running = state == "running"
    Surface(
        onClick = { chat?.let { context.startActivity(it) } },
        enabled = chat != null,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(16.dp).animateContentSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (running) {
                    LoadingIndicator(Modifier.size(44.dp))
                } else {
                    ShapedIcon(PrismIcons.Sparkle, MaterialShapes.Cookie4Sided, tone?.container ?: MaterialTheme.colorScheme.secondaryContainer,
                        tone?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer, size = 44.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(agent.displayTitle, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val host = agent.t3?.host?.takeIf { it.isNotEmpty() } ?: agent.target
                    if (host.isNotEmpty()) HostBadge(host)
                    Text(
                        (if (asks) "waits for your answer" else if (stopped) "stopped" else state) + " · " + ago(agent.lastChange),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (asks) FontWeight.Bold else null,
                        color = tone?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (agent.lastMessage.isNotEmpty()) {
                    Text(agent.lastMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (asks) {
                    // Answered in the inbox, which opens at this agent's question.
                    FilledTonalButton(
                        onClick = { context.startActivity(MainActivity.inboxIntent(context, InboxItem.threadKey(agent.t3?.threadId?.ifEmpty { null } ?: agent.id))) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(PrismIcons.Chat, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Answer")
                    }
                } else if (chat != null) {
                    FilledTonalButton(onClick = { context.startActivity(chat) }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(top = 4.dp)) {
                        Icon(PrismIcons.Chat, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Open chat")
                    }
                }
                if (running || asks) {
                    OutlinedButton(onClick = onStop, enabled = !stopping) {
                        Text(if (stopping) "Stopping…" else "Stop agent")
                    }
                }
            }
        }
    }
}
