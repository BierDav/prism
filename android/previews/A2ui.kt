// The assistant's A2UI surfaces (assistant/a2ui/): each card of Prism's
// catalog as the renderer draws it from its surface, with its buttons, and
// surfaces the model composes with show_ui (a contact picker, a small form),
// in the overlay's panel as the owner sees them.

package at.quickme.prism.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.quickme.prism.assistant.AssistantTranscript
import at.quickme.prism.assistant.a2ui.A2uiSurface
import at.quickme.prism.assistant.a2ui.LocalNow
import at.quickme.prism.assistant.a2ui.rememberSurfaceHost
import at.quickme.prism.assistant.core.AgentEntry
import at.quickme.prism.assistant.core.AgentsCard
import at.quickme.prism.assistant.core.AlarmCard
import at.quickme.prism.assistant.core.CallCard
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.CardSurfaces
import at.quickme.prism.assistant.core.ContactEntry
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.DayForecast
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.EventEntry
import at.quickme.prism.assistant.core.EventsCard
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.HourForecast
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.ScreenshotCard
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.ShowUi
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadListCard
import at.quickme.prism.assistant.core.ThreadMessage
import at.quickme.prism.assistant.core.TimerCard
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.UiActions
import at.quickme.prism.assistant.core.WeatherCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val AT = 1_795_540_500_000L

/** [card] as the renderer draws it from its surface (CardSurfaces), on the panel's background. */
@Composable
private fun CardSurface(card: Card) = Part {
    val entries = remember { listOf(Entry.Ui("tool:p", "preview", CardSurfaces.surface(card, "tool:p"))) }
    val host = rememberSurfaceHost(entries, null)
    CompositionLocalProvider(LocalNow provides { AT + 75_000 }) {
        Box(Modifier.padding(16.dp)) { A2uiSurface(host, "tool:p") }
    }
}

@PartPreviews @Composable
private fun A2uiWeatherPreview() = CardSurface(
    WeatherCard(
        "Vienna", 4.2, 1.0, 2, true, 6.0, -1.0, 20, 12.0,
        hours = listOf(
            HourForecast("18:00", 4.0, 2, false, 10, 8.0), HourForecast("19:00", 3.0, 3, false, 20, 9.0),
            HourForecast("20:00", 3.0, 61, false, 60, 12.0), HourForecast("21:00", 2.0, 61, false, 80, 14.0),
            HourForecast("22:00", 2.0, 3, false, 40, 10.0), HourForecast("23:00", 1.0, 2, false, 10, 6.0),
        ),
        days = listOf(DayForecast("Today", 6.0, -1.0, 2, 10), DayForecast("Wed", 3.0, -2.0, 61, 60), DayForecast("Thu", 1.0, -3.0, 73, 90)),
    ),
)

@PartPreviews @Composable
private fun A2uiCallPreview() = CardSurface(CallCard("Anna Berger", "+43 664 1234567", "Mobile"))

@PartPreviews @Composable
private fun A2uiMessagePreview() = CardSurface(MessageCard(MessageCard.MATRIX, "Team", "!team:quickme.at", "The deploy is at 16:00 today.", "\$e1", edited = true, at = AT))

@PartPreviews @Composable
private fun A2uiThreadPreview() = CardSurface(
    ThreadCard(
        MessageCard.SMS, "Anna Berger",
        listOf(ThreadMessage("Anna Berger", "Are you coming tonight?", AT - 600_000), ThreadMessage("Me", "Yes, at 6", AT - 300_000, mine = true)),
        address = "+436641234567",
    ),
)

@PartPreviews @Composable
private fun A2uiThreadListPreview() = CardSurface(
    ThreadListCard(MessageCard.SMS, listOf(ThreadMessage("Anna Berger", "See you at 6", AT - 60_000), ThreadMessage("Mum", "Call me when you can", AT - 3_600_000))),
)

@PartPreviews @Composable
private fun A2uiAlarmPreview() = CardSurface(AlarmCard(6, 45, "Work", listOf(2, 3, 4, 5, 6)))

@PartPreviews @Composable
private fun A2uiTimerPreview() = CardSurface(TimerCard(600, "Tea", AT))

@PartPreviews @Composable
private fun A2uiSettingPreview() = CardSurface(SettingCard("dnd", "Do Not Disturb", "On", true))

@PartPreviews @Composable
private fun A2uiContactsPreview() = CardSurface(
    ContactsCard(listOf(ContactEntry("Anna Berger", "+43 664 1234567", "Mobile"), ContactEntry("Anna Kern", "+43 1 900 00", "Work"))),
)

@PartPreviews @Composable
private fun A2uiEventsPreview() = CardSurface(
    EventsCard(listOf(EventEntry("Standup", AT + 3_600_000, AT + 5_400_000, location = "Room 1", id = 7), EventEntry("Dinner with Anna", AT + 10_800_000, AT + 18_000_000, id = 8))),
)

@PartPreviews @Composable
private fun A2uiHandoffPreview() = CardSurface(HandoffCard("Fix the failing deploy of the worker", "r1", "t-1", "prism", "Fix the worker's deploy"))

@PartPreviews @Composable
private fun A2uiAgentsPreview() = CardSurface(
    AgentsCard(listOf(AgentEntry("t-1", "Fix the worker's deploy", "running"), AgentEntry("t-2", "Bump CameraX", "done"))),
)

