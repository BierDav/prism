// The assistant's cards as Gemini's action cards render them (the Google app's
// robin action cards: 16 dp padding, a header with the app's icon and name, a
// "x • y" subtitle, the content, borderless buttons), drawn for the card
// components of Prism's A2UI catalog (PrismCatalogUi.kt). Their buttons send
// A2UI events ([CardAct], core/UiActions.kt): call back or text, reply,
// snooze or dismiss an alarm, stop a timer or add a minute, flip a setting,
// edit a sent message, open an event or the T3 agent, open a conversation
// of a list; tapping an hour of the weather shows its details here.

package at.quickme.prism.assistant.a2ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.quickme.prism.AgentConversation
import at.quickme.prism.assistant.FollowedAgent
import at.quickme.prism.assistant.core.A2ui
import at.quickme.prism.assistant.core.AgentsCard
import at.quickme.prism.assistant.core.AlarmCard
import at.quickme.prism.assistant.core.CallCard
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.ClockTime
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.EventsCard
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.HourForecast
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.ScreenshotCard
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadListCard
import at.quickme.prism.assistant.core.TimerCard
import at.quickme.prism.assistant.core.Toggles
import at.quickme.prism.assistant.core.UiActions
import at.quickme.prism.assistant.core.Weather
import at.quickme.prism.assistant.core.WeatherCard
import at.quickme.prism.ui.PrismIcons
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** What a card's buttons send: an A2UI event's name and its context (core/UiActions.kt). */
typealias CardAct = (name: String, context: Map<String, Any?>) -> Unit

/**
 * Where the action a surface sent is: [busy] while the phone handles it (its
 * name), [error] when it couldn't, and [done] counting those that worked.
 */
@Immutable
data class SurfaceStatus(val busy: String? = null, val error: String? = null, val done: Int = 0)

/** The status of the surface being drawn (SurfaceHost provides it). */
val LocalSurfaceStatus = compositionLocalOf { SurfaceStatus() }

/** The agent the overlay follows, shown live in its hand-off's card. */
val LocalFollowedAgent = compositionLocalOf<FollowedAgent?> { null }

/** The time timers count from; the clock in the app, fixed in previews. */
val LocalNow = compositionLocalOf<(() -> Long)?> { null }

/** Whether the cards' buttons act (false: the history of another device's chat, a preview of the look only). */
val LocalCardsAct = compositionLocalOf { true }

