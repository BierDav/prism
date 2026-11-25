// One alert: what fires and where, the machine check's command and output,
// the agents on it, and the owner's actions (docs/prism-api.md, "Actions on an
// alert"): Fix (an agent investigates and fixes) and Instruct (an agent
// follows your instruction). An alert ends only when its condition does.
// The main screen shows it (Route.AlertDetail): beside the Alerts list where
// the window is wide enough, on its own otherwise.

package at.quickme.prism

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import at.quickme.prism.ui.CodeBlock
import at.quickme.prism.ui.CodeFamily
import at.quickme.prism.ui.CodeStyle
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.Severity
import at.quickme.prism.ui.SeverityBadge
import at.quickme.prism.ui.ago
import at.quickme.prism.ui.dpadStop
import at.quickme.prism.ui.readableGutter
import at.quickme.prism.ui.shortWindow
import at.quickme.prism.ui.tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import at.quickme.prism.shared.Agent
import at.quickme.prism.shared.Alert
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismError

internal data class Action(val what: String, val label: String)

@Composable
internal fun AlertScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var alert by remember { mutableStateOf<Alert?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(setOf<String>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            error = try {
                alert = PrismApi.call(context) { alert(id) }
                null
            } catch (e: PrismError) {
                e.message
            }
        }
    }
    // On resume, on every push, and every 30 s while on screen (10 s while it fails).
    LifecycleResumeEffect(id) {
        val reload = Runnable { load() }
        PrismPush.listen(reload)
        load()
        val poll = scope.launch {
            while (true) {
                delay(if (error != null) 10_000 else 30_000)
                load()
            }
        }
        onPauseOrDispose {
            PrismPush.unlisten(reload)
            poll.cancel()
        }
    }

    fun act(a: Action) {
        if (busy != null) return
        busy = a.what
        val requestId = PrismClient.requestId()
        scope.launch {
            val message = try {
                val result = when (a.what) {
                    "fix" -> {
                        val agent = PrismApi.call(context) { fix(id, null, requestId) }
                        alert = alert?.let { it.copy(agents = listOf(agent) + it.agents.filterNot { a -> a.id == agent.id }) }
                        "Agent started."
                    }
                    else -> null
                }
                PrismPush.changed()
                result
            } catch (e: PrismError) {
                e.message
            } finally {
                busy = null
            }
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    fun stop(agentId: String) {
        if (agentId in stopping) return
        stopping += agentId
        scope.launch {
            val message = try {
                PrismApi.call(context) { stopAgent(agentId, PrismClient.requestId()) }
                PrismPush.changed()
                "Stop requested."
            } catch (e: PrismError) {
                e.message
            } finally {
                stopping -= agentId
            }
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    AlertContent(id, alert, error, busy, snackbar, onBack,
        onAction = ::act,
        stopping = stopping, onStop = ::stop)
}

/**
 * Alert [id] as it shows [alert] (null while loading), the last load's
 * [error] and the action [busy] (its path's last part).
 */
@Composable
internal fun AlertContent(
    id: Long,
    alert: Alert?,
    error: String?,
    busy: String?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (Action) -> Unit,
    stopping: Set<String> = emptySet(),
    onStop: (String) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val a = alert
    val severity = Severity.of(a?.level)
    val short = shortWindow()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val title: @Composable () -> Unit = { Text(a?.title ?: "#$id", maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis) }
            val subtitle: @Composable () -> Unit = {
                Text("#$id" + (a?.let { " · " + it.category } ?: ""),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = CodeFamily), color = severity.tone().color)
            }
            val back: @Composable () -> Unit = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
            val colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            if (short) {
                TopAppBar(title = title, subtitle = subtitle, navigationIcon = back, colors = colors)
            } else {
                LargeFlexibleTopAppBar(title = title, subtitle = subtitle, navigationIcon = back,
                    scrollBehavior = scrollBehavior, colors = colors)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (a == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (error != null) ErrorCard(error) else ContainedLoadingIndicator(Modifier.size(72.dp))
            }
            return@Scaffold
        }
        val open = a.isOpen
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = readableGutter(maxWidth)
        LazyColumn(
            // Below the top bar, not under it: D-pad focus moves down from the bar's buttons
            // only into what lies wholly below them (the list counts as one block).
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp + gutter, end = 16.dp + gutter, top = 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { item { ErrorCard(it) } }
            item { Hero(a, severity) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(PrismIcons.Sparkle, "Fix", primary = true, enabled = open && busy == null, busy = busy == "fix") {
                        onAction(Action("fix", "Fix"))
                    }
                }
            }
            item { Instruct(id) }
            a.description.takeIf { it.isNotEmpty() }?.let { d ->
                item {
                    Text(d, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.dpadStop(MaterialTheme.shapes.small).padding(horizontal = 4.dp))
                }
            }
            a.command.takeIf { it.isNotEmpty() }?.let { c ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Command", Modifier.padding(start = 4.dp))
                        CodeBlock(c, prompt = "$")
                    }
                }
            }
            a.output.takeIf { it.isNotEmpty() }?.let { o ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Output", Modifier.padding(start = 4.dp))
                        CodeBlock(o, style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight), wrap = false)
                    }
                }
            }
            val agents = a.agents
            if (agents.isNotEmpty()) {
                item { SectionHeader("Agents", Modifier.padding(start = 4.dp)) }
                itemsIndexed(agents, key = { _, ag -> ag.id }) { i, ag ->
                    AgentCard(ag, segmentShape(i, agents.size), stopping = ag.id in stopping, onStop = { onStop(ag.id) })
                }
            }
        }
        }
    }
}

