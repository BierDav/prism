// A conversation as Rocky stores it (rocky-prism/src/assistant.rs): one row
// per transcript entry and one per message of the model's history, each the
// JSON the app reads back, at most ROW_BYTES. Images and files never get
// here: an entry names what was attached, and the harness replaces them in
// the history by their names (Harness.encodeEach). Too long a text is cut,
// longest first, until its row fits. No Android here (android/tests).

package at.quickme.prism.assistant.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object Conversations {
    /** The most one row's JSON holds, in UTF-8 bytes (rocky-prism's ASSISTANT_MAX_BODY_BYTES). */
    const val ROW_BYTES = 16 * 1024

    /** What a cut text ends with. */
    const val CUT = " […]"

    /** A title's length at most, in characters. */
    const val TITLE_CHARS = 60

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** A new conversation's ID: when it began and a random part. */
    fun newId(now: Long = System.currentTimeMillis(), random: kotlin.random.Random = kotlin.random.Random.Default): String =
        "c-$now-" + (1..8).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")

    /** The transcript's rows: each entry's ID and JSON, in order. */
    fun entryRows(t: Transcript): List<Pair<String, String>> =
        t.entries.map { e -> e.id to fit(json.encodeToJsonElement(Entry.serializer(), e)) }

    /**
     * The transcript from its rows: entries that don't decode (written by a
     * newer app) are left out, tools that were still running are stopped,
     * and new entries get IDs no stored one has.
     */
    fun transcript(rows: List<Pair<String, String>>): Transcript {
        val entries = rows.mapNotNull { (_, body) -> runCatching { json.decodeFromString(Entry.serializer(), body) }.getOrNull() }
        return Transcript(entries, next = rows.size).settled()
    }

    /**
     * The model's history as rows: each message's JSON ([messages], Koog's,
     * from Harness.encodeEach), fitted. Only free text (`text`) is cut, never
     * a tool call's arguments (JSON in a string, which the gateway gets
     * again): a message still too long has its tool calls replaced by a line
     * saying what was called, and their results, in the messages after it,
     * by text, so every call keeps its result and the model gets valid JSON.
     * Results too many or too long to keep as they are go as text the same
     * way, and so do their calls, in the message before: never a call
     * without its result (Harness.sanitize would drop the rest after it).
     */
    fun historyRows(messages: List<String>): List<String> {
        val parsed = messages.mapNotNull { m -> runCatching { Json.parseToJsonElement(m) as? JsonObject }.getOrNull() }
        val tooLong = parsed.filter { fitText(it) == null }.flatMapTo(HashSet()) { ids(it, RESULT) }
        val summarized = HashMap<String, String>()
        return parsed.map { m ->
            val pairs = withParts(m) { p -> if (p.str("id") in tooLong) callAsText(p, summarized) else p }
            val message = withParts(pairs) { p -> resultAsText(p, summarized) }
            fitText(message)
                ?: fitText(withParts(message) { p -> callAsText(p, summarized) })
                ?: withParts(message) { p -> callAsText(p, summarized) }.let { m2 ->
                    JsonObject(m2 + ("parts" to JsonArray(listOf(text("[A message too long to keep.]"))))).toString()
                }
        }
    }

    /** The IDs of [m]'s parts of [type]. */
    private fun ids(m: JsonObject, type: String): List<String> =
        (m["parts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") == type }?.str("id") }

    private const val KOOG = "ai.koog.prompt.message.MessagePart."
    private const val TEXT = KOOG + "Text"
    private const val CALL = KOOG + "Tool.Call"
    private const val RESULT = KOOG + "Tool.Result"

    private fun text(s: String) = JsonObject(mapOf("type" to JsonPrimitive(TEXT), "text" to JsonPrimitive(s)))

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun withParts(m: JsonObject, map: (JsonObject) -> JsonObject): JsonObject {
        val parts = m["parts"] as? JsonArray ?: return m
        return JsonObject(m + ("parts" to JsonArray(parts.map { if (it is JsonObject) map(it) else it })))
    }

    /** A tool call as a line of text; its result then goes as text too. */
    private fun callAsText(p: JsonObject, summarized: MutableMap<String, String>): JsonObject {
        if (p.str("type") != CALL) return p
        val tool = p.str("tool").orEmpty()
        p.str("id")?.let { summarized[it] = tool }
        return text("[Called $tool; too long to keep as a call.]")
    }

    /** The result of a call that went as text, as text. */
    private fun resultAsText(p: JsonObject, summarized: Map<String, String>): JsonObject {
        if (p.str("type") != RESULT) return p
        val tool = summarized[p.str("id") ?: return p] ?: return p
        val said = (p["parts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
        return text("[$tool answered: $said]")
    }

    /** [m] as at most [ROW_BYTES], cutting `text` values only; null when that isn't enough. */
    private fun fitText(m: JsonObject): String? {
        val whole = m.toString()
        if (whole.encodeToByteArray().size <= ROW_BYTES) return whole
        for (chars in intArrayOf(8000, 4000, 2000, 1000, 400, 120)) {
            val cut = cutTexts(m, chars).toString()
            if (cut.encodeToByteArray().size <= ROW_BYTES) return cut
        }
        return null
    }

    private fun cutTexts(e: JsonElement, chars: Int): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (k == "text" && s != null && s.length > chars) JsonPrimitive(s.take(chars - CUT.length) + CUT) else cutTexts(v, chars)
        })
        is JsonArray -> JsonArray(e.map { cutTexts(it, chars) })
        is JsonPrimitive -> e
    }

    /**
     * A conversation's title: its first request on one line, cut at a word
     * to [TITLE_CHARS], or what it attached; null before the owner said anything.
     */
    fun title(t: Transcript): String? {
        val first = t.entries.firstOrNull { it is Entry.User } as? Entry.User ?: return null
        val text = first.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isEmpty()) return first.attachments.firstOrNull()
        if (text.length <= TITLE_CHARS) return text
        val cut = text.take(TITLE_CHARS - 1)
        val word = cut.lastIndexOf(' ').takeIf { it >= TITLE_CHARS / 2 } ?: cut.length
        return cut.take(word).trimEnd(' ', ',', '.', ';', ':') + "…"
    }

    /**
     * [e] as JSON of at most [ROW_BYTES]: strings longer than a limit are
     * cut, the limit lowered until it fits, then lists shortened (a thread
     * card with hundreds of messages keeps its first ones).
     */
    fun fit(e: JsonElement, max: Int = ROW_BYTES): String {
        val whole = e.toString()
        if (whole.encodeToByteArray().size <= max) return whole
        for (chars in intArrayOf(8000, 4000, 2000, 1000, 400, 120)) {
            val text = cut(e, chars, Int.MAX_VALUE).toString()
            if (text.encodeToByteArray().size <= max) return text
        }
        for (items in intArrayOf(50, 20, 5, 1, 0)) {
            val text = cut(e, 120, items).toString()
            if (text.encodeToByteArray().size <= max) return text
        }
        error("a row that can't be made to fit")
    }

    private fun cut(e: JsonElement, chars: Int, items: Int): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> cut(v, chars, items) })
        is JsonArray -> JsonArray(e.take(items).map { cut(it, chars, items) })
        is JsonPrimitive -> {
            val s = if (e.isString) e.contentOrNull else null
            if (s != null && s.length > chars) JsonPrimitive(s.take(chars - CUT.length) + CUT) else e
        }
    }
}
