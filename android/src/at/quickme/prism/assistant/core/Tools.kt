// The assistant's tools as the model sees them: their names, a line for the
// transcript while each runs, and the parsing of their arguments (the model's
// JSON), with errors the model can act on. The tools themselves are
// assistant/PhoneTools.kt (Android) and assistant/T3Tools.kt.

package at.quickme.prism.assistant.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object Tools {
    const val WEATHER = "get_weather"
    const val FIND_CONTACTS = "find_contacts"
    const val CALL = "call_phone"
    const val SEND_SMS = "send_sms"
    const val READ_SMS = "read_sms"
    const val MATRIX_ROOMS = "matrix_rooms"
    const val MATRIX_READ = "matrix_read"
    const val MATRIX_SEND = "matrix_send"
    const val MATRIX_EDIT = "matrix_edit"
    const val ALARM = "set_alarm"
    const val TIMER = "set_timer"
    const val OPEN_APP = "open_app"
    const val OPEN_URL = "open_url"
    const val NAVIGATE = "navigate"
    const val MEDIA = "media_control"
    const val VOLUME = "set_volume"
    const val FLASHLIGHT = "flashlight"
    const val DND = "do_not_disturb"
    const val CALENDAR = "calendar_events"
    const val SCREENSHOT = "take_screenshot"
    const val START_T3 = "start_t3_agent"
    const val LIST_T3 = "list_t3_threads"
    const val SEND_T3 = "send_to_t3_thread"
    const val T3_PROJECTS = "list_t3_projects"
    const val SHOW_UI = "show_ui"

    /** What the transcript says while [tool] runs with [args]. */
    fun label(tool: String, args: JsonObject = JsonObject(emptyMap())): String {
        val a = Args(args)
        fun s(name: String) = a.optString(name)?.takeIf { it.isNotBlank() }
        return when (tool) {
            WEATHER -> s("place")?.let { "Checking the weather in $it" } ?: "Checking the weather here"
            FIND_CONTACTS -> s("query")?.let { "Looking up $it" } ?: "Looking up contacts"
            CALL -> s("to")?.let { "Calling $it" } ?: "Calling"
            SEND_SMS -> s("to")?.let { "Texting $it" } ?: "Sending a text"
            READ_SMS -> s("contact")?.let { "Reading texts with $it" } ?: "Reading recent texts"
            MATRIX_ROOMS -> "Looking through Matrix rooms"
            MATRIX_READ -> s("room")?.let { "Reading $it on Matrix" } ?: "Reading Matrix"
            MATRIX_SEND -> s("room")?.let { "Messaging $it on Matrix" } ?: "Sending on Matrix"
            MATRIX_EDIT -> "Editing the message"
            ALARM -> "Setting an alarm"
            TIMER -> "Starting a timer"
            OPEN_APP -> s("app")?.let { "Opening $it" } ?: "Opening the app"
            OPEN_URL -> "Opening the link"
            NAVIGATE -> s("destination")?.let { "Directions to $it" } ?: "Starting navigation"
            MEDIA -> "Controlling playback"
            VOLUME -> "Changing the volume"
            FLASHLIGHT -> "Switching the flashlight"
            DND -> "Switching Do Not Disturb"
            CALENDAR -> "Checking the calendar"
            SCREENSHOT -> "Looking at the screen"
            START_T3 -> "Handing this to a T3 agent"
            LIST_T3 -> "Looking at T3 threads"
            SEND_T3 -> "Sending to the T3 thread"
            T3_PROJECTS -> "Looking at the projects"
            SHOW_UI -> "Putting it on screen"
            else -> tool.replace('_', ' ')
        }
    }
}

/** A tool's arguments were unusable: [message] goes back to the model, which can correct them. */
class ToolArgumentException(message: String) : IllegalArgumentException(message)

/** A tool's JSON arguments, read with errors the model understands. */
class Args(val json: JsonObject) {
    private fun primitive(name: String): JsonPrimitive? = when (val v = json[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> v
        else -> throw ToolArgumentException("\"$name\" must be a single value")
    }

    fun optString(name: String): String? = primitive(name)?.contentOrNull

    fun string(name: String): String =
        optString(name)?.takeIf { it.isNotBlank() } ?: throw ToolArgumentException("\"$name\" is required")

    fun optInt(name: String): Int? {
        val p = primitive(name) ?: return null
        val n = p.longOrNull ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toLong() ?: p.contentOrNull?.trim()?.toLongOrNull()
            ?: throw ToolArgumentException("\"$name\" must be a whole number")
        if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) throw ToolArgumentException("\"$name\" is out of range")
        return n.toInt()
    }

    fun int(name: String): Int = optInt(name) ?: throw ToolArgumentException("\"$name\" is required")

    fun optBoolean(name: String): Boolean? {
        val p = primitive(name) ?: return null
        return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> throw ToolArgumentException("\"$name\" must be true or false")
        }
    }

    fun boolean(name: String): Boolean = optBoolean(name) ?: throw ToolArgumentException("\"$name\" is required")

    /** One of [allowed] (case-insensitive), or null when absent. */
    fun optChoice(name: String, allowed: Collection<String>): String? {
        val v = optString(name)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (v !in allowed) throw ToolArgumentException("\"$name\" must be one of ${allowed.joinToString(", ")}")
        return v
    }

    fun choice(name: String, allowed: Collection<String>): String =
        optChoice(name, allowed) ?: throw ToolArgumentException("\"$name\" is required (${allowed.joinToString(", ")})")

    fun optInts(name: String): List<Int> = when (val v = json[name]) {
        null, JsonNull -> emptyList()
        is JsonArray -> v.map {
            (it as? JsonPrimitive)?.let { p -> p.longOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull() }
                ?: throw ToolArgumentException("\"$name\" must be a list of numbers")
        }
        else -> throw ToolArgumentException("\"$name\" must be a list")
    }
}

/** Phone numbers as the model and the contacts give them. */
object PhoneNumbers {
    /** Digits and a leading + only; null when it isn't a dialable number. */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        val plus = t.startsWith("+") || t.startsWith("00")
        val digits = t.filter { it.isDigit() }.let { if (t.startsWith("00")) it.drop(2) else it }
        if (digits.length < 3 || digits.length > 15) return null
        if (t.any { !(it.isDigit() || it in " +-()./") }) return null
        return (if (plus) "+" else "") + digits
    }

    /** Whether two numbers are the same line: their last nine digits (national and international forms). */
    fun same(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }
        val y = b.filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        val n = minOf(9, x.length, y.length)
        return x.takeLast(n) == y.takeLast(n)
    }
}

/** Times of day for alarms: "7:30", "07:30", "7.30 pm", "19h", "7 am". */
object ClockTime {
    private val pattern = Regex("""^\s*(\d{1,2})(?:\s*[:.h]\s*(\d{2}))?\s*(?:h)?\s*(am|pm|a\.m\.|p\.m\.)?\s*$""", RegexOption.IGNORE_CASE)

    /** (hour 0–23, minute), or null. */
    fun parse(text: String): Pair<Int, Int>? {
        val m = pattern.matchEntire(text) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3].lowercase().replace(".", "")
        if (minute > 59) return null
        when (half) {
            "am" -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            "pm" -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            else -> if (hour > 23) return null
        }
        return hour to minute
    }

    fun format(hour: Int, minute: Int): String = "%d:%02d".format(hour, minute)
}

/** A duration for the transcript: "1 h 5 min", "90 s" → "1 min 30 s". */
fun formatDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" },
        s.takeIf { it > 0 || totalSeconds == 0 }?.let { "$it s" },
    ).joinToString(" ")
}