@Composable
private fun Hero(a: Alert, severity: Severity) {
    val tone = severity.tone()
    Surface(Modifier.fillMaxWidth().dpadStop(MaterialTheme.shapes.extraLarge), shape = MaterialTheme.shapes.extraLarge, color = tone.container) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SeverityBadge(severity, size = 56.dp, strong = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(severity.name, style = MaterialTheme.typography.titleLargeEmphasized, color = tone.onContainer)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.host.takeIf { it.isNotEmpty() }?.let { HostBadge(it) }
                        val now = System.currentTimeMillis() / 1000
                        Text(
                            listOfNotNull(
                                if (a.machineEvent) "machine check" else "cluster alert",
                                ago(a.since(now), now).takeIf { it.isNotEmpty() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = tone.onContainer.copy(alpha = 0.8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            a.summary.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, color = tone.onContainer)
            }
            a.brief.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = tone.onContainer.copy(alpha = 0.85f))
            }
            val state = a.state
            if (!a.isOpen) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                    Text("This alert is $state", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    icon: ImageVector,
    label: String,
    primary: Boolean = false,
    outlined: Boolean = false,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val mod = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight)
    val content: @Composable () -> Unit = {
        if (busy) {
            LoadingIndicator(Modifier.size(28.dp))
        } else {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
    when {
        primary -> Button(onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = mod) { content() }
        outlined -> OutlinedButton(onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = mod) { content() }
        else -> FilledTonalButton(onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = mod) { content() }
    }
}

/** A free-text instruction for an agent on this alert, as on the Mac. */
@Composable
private fun Instruct(id: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Started?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Tell an agent what to do…") },
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f),
            maxLines = 4,
        )
        FilledIconButton(
            onClick = {
                sending = true
                val instruction = text.trim()
                scope.launch {
                    started = try {
                        Started(PrismApi.call(context) { fix(id, instruction, PrismClient.requestId()) }, null).also {
                            text = ""
                            PrismPush.changed()
                        }
                    } catch (e: PrismError) {
                        Started(null, e.message)
                    }
                    sending = false
                }
            },
            enabled = text.isNotBlank() && !sending,
            modifier = Modifier.size(56.dp),
        ) {
            if (sending) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.AutoMirrored.Rounded.Send, "Send to an agent")
        }
    }
    started?.let { StartedDialog(it, onDismiss = { started = null }) }
}

private class Started(val agent: Agent?, val error: String?)

/** What came of starting an agent, with the way into its chat. */
@Composable
private fun StartedDialog(started: Started, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val agent = started.agent
    val error = started.error
    val chat = agent?.let { T3Activity.intent(context, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (error == null) PrismIcons.Sparkle else PrismIcons.Octagon, null) },
        title = { Text(if (error == null) "Agent started" else "Not started") },
        text = { Text(error ?: agent?.displayTitle.orEmpty()) },
        confirmButton = {
            if (error == null && chat != null) {
                TextButton(onClick = {
                    onDismiss()
                    context.startActivity(chat)
                }) { Text("Open chat") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (error == null && chat != null) {
            { TextButton(onClick = onDismiss) { Text("Close") } }
        } else null,
    )
}
