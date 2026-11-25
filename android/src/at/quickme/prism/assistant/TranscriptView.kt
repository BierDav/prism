// The assistant's conversation above the composer, in Gemini's look: the
// overlay's card (GeminiPlate.kt's chip, black or white) with a header and a
// "New chat" button, the owner's words at the right, the answers as simple
// Markdown, a line per tool while it runs, and a card per tool result as
// Gemini's action cards render them (the Google app's robin action cards:
// 16 dp padding, a header with the app's icon and name, a "x • y" subtitle,
// the content, a borderless button): weather with the next hours, the call,
// the message with its edit button, the alarm and timer, the setting with
// its switch (which the owner can flip: core/Toggles.kt), and the T3 agent's
// live activity after a hand-off.

package at.quickme.prism.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.quickme.prism.AgentConversation
import at.quickme.prism.assistant.core.AgentsCard
import at.quickme.prism.assistant.core.AlarmCard
import at.quickme.prism.assistant.core.CallCard
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.ClockTime
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.EventsCard
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.Markdown
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.ScreenshotCard
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadListCard
import at.quickme.prism.assistant.core.TimerCard
import at.quickme.prism.assistant.core.Toggles
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.Weather
import at.quickme.prism.assistant.core.WeatherCard
import at.quickme.prism.geminiChip
import at.quickme.prism.shared.AgentActivity
import at.quickme.prism.ui.PrismIcons
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The agent the overlay follows, for the hand-off card that started it. */
class FollowedAgent(
    val agentId: String,
    val title: String,
    val activity: AgentActivity?,
    val pending: List<String>,
    val error: String?,
    val onOpen: (() -> Unit)?,
    val onStop: (() -> Unit)?,
)

/**
 * The conversation in the overlay's card: [transcript] as it grows while
 * [running]; [agent] is what the newest hand-off's agent does now. [onEdit]
 * changes a sent message (entry, new text) and says why it couldn't;
 * [onToggle] flips a card's switch (entry, new state), likewise.
 */
@Composable
fun AssistantTranscript(
    transcript: Transcript,
    running: Boolean,
    agent: FollowedAgent?,
    onNew: () -> Unit,
    onEdit: suspend (String, String) -> String?,
    modifier: Modifier = Modifier,
    /** The time timers count from; the clock in the app, fixed in previews. */
    now: (() -> Long)? = null,
    /** Without it, the cards' switches only show their state. */
    onToggle: (suspend (String, Boolean) -> String?)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val list = rememberLazyListState()
    val entries = transcript.entries
    val handoff = transcript.latestHandoff()?.first
    LaunchedEffect(entries.size, (entries.lastOrNull() as? Entry.Answer)?.text?.length, running) {
        if (entries.isEmpty()) return@LaunchedEffect
        // To the end of the last item, also once the card has grown to fit
        // what was added (animateContentSize): a list scrolled past its end
        // stops at it.
        repeat(2) {
            list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 100_000)
            delay(400)
        }
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = modifier.animateContentSize().geminiChip(if (dark) Color.Black else Color.White, colors.primary, dark, 24.dp),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PrismIcons.Sparkle, null, Modifier.size(20.dp), tint = colors.primary)
                Text("Prism", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
                TextButton(onClick = onNew, enabled = !running) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Text("New chat", modifier = Modifier.padding(start = 6.dp))
                }
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(entries, key = { it.id }) { e ->
                    TranscriptEntry(e, agent?.takeIf { e.id == handoff }, onEdit, now, onToggle)
                }
                if (thinking(transcript, running)) {
                    item(key = "thinking") { Thinking() }
                }
            }
        }
    }
}

/**
 * One entry of a transcript, as the overlay and the history screen show it;
 * [agent] for the hand-off whose agent is followed.
 */
@Composable
fun TranscriptEntry(
    e: Entry,
    agent: FollowedAgent?,
    onEdit: suspend (String, String) -> String?,
    now: (() -> Long)? = null,
    onToggle: (suspend (String, Boolean) -> String?)? = null,
) {
    when (e) {
        is Entry.User -> UserLine(e)
        is Entry.Answer -> MarkdownText(e.text)
        is Entry.Step -> StepLine(e)
        is Entry.Problem -> ProblemLine(e.text)
        is Entry.Shown -> ToolCard(
            e.card, agent = agent, onEdit = { text -> onEdit(e.id, text) }, now = now,
            onToggle = onToggle?.let { toggle -> { on: Boolean -> toggle(e.id, on) } },
        )
    }
}

