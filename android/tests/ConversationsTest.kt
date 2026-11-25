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
import kotlinx.serialization.json.Json
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

    // --- The model's history ---
    val message = """{"type":"user","parts":[{"type":"text","text":"${"x".repeat(50_000)}"}]}"""
    val history = Conversations.historyRows(listOf("""{"type":"assistant","text":"hi"}""", message, "not json"))
    check(history.size == 2, "a message that isn't JSON is left out")
    check(history[0] == """{"type":"assistant","text":"hi"}""", "a small message stays as it is")
    check(bytes(history[1]) <= Conversations.ROW_BYTES, "a long message fits its row")
    check(Json.parseToJsonElement(history[1]).jsonObject["type"]!!.jsonPrimitive.content == "user", "and keeps its shape")

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
