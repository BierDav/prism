// The assistant's conversation above the composer, in Gemini's look: its
// answer panel (GeminiPlate.kt's chip, black or white) with Gemini's controls
// above it (New chat, full screen), the owner's words at the right in
// Gemini's user bubble, the answers as simple
// Markdown, a line per tool while it runs, and a card per tool result as
// Gemini's action cards render them (the Google app's robin action cards:
// 16 dp padding, a header with the app's icon and name, a "x • y" subtitle,
// the content, a borderless button). Every card is an A2UI surface (the
// tools' cards and what the model composes with show_ui), drawn by Google's
// renderer with Prism's catalog (a2ui/), one renderer per screen
// (SurfaceHost); what the owner does on a surface goes to [onAction]
// (AssistantSession.act): the phone handles a card's own buttons, anything
// else is the next turn.

package at.quickme.prism.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.CompositionLocalProvider
import at.quickme.prism.assistant.a2ui.A2uiSurface
import at.quickme.prism.assistant.a2ui.CardView
import at.quickme.prism.assistant.a2ui.LocalCardsAct
import at.quickme.prism.assistant.a2ui.LocalFollowedAgent
import at.quickme.prism.assistant.a2ui.LocalNow
import at.quickme.prism.assistant.a2ui.SurfaceHost
import at.quickme.prism.assistant.a2ui.rememberSurfaceHost
import at.quickme.prism.assistant.core.UiAction
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
 * The conversation above the overlay's card, as Gemini's floaty answer page
 * (the Google app's floaty2_response_page_v4): no header, but Gemini's
 * controls above the panel at its end, a chip ("New chat", where Gemini has
 * its action chip) and a round button that opens the conversation full screen
 * ([onOpen], Gemini's "open in app"); the panel itself in Gemini's chip look
 * with 28 dp corners, 24 dp above the first line and 16 dp at its sides, its
 * edges fading over 48 dp where it scrolls, as tall as the screen allows and
 * growing as the answer does. [transcript] grows while [running]; [agent] is
 * what the newest hand-off's agent does now. [onAction] handles what the
 * owner did on a surface (entry, A2UI action) and says why it couldn't;
 * without it the cards only show.
 */
@Composable
fun AssistantTranscript(
    transcript: Transcript,
    running: Boolean,
    agent: FollowedAgent?,
    onNew: () -> Unit,
    onAction: (suspend (String, UiAction) -> String?)?,
    modifier: Modifier = Modifier,
    /** The time timers count from; the clock in the app, fixed in previews. */
    now: (() -> Long)? = null,
    /** The conversation full screen; no button without it. */
    onOpen: (() -> Unit)? = null,
    /** The renderer, when the caller made it (previews settle it first). */
    host: SurfaceHost? = null,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val list = rememberLazyListState()
    val entries = transcript.entries
    val handoff = transcript.latestHandoff()?.first
    val surfaces = host ?: rememberSurfaceHost(entries, onAction)
    LaunchedEffect(entries.size, (entries.lastOrNull() as? Entry.Answer)?.text?.length, running) {
        if (entries.isEmpty()) return@LaunchedEffect
        // To the end of the last item, also once the panel has grown to fit
        // what was added (animateContentSize): a list scrolled past its end
        // stops at it.
        repeat(2) {
            list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 100_000)
            delay(400)
        }
    }
    Column(modifier) {
        // Gemini's controls: at the end, 4 dp above the panel, each button's
        // 4 dp inset outside the panel's edge.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp).offset(x = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlChip(Icons.Rounded.Add, "New chat", onNew, enabled = !running, modifier = Modifier.padding(end = 4.dp))
            if (onOpen != null) ControlButton(PrismIcons.OpenInNew, "Open full screen", onOpen)
        }
        Box(
            Modifier
                .fillMaxWidth()
                // 12 dp to the card, with the composer's 8.
                .padding(bottom = 4.dp)
                .geminiChip(if (dark) Color.Black else Color.White, colors.primary, dark, PanelRadius)
                .clip(RoundedCornerShape(PanelRadius))
                .animateContentSize(tween(350, easing = PanelEasing)),
        ) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().fadingEdges(list, 48.dp),
            ) {
                items(entries, key = { it.id }) { e ->
                    TranscriptEntry(e, surfaces, agent?.takeIf { e.id == handoff }, now)
                }
                if (thinking(transcript, running)) {
                    item(key = "thinking") { Thinking() }
                }
            }
        }
    }
}