@PartPreviews @Composable
private fun A2uiScreenshotPreview() = CardSurface(ScreenshotCard("Screenshot 18:15"))

/** The contact picker the model shows with show_ui when two Annas match. */
private val PICKER = """
{"messages":[{"createSurface":{"surfaceId":"pick_anna","sendDataModel":true,
 "components":[
  {"id":"root","component":"Column","children":["title","choice","actions"]},
  {"id":"title","component":"Text","text":"Which Anna should I call?","variant":"h4"},
  {"id":"choice","component":"ChoicePicker","variant":"mutuallyExclusive","value":{"@path":"/picked"},
   "options":[{"label":"Anna Berger · mobile · +43 664 1234567","value":"+436641234567"},{"label":"Anna Kern · work · +43 1 900 00","value":"+43190000"}]},
  {"id":"actions","component":"Row","justify":"end","children":["text","call"]},
  {"id":"text","component":"Button","variant":"borderless","child":"text_label","action":{"event":{"name":"text","context":{"number":{"@path":"/picked"}},"userMessage":"Text her instead"}}},
  {"id":"text_label","component":"Text","text":"Text instead"},
  {"id":"call","component":"Button","variant":"primary","child":"call_label","action":{"event":{"name":"call","context":{"number":{"@path":"/picked"}},"userMessage":"Picked: Anna Berger (mobile)"}}},
  {"id":"call_label","component":"Text","text":"Call"}],
 "dataModel":{"picked":["+436641234567"]}}}]}
"""

/** A small form: what the model is about to set, to confirm or tweak. */
private val FORM = """
{"messages":[{"createSurface":{"surfaceId":"reminder","sendDataModel":true,
 "components":[
  {"id":"root","component":"Card","child":"body"},
  {"id":"body","component":"Column","children":["head","what","when","minutes","loud","go"]},
  {"id":"head","component":"Text","text":"Reminder","variant":"h4"},
  {"id":"what","component":"TextField","label":"What","value":{"@path":"/what"}},
  {"id":"when","component":"ChoicePicker","label":"When","displayStyle":"chips","variant":"mutuallyExclusive","value":{"@path":"/when"},
   "options":[{"label":"Tonight","value":"tonight"},{"label":"Tomorrow 9:00","value":"tomorrow"},{"label":"Saturday","value":"saturday"}]},
  {"id":"minutes","component":"Slider","label":"Remind me minutes before","min":0,"max":60,"value":{"@path":"/minutes"}},
  {"id":"loud","component":"CheckBox","label":"Even with Do Not Disturb on","value":{"@path":"/loud"}},
  {"id":"go","component":"Button","variant":"primary","child":"go_label","action":{"event":{"name":"set_reminder","context":{"what":{"@path":"/what"}},"userMessage":"Set the reminder"}}},
  {"id":"go_label","component":"Text","text":"Set it"}],
 "dataModel":{"what":"Take the umbrella","when":["tomorrow"],"minutes":15,"loud":false}}}]}
"""

@Composable
private fun Panel(t: Transcript) = Part {
    at.quickme.prism.GeminiColors {
        Box(Modifier.background(BottomSheetDefaults.ScrimColor).padding(12.dp)) {
            AssistantTranscript(t, false, null, onNew = {}, onAction = null, modifier = Modifier.fillMaxWidth(), now = { AT + 75_000 })
        }
    }
}

private fun shown(json: String, id: String, said: String, answer: String): Transcript {
    val plan = ShowUi.plan(Json.parseToJsonElement(json).jsonObject, { null })
    return listOf(
        Event.Said(said),
        Event.ToolStarted(id, Tools.SHOW_UI, Tools.label(Tools.SHOW_UI)),
        Event.ToolFinished(id, Tools.SHOW_UI, plan.summary(), ui = plan),
        Event.Answer(answer),
    ).fold(Transcript()) { t, e -> t.after(e) }
}

/** show_ui: two contacts match, so the model asks with a picker instead of in words. */
@PartPreviews @Composable
private fun A2uiContactPickerPreview() = Panel(
    remember { shown(PICKER, "u1", "Call Anna", "Two contacts are called Anna; pick one.") },
)

/** The owner tapped Call on the picker: the transcript shows what they did, and the call that followed. */
@PartPreviews @Composable
private fun A2uiPickedPreview() = Panel(
    remember {
        shown(PICKER, "u1", "Call Anna", "Two contacts are called Anna; pick one.")
            .after(Event.Said(UiActions.shown(at.quickme.prism.assistant.core.UiAction("call", "pick_anna", "call", userMessage = "Picked: Anna Berger (mobile)"))))
            .after(Event.ToolStarted("c1", Tools.CALL, "Calling +436641234567"))
            .after(Event.ToolFinished("c1", Tools.CALL, "Calling Anna Berger.", CallCard("Anna Berger", "+43 664 1234567", "Mobile")))
    },
)

/** show_ui: a reminder's details to confirm or tweak, with a text field, chips, a slider and a checkbox. */
@PartPreviews @Composable
private fun A2uiFormPreview() = Panel(
    remember { shown(FORM, "u2", "Remind me to take the umbrella", "Here's the reminder; change anything, then set it.") },
)
