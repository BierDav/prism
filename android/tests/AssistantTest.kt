// The assistant's pure logic on a plain JVM (src/at/quickme/prism/assistant/core):
// tool arguments, phone numbers and clock times, the weather decoding, the
// Matrix events and their decoding, the transcript as the harness reports
// its progress, a card's switch flipped by hand and what the model hears of
// it, and the Markdown the transcript shows.
//
//   java -cp <classes>:kotlin-stdlib.jar:kotlinx-serialization-*.jar AssistantTestKt

import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.ClockTime
import at.quickme.prism.assistant.core.Conversations
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.HandNotes
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.Markdown
import at.quickme.prism.assistant.core.Matrix
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.PhoneNumbers
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.Toggles
import at.quickme.prism.assistant.core.ToolArgumentException
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.Weather
import at.quickme.prism.assistant.core.WeatherCard
import at.quickme.prism.assistant.core.formatDuration
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

private fun args(json: String) = Args(Json.parseToJsonElement(json).jsonObject)

private fun refused(what: () -> Unit): String? = try {
    what(); null
} catch (e: ToolArgumentException) {
    e.message
}

private const val FORECAST = """{"latitude":48.19,"longitude":16.36,"timezone":"Europe/Vienna",
 "current":{"time":"2026-11-24T18:15","interval":900,"temperature_2m":1.5,"apparent_temperature":-2.1,"weather_code":0,"is_day":0,"wind_speed_10m":4.9,"precipitation_probability":0},
 "hourly":{"time":["2026-11-24T17:00","2026-11-24T18:00","2026-11-24T19:00","2026-11-24T20:00","2026-11-24T21:00"],
   "temperature_2m":[2.0,1.6,1.1,0.7,0.2],"weather_code":[1,0,0,3,61],"is_day":[0,0,0,0,0]},
 "daily":{"time":["2026-11-24","2026-11-25","2026-11-26"],"weather_code":[3,51,73],
   "temperature_2m_max":[4.1,2.6,0.4],"temperature_2m_min":[-0.5,-1.9,-0.4],"precipitation_probability_max":[0,5,99]}}"""

private const val SLIDING = """{"pos":"1","lists":{"rooms":{"count":3,"ops":[{"op":"SYNC","range":[0,2],"room_ids":["!team:example.org","!dm:example.org","!bob:example.org","!new:example.org"]}]}},
"rooms":{"!dm:example.org":{"name":"Anna","is_dm":true},
"!team:example.org":{"name":"Team","is_dm":false,"required_state":[{"type":"m.room.encryption","state_key":"","content":{"algorithm":"m.megolm.v1.aes-sha2"}}]},
"!bob:example.org":{"is_dm":true,"heroes":[{"user_id":"@bob:example.org","displayname":"Bob"}]},
"!new:example.org":{}}}"""

private const val SYNC = """{"next_batch":"s1",
 "account_data":{"events":[{"type":"m.direct","content":{"@anna:example.org":["!dm:example.org"]}}]},
 "rooms":{"join":{
   "!team:example.org":{"summary":{},"state":{"events":[{"type":"m.room.name","state_key":"","content":{"name":"Team"}},{"type":"m.room.encryption","state_key":"","content":{"algorithm":"m.megolm.v1.aes-sha2"}}]},
     "timeline":{"events":[{"type":"m.room.message","event_id":"${'$'}t1","sender":"@bob:example.org","origin_server_ts":1000,"content":{"msgtype":"m.text","body":"hi"}}]}},
   "!dm:example.org":{"summary":{"m.heroes":["@anna:example.org"]},"state":{"events":[{"type":"m.room.member","state_key":"@anna:example.org","content":{"membership":"join","displayname":"Anna"}}]},
     "timeline":{"events":[{"type":"m.room.message","event_id":"${'$'}d1","sender":"@anna:example.org","origin_server_ts":5000,"content":{"msgtype":"m.text","body":"see you"}}]}}
 }}}"""

