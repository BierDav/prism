// The assistant's conversations as Rocky stores them (assistant/core/Conversations.kt):
// a transcript to rows and back, rows that always fit, titles, and the
// history's messages fitted.
//
//   java -cp <classes>:kotlin-stdlib.jar:kotlinx-serialization-*.jar ConversationsTestKt

import at.quickme.prism.assistant.core.Conversations
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.ScreenshotCard
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadMessage
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import kotlin.system.exitProcess
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

private fun bytes(s: String) = s.encodeToByteArray().size

fun main() {
    // --- A transcript to rows and back ---
    var t = Transcript()
    t = t.after(Event.Said("Weather in  Vienna\nand a screenshot?", listOf("photo.jpg")))
    t = t.after(Event.ToolStarted("c1", Tools.SCREENSHOT, "Looking at the screen"))
    t = t.after(Event.ToolFinished("c1", Tools.SCREENSHOT, "taken", card = ScreenshotCard("Screenshot 18:15")))
    t = t.after(Event.Answer("It's **2°** and clear."))
    t = t.after(Event.ToolStarted("c2", Tools.START_T3, "Handing this to a T3 agent"))
    t = t.after(Event.ToolFinished("c2", Tools.START_T3, "started", card = HandoffCard("fix it", "r1", "t-1")))
    val rows = Conversations.entryRows(t)
    check(rows.map { it.first } == t.entries.map { it.id }, "a row per entry, by its ID: ${rows.map { it.first }}")
    check(rows.all { (_, body) -> "photo.jpg" !in body || "\"attachments\":[\"photo.jpg\"]" in body }, "an attachment is its name only")
    val back = Conversations.transcript(rows)
    check(back.entries == t.entries, "the entries come back as they were")
    val more = back.after(Event.Answer("And tomorrow?"))
    check(more.entries.map { it.id }.toSet().size == more.entries.size, "new entries after a reload get IDs no stored one has")
    check(Conversations.transcript(rows + ("x" to "{\"type\":\"from-the-future\"}")).entries == t.entries, "an entry a newer app wrote is left out")
    val running = t.after(Event.ToolStarted("c3", Tools.WEATHER, "Checking the weather"))
    val reloaded = Conversations.transcript(Conversations.entryRows(running)).entries.last() as Entry.Step
    check(!reloaded.running && reloaded.failed && reloaded.detail == "Stopped", "a tool that still ran is stopped on reload")
    val again = Conversations.entryRows(back)
    check(again == rows, "the same transcript gives the same rows (an unchanged save writes nothing)")

    // --- Rows always fit ---
    val long = "Ein längerer Absatz mit Umlauten — äöü 🌧️. ".repeat(4000)
    val answer = Transcript().after(Event.Answer(long))
    val (_, body) = Conversations.entryRows(answer).single()
    check(bytes(body) <= Conversations.ROW_BYTES, "a long answer fits its row (${bytes(body)} bytes)")
    val cutAnswer = Conversations.transcript(listOf("e0" to body)).entries.single() as Entry.Answer
    check(cutAnswer.text.endsWith(Conversations.CUT) && long.startsWith(cutAnswer.text.removeSuffix(Conversations.CUT)), "it is cut at its end, and says so")
    val thread = ThreadCard("matrix", "Team", (1..3000).map { ThreadMessage("Anna", "Message number $it with some words", it.toLong()) })
    val threadRows = Conversations.entryRows(
        Transcript().after(Event.ToolStarted("c1", Tools.MATRIX_READ, "Reading")).after(Event.ToolFinished("c1", Tools.MATRIX_READ, "read", card = thread)),
    )
    check(bytes(threadRows.single().second) <= Conversations.ROW_BYTES, "a thread of 3000 messages fits its row")
    val shown = Conversations.transcript(threadRows).entries.single() as Entry.Shown
    val kept = (shown.card as ThreadCard).messages
    check(kept.isNotEmpty() && kept.first().text == "Message number 1 with some words", "the thread keeps its first messages (${kept.size})")
    check(Conversations.fit(Json.parseToJsonElement(Conversations.fit(Json.parseToJsonElement(body)))) == body, "fitting a fitted row changes nothing")

    // --- The model's history (Koog's JSON, as Harness.encodeEach writes it) ---
    val koog = "ai.koog.prompt.message."
    fun textPart(t: String) = """{"type":"${koog}MessagePart.Text","text":${Json.encodeToString(String.serializer(), t)}}"""
    fun user(vararg parts: String) = """{"type":"${koog}Message.User","parts":[${parts.joinToString(",")}],"metaInfo":{"timestamp":"2026-11-24T18:15:00Z"}}"""
    fun assistant(vararg parts: String) = """{"type":"${koog}Message.Assistant","parts":[${parts.joinToString(",")}],"metaInfo":{"timestamp":"2026-11-24T18:15:00Z"}}"""
    fun call(id: String, tool: String, args: String) =
        """{"type":"${koog}MessagePart.Tool.Call","id":"$id","tool":"$tool","args":${Json.encodeToString(String.serializer(), args)}}"""
    fun result(id: String, tool: String, t: String) = """{"type":"${koog}MessagePart.Tool.Result","id":"$id","tool":"$tool","parts":[${textPart(t)}]}"""
    fun parts(row: String) = Json.parseToJsonElement(row).jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
    fun type(p: kotlinx.serialization.json.JsonObject) = p["type"]!!.jsonPrimitive.content.removePrefix(koog)

    val small = user(textPart("hi"))
    val history = Conversations.historyRows(listOf(small, "not json"))
    check(history == listOf(small), "a small message stays as it is, one that isn't JSON is left out")

    // A long text is cut; the tool call's arguments in the same message never are.
    val args = """{"to":"Anna","text":"${"a".repeat(5000)}"}"""
    val longRow = Conversations.historyRows(listOf(assistant(textPart("x".repeat(40_000)), call("c1", "send_sms", args)))).single()
    check(bytes(longRow) <= Conversations.ROW_BYTES, "a long message fits its row")
    val keptParts = parts(longRow)
    check(keptParts[0]["text"]!!.jsonPrimitive.content.endsWith(Conversations.CUT), "its text is cut")
    check(type(keptParts[1]) == "MessagePart.Tool.Call" && keptParts[1]["args"]!!.jsonPrimitive.content == args, "its call's arguments stay whole")

    // Arguments too long for any row: the call and its result become text, in pairs.
    val huge = """{"text":"${"b".repeat(40_000)}"}"""
    val messageRows = Conversations.historyRows(listOf(
        user(textPart("Save this note")),
        assistant(call("c2", "save_note", huge), call("c3", "get_weather", "{}")),
        user(result("c2", "save_note", "saved"), result("c3", "get_weather", "2°")),
        assistant(textPart("Done.")),
    ))
    check(messageRows.all { bytes(it) <= Conversations.ROW_BYTES }, "every row fits")
    val calls = parts(messageRows[1])
    check(calls.none { type(it) == "MessagePart.Tool.Call" } && calls.all { type(it) == "MessagePart.Text" }, "the calls of that message went as text")
    check("[Called save_note;" in calls[0]["text"]!!.jsonPrimitive.content, "saying what was called")
    val results = parts(messageRows[2])
    check(results.none { type(it) == "MessagePart.Tool.Result" }, "their results went as text too: no result without its call")
    check(results[0]["text"]!!.jsonPrimitive.content == "[save_note answered: saved]", "with what the tool said")
    check(messageRows[3] == assistant(textPart("Done.")), "what follows stays as it is")
    check(messageRows.all { r -> runCatching { Json.parseToJsonElement(r) }.isSuccess }, "every row is valid JSON")

    // --- Titles and IDs ---
    check(Conversations.title(t) == "Weather in Vienna and a screenshot?", "the title is the first request on one line")
    val wordy = Transcript().after(Event.Said("Please look into why the nightly backup on david-mm keeps failing since Tuesday"))
    val title = Conversations.title(wordy)!!
    check(title.length <= Conversations.TITLE_CHARS && title.endsWith("…") && !title.contains("Tuesd"), "a long request is cut at a word: $title")
    check(Conversations.title(Transcript().after(Event.Said("", listOf("scan.pdf")))) == "scan.pdf", "attachments alone name it")
    check(Conversations.title(Transcript()) == null, "nothing said, no title")
    val id = Conversations.newId(1_795_540_500_000L, kotlin.random.Random(1))
    check(Regex("c-1795540500000-[a-z0-9]{8}").matches(id), "an ID as Rocky takes it: $id")

    println(if (failed == 0) "all passed" else "$failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
