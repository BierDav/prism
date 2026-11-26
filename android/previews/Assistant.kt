// The on-device assistant's transcript (assistant/TranscriptView.kt) with
// sample conversations: a card for each kind of tool result as Gemini renders
// its action cards (weather, call, messages with the edit button, alarm,
// timer, settings, contacts, calendar, texts read back, the T3 hand-off with
// the agent's live activity), the lines of tools at work, and the overlay as a
// whole with the answer panel above the composer, Gemini's answer page, and
// the overlay's conversation full screen, where the card grows into.

package at.quickme.prism.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import at.quickme.prism.AssistComposer
import at.quickme.prism.Tab
import at.quickme.prism.assistant.AssistantTranscript
import at.quickme.prism.assistant.FollowedAgent
import at.quickme.prism.assistant.HistoryChat
import at.quickme.prism.haloScrim
import at.quickme.prism.assistant.core.AlarmCard
import at.quickme.prism.assistant.core.CallCard
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.ContactEntry
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.DayForecast
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.EventEntry
import at.quickme.prism.assistant.core.EventsCard
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.HourForecast
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadMessage
import at.quickme.prism.assistant.core.TimerCard
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.WeatherCard
import at.quickme.prism.shared.ActivityItem
import at.quickme.prism.shared.Agent
import at.quickme.prism.shared.AgentActivity

/** A fixed "now" for the previews: 24 Nov 2026, 18:15 in Vienna. */
private const val NOW = 1_795_540_500_000L

/** A transcript from events, as the harness reports them. */
private fun transcript(vararg events: Event) = events.fold(Transcript()) { t, e -> t.after(e) }

/** A tool that ran and showed [card]. */
private fun ran(id: String, tool: String, card: Card) = arrayOf(
    Event.ToolStarted(id, tool, Tools.label(tool)),
    Event.ToolFinished(id, tool, "", card),
)

private val vienna = WeatherCard(
    place = "Vienna", temperature = 4.2, feelsLike = 1.0, code = 2, day = true, high = 6.0, low = -1.0,
    precipitationChance = 10, wind = 12.0,
    hours = listOf(
        HourForecast("19:00", 3.6, 2, false), HourForecast("20:00", 3.0, 3, false), HourForecast("21:00", 2.4, 61, false),
        HourForecast("22:00", 1.9, 61, false), HourForecast("23:00", 1.2, 3, false), HourForecast("00:00", 0.6, 45, false),
    ),
    days = listOf(
        DayForecast("Today", 6.0, -1.0, 2, 10), DayForecast("Wed", 3.0, -2.0, 61, 60),
        DayForecast("Thu", 1.0, -3.0, 73, 90), DayForecast("Fri", 5.0, 0.0, 0, 0), DayForecast("Sat", 7.0, 1.0, 95, 40),
    ),
)

@Composable
private fun Transcript(t: Transcript, running: Boolean = false, agent: FollowedAgent? = null) = Part {
    at.quickme.prism.GeminiColors {
        Box(Modifier.background(BottomSheetDefaults.ScrimColor).padding(12.dp)) {
            AssistantTranscript(t, running, agent, onNew = {}, onAction = null, modifier = Modifier.fillMaxWidth(), now = { NOW + 75_000 })
        }
    }
}

/** The weather here: Gemini's weather card with the next hours and days. */
@PartPreviews
@Composable
private fun AssistantWeatherPreview() = Transcript(
    transcript(
        Event.Said("What's the weather like tonight?"),
        *ran("w", Tools.WEATHER, vienna),
        Event.Answer("Partly cloudy and **4°** now, with light rain from about 21:00. Take an umbrella if you're out late."),
    ),
)

/** Texts sent without asking: an SMS (with its correction, as it can't be edited) and a Matrix message, edited in place. */
@PartPreviews
@Composable
private fun AssistantMessagesPreview() = Transcript(
    transcript(
        Event.Said("Text Anna that I'll be there at 6"),
        *ran("s1", Tools.SEND_SMS, MessageCard(MessageCard.SMS, "Anna Berger", "+436601234567", "I'll be there at 6", at = NOW)),
        Event.Answer("Sent."),
        Event.Said("Tell the team on Matrix the deploy is at 15:00, actually make it 16:00"),
        *ran("m1", Tools.MATRIX_SEND, MessageCard(MessageCard.MATRIX, "Team", "!team:quickme.at", "The deploy is at 16:00 today.", "\$e1", edited = true, at = NOW)),
        Event.Answer("Sent to *Team* and changed it to 16:00 in place."),
    ),
)