/** [card] as Gemini's action card for it; its buttons send through [act]. */
@Composable
fun CardView(card: Card, act: CardAct) {
    val agent = LocalFollowedAgent.current
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
    val colors = MaterialTheme.colorScheme
    when (card) {
        is WeatherCard -> WeatherView(card)
        is CallCard -> ActionCard(Icons.Rounded.Call, "Phone", "Calling…") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(card.name)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(card.label, card.number).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Buttons {
                CardButton(Icons.Rounded.Email, "Message") { act(UiActions.MESSAGE, mapOf("name" to card.name, "number" to card.number)) }
                CardButton(Icons.Rounded.Call, "Call back") { act(UiActions.CALL, mapOf("name" to card.name, "number" to card.number)) }
            }
        }
        is MessageCard -> MessageView(card, act)
        is ThreadCard -> ActionCard(channelIcon(card.channel), channelName(card.channel), card.title) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.messages.takeLast(8).forEach { m -> Bubble(m.from, m.text, m.at, m.mine) }
            }
            if (card.address.isNotEmpty()) Buttons {
                CardButton(Icons.Rounded.Edit, "Reply") { act(UiActions.REPLY, mapOf("channel" to card.channel, "address" to card.address, "title" to card.title)) }
            }
        }
        is ThreadListCard -> ActionCard(channelIcon(card.channel), channelName(card.channel), "Recent") {
            Column {
                card.threads.take(8).forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clip16().clickable(enabled = LocalCardsAct.current) {
                            act(
                                UiActions.OPEN_THREAD,
                                mapOf("channel" to card.channel, "from" to t.from, A2ui.USER_MESSAGE to "Open my ${if (card.channel == MessageCard.MATRIX) "Matrix chat" else "texts"} with ${t.from}"),
                            )
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(t.from, 32.dp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(t.from, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (t.at > 0) Text(shortTime(t.at), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        is AlarmCard -> ActionCard(Icons.Rounded.Notifications, "Alarm", if (card.days.isEmpty()) "Once" else days(card.days)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ClockTime.format(card.hour, card.minute), style = MaterialTheme.typography.displaySmall)
                    val line = listOf(card.label, when (card.state) { AlarmCard.SNOOZED -> "Snoozed"; AlarmCard.DISMISSED -> "Dismissed"; else -> "" }).filter { it.isNotEmpty() }.joinToString(" • ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Busy()
                Switch(checked = card.state != AlarmCard.DISMISSED, onCheckedChange = null)
            }
            if (card.state != AlarmCard.DISMISSED) Buttons {
                CardButton(PrismIcons.Clock, "Snooze") { act(UiActions.SNOOZE, mapOf("hour" to card.hour, "minute" to card.minute)) }
                CardButton(Icons.Rounded.Close, "Dismiss") { act(UiActions.DISMISS, mapOf("hour" to card.hour, "minute" to card.minute)) }
            }
        }
        is TimerCard -> TimerView(card, act)
        is SettingCard -> SettingView(card, act)
        is ContactsCard -> ActionCard(Icons.Rounded.Person, "Contacts", null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.contacts.take(6).forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 32.dp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(c.label, c.number).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        val who = mapOf("name" to c.name, "number" to c.number)
                        RoundAction(Icons.Rounded.Email, "Text ${c.name}") { act(UiActions.MESSAGE, who) }
                        RoundAction(Icons.Rounded.Call, "Call ${c.name}", Modifier.padding(start = 8.dp)) { act(UiActions.CALL, who) }
                    }
                }
            }
        }
        is EventsCard -> ActionCard(Icons.Rounded.DateRange, "Calendar", null) {
            Column {
                card.events.take(8).forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clip16().clickable(enabled = LocalCardsAct.current && e.id > 0) {
                            act(UiActions.OPEN_EVENT, mapOf("id" to e.id, "title" to e.title, "start" to e.start))
                        }.padding(vertical = 5.dp),
                    ) {
                        Box(Modifier.padding(top = 4.dp).size(width = 4.dp, height = 32.dp).background(colors.primary, RoundedCornerShape(2.dp)))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (e.allDay) "All day" else shortTime(e.start) + "–" + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.end))) +
                                    (if (e.location.isNotBlank()) " • ${e.location}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            if (card.agentId.isNotEmpty()) Buttons {
                CardButton(PrismIcons.OpenInNew, "Open in T3") { act(UiActions.OPEN_T3, mapOf("agentId" to card.agentId, "title" to card.title)) }
            }
        }
        is AgentsCard -> ActionCard(Icons.Rounded.Build, "T3 Code", "Threads") {
            Column {
                card.agents.take(8).forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clip16().clickable(enabled = LocalCardsAct.current) { act(UiActions.OPEN_T3, mapOf("agentId" to a.id, "title" to a.title)) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(a.state, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
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
internal fun ActionCard(icon: ImageVector, header: String, subtitle: String?, content: @Composable () -> Unit) {
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
        LocalSurfaceStatus.current.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
    }
}

/**
 * Gemini's borderless buttons at the card's end: the last ends at the card's
 * padding, and their 48 dp touch targets reach into it below.
 */
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().offset(x = 12.dp).layout { m, c ->
            val p = m.measure(c)
            val trim = 12.dp.roundToPx()
            layout(p.width, (p.height - trim).coerceAtLeast(0)) { p.place(0, -trim / 2) }
        },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun CardButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val status = LocalSurfaceStatus.current
    TextButton(onClick = onClick, enabled = LocalCardsAct.current && status.busy == null) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A row's own action (a contact's call or text): a small tonal disc. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = LocalCardsAct.current && LocalSurfaceStatus.current.busy == null,
        modifier = modifier.size(36.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { Icon(icon, label, Modifier.size(18.dp)) }
}

/** The spinner while the phone handles what was tapped. */
@Composable
private fun Busy() {
    if (LocalSurfaceStatus.current.busy != null) LoadingIndicator(Modifier.padding(end = 8.dp).size(20.dp))
}

/** A tappable row's ripple, in rounded corners. */
private fun Modifier.clip16() = clip(RoundedCornerShape(12.dp))

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
private fun MessageView(card: MessageCard, act: CardAct) {
    val colors = MaterialTheme.colorScheme
    val status = LocalSurfaceStatus.current
    var editing by remember(card) { mutableStateOf(false) }
    var draft by remember(card) { mutableStateOf(card.text) }
    // Leaves editing once the edit went through (the status counts it).
    var savedAt by remember { mutableStateOf(-1) }
    LaunchedEffect(status) {
        if (savedAt >= 0 && status.busy == null) {
            if (status.error == null && status.done > savedAt) editing = false
            savedAt = -1
        }
    }
    val saving = status.busy == UiActions.EDIT
    ActionCard(channelIcon(card.channel), channelName(card.channel), card.to) {
        if (editing) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                enabled = !saving, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (saving) LoadingIndicator(Modifier.size(20.dp))
                TextButton(onClick = { editing = false; draft = card.text }, enabled = !saving) { Text("Cancel") }
                TextButton(
                    onClick = {
                        savedAt = status.done
                        act(UiActions.EDIT, mapOf("text" to draft))
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
                if (card.editable && LocalCardsAct.current) {
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
    }
}

/**
 * A setting, as Gemini's "Settings • Flashlight" card: its state, and the
 * switch the owner flips to change it back (or again). The switch moves at
 * once and waits for the phone; the card then shows what the tool reports,
 * or why it couldn't.
 */
@Composable
private fun SettingView(card: SettingCard, act: CardAct) {
    val colors = MaterialTheme.colorScheme
    val status = LocalSurfaceStatus.current
    var wanted by remember(card) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(status.busy) { if (status.busy == null) wanted = null }
    val switchable = LocalCardsAct.current && Toggles.switchable(card)
    ActionCard(settingIcon(card.kind), if (card.kind in setOf("app", "link", "navigation")) settingHeader(card.kind) else "Settings • ${card.title}", null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.subtitle.isNotEmpty()) Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Busy()
            card.on?.let { on ->
                Switch(
                    checked = wanted ?: on,
                    onCheckedChange = if (switchable) ({ w: Boolean ->
                        wanted = w
                        act(UiActions.TOGGLE, mapOf("on" to w))
                    }) else null,
                    enabled = !switchable || status.busy == null,
                    modifier = Modifier.semantics { contentDescription = "${card.title} ${if (wanted ?: on) "on" else "off"}" },
                )
            }
        }
    }
}

@Composable
private fun TimerView(card: TimerCard, act: CardAct) {
    val now = LocalNow.current
    var clock by remember { mutableLongStateOf(now?.invoke() ?: System.currentTimeMillis()) }
    if (now == null) LaunchedEffect(card) {
        while (true) {
            clock = System.currentTimeMillis()
            if (card.stoppedAt > 0 || clock - card.startedAt > card.seconds * 1000L) break
            delay(1000)
        }
    }
    val at = if (card.stoppedAt > 0) card.stoppedAt else clock
    val left = (card.seconds - ((at - card.startedAt) / 1000).toInt()).coerceIn(0, card.seconds)
    ActionCard(PrismIcons.Clock, "Timer", card.label.ifEmpty { null }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(clockText(left), style = MaterialTheme.typography.displaySmall)
                Text(
                    when {
                        card.stoppedAt > 0 -> "Stopped"
                        left == 0 -> "Done"
                        else -> "of ${clockText(card.seconds)}"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Busy()
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
        if (card.stoppedAt == 0L && left > 0) Buttons {
            CardButton(Icons.Rounded.Add, "1 min") { act(UiActions.ADD_MINUTE, mapOf("left" to left, "label" to card.label)) }
            CardButton(Icons.Rounded.Close, "Stop") { act(UiActions.STOP_TIMER, mapOf("label" to card.label)) }
        }
    }
}

/** Gemini's weather: the temperature large with the sky, the day's range, the next hours (tap one for its details) and days. */
@Composable
private fun WeatherView(card: WeatherCard) {
    val colors = MaterialTheme.colorScheme
    var picked by remember(card) { mutableStateOf<Int?>(null) }
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
            Column(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(vertical = 10.dp)) {
                Row {
                    card.hours.take(6).forEachIndexed { i, h ->
                        Column(
                            Modifier.weight(1f).clip16()
                                .background(if (picked == i) colors.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable(onClickLabel = "Details of ${h.time}") { picked = if (picked == i) null else i }
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(Weather.deg(h.temperature), style = MaterialTheme.typography.labelLarge)
                            SkyIcon(Weather.sky(h.code), h.day, Modifier.padding(vertical = 4.dp).size(24.dp))
                            Text(h.time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                picked?.let { card.hours.getOrNull(it) }?.let { h ->
                    Text(
                        hourDetails(h),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurface,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    )
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

/** "14:00 • Light rain • 3° • 40% rain • wind 12 km/h". */
internal fun hourDetails(h: HourForecast): String = listOfNotNull(
    h.time,
    Weather.condition(h.code),
    Weather.deg(h.temperature),
    h.precipitationChance?.let { "$it% rain" },
    h.wind?.let { "wind ${it.toInt()} km/h" },
).joinToString(" • ")

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

internal fun shortTime(at: Long): String {
    val today = android.text.format.DateUtils.isToday(at)
    return (if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)).format(Date(at))
}