/** Whether "Thinking…" shows below [t]: a turn runs and no tool line says what it does. */
fun thinking(t: Transcript, running: Boolean): Boolean = running && (t.entries.lastOrNull() as? Entry.Step)?.running != true

/** What the owner said, at the right, with what they attached. */
@Composable
private fun UserLine(e: Entry.User) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (e.attachments.isNotEmpty()) {
            Text(
                e.attachments.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            e.text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSecondaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(colors.secondaryContainer, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun StepLine(e: Entry.Step) {
    val colors = MaterialTheme.colorScheme
    val tint = if (e.failed) colors.error else colors.onSurfaceVariant
    Column(Modifier.fillMaxWidth().semantics { contentDescription = e.label }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (e.failed) Icons.Rounded.Warning else toolIcon(e.tool), null, Modifier.size(16.dp), tint = tint)
            Text(
                e.label, style = MaterialTheme.typography.labelLarge, color = tint,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (e.running) LoadingIndicator(Modifier.padding(start = 8.dp).size(18.dp))
            else if (!e.failed) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.primary)
        }
        if (e.failed && e.detail.isNotBlank()) {
            Text(e.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
    }
}

@Composable
private fun ProblemLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(20.dp))
        Text("Thinking…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
    }
}

// --- Markdown ---

/** The assistant's Markdown (Markdown.kt), styled; links name their site. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val blocks = remember(text) { Markdown.parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in blocks) when (b) {
                is Markdown.Block.Paragraph -> Text(spans(b.spans, colors.primary, colors.surfaceContainerHigh), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                is Markdown.Block.Heading -> Text(
                    spans(b.spans, colors.primary, colors.surfaceContainerHigh),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                is Markdown.Block.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(22.dp))
                    Text(spans(b.spans, colors.primary, colors.surfaceContainerHigh), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                }
                is Markdown.Block.Code -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurface,
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        }
    }
}

private fun spans(spans: List<Markdown.Span>, link: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBackground else Color.Unspecified,
        )
        val target = s.link
        if (target != null) {
            withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(s.text) }
            }
            val host = runCatching { java.net.URI(target).host }.getOrNull()
            if (host != null && !s.text.contains(host)) withStyle(SpanStyle(color = link.copy(alpha = 0.7f))) { append(" ($host)") }
        } else withStyle(style) { append(s.text) }
    }
}

// --- Cards ---

/** A tool's result, as Gemini's action card for it; [onToggle] flips its switch, if it has one. */
@Composable
fun ToolCard(
    card: Card,
    agent: FollowedAgent? = null,
    onEdit: suspend (String) -> String? = { null },
    now: (() -> Long)? = null,
    onToggle: (suspend (Boolean) -> String?)? = null,
) {
    // The live agent only in its own card: a failed or other hand-off keeps its own text.
    if (card is HandoffCard && agent != null && card.failed.isEmpty() && agent.agentId == card.agentId) {
        AgentConversation(
            title = card.title.ifEmpty { agent.title.ifEmpty { card.request } },
            activity = agent.activity,
            pending = agent.pending,
            error = agent.error,
            shape = RoundedCornerShape(20.dp),
            onOpen = agent.onOpen,
            onStop = agent.onStop,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    when (card) {
        is WeatherCard -> WeatherView(card)
        is CallCard -> ActionCard(Icons.Rounded.Call, "Phone", "Calling…") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(card.name)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(card.label, card.number).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is MessageCard -> MessageView(card, onEdit)
        is ThreadCard -> ActionCard(channelIcon(card.channel), channelName(card.channel), card.title) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.messages.takeLast(8).forEach { m -> Bubble(m.from, m.text, m.at, m.mine) }
            }
        }
        is ThreadListCard -> ActionCard(channelIcon(card.channel), channelName(card.channel), "Recent") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                card.threads.take(8).forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(t.from, 32.dp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(t.from, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (t.at > 0) Text(shortTime(t.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        is AlarmCard -> ActionCard(Icons.Rounded.Notifications, "Alarm", if (card.days.isEmpty()) "Once" else days(card.days)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ClockTime.format(card.hour, card.minute), style = MaterialTheme.typography.displaySmall)
                    if (card.label.isNotEmpty()) Text(card.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = true, onCheckedChange = null)
            }
        }
        is TimerCard -> TimerView(card, now)
        is SettingCard -> SettingView(card, onToggle?.takeIf { Toggles.switchable(card) })
        is ContactsCard -> ActionCard(Icons.Rounded.Person, "Contacts", null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                card.contacts.take(6).forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 32.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(c.label, c.number).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        is EventsCard -> ActionCard(Icons.Rounded.DateRange, "Calendar", null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                card.events.take(8).forEach { e ->
                    Row {
                        Box(Modifier.padding(top = 4.dp).size(width = 4.dp, height = 32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (e.allDay) "All day" else shortTime(e.start) + "–" + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.end))) +
                                    (if (e.location.isNotBlank()) " • ${e.location}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        is HandoffCard -> ActionCard(Icons.Rounded.Build, "T3 Code", if (card.followUp) "Follow-up" else projectName(card.project)) {
            Text(card.title.ifEmpty { card.request }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    card.followUp -> "Sent into the thread"
                    card.failed.isNotEmpty() -> "Didn't start: ${card.failed}"
                    card.agentId.isEmpty() -> "Starting…"
                    else -> "Agent started"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is AgentsCard -> ActionCard(Icons.Rounded.Build, "T3 Code", "Threads") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.agents.take(8).forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(a.state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        is ScreenshotCard -> ActionCard(Icons.Rounded.Settings, "Screen", null) {
            Text("Looked at the screen", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Gemini's action card: a header with the app's icon and name and a "•"
 * subtitle, then the content, in a tonal container.
 */
@Composable
private fun ActionCard(icon: ImageVector, header: String, subtitle: String?, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = colors.primary)
            Text(
                listOfNotNull(header, subtitle?.takeIf { it.isNotEmpty() }).joinToString(" • "),
                style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
            )
        }
        content()
    }
}

@Composable
private fun Avatar(name: String, size: Dp = 40.dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            color = colors.onPrimaryContainer,
        )
    }
}

@Composable
private fun Bubble(from: String, text: String, at: Long, mine: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (mine) colors.onPrimaryContainer else colors.onSurface,
            modifier = Modifier.widthIn(max = 280.dp)
                .background(if (mine) colors.primaryContainer else colors.surfaceContainerHighest, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Text(
            listOfNotNull(from.takeIf { !mine }, at.takeIf { it > 0 }?.let(::shortTime)).joinToString(" • "),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/** A sent message, with Gemini's "Modify" as an edit in place (Matrix); an SMS says it can't be. */
@Composable
private fun MessageView(card: MessageCard, onEdit: suspend (String) -> String?) {
    val colors = MaterialTheme.colorScheme
    var editing by remember(card) { mutableStateOf(false) }
    var draft by remember(card) { mutableStateOf(card.text) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ActionCard(channelIcon(card.channel), channelName(card.channel), card.to) {
        if (editing) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it; error = null },
                enabled = !saving, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (saving) LoadingIndicator(Modifier.size(20.dp))
                TextButton(onClick = { editing = false; draft = card.text; error = null }, enabled = !saving) { Text("Cancel") }
                TextButton(
                    onClick = {
                        saving = true
                        scope.launch {
                            error = onEdit(draft)
                            saving = false
                            if (error == null) editing = false
                        }
                    },
                    enabled = !saving && draft.isNotBlank(),
                ) { Text("Save") }
            }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    card.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.widthIn(max = 300.dp)
                        .background(colors.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.primary)
                Text(
                    when {
                        card.unconfirmed -> "Sending not confirmed yet"
                        card.edited -> "Sent • edited"
                        else -> "Sent"
                    },
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (card.editable) {
                    TextButton(onClick = { editing = true }) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                        Text("Edit", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (card.channel == MessageCard.SMS) {
                Text(
                    "An SMS can't be changed once sent. Ask Prism to send a correction.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
    }
}

/**
 * A setting, as Gemini's "Settings • Flashlight" card: its state, and the
 * switch the owner flips to change it back (or again) when [onToggle] is
 * given. The switch moves at once and waits for the phone; the card then
 * shows what the tool reports, or why it couldn't.
 */
@Composable
private fun SettingView(card: SettingCard, onToggle: (suspend (Boolean) -> String?)?) {
    val colors = MaterialTheme.colorScheme
    var switching by remember(card) { mutableStateOf<Boolean?>(null) }
    var error by remember(card) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ActionCard(settingIcon(card.kind), if (card.kind in setOf("app", "link", "navigation")) settingHeader(card.kind) else "Settings • ${card.title}", null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.subtitle.isNotEmpty()) Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (switching != null) LoadingIndicator(Modifier.padding(end = 8.dp).size(20.dp))
            card.on?.let { on ->
                Switch(
                    checked = switching ?: on,
                    onCheckedChange = onToggle?.let { toggle ->
                        { wanted: Boolean ->
                            switching = wanted
                            error = null
                            scope.launch {
                                // On success the transcript's card changes, and with it this view's state.
                                error = toggle(wanted)
                                switching = null
                            }
                        }
                    },
                    enabled = onToggle == null || switching == null,
                    modifier = Modifier.semantics { contentDescription = "${card.title} ${if (switching ?: on) "on" else "off"}" },
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
    }
}

@Composable
private fun TimerView(card: TimerCard, now: (() -> Long)?) {
    var clock by remember { mutableLongStateOf(now?.invoke() ?: System.currentTimeMillis()) }
    if (now == null) LaunchedEffect(card) {
        while (true) {
            clock = System.currentTimeMillis()
            if (clock - card.startedAt > card.seconds * 1000L) break
            delay(1000)
        }
    }
    val left = (card.seconds - ((clock - card.startedAt) / 1000).toInt()).coerceIn(0, card.seconds)
    ActionCard(PrismIcons.Clock, "Timer", card.label.ifEmpty { null }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(clockText(left), style = MaterialTheme.typography.displaySmall)
                Text(if (left == 0) "Done" else "of ${clockText(card.seconds)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val progress = if (card.seconds > 0) left / card.seconds.toFloat() else 0f
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            val fill = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(48.dp)) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(fill, -90f, 360f * progress, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

/** Gemini's weather: the temperature large with the sky, the day's range, the next hours and days. */
@Composable
private fun WeatherView(card: WeatherCard) {
    val colors = MaterialTheme.colorScheme
    ActionCard(Icons.Rounded.LocationOn, "Weather", card.place) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Weather.deg(card.temperature), style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp))
            SkyIcon(Weather.sky(card.code), card.day, Modifier.padding(start = 12.dp).size(48.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                Text(Weather.condition(card.code), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                if (card.high != null && card.low != null) {
                    Text("${Weather.deg(card.high)} / ${Weather.deg(card.low)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                card.feelsLike?.let { Text("Feels like ${Weather.deg(it)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
        if (card.hours.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(vertical = 10.dp)) {
                card.hours.take(6).forEach { h ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Weather.deg(h.temperature), style = MaterialTheme.typography.labelLarge)
                        SkyIcon(Weather.sky(h.code), h.day, Modifier.padding(vertical = 4.dp).size(24.dp))
                        Text(h.time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (card.days.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.days.drop(1).take(4).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.day, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                        SkyIcon(Weather.sky(d.code), true, Modifier.size(20.dp))
                        Text(
                            d.precipitationChance?.takeIf { it >= 20 }?.let { "$it%" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall, color = colors.primary, modifier = Modifier.padding(start = 8.dp).width(36.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text("${Weather.deg(d.high)} / ${Weather.deg(d.low)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The sky drawn: sun or moon, clouds, rain, snow, lightning. */
@Composable
fun SkyIcon(sky: String, day: Boolean, modifier: Modifier = Modifier) {
    val sun = Color(0xFFFFB300)
    val moon = Color(0xFFB0BEC5)
    val cloud = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFCFD8DC) else Color(0xFF90A4AE)
    val water = Color(0xFF42A5F5)
    Canvas(modifier.semantics { contentDescription = sky }) {
        val w = size.width
        fun body(center: Offset, r: Float) = if (day) {
            drawCircle(sun, r, center)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val from = Offset(center.x + (r * 1.35f) * kotlin.math.cos(a).toFloat(), center.y + (r * 1.35f) * kotlin.math.sin(a).toFloat())
                val to = Offset(center.x + (r * 1.75f) * kotlin.math.cos(a).toFloat(), center.y + (r * 1.75f) * kotlin.math.sin(a).toFloat())
                drawLine(sun, from, to, strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            }
        } else {
            drawCircle(moon, r, center)
        }
        when (sky) {
            "clear" -> body(Offset(w / 2, w / 2), w * 0.24f)
            "partly" -> {
                body(Offset(w * 0.36f, w * 0.34f), w * 0.17f)
                cloudShape(cloud, Offset(w * 0.56f, w * 0.6f), w * 0.72f)
            }
            "fog" -> for (i in 0 until 3) drawLine(cloud, Offset(w * 0.15f, w * (0.35f + i * 0.17f)), Offset(w * 0.85f, w * (0.35f + i * 0.17f)), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            else -> {
                cloudShape(cloud, Offset(w / 2, w * 0.4f), w * 0.86f)
                when (sky) {
                    "rain" -> for (i in 0 until 3) {
                        val x = w * (0.32f + i * 0.18f)
                        drawLine(water, Offset(x, w * 0.72f), Offset(x - w * 0.06f, w * 0.92f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
                    }
                    "snow" -> for (i in 0 until 3) drawCircle(cloud, w * 0.06f, Offset(w * (0.3f + i * 0.2f), w * (0.78f + (i % 2) * 0.1f)))
                    "storm" -> drawLine(sun, Offset(w * 0.54f, w * 0.66f), Offset(w * 0.42f, w * 0.94f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun DrawScope.cloudShape(color: Color, center: Offset, width: Float) {
    val r = width * 0.2f
    drawCircle(color, r, Offset(center.x - width * 0.22f, center.y + r * 0.3f))
    drawCircle(color, r * 1.35f, Offset(center.x + width * 0.02f, center.y - r * 0.1f))
    drawCircle(color, r * 0.95f, Offset(center.x + width * 0.26f, center.y + r * 0.35f))
    drawRoundRect(color, Offset(center.x - width * 0.42f, center.y + r * 0.3f), Size(width * 0.84f, r * 1.0f), androidx.compose.ui.geometry.CornerRadius(r * 0.5f))
}

private fun toolIcon(tool: String): ImageVector = when (tool) {
    Tools.WEATHER -> Icons.Rounded.LocationOn
    Tools.CALL -> Icons.Rounded.Call
    Tools.FIND_CONTACTS -> Icons.Rounded.Person
    Tools.SEND_SMS, Tools.READ_SMS, Tools.MATRIX_ROOMS, Tools.MATRIX_READ, Tools.MATRIX_SEND, Tools.MATRIX_EDIT -> Icons.Rounded.Email
    Tools.ALARM -> Icons.Rounded.Notifications
    Tools.TIMER -> PrismIcons.Clock
    Tools.CALENDAR -> Icons.Rounded.DateRange
    Tools.MEDIA -> Icons.Rounded.PlayArrow
    Tools.START_T3, Tools.LIST_T3, Tools.SEND_T3, Tools.T3_PROJECTS -> Icons.Rounded.Build
    else -> Icons.Rounded.Settings
}

private fun channelIcon(channel: String) = if (channel == MessageCard.MATRIX) PrismIcons.Chat else Icons.Rounded.Email
private fun channelName(channel: String) = if (channel == MessageCard.MATRIX) "Matrix" else "Messages"

private fun settingIcon(kind: String): ImageVector = when (kind) {
    "flashlight" -> PrismIcons.Bolt
    "dnd" -> Icons.Rounded.Notifications
    "media" -> Icons.Rounded.PlayArrow
    "navigation" -> Icons.Rounded.LocationOn
    "link", "app" -> PrismIcons.OpenInNew
    else -> Icons.Rounded.Settings
}

private fun settingHeader(kind: String) = when (kind) {
    "app" -> "App"
    "link" -> "Browser"
    else -> "Maps"
}

private fun projectName(project: String) = when (project) {
    "auto", "" -> if (project.isEmpty()) "No project" else "Prism picks the project"
    else -> project
}

private val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private fun days(days: List<Int>): String = when (days.sorted()) {
    listOf(2, 3, 4, 5, 6) -> "Weekdays"
    listOf(1, 7) -> "Weekends"
    (1..7).toList() -> "Every day"
    else -> days.sorted().joinToString(", ") { dayNames.getOrElse(it - 1) { "?" } }
}

private fun clockText(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun shortTime(at: Long): String {
    val today = android.text.format.DateUtils.isToday(at)
    return (if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)).format(Date(at))
}