/** A call, an alarm, a timer, the flashlight and Do Not Disturb. */
@PartPreviews
@Composable
private fun AssistantActionsPreview() = Transcript(
    transcript(
        Event.Said("Call mum"),
        *ran("c", Tools.CALL, CallCard("Mum", "+43 664 7654321", "Mobile")),
        Event.Said("Wake me at 6:45 on weekdays, and a 10 minute tea timer"),
        *ran("a", Tools.ALARM, AlarmCard(6, 45, "Work", listOf(2, 3, 4, 5, 6))),
        *ran("t", Tools.TIMER, TimerCard(600, "Tea", NOW)),
        Event.Answer("Alarm set for 6:45 on weekdays, and the tea timer is running."),
        Event.Said("Flashlight on and do not disturb"),
        *ran("f", Tools.FLASHLIGHT, SettingCard("flashlight", "Flashlight", "On", true)),
        *ran("d", Tools.DND, SettingCard("dnd", "Do Not Disturb", "On", true)),
    ),
)

/** Reading: contacts, the calendar, and a text thread to answer "what did Anna write?". */
@PartPreviews
@Composable
private fun AssistantReadingPreview() = Transcript(
    transcript(
        Event.Said("What did Anna write, and what's on today?"),
        *ran("r", Tools.READ_SMS, ThreadCard(MessageCard.SMS, "Anna Berger", listOf(
            ThreadMessage("Anna Berger", "Are you coming to the concert?", NOW - 3_600_000),
            ThreadMessage("Me", "Yes! What time?", NOW - 3_000_000, mine = true),
            ThreadMessage("Anna Berger", "Doors at 7, see you at the bar 🎶", NOW - 600_000),
        ))),
        *ran("e", Tools.CALENDAR, EventsCard(listOf(
            EventEntry("Dentist", NOW + 3_600_000, NOW + 5_400_000, location = "Mariahilfer Str. 12"),
            EventEntry("Concert with Anna", NOW + 7_200_000, NOW + 14_400_000),
        ))),
        *ran("k", Tools.FIND_CONTACTS, ContactsCard(listOf(ContactEntry("Anna Berger", "+43 660 1234567", "Mobile"), ContactEntry("Anna Huber", "+43 1 5551234", "Work")))),
        Event.Answer("Anna says doors open at **7** and she'll meet you at the bar.\n\nToday:\n- 19:15 Dentist\n- 20:15 Concert with Anna"),
    ),
)

/** A hand-off to T3 Code: the agent's live activity in the transcript. */
@PartPreviews
@Composable
private fun AssistantHandoffPreview() {
    val agent = Agent(id = "a7", title = "Fix the stale Paperless backup", target = "david-server")
    fun item(n: Int, type: String, text: String, status: String = "completed") = ActivityItem("i$n", n.toLong(), "r1", type, status, "", text)
    val activity = AgentActivity(agent, "running", listOf(
        item(1, "user_message", "The Paperless backup on david-server hasn't run since Friday; find out why and fix it."),
        item(2, "assistant_message", "Checking the backup job's last runs."),
        item(3, "command_execution", "$ kubectl -n paperless get cronjobs"),
        item(4, "command_execution", "$ kubectl -n paperless logs job/backup-29384", status = "running"),
    ))
    Transcript(
        transcript(
            Event.Said("The paperless backup is stale again, can you get someone on it"),
            *ran("h", Tools.START_T3, HandoffCard("The Paperless backup on david-server hasn't run since Friday; find out why and fix it.", "r1", "a7", "paperless", agent.title)),
            Event.Answer("A T3 agent is on it in the Paperless project."),
        ),
        agent = FollowedAgent("a7", agent.title, activity, emptyList(), null, onOpen = {}, onStop = {}),
    )
}

/** At work: a tool's line while it runs, one that failed, and the error of a turn. */
@PartPreviews
@Composable
private fun AssistantWorkingPreview() = Transcript(
    transcript(
        Event.Said("Message Lukas on Matrix that I'm late"),
        Event.ToolStarted("x", Tools.MATRIX_SEND, "Messaging Lukas on Matrix"),
        Event.ToolFinished("x", Tools.MATRIX_SEND, "Matrix isn't set up on this phone (settings matrix_homeserver and matrix_access_token)", failed = true),
        Event.Answer("Matrix isn't set up on this phone. Should I send Lukas an SMS instead?"),
        Event.Said("yes"),
        Event.ToolStarted("y", Tools.SEND_SMS, "Texting Lukas"),
    ),
    running = true,
)

