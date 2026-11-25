// The assistant's chat as A2UI (assistant/core/A2ui.kt, PrismCatalog.kt,
// UiActions.kt): v1.0 messages parsed, surfaces checked against Prism's
// catalog, every card to a surface and back, conversations stored with the
// typed cards opening as surfaces, large surfaces split into rows, the
// translation for the v0.9 renderer, and where an action goes (the phone and
// a note, or the next turn).
//
//   java -cp <classes>:kotlin-stdlib.jar:kotlinx-serialization-*.jar A2uiTestKt

import at.quickme.prism.assistant.core.A2ui
import at.quickme.prism.assistant.core.AgentEntry
import at.quickme.prism.assistant.core.AgentsCard
import at.quickme.prism.assistant.core.AlarmCard
import at.quickme.prism.assistant.core.CallCard
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.CardSurfaces
import at.quickme.prism.assistant.core.ContactEntry
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.Conversations
import at.quickme.prism.assistant.core.DayForecast
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.EventEntry
import at.quickme.prism.assistant.core.EventsCard
import at.quickme.prism.assistant.core.HandNotes
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.HourForecast
import at.quickme.prism.assistant.core.JsonPointer
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.PrismCatalog
import at.quickme.prism.assistant.core.Renderer
import at.quickme.prism.assistant.core.ScreenshotCard
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.ShowUi
import at.quickme.prism.assistant.core.Surface
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadListCard
import at.quickme.prism.assistant.core.ThreadMessage
import at.quickme.prism.assistant.core.TimerCard
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.UiAction
import at.quickme.prism.assistant.core.UiActions
import at.quickme.prism.assistant.core.UiChange
import at.quickme.prism.assistant.core.UiError
import at.quickme.prism.assistant.core.UiMessage
import at.quickme.prism.assistant.core.WeatherCard
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

private fun bytes(s: String) = s.encodeToByteArray().size

/** What [f] throws as a UiError, or null. */
private fun uiError(f: () -> Unit): String? = try { f(); null } catch (e: UiError) { e.message }

/** A contact picker, as the model would send it with show_ui. */
private val PICKER = """
{"messages":[{"version":"1.0","createSurface":{"surfaceId":"pick","catalogId":"${PrismCatalog.ID}","sendDataModel":true,
 "components":[
  {"id":"root","component":"Column","children":["title","choice","send"]},
  {"id":"title","component":"Text","text":"Which Anna?","variant":"h4"},
  {"id":"choice","component":"ChoicePicker","options":[{"label":"Anna Berger (mobile)","value":"+436641234567"},{"label":"Anna Kern (work)","value":"+43190000"}],"value":{"@path":"/picked"},"variant":"mutuallyExclusive"},
  {"id":"send","component":"Button","child":"send_label","variant":"primary","action":{"event":{"name":"pick","context":{"number":{"@path":"/picked"}},"userMessage":"Picked: Anna Berger (mobile)"}}},
  {"id":"send_label","component":"Text","text":"Call"}],
 "dataModel":{"picked":[]}}}]}
"""