/** Gemini's panel corners (floaty_response_background). */
private val PanelRadius = 28.dp

/** Gemini's grow-in-out: the panel follows its content on M3's emphasized decelerate. */
private val PanelEasing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * Android's fading edges as Gemini's panel has them (requiresFadingEdge,
 * 48 dp): what scrolls out fades towards the edge it leaves by.
 */
private fun Modifier.fadingEdges(list: androidx.compose.foundation.lazy.LazyListState, length: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val l = length.toPx().coerceAtMost(size.height / 2)
        if (list.canScrollBackward) drawRect(
            Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = l),
            size = Size(size.width, l), blendMode = BlendMode.DstOut,
        )
        if (list.canScrollForward) drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = size.height - l, endY = size.height),
            topLeft = Offset(0f, size.height - l), size = Size(size.width, l), blendMode = BlendMode.DstOut,
        )
    }

/**
 * Gemini's action chip above its panel: a pill in the surface colour, 36 dp
 * tall in a 48 dp touch target, the icon 18 dp, 12 dp before it and 16 dp
 * after the label.
 */
@Composable
private fun ControlChip(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick, enabled = enabled, shape = CircleShape, color = colors.surface,
            contentColor = colors.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        ) {
            Row(Modifier.height(36.dp).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Gemini's "open in app" above its panel: a 40 dp disc in the surface colour, in a 48 dp touch target. */
@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, shape = CircleShape, color = colors.surface, contentColor = colors.onSurfaceVariant, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(24.dp)) }
        }
    }
}

/**
 * One entry of a transcript, as the overlay and the history screen show it,
 * its surface drawn by [host]; [agent] for the hand-off whose agent is
 * followed.
 */
@Composable
fun TranscriptEntry(
    e: Entry,
    host: SurfaceHost,
    agent: FollowedAgent?,
    now: (() -> Long)? = null,
) {
    when (e) {
        is Entry.User -> UserLine(e)
        is Entry.Answer -> MarkdownText(e.text)
        is Entry.Step -> StepLine(e)
        is Entry.Problem -> ProblemLine(e.text)
        is Entry.Ui -> CompositionLocalProvider(LocalFollowedAgent provides agent, LocalNow provides now) {
            A2uiSurface(host, e.surface.surfaceId)
        }
        // Transcripts are migrated when read (Transcript.migrated); a stray old card still shows.
        is Entry.Shown -> CompositionLocalProvider(LocalFollowedAgent provides agent, LocalNow provides now, LocalCardsAct provides false) {
            CardView(e.card) { _, _ -> }
        }
    }
}

/** Whether "Thinking…" shows below [t]: a turn runs and no tool line says what it does. */
fun thinking(t: Transcript, running: Boolean): Boolean = running && (t.entries.lastOrNull() as? Entry.Step)?.running != true

/**
 * What the owner said, at the right, with what they attached: Gemini's user
 * bubble (user_bubble_layout_v5), the highest-but-one surface container with
 * 24 dp corners but 4 dp at the top end, 12 dp above and below the text, at
 * least 42 dp from the start.
 */
@Composable
private fun UserLine(e: Entry.User) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 42.dp, top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.End) {
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
            color = colors.onSurface,
            modifier = Modifier
                .background(colors.surfaceContainerHigh, RoundedCornerShape(topStart = 24.dp, topEnd = 4.dp, bottomEnd = 24.dp, bottomStart = 24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
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