/** The overlay: the answer panel above Gemini's input card, over the live app, with the overlay's halo. */
@ScreenPreviews
@Composable
private fun AssistantOverlayPreview() = OverlaySample(
    transcript(
        Event.Said("What's the weather like tonight?"),
        *ran("w", Tools.WEATHER, vienna),
        Event.Answer("Partly cloudy and **4°** now, with light rain from about 21:00."),
    ),
)

/**
 * The panel as Gemini's answer page: its controls above it, Gemini's user
 * bubble and the answer, a conversation long enough to scroll (its edges
 * fade), as tall as the screen allows.
 */
@DarkScreenPreview
@Composable
private fun AssistantPanelPreview() = OverlaySample(
    transcript(
        Event.Said("Is the Paperless backup fresh again?"),
        Event.Answer(
            "Not yet: the last restic snapshot of **paperless** is 30 hours old, so #405 still fires. " +
                "The nightly job on david-server failed at 03:00 with a lock left over from Monday.",
        ),
        Event.Said("Remove the stale lock and run it now"),
        *ran("h", Tools.START_T3, HandoffCard("Remove the stale lock and run the Paperless backup now", "r2", "a8", "selfhosted-platform", "Paperless backup")),
        Event.Answer("A T3 agent is on it in **selfhosted-platform**. I'll tell you when the snapshot is there."),
        Event.Said("And the weather tonight?"),
        *ran("w", Tools.WEATHER, vienna),
        Event.Answer("Partly cloudy and **4°** now, with light rain from about 21:00."),
    ),
)

/** Over the live app: the halo round the card and the panel, the panel above the card in Gemini's 16 dp margins. */
@Composable
private fun OverlaySample(t: Transcript) = Themed {
    var card by remember { mutableStateOf(Rect.Zero) }
    var panel by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize()) {
        SampleHome(Tab.Alerts)
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val round = if (panel.isEmpty) card else Rect(
                    minOf(card.left, panel.left), minOf(card.top, panel.top), maxOf(card.right, panel.right), maxOf(card.bottom, panel.bottom),
                )
                if (!round.isEmpty) repeat(2) {
                    drawIntoCanvas { c ->
                        haloScrim()?.draw(
                            c.nativeCanvas, size.width, size.height, round.left, round.top, round.right, round.bottom,
                            28.dp.toPx(), density, dark = true, alpha = 1f,
                        )
                    }
                }
                drawContent()
            },
        ) {
            at.quickme.prism.GeminiColors {
                AssistComposer(
                    text = "", onText = {}, canSend = true, sending = false, onSend = {}, animate = false, onMic = {},
                    onCard = { card = it },
                    // Empty, the card sits back in Gemini's 40 dp margins under the 16 dp panel.
                    cardInset = 24.dp,
                    selectors = {
                        AssistantTranscript(
                            t, false, null, onNew = {}, onAction = null, lastChat = "Weather in Vienna", now = { NOW + 75_000 },
                            modifier = Modifier.fillMaxWidth().onGloballyPositioned { panel = it.boundsInRoot() },
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).statusBarsPadding().padding(top = 8.dp)
                        .padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The card dragged up into the full screen: the overlay's conversation in
 * the chat view (HistoryChat), with the drawer's hamburger, the handle and
 * New chat above, and the overlay's own card, flat, at the bottom.
 */
@ScreenPreviews
@Composable
private fun AssistantFullChatPreview() = Themed {
    val t = remember {
        transcript(
            Event.Said("What's the weather like tonight?"),
            *ran("w", Tools.WEATHER, vienna),
            Event.Answer("Partly cloudy and **4°** now, with light rain from about 21:00."),
            Event.Said("And tomorrow morning?"),
            Event.Answer("Around **-1°** at 7:00 with drizzle later; a warm coat and an umbrella."),
        )
    }
    at.quickme.prism.GeminiColors {
        HistoryChat(
            transcript = t, running = false, loading = false, error = null, configured = true, canSend = true,
            text = "", onText = {}, onSend = {}, onAction = null, onNew = {}, onBack = {},
            now = { NOW + 75_000 }, chats = {}, onCollapse = {},
            composer = {
                AssistComposer(
                    text = "", onText = {}, canSend = true, sending = false, onSend = {}, animate = false, onMic = {},
                    flat = true, selectors = {}, modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}