fun main() {
    // --- Messages (A2UI v1.0) ---
    val create = UiMessage.parse(obj("""{"version":"1.0","createSurface":{"surfaceId":"s1","components":[{"id":"root","component":"Text","text":{"@path":"/hi"}}],"dataModel":{"hi":"Hello"}}}"""))
    check(create is UiMessage.Create && create.surface.surfaceId == "s1" && create.surface.catalogId == PrismCatalog.ID, "createSurface with its components and data model (the catalog Prism's by default)")
    check(UiMessage.parse(UiMessage.encode(create)) == create, "a message encodes and parses back the same")
    check(uiError { UiMessage.parse(obj("""{"version":"0.9","createSurface":{"surfaceId":"s"}}""")) }?.contains("version") == true, "another version is refused")
    check(uiError { UiMessage.parse(obj("""{"createSurface":{"surfaceId":"s","theme":{}}}""")) }?.contains("theme") == true, "v1.0 has no theme")
    check(uiError { UiMessage.parse(obj("""{"updateDataModel":{"surfaceId":"s","path":"x","value":1}}""")) }?.contains("JSON Pointer") == true, "a path must be a JSON Pointer")
    check(uiError { UiMessage.parse(obj("""{"createSurface":{"surfaceId":"s"},"deleteSurface":{"surfaceId":"s"}}""")) } != null, "one operation per message")
    val s1 = (create as UiMessage.Create).surface
    val updated = s1.after(UiMessage.UpdateDataModel("s1", "/hi", JsonPrimitive("Servus")))
        .after(UiMessage.UpdateComponents("s1", listOf(obj("""{"id":"root","component":"Text","text":"Fixed"}"""), obj("""{"id":"extra","component":"Divider"}"""))))
    check(updated.dataModel["hi"]?.jsonPrimitive?.content == "Servus" && updated.root?.get("text")?.jsonPrimitive?.content == "Fixed" && updated.components.size == 2, "updates change the data model and replace or add components")
    check(s1.after(UiMessage.UpdateDataModel("s1", "/hi", null)).dataModel.isEmpty(), "a null value removes")

    // --- JSON Pointer ---
    val doc = obj("""{"a":{"b":[1,2]},"x/y":3}""")
    check(JsonPointer.get(doc, "/a/b/1")?.jsonPrimitive?.content == "2" && JsonPointer.get(doc, "/x~1y")?.jsonPrimitive?.content == "3", "pointers read objects, lists and escaped keys")
    check(JsonPointer.get(JsonPointer.set(doc, "/a/b/-", JsonPrimitive(9)), "/a/b/2")?.jsonPrimitive?.content == "9", "- appends to a list")
    check(JsonPointer.get(JsonPointer.set(doc, "/new/deep", JsonPrimitive(true)), "/new/deep")?.jsonPrimitive?.content == "true", "objects are made on the way")

    // --- The catalog and its checks ---
    check(PrismCatalog.basic.map { it.name }.containsAll(listOf("Text", "Image", "Icon", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs", "Divider", "Modal", "Button", "CheckBox", "TextField", "DateTimeInput", "ChoicePicker", "Slider")), "the basic catalog's 18 components")
    check(PrismCatalog.cards.map { it.name } == listOf("Weather", "Call", "Message", "Thread", "ThreadList", "Alarm", "Timer", "Setting", "Contacts", "Events", "Handoff", "Agents", "Screenshot"), "and the cards")
    check(PrismCatalog.validate(s1).isEmpty(), "a plain surface is valid")
    fun errors(components: String, data: String = "{}") = PrismCatalog.validate(Surface("x", components = Json.parseToJsonElement(components).jsonArray.map { it.jsonObject }, dataModel = obj(data)))
    check(errors("""[{"id":"top","component":"Text","text":"a"}]""").any { "root" in it }, "a surface needs a root")
    check(errors("""[{"id":"root","component":"Blink","text":"a"}]""").any { "unknown component \"Blink\"" in it }, "an unknown component says which there are")
    check(errors("""[{"id":"root","component":"Text","txt":"a"}]""").let { e -> e.any { "no property \"txt\"" in it } && e.any { "needs \"text\"" in it } }, "an unknown property and a missing one")
    check(errors("""[{"id":"root","component":"Text","text":{"path":"/a"}}]""").any { "@path" in it }, "a v0.9 binding gets a hint to v1.0's @path")
    check(errors("""[{"id":"root","component":"Column","children":["a","ghost"]},{"id":"a","component":"Text","text":"x"}]""").any { "\"ghost\"" in it }, "a child that isn't there")
    check(errors("""[{"id":"root","component":"Card","child":"a"},{"id":"a","component":"Card","child":"root"}]""").any { "circle" in it }, "components in a circle")
    check(errors("""[{"id":"root","component":"Text","text":"a","variant":"huge"}]""").any { "one of h1" in it }, "an enum's values are named")
    check(errors("""[{"id":"root","component":"Button","child":"l","action":{"event":{}}},{"id":"l","component":"Text","text":"Go"}]""").any { "needs a \"name\"" in it }, "an event needs its name")
    check(errors("""[{"id":"root","component":"Contacts","contacts":[{"number":"+43"}]}]""").any { "root" in it && "Contacts" in it }, "a card component with fields that make no card")
    check(errors("""[{"id":"root","component":"Contacts","contacts":{"@path":"/c"}}]""", """{"c":[{"name":"Anna","number":"+43"}]}""").isEmpty(), "and one bound to its data")
    check(errors("""[{"id":"root","component":"List","children":{"componentId":"row","path":"/items"}},{"id":"row","component":"Text","text":{"@path":"name"}}]""").isEmpty(), "a template list")
    check(PrismCatalog.describe().let { "ChoicePicker(" in it && "Contacts(contacts*)" in it }, "the catalog in a few lines for the model")

    // --- show_ui ---
    val plan = ShowUi.plan(obj(PICKER), { null })
    val picker = plan.created!!
    check(picker.surfaceId == "pick" && picker.sendDataModel && plan.updated.isEmpty(), "show_ui creates the picker")
    check(plan.summary().contains("\"pick\""), "and tells the model what it showed")
    val shown = mapOf("pick" to picker)
    val upd = ShowUi.plan(obj("""{"messages":[{"updateDataModel":{"surfaceId":"pick","path":"/picked","value":["+43190000"]}},{"updateComponents":{"surfaceId":"pick","components":[{"id":"title","component":"Text","text":"Which Anna? (2 found)"}]}}]}"""), shown::get)
    check(upd.created == null && upd.updated.single().dataModel["picked"]!!.jsonArray.single().jsonPrimitive.content == "+43190000" && upd.updated.single().component("title")!!["text"]!!.jsonPrimitive.content.endsWith("found)"), "a later call updates its data and components")
    check(uiError { ShowUi.plan(obj(PICKER), shown::get) }?.contains("already shown") == true, "a surfaceId that is shown is updated, not created again")
    check(uiError { ShowUi.plan(obj("""{"messages":[{"updateDataModel":{"surfaceId":"nope","path":"/a","value":1}}]}"""), shown::get) }?.contains("create it first") == true, "an update of nothing says so")
    val bad = uiError { ShowUi.plan(obj("""{"messages":[{"createSurface":{"surfaceId":"b","components":[{"id":"root","component":"Column","children":["x"]},{"id":"y","component":"Slider","value":1}]}}]}"""), { null }) }
    check(bad != null && "\"x\"" in bad && "needs \"max\"" in bad, "every problem is listed for the model: $bad")
    check(ShowUi.plan(obj("""{"createSurface":{"components":[{"id":"root","component":"Text","text":"hi"}]}}"""), { null }, { "auto" }).created?.surfaceId == "auto", "a bare message and a missing surfaceId are taken")
    check(ShowUi.plan(obj("""{"messages":[{"deleteSurface":{"surfaceId":"pick"}}]}"""), shown::get).deleted == listOf("pick"), "deleteSurface")
    val longId = "x".repeat(ShowUi.MAX_ID + 1)
    check(uiError { ShowUi.plan(obj("""{"createSurface":{"surfaceId":"$longId","components":[{"id":"root","component":"Text","text":"hi"}]}}"""), { null }) }?.contains("at most") == true, "a surfaceId too long to store is refused")

    // --- Every card to a surface and back ---
    val cards: List<Card> = listOf(
        WeatherCard("Vienna", 2.5, 0.1, 3, true, 5.0, -1.0, 20, 12.0, listOf(HourForecast("14:00", 3.0, 61, true, 40, 10.0)), listOf(DayForecast("Tue", 5.0, -1.0, 3, 10))),
        CallCard("Anna", "+436641234567", "mobile"),
        MessageCard(MessageCard.MATRIX, "Team", "!room:x", "at 7", eventId = "\$e1", at = 1),
        ThreadCard(MessageCard.SMS, "Anna", listOf(ThreadMessage("Anna", "hi", 1), ThreadMessage("Me", "hey", 2, mine = true)), address = "+43664"),
        ThreadListCard(MessageCard.SMS, listOf(ThreadMessage("Anna", "hi", 1))),
        AlarmCard(7, 30, "Gym", listOf(2, 4)),
        TimerCard(300, "Pasta", 1000),
        SettingCard("dnd", "Do Not Disturb", "On", true),
        ContactsCard(listOf(ContactEntry("Anna", "+43664", "mobile"))),
        EventsCard(listOf(EventEntry("Standup", 1, 2, location = "Room 1", id = 42))),
        HandoffCard("fix it", "r1", "t-1", "prism", "Fix it"),
        AgentsCard(listOf(AgentEntry("t-1", "Fix it", "running"))),
        ScreenshotCard("Screenshot 18:15"),
    )
    for (c in cards) {
        val s = CardSurfaces.surface(c, "card")
        val name = PrismCatalog.specOf(c).name
        check(s.root?.get("component")?.jsonPrimitive?.content == name && PrismCatalog.validate(s).isEmpty(), "$name: a valid surface with the card at its root")
        check(CardSurfaces.card(s) == c, "$name: and the same card from it")
    }
    val weather = CardSurfaces.surface(cards[0], "w")
    check(weather.root!!["place"] == obj("""{"@path":"/place"}""") && weather.dataModel["place"]!!.jsonPrimitive.content == "Vienna", "a card's fields are bound to its data model")
    check((CardSurfaces.card(weather.after(UiMessage.UpdateDataModel("w", "/temperature", JsonPrimitive(9.0)))) as WeatherCard).temperature == 9.0, "so updateDataModel changes the card")
    check(CardSurfaces.card(s1) == null, "a surface the model composed shows no card")

    // --- Conversations stored with typed cards open as surfaces ---
    val oldRows = listOf(
        "e0" to """{"type":"user","id":"e0","text":"call anna"}""",
        "tool:c1" to """{"type":"card","id":"tool:c1","tool":"call_phone","card":{"type":"call","name":"Anna","number":"+43664"}}""",
        "tool:c2" to """{"type":"card","id":"tool:c2","tool":"set_alarm","card":{"type":"alarm","hour":7,"minute":0}}""",
    )
    val migrated = Conversations.transcript(oldRows)
    check(migrated.entries.size == 3 && migrated.entries.drop(1).all { it is Entry.Ui }, "old card rows become surfaces")
    check(migrated.card("tool:c1") == CallCard("Anna", "+43664") && migrated.card("tool:c2") == AlarmCard(7, 0), "with the same cards")
    check((migrated.entries[1] as Entry.Ui).surface.surfaceId == "tool:c1", "each surface named by its entry")
    val resaved = Conversations.entryRows(migrated)
    check(resaved[1].second.let { "\"createSurface\"" in it && "\"type\":\"ui\"" in it && "\"Call\"" in it }, "saved again, a card is its A2UI createSurface")
    check(Conversations.transcript(resaved).entries == migrated.entries, "and reads back the same")
    val memory = Json.decodeFromString(Transcript.serializer(), """{"entries":[{"type":"card","id":"tool:x","tool":"set_timer","card":{"type":"timer","seconds":60}}],"next":1}""").migrated()
    check(memory.card("tool:x") == TimerCard(60), "the resume window's transcript too")

    // --- Large surfaces in several rows ---
    val many = ContactsCard((1..1500).map { ContactEntry("Contact number $it", "+43 664 ${1000000 + it}", "mobile") })
    val big = Transcript().after(Event.ToolStarted("c", Tools.FIND_CONTACTS, "Looking up")).after(Event.ToolFinished("c", Tools.FIND_CONTACTS, "found", card = many))
    val bigRows = Conversations.entryRows(big)
    check(bigRows.size > 2 && bigRows.all { bytes(it.second) <= Conversations.ROW_BYTES }, "1500 contacts in ${bigRows.size} rows, each fitting")
    check(bigRows.drop(1).all { (id, body) -> id.startsWith("tool:c" + Conversations.PART_SEPARATOR) && "\"ui-part\"" in body && "\"chunk\"" in body }, "the rows after the first are pieces of its JSON")
    check(Conversations.transcript(bigRows).card("tool:c") == many, "they make the whole surface again")
    check(Conversations.transcript(bigRows.dropLast(1)).surface("tool:c")!!.surface.components.isEmpty(), "with a piece missing, the surface stays empty (never half of it)")
    check(Conversations.transcript(bigRows.reversed()).card("tool:c") == many, "in any order")
    val bigForm = Surface("f", components = (0 until 150).map { obj("""{"id":"t$it","component":"Text","text":"${"x".repeat(150)} $it"}""") } + obj("""{"id":"root","component":"Column","children":[${(0 until 150).joinToString(",") { "\"t$it\"" }}]}"""))
    val formRows = Conversations.uiRows(Entry.Ui("e5", Tools.SHOW_UI, bigForm))
    check(formRows.size > 1 && formRows.all { bytes(it.second) <= Conversations.ROW_BYTES } && Conversations.transcript(formRows).surface("f")?.surface == bigForm, "a large composed surface splits and comes back the same")
    // B-S4: one component or value larger than a row is split too, never cut.
    val huge = "Ä€😀\"\\\n<x>".repeat(2600)
    val oneText = Surface("one", components = listOf(obj("""{"id":"root","component":"Text","text":${JsonPrimitive(huge)}}""")), dataModel = obj("""{"big":${JsonPrimitive(huge)}}"""))
    val oneRows = Conversations.uiRows(Entry.Ui("e6", Tools.SHOW_UI, oneText))
    check(bytes(oneText.components.single().toString()) > 20 * 1024, "a single Text over 20 KiB")
    check(oneRows.size > 2 && oneRows.all { bytes(it.second) <= Conversations.ROW_BYTES }, "goes in ${oneRows.size} rows, each whole row at most ${Conversations.ROW_BYTES} bytes")
    check(Conversations.transcript(oneRows).surface("one")?.surface == oneText, "and comes back exactly, text and data model uncut")

    // --- For the renderer (A2UI v0.9) ---
    val drawn = Renderer.create(picker).map(::obj)
    check(drawn.map { it.keys - "version" } == listOf(setOf("createSurface"), setOf("updateDataModel"), setOf("updateComponents")) && drawn.all { it["version"]!!.jsonPrimitive.content == A2ui.RENDERER_VERSION }, "createSurface, then its data model and components, as v0.9")
    val choice = drawn[2]["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "choice" }
    check(choice["value"] == obj("""{"path":"/picked"}"""), "bindings as v0.9's path")
    val button = drawn[2]["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "send" }
    check(button["action"]!!.jsonObject["event"]!!.jsonObject["context"]!!.jsonObject[A2ui.USER_MESSAGE]!!.jsonPrimitive.content == "Picked: Anna Berger (mobile)", "an event's userMessage rides in its context")
    check(Renderer.update(picker, upd.updated.single()).map(::obj).map { it.keys - "version" } == listOf(setOf("updateDataModel"), setOf("updateComponents")), "an update sends what changed")
    check(Renderer.update(picker, picker).isEmpty(), "and nothing when nothing did")

    // --- Actions: the phone, or the next turn ---
    val back = UiAction.fromRenderer("pick", "pick", "send", 1_700_000_000_000, mapOf("number" to listOf("+436641234567"), A2ui.USER_MESSAGE to "Picked: Anna Berger (mobile)"), mapOf("pick" to mapOf("picked" to listOf("+436641234567"))))
    check(back.userMessage == "Picked: Anna Berger (mobile)" && A2ui.USER_MESSAGE !in back.context, "the renderer's event as a v1.0 action, its userMessage taken out")
    check(back.toJson()["action"]!!.jsonObject.let { it["name"]!!.jsonPrimitive.content == "pick" && it["sourceComponentId"]!!.jsonPrimitive.content == "send" && it["timestamp"]!!.jsonPrimitive.content.startsWith("2023-11-14T") }, "with name, source and an ISO timestamp")
    val turn = UiActions.route(picker, back, trusted = false)
    check(turn is UiActions.Route.Turn && turn.shown == "Picked: Anna Berger (mobile)", "a pick on the model's surface is the next turn, shown as the owner did it")
    check((turn as UiActions.Route.Turn).prompt.let { "\"action\"" in it && "+436641234567" in it && "data model" in it }, "the model gets the action as A2UI JSON with the data")
    check(UiActions.shown(UiAction("choose_room", "s", "b", context = obj("""{"room":"Team"}"""))) == "Choose room: Team", "without a userMessage, what it was and its values")
    check(UiActions.shown(UiAction("x", "s", "b", context = obj("""{"label":"Tomorrow 9:00"}"""))) == "Tomorrow 9:00", "or its label")

    val call = CardSurfaces.surface(CallCard("Anna", "+43664"), "tool:c1")
    val callBack = UiActions.route(call, UiAction(UiActions.CALL, "tool:c1", "root", context = obj("""{"name":"Anna","number":"+43664"}""")), trusted = true)
    check(callBack is UiActions.Route.Local && callBack.card == CallCard("Anna", "+43664"), "a card's Call back stays on the phone, with its card")
    check(UiActions.note(callBack as UiActions.Route.Local, "Calling Anna.").let { "called Anna +43664" in it && "Calling Anna." in it }, "and the model hears of it: ${UiActions.note(callBack)}")
    val alarm = CardSurfaces.surface(AlarmCard(7, 30), "a")
    val clock = UiActions.route(alarm, UiAction(UiActions.OPEN_ALARMS, "a", "root"), trusted = true)
    check(clock is UiActions.Route.Local && UiActions.note(clock).let { "opened the Clock app's alarms" in it && "7:30" in it && "can't tell" in it }, "an alarm opens Clock, and the model isn't told it changed")
    val timer = CardSurfaces.surface(TimerCard(300, "Pasta", 1), "t")
    check(UiActions.note(UiActions.route(timer, UiAction(UiActions.OPEN_TIMERS, "t", "root"), trusted = true) as UiActions.Route.Local).let { "timers" in it && "\"Pasta\"" in it && "stopped" !in it }, "a timer too")
    val list = CardSurfaces.surface(ThreadListCard(MessageCard.SMS, listOf(ThreadMessage("Anna", "hi", 1))), "l")
    check(UiActions.route(list, UiAction(UiActions.OPEN_THREAD, "l", "root", userMessage = "Open my texts with Anna"), trusted = true).let { it is UiActions.Route.Turn && it.shown == "Open my texts with Anna" }, "a conversation tapped in a list is a turn")
    check(UiActions.route(picker, UiAction(UiActions.CALL, "pick", "send"), trusted = false) is UiActions.Route.Turn, "a \"call\" from the model's own button is a turn, never a call")
    val notes = HandNotes()
    notes.add(UiActions.note(callBack))
    notes.add(UiActions.note(clock as UiActions.Route.Local))
    check(HandNotes.framed(notes.take())!!.let { it.startsWith("[Since the last turn:") && "called" in it && "Clock" in it }, "local actions reach the model through the hand notes")

    // --- The transcript with show_ui ---
    var t = Transcript().after(Event.ToolStarted("u1", Tools.SHOW_UI, "Showing choices"))
        .after(Event.ToolFinished("u1", Tools.SHOW_UI, plan.summary(), ui = plan))
    check((t.entries.single() as Entry.Ui).surface.surfaceId == "pick" && t.surface("pick") != null, "show_ui's surface takes its line's place")
    t = t.after(Event.ToolStarted("u2", Tools.SHOW_UI, "Updating")).after(Event.ToolFinished("u2", Tools.SHOW_UI, upd.summary(), ui = upd))
    check(t.surface("pick")!!.surface == upd.updated.single() && t.entries.last() is Entry.Step, "an update changes the surface where it is, and its call leaves a line")
    t = t.withSurface(t.surface("pick")!!.id, t.surface("pick")!!.surface.copy(dataModel = obj("""{"picked":["+43664"]}""")))
    check(t.surface("pick")!!.surface.dataModel["picked"]!!.jsonArray.size == 1, "what the owner filled in is kept with it")
    check(t.did(Tools.CALL, "Calling Anna").entries.last().let { it is Entry.Step && !it.running && it.label == "Calling Anna" }, "a local action's line")

    // --- B-S1: what the model composes never makes the phone act ---
    val forged = ShowUi.plan(obj("""{"messages":[{"createSurface":{"surfaceId":"fake_call","components":[{"id":"root","component":"Call","name":"Anna","number":"+43900123456"}],"dataModel":{}}}]}"""), { null }).created!!
    val forgedTap = UiAction(UiActions.CALL, "fake_call", "root", context = obj("""{"name":"Anna","number":"+43900123456"}"""))
    check(UiActions.route(forged, forgedTap, trusted = false) is UiActions.Route.Turn, "a Call card the model composed: its Call is a turn, not a call")
    check(UiActions.route(call, forgedTap.copy(surfaceId = "tool:c1"), trusted = true) is UiActions.Route.Local, "the same card from call_phone calls")
    var mixed = Transcript().after(Event.ToolStarted("c1", Tools.CALL, "Calling")).after(Event.ToolFinished("c1", Tools.CALL, "Calling Anna.", card = CallCard("Anna", "+43664")))
        .after(Event.ToolStarted("u1", Tools.SHOW_UI, "Showing")).after(Event.ToolFinished("u1", Tools.SHOW_UI, "shown", ui = ShowUi.plan(obj("""{"messages":[{"createSurface":{"surfaceId":"fake_call","components":[{"id":"root","component":"Call","name":"Anna","number":"+43900123456"}]}}]}"""), { null })))
    check(mixed.surface("tool:c1")!!.trusted && !mixed.surface("fake_call")!!.trusted, "each surface keeps who made it")
    check(mixed.card("tool:c1") == CallCard("Anna", "+43664") && mixed.card("fake_call") == null, "only a tool's card is a card the phone acts on")
    check(Conversations.transcript(Conversations.entryRows(mixed)).let { it.surface("tool:c1")!!.trusted && !it.surface("fake_call")!!.trusted }, "and keeps it when stored")
    val taken = { id: String -> mixed.surface(id)?.trusted == true }
    val toolEdit = """{"messages":[{"updateDataModel":{"surfaceId":"tool:c1","path":"/number","value":"+43900123456"}}]}"""
    check(uiError { ShowUi.plan(obj(toolEdit), mixed::modelSurface, taken = taken) }?.contains("is a tool's card") == true, "show_ui can't change a tool's card")
    check(uiError { ShowUi.plan(obj("""{"messages":[{"deleteSurface":{"surfaceId":"tool:c1"}}]}"""), mixed::modelSurface, taken = taken) }?.contains("is a tool's card") == true, "nor delete it")
    check(uiError { ShowUi.plan(obj("""{"messages":[{"createSurface":{"surfaceId":"tool:c9","components":[{"id":"root","component":"Text","text":"x"}]}}]}"""), mixed::modelSurface, taken = taken) }?.contains("is a tool's card") == true, "nor take a tool's surfaceId before the tool does")
    val sneaky = UiChange(updated = listOf(CardSurfaces.surface(CallCard("Anna", "+43900123456"), "tool:c1")), deleted = listOf("tool:c1"))
    mixed = mixed.after(Event.ToolStarted("u2", Tools.SHOW_UI, "x")).after(Event.ToolFinished("u2", Tools.SHOW_UI, "x", ui = sneaky))
    check(mixed.card("tool:c1") == CallCard("Anna", "+43664"), "and the transcript never applies a change to one")
    check(ShowUi.plan(obj("""{"messages":[{"updateDataModel":{"surfaceId":"fake_call","path":"/name","value":"Bob"}}]}"""), mixed::modelSurface, taken = taken).updated.single().surfaceId == "fake_call", "its own surfaces it may change")

    // --- B-S2: no function on a surface acts ---
    fun linkSurface(action: String) = """{"messages":[{"createSurface":{"surfaceId":"link","components":[{"id":"root","component":"Button","child":"l","action":$action},{"id":"l","component":"Text","text":"Open"}]}}]}"""
    check(uiError { ShowUi.plan(obj(linkSurface("""{"functionCall":{"@call":"openUrl","args":{"url":"https://example.com"}}}""")), { null }) }?.contains("can't run functions") == true, "a Button can't call openUrl")
    check(uiError { ShowUi.plan(obj("""{"messages":[{"createSurface":{"surfaceId":"t","components":[{"id":"root","component":"Text","text":{"@call":"openUrl","args":{"url":"https://example.com"}}}]}}]}"""), { null }) }?.contains("\"@call\" must be one of") == true, "nor may a binding, even one only drawn")
    check(uiError { ShowUi.plan(obj("""{"messages":[{"createSurface":{"surfaceId":"t","components":[{"id":"root","component":"Text","text":{"@call":"formatString","args":{"value":"hi"}}}]}}]}"""), { null }) } == null, "a function that only computes is fine")
    check("openUrl" !in PrismCatalog.FUNCTIONS, "openUrl isn't among the functions a surface may call (nor in the renderer's catalog)")
    check(ShowUi.plan(obj(linkSurface("""{"event":{"name":"open_link","context":{"url":"https://example.com"}}}""")), { null }).created != null, "a link is an event, then the model's open_url")

    // --- B-S3: only the T3 tool's hand-off is followed ---
    val fakeHandoff = """{"messages":[{"createSurface":{"surfaceId":"h","components":[{"id":"root","component":"Handoff","request":"fix","requestId":"r9","agentId":"t-9"}]}}]}"""
    var h = Transcript().after(Event.ToolStarted("u", Tools.SHOW_UI, "x")).after(Event.ToolFinished("u", Tools.SHOW_UI, "x", ui = ShowUi.plan(obj(fakeHandoff), { null })))
    check(h.latestHandoff() == null, "a Handoff the model composed is never resumed")
    h = h.after(Event.ToolStarted("w", Tools.WEATHER, "x")).after(Event.ToolFinished("w", Tools.WEATHER, "x", card = HandoffCard("fix", "r8", "t-8")))
    check(h.latestHandoff() == null, "nor one a tool other than T3's returned")
    h = h.after(Event.ToolStarted("s", Tools.START_T3, "x")).after(Event.ToolFinished("s", Tools.START_T3, "x", card = HandoffCard("fix", "r1", "t-1")))
    check(h.latestHandoff()?.second?.requestId == "r1", "start_t3_agent's is")
    check(Conversations.transcript(Conversations.entryRows(h)).latestHandoff()?.second?.requestId == "r1", "also once stored")

    // --- B-S7: a Slider's range must be drawable ---
    fun slider(range: String) = """{"messages":[{"createSurface":{"surfaceId":"sl","components":[{"id":"root","component":"Slider","value":{"@path":"/v"},$range}],"dataModel":{"v":1}}}]}"""
    check(uiError { ShowUi.plan(obj(slider(""""min":10,"max":0""")), { null }) }?.contains("must be below max") == true, "a reversed range is refused")
    check(uiError { ShowUi.plan(obj(slider(""""min":5,"max":5""")), { null }) }?.contains("must be below max") == true, "so is an empty one")
    check(uiError { ShowUi.plan(obj(slider(""""max":-3""")), { null }) }?.contains("must be below max") == true, "the default min (0) counts")
    check(uiError { ShowUi.plan(obj(slider(""""min":0,"max":1e999""")), { null }) } != null, "and a range that isn't finite")
    check(uiError { ShowUi.plan(obj(slider(""""min":0,"max":10""")), { null }) } == null, "a plain one is fine")

    // --- B-S8: another surface under a drawn one's ID is drawn anew ---
    val plain = Surface("x", components = listOf(obj("""{"id":"root","component":"Text","text":"a"}""")))
    check(Renderer.sync(plain, "e1", plain.copy(sendDataModel = true), "e1").map(::obj).map { it.keys - "version" }.take(2) == listOf(setOf("deleteSurface"), setOf("createSurface")), "sendDataModel changed: deleted and created")
    check(Renderer.sync(plain, "e1", plain, "e2").map(::obj).first().keys - "version" == setOf("deleteSurface"), "another entry's: too")
    check(Renderer.sync(plain, "e1", plain, "e1").isEmpty() && Renderer.sync(null, null, plain, "e1") == Renderer.create(plain), "the same is nothing, a new one is created")

    if (failed > 0) {
        println("$failed failed")
        exitProcess(1)
    }
    println("all passed")
}