private const val MESSAGES = """{"chunk":[
  {"type":"m.room.message","event_id":"${'$'}e3","sender":"@me:example.org","origin_server_ts":3000,"content":{"msgtype":"m.text","body":"* at 7","m.new_content":{"msgtype":"m.text","body":"at 7"},"m.relates_to":{"rel_type":"m.replace","event_id":"${'$'}e2"}}},
  {"type":"m.room.message","event_id":"${'$'}e2","sender":"@me:example.org","origin_server_ts":2000,"content":{"msgtype":"m.text","body":"at 6"}},
  {"type":"m.room.message","event_id":"${'$'}e1","sender":"@anna:example.org","origin_server_ts":1000,"content":{"msgtype":"m.text","body":"when?"}}
 ],"state":[{"type":"m.room.member","state_key":"@anna:example.org","content":{"displayname":"Anna"}}]}"""

fun main() {
    // --- Tool arguments ---
    val a = args("""{"to":"Anna","n":"5","b":"on","d":3.0,"days":[2,3],"mode":"Driving"}""")
    check(a.string("to") == "Anna" && a.optString("missing") == null, "strings")
    check(a.int("n") == 5 && a.int("d") == 3, "numbers as text and as whole floats")
    check(a.boolean("b"), "\"on\" is true")
    check(a.optInts("days") == listOf(2, 3), "a list of numbers")
    check(a.choice("mode", listOf("driving", "walking")) == "driving", "a choice, case-insensitive")
    check(refused { a.string("missing") } == "\"missing\" is required", "a missing argument says which")
    check(refused { args("""{"n":"x"}""").int("n") } != null, "a word isn't a number")
    check(refused { args("""{"n":2.5}""").int("n") } != null, "a fraction isn't a whole number")
    check(refused { a.choice("to", listOf("x", "y")) }?.contains("one of x, y") == true, "a choice outside the list")
    check(refused { args("""{"s":{"a":1}}""").string("s") } != null, "an object isn't a string")

    // --- Phone numbers, times, durations ---
    check(PhoneNumbers.normalize("+43 660 123 45-67") == "+436601234567", "an international number")
    check(PhoneNumbers.normalize("0043 660 1234567") == "+436601234567", "00 is +")
    check(PhoneNumbers.normalize("0660/1234567") == "06601234567", "a national number")
    check(PhoneNumbers.normalize("Anna") == null && PhoneNumbers.normalize("12") == null, "names and short codes aren't numbers")
    check(PhoneNumbers.same("+43 660 1234567", "0660 1234567"), "national and international forms are the same line")
    check(!PhoneNumbers.same("+43 660 1234567", "+43 660 7654321"), "different lines")
    check(ClockTime.parse("7:30") == (7 to 30) && ClockTime.parse("07.05") == (7 to 5), "24-hour times")
    check(ClockTime.parse("7 pm") == (19 to 0) && ClockTime.parse("12:15 am") == (0 to 15) && ClockTime.parse("12 pm") == (12 to 0), "am and pm")
    check(ClockTime.parse("19h") == (19 to 0), "19h")
    check(ClockTime.parse("25:00") == null && ClockTime.parse("7:60") == null && ClockTime.parse("13 pm") == null, "impossible times refused")
    check(formatDuration(3900) == "1 h 5 min" && formatDuration(90) == "1 min 30 s" && formatDuration(0) == "0 s", "durations")
    check(Tools.label(Tools.WEATHER, buildArgs("place" to "Graz")) == "Checking the weather in Graz", "a tool's line with its argument")
    check(Tools.label(Tools.WEATHER) == "Checking the weather here", "a tool's line without")

    // --- Weather ---
    check(Weather.geocodeUrl("Sankt Pölten").endsWith("name=Sankt+P%C3%B6lten"), "the place is encoded")
    val place = Weather.decodePlace("""{"results":[{"name":"Vienna","latitude":48.2,"longitude":16.37,"country":"Austria"}]}""")
    check(place == Weather.Place("Vienna, Austria", 48.2, 16.37), "a place found")
    check(Weather.decodePlace("""{"generationtime_ms":0.1}""") == null, "no place found")
    val w = Weather.decodeForecast(FORECAST, "Vienna")
    check(w.temperature == 1.5 && w.feelsLike == -2.1 && w.code == 0 && !w.day, "the weather now")
    check(w.high == 4.1 && w.low == -0.5 && w.precipitationChance == 0, "today's high and low")
    check(w.hours.map { it.time } == listOf("19:00", "20:00", "21:00"), "the hours after this one")
    check(w.days.map { it.day } == listOf("Today", "Wed", "Thu") && w.days[2].precipitationChance == 99, "the days ahead")
    val summary = Weather.summary(w)
    check(summary.startsWith("Vienna: 2°, clear, feels like -2°; today 0° to 4°") && "Thu 0°–0° snow (99% chance of precipitation)" in summary, "the summary for the model: $summary")
    check(Weather.condition(95) == "Thunderstorm" && Weather.sky(63) == "rain" && Weather.sky(73) == "snow", "codes")

    // --- Matrix ---
    val edit = Matrix.edit("\$e2", "at 7")
    check(edit["m.relates_to"]!!.jsonObject["rel_type"]!!.jsonPrimitive.content == "m.replace", "an edit replaces")
    check(edit["m.relates_to"]!!.jsonObject["event_id"]!!.jsonPrimitive.content == "\$e2", "the edit names its message")
    check(edit["m.new_content"]!!.jsonObject["body"]!!.jsonPrimitive.content == "at 7" && edit["body"]!!.jsonPrimitive.content == "* at 7", "the edit's new text, and the fallback")
    check(Matrix.text("hi") == JsonObject(mapOf("msgtype" to JsonPrimitive("m.text"), "body" to JsonPrimitive("hi"))), "a text message")
    check(Matrix.sendPath("!a:b.c", "t1") == "/rooms/%21a%3Ab.c/send/m.room.message/t1", "the send path is encoded")
    check(Matrix.encryptionPath("!a:b.c") == "/rooms/%21a%3Ab.c/state/m.room.encryption/", "the encryption state path")
    check(Matrix.base("https://matrix.example.org/") == "https://matrix.example.org/_matrix/client/v3", "the API base")
    val rooms = Matrix.decodeRooms(SYNC, "@me:example.org")
    check(rooms.map { it.name } == listOf("Anna", "Team"), "rooms by activity, a direct chat named by its member: ${rooms.map { it.name }}")
    check(rooms.first().direct && !rooms.last().direct, "m.direct marks direct chats")
    check(rooms.last().encrypted && !rooms.first().encrypted, "m.room.encryption marks an encrypted room")
    check(Matrix.find(rooms, "team").single().id == "!team:example.org", "a room by name")
    check(Matrix.find(rooms, "!dm:example.org").single().name == "Anna", "a room by ID")
    check(Matrix.find(rooms, "nobody").isEmpty(), "no room")
    val sliding = Matrix.decodeSlidingRooms(SLIDING)
    check(sliding.map { it.name } == listOf("Team", "Anna", "Bob", "!new:example.org"), "sliding sync rooms in the list's order, an unnamed one by its heroes, else by ID: ${sliding.map { it.name }}")
    check(sliding[0].encrypted && !sliding[1].encrypted && sliding[1].direct && !sliding[0].direct, "encryption from required_state, direct from is_dm")
    check(sliding.all { it.lastActive == 0L }, "no made-up activity times")
    check(Matrix.slidingSyncBody(300).toString().contains("[[0,299]]"), "the range covers the limit: ${Matrix.slidingSyncBody(300)}")
    val names = Matrix.decodeNames(MESSAGES)
    val msgs = Matrix.decodeMessages(MESSAGES, names)
    check(msgs.map { it.text } == listOf("when?", "at 7"), "messages oldest first, the edit in place: ${msgs.map { it.text }}")
    check(msgs[1].edited && !msgs[0].edited && msgs[0].senderName == "Anna" && msgs[1].senderName == "me", "who wrote what, and what was edited")
    check(Matrix.eventId("""{"event_id":"${'$'}x"}""") == "\$x", "the sent event's ID")

    // --- Transcript ---
    var t = Transcript()
    t = t.after(Event.Said("Weather?", listOf("photo.jpg")))
    t = t.after(Event.ToolStarted("c1", Tools.WEATHER, "Checking the weather here"))
    check(t.entries.last() is Entry.Step && (t.entries.last() as Entry.Step).running, "a tool shows while it runs")
    t = t.after(Event.ToolFinished("c1", Tools.WEATHER, "Vienna: 2°", card = w))
    check(t.entries.size == 2 && t.card(t.entries.last().id) == w, "its card takes its place")
    t = t.after(Event.ToolStarted("c2", Tools.CALL, "Calling Anna"))
    t = t.after(Event.ToolFinished("c2", Tools.CALL, "no permission", failed = true))
    val step = t.entries.last() as Entry.Step
    check(!step.running && step.failed && step.label == "Calling Anna" && step.detail == "no permission", "a failed tool keeps its line, and says why")
    t = t.after(Event.Answer("  It's **2°**.  "))
    check((t.entries.last() as Entry.Answer).text == "It's **2°**.", "the answer, trimmed")
    t = t.after(Event.Answer(" "))
    check(t.entries.size == 4, "an empty answer adds nothing")
    val ids = t.entries.map { it.id }
    check(ids.toSet().size == ids.size, "entries have their own IDs: $ids")
    val sent = MessageCard(MessageCard.MATRIX, "Anna", "!dm:example.org", "at 6", eventId = "\$e2")
    t = t.after(Event.ToolStarted("c3", Tools.MATRIX_SEND, "Messaging Anna on Matrix"))
    t = t.after(Event.ToolFinished("c3", Tools.MATRIX_SEND, "sent", card = sent))
    check(sent.editable && !sent.copy(channel = MessageCard.SMS).editable, "a Matrix message is editable, an SMS isn't")
    t = t.withCard("tool:c3", sent.copy(text = "at 7", edited = true))
    check((t.card(t.entries.last().id) as MessageCard).let { it.text == "at 7" && it.edited }, "an edit shows on its card")
    check(t.latestHandoff() == null, "no hand-off yet")
    t = t.after(Event.ToolStarted("c4", Tools.START_T3, "Handing this to a T3 agent"))
    t = t.after(Event.ToolFinished("c4", Tools.START_T3, "started", card = HandoffCard("fix it", "r1")))
    check(t.latestHandoff()?.second?.requestId == "r1", "the newest hand-off")
    t = t.after(Event.ToolStarted("c5", Tools.WEATHER, "…"))
    check(t.settled().entries.none { it is Entry.Step && it.running }, "settled stops what still ran")
    val json = Json.encodeToString(Transcript.serializer(), t)
    val back = Json.decodeFromString(Transcript.serializer(), json)
    check(back == t, "the transcript survives JSON (the resume window)")
    check(Json.decodeFromString(Card.serializer(), Json.encodeToString(Card.serializer(), w)) is WeatherCard, "cards are polymorphic")

    // --- A card's switch flipped by hand (Toggles, HandNotes) ---
    val dndOn = SettingCard("dnd", "Do Not Disturb", "On", true)
    val torchOn = SettingCard("flashlight", "Flashlight", "On", true)
    check(Toggles.call(dndOn, false) == (Tools.DND to buildBool("on", false)), "Do Not Disturb's switch runs do_not_disturb with on=false")
    check(Toggles.call(torchOn, false)?.let { (tool, a) -> tool == Tools.FLASHLIGHT && !Args(a).boolean("on") } == true, "the flashlight's runs flashlight, as the tool reads it")
    check(Toggles.call(SettingCard("media", "Media", "Paused", false), true)?.let { (tool, a) -> tool == Tools.MEDIA && Args(a).string("action") == "play" } == true, "media's plays")
    val volume = SettingCard("volume", "Ring volume", "40%", true)
    check(Toggles.stream(volume) == "ring", "a volume card's stream from its title")
    check(
        Toggles.call(volume, false)?.let { (tool, a) -> tool == Tools.VOLUME && Args(a).choice("stream", listOf("media", "ring", "notification", "alarm", "call")) == "ring" && Args(a).choice("change", listOf("up", "down", "mute", "unmute")) == "mute" } == true,
        "a volume's switch mutes, as set_volume reads it",
    )
    check(Toggles.call(volume.copy(on = false), true)?.second?.let { Args(it).string("change") } == "unmute", "and unmutes")
    check(Toggles.call(SettingCard("app", "Maps", "Opened"), true) == null, "an opened app has no switch")
    check(Toggles.call(SettingCard("volume", "Odd volume", "3%", true), false) == null, "nor a volume card it can't name")
    check(Toggles.switchable(dndOn) && !Toggles.switchable(SettingCard("link", "example.org", "https://example.org")) && !Toggles.switchable(w), "which cards are switchable")
    var toggled = listOf(
        Event.Said("do not disturb please"),
        Event.ToolStarted("d1", Tools.DND, "Switching Do Not Disturb"),
        Event.ToolFinished("d1", Tools.DND, "Do Not Disturb is on.", card = dndOn),
        Event.Answer("Do Not Disturb is on."),
    ).fold(Transcript()) { acc, e -> acc.after(e) }
    // What AssistantSession.toggle does with what the tool reports.
    val dndOff = SettingCard("dnd", "Do Not Disturb", "Off", false)
    toggled = toggled.withCard("tool:d1", dndOff)
    check((toggled.card("tool:d1") as SettingCard).on == false, "the card shows the new state")
    check(toggled.entries.size == 3 && toggled.entries.last() is Entry.Answer, "and stays where it was")
    val kept = Json.decodeFromString(Transcript.serializer(), Json.encodeToString(Transcript.serializer(), toggled))
    check((kept.card("tool:d1") as SettingCard).on == false, "the new state survives the resume window")
    val stored = Conversations.transcript(Conversations.entryRows(toggled))
    check((stored.card("tool:d1") as SettingCard).let { it.on == false && it.subtitle == "Off" }, "and the stored conversation (its rows)")
    val said = Toggles.note(dndOn, dndOff, "Do Not Disturb is off.")
    check(said == "The owner switched Do Not Disturb off by hand on its card (it was on); the phone says: \"Do Not Disturb is off.\".", "the note says what changed: $said")
    val notes = HandNotes()
    check(notes.isEmpty && HandNotes.framed(notes.take()) == null, "no notes, nothing for the model")
    notes.add("The owner edited the Matrix message to Anna by hand.")
    notes.add(said)
    val next = notes.take()
    check(next.startsWith("The owner edited") && next.endsWith("is off.\".") && notes.isEmpty, "the next turn takes every note, in order, once")
    check(HandNotes.framed(next) == "[Since the last turn: $next]", "the model reads them ahead of the owner's words")
    check(HandNotes.framed(notes.take()) == null, "and the turn after hears nothing again")

    // --- Markdown ---
    val md = Markdown.parse("# Title\nSome **bold** and *it* and `x_y` here.\n\n- one\n  - two\n3. three\n```\ncode *raw*\n```\nsnake_case_name stays")
    check(md[0] == Markdown.Block.Heading(1, listOf(Markdown.Span("Title"))), "a heading")
    val p = md[1] as Markdown.Block.Paragraph
    check(p.spans.any { it.bold && it.text == "bold" } && p.spans.any { it.italic && it.text == "it" } && p.spans.any { it.code && it.text == "x_y" }, "bold, italic, code")
    check((md[2] as Markdown.Block.Item).marker == "•" && (md[3] as Markdown.Block.Item).depth == 1, "bullets, nested")
    check((md[4] as Markdown.Block.Item).marker == "3.", "numbered")
    check(md[5] == Markdown.Block.Code("code *raw*"), "fenced code stays raw")
    check((md[6] as Markdown.Block.Paragraph).spans.joinToString("") { it.text } == "snake_case_name stays" && (md[6] as Markdown.Block.Paragraph).spans.none { it.italic }, "underscores inside words")
    val link = Markdown.inline("see [docs](https://example.org/a) and [x](javascript:alert)")
    check(link.any { it.link == "https://example.org/a" && it.text == "docs" }, "a link")
    check(link.none { it.link?.startsWith("javascript") == true }, "only web links are links")
    check(Markdown.inline("2 * 3 * 4") == listOf(Markdown.Span("2 * 3 * 4")), "a lone star is a star")
    check(Markdown.inline("**open") == listOf(Markdown.Span("**open")), "an unclosed marker stays")
    check(Markdown.plain("**a** and *b*\n- c") == "a and b\n• c", "plain text")

    println(if (failed == 0) "all passed" else "$failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}

private fun buildBool(name: String, value: Boolean) = JsonObject(mapOf(name to JsonPrimitive(value)))

private fun buildArgs(vararg pairs: Pair<String, String>) =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
