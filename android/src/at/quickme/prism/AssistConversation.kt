// The assistant overlay's conversation: once a request is sent, the agent it
// started stays above the composer, as Gemini's answer does. What the agent
// says, the steps it takes (commands, edits, tools) one line each, the
// owner's follow-ups, and its state, read live from its T3 thread through the
// Prism server (GET /v1/agents/{id}/activity). Text is untrusted Markdown,
// shown as plain text.

package at.quickme.prism

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.quickme.prism.shared.ActivityItem
import at.quickme.prism.shared.AgentActivity
import at.quickme.prism.ui.PrismIcons

/** A line of the conversation: an item from the thread, or a follow-up not yet in it. */
private sealed interface Line {
    val key: String

    data class Item(val item: ActivityItem) : Line {
        override val key get() = "item:" + item.id
    }

    data class Pending(val text: String, val index: Int) : Line {
        override val key get() = "pending:$index"
    }
}

/**
 * The agent's conversation in a card of [shape]: a header with its title and
 * state, then its items; [pending] are follow-ups sent but not yet in the
 * thread. [error] says why the activity can't be read now.
 */
@Composable
internal fun AgentConversation(
    title: String,
    activity: AgentActivity?,
    pending: List<String>,
    error: String?,
    shape: Shape,
    onOpen: (() -> Unit)?,
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Leaves this agent for a new request. */
    onNew: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val agent = activity?.agent
    val state = agent?.status ?: "running"
    val working = state == "running"
    val lines = buildList<Line> {
        activity?.items.orEmpty().forEach { add(Line.Item(it)) }
        pending.forEachIndexed { i, text -> add(Line.Pending(text, i)) }
    }
    val list = rememberLazyListState()
    // Follow the newest line as it comes, as a chat does.
    LaunchedEffect(lines.size, lines.lastOrNull()?.let { (it as? Line.Item)?.item?.text?.length }) {
        if (lines.isNotEmpty()) list.animateScrollToItem(lines.size)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val dark = colors.background.luminance() < 0.5f
    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = modifier.animateContentSize()
            .geminiChip(if (dark) Color.Black else Color.White, colors.primary, dark, 24.dp),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateMark(state)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        agent?.displayTitle?.takeIf { it != "Agent" } ?: title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stateLabel(state, agent?.target),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onStop != null && working) {
                    IconButton(onClick = onStop) { Icon(PrismIcons.Stop, "Stop the agent", Modifier.size(20.dp)) }
                }
                if (onOpen != null) {
                    IconButton(onClick = onOpen) { Icon(PrismIcons.OpenInNew, "Open in T3", Modifier.size(20.dp)) }
                }
                if (onNew != null) {
                    IconButton(onClick = onNew) { Icon(Icons.Rounded.Add, "New request", Modifier.size(20.dp)) }
                }
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(lines, key = { it.key }) { line ->
                    when (line) {
                        is Line.Pending -> Said(line.text, sending = true)
                        is Line.Item -> when (line.item.type) {
                            "user_message" -> Said(line.item.text, sending = false)
                            "assistant_message" -> Answer(line.item)
                            else -> Step(line.item)
                        }
                    }
                }
                item(key = "footer") {
                    when {
                        error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                        agent?.questionPending == true -> Text(
                            "The agent is asking you something. Answer it in Prism's inbox or in T3.",
                            style = MaterialTheme.typography.bodyMedium, color = colors.tertiary,
                        )
                        working && lines.lastOrNull().let { it !is Line.Item || it.item.status != "running" } ->
                            Working()
                    }
                }
            }
        }
    }
}

@Composable
private fun StateMark(state: String) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (state) {
            "running" -> LoadingIndicator(Modifier.size(28.dp))
            "waiting" -> Icon(PrismIcons.Chat, null, tint = colors.tertiary)
            "failed" -> Icon(Icons.Rounded.Warning, null, tint = colors.error)
            else -> Icon(Icons.Rounded.Check, null, tint = colors.primary)
        }
    }
}

private fun stateLabel(state: String, target: String?): String {
    val where = target?.let { if (it == "cluster") "on the cluster" else "on $it" }.orEmpty()
    val what = when (state) {
        "running" -> "Working"
        "waiting" -> "Waiting for you"
        "failed" -> "Stopped"
        "stale" -> "Inactive"
        else -> "Done"
    }
    return listOf(what, where).filter { it.isNotEmpty() }.joinToString(" ")
}

/** The owner's words, at the right. */
@Composable
private fun Said(text: String, sending: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSecondaryContainer.copy(alpha = if (sending) 0.6f else 1f),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(colors.secondaryContainer, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** What the agent says. */
@Composable
private fun Answer(item: ActivityItem) {
    SelectionContainer {
        Text(
            item.text.trim() + if (item.truncated) "…" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A step the agent takes, on one line. */
@Composable
private fun Step(item: ActivityItem) {
    val colors = MaterialTheme.colorScheme
    val failed = item.status == "failed"
    val label = item.title.ifBlank { item.text.lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("$ ").orEmpty() }
        .ifBlank { item.type.replace('_', ' ') }
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "${item.type.replace('_', ' ')}: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(stepIcon(item.type), null, Modifier.size(16.dp), tint = if (failed) colors.error else colors.onSurfaceVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (failed) colors.error else colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        if (item.status == "running") LoadingIndicator(Modifier.padding(start = 8.dp).size(18.dp))
    }
}

private fun stepIcon(type: String): ImageVector = when {
    type == "command_execution" -> PrismIcons.Terminal
    type == "file_change" -> Icons.Rounded.Edit
    type.contains("search") -> Icons.Rounded.Search
    type.contains("tool") -> Icons.Rounded.Build
    else -> PrismIcons.Sparkle
}

@Composable
private fun Working() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(20.dp))
        Text(
            "Working…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
