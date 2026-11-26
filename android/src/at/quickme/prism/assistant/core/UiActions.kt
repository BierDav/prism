// What the owner does on the transcript's surfaces, and what the model sends
// with show_ui. A component's action (A2UI's event) either stays on the phone
// — the buttons of a card a tool made: call back, a switch, open the event,
// … — and the model hears of it in its next turn as a note (HandNotes), or it
// is the next turn itself: the owner picked a contact on a surface the model
// composed, and the model gets the action as A2UI JSON while the transcript
// shows what the owner did ("Picked: Anna (mobile)"). Nothing the model
// composes makes the phone act: an action on its surface is only ever a turn,
// after which the model has to call a real tool; show_ui can't change a
// tool's card. No Android here (tested
// on a plain JVM: android/tests/A2uiTest.kt).

package at.quickme.prism.assistant.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object UiActions {
    // The cards' own events (assistant/a2ui/PrismCatalogUi.kt dispatches them).
    const val CALL = "call"
    const val MESSAGE = "message"
    const val REPLY = "reply"
    /** The alarms or timers in the Clock app: no app API names one alarm or timer and says it changed. */
    const val OPEN_ALARMS = "open_alarms"
    const val OPEN_TIMERS = "open_timers"
    const val TOGGLE = "toggle"
    const val EDIT = "edit_message"
    const val OPEN_EVENT = "open_event"
    const val OPEN_T3 = "open_t3"

    /** A card's event that becomes a turn: a conversation of a list to read. */
    const val OPEN_THREAD = "open_thread"

    /** The events the phone handles itself, when a card's component sends them. */
    val LOCAL = setOf(CALL, MESSAGE, REPLY, OPEN_ALARMS, OPEN_TIMERS, TOGGLE, EDIT, OPEN_EVENT, OPEN_T3)

    sealed interface Route {
        /** On the phone: [name] with its [context], for the card it came from ([card], when it is the surface's). */
        data class Local(val name: String, val context: JsonObject, val card: Card?) : Route

        /** The next turn: [shown] in the transcript, [prompt] for the model. */
        data class Turn(val shown: String, val prompt: String) : Route
    }

    /**
     * Where [a] goes: an event of a card's own component on a [trusted]
     * surface (a tool made it) stays on the phone; anything else, every
     * action on a surface the model made and a card's open_thread, is a turn.
     */
    fun route(s: Surface, a: UiAction, trusted: Boolean): Route {
        val type = s.type(a.sourceComponentId)
        val fromCard = type != null && PrismCatalog.all[type]?.card != null
        if (trusted && fromCard && a.name in LOCAL) {
            val card = if (a.sourceComponentId == Surface.ROOT) CardSurfaces.card(s) else null
            return Route.Local(a.name, a.context, card)
        }
        return Route.Turn(shown(a), prompt(a))
    }

    /** What the transcript shows the owner did: the event's userMessage, its label, or its name and values. */
    fun shown(a: UiAction): String {
        a.userMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        a.context.text("label")?.let { return it }
        val values = a.context.values.mapNotNull { v -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        return words(a.name) + if (values.isEmpty()) "" else ": " + values.joinToString(", ")
    }

    /** The action as the model reads it: A2UI v1.0's action message, and the surface's data when it sends it. */
    fun prompt(a: UiAction): String = buildString {
        append("[The owner acted on your surface \"${a.surfaceId}\"; A2UI action: ")
        append(a.toJson().toString())
        a.dataModel?.let { append(" Its data model: ").append(it.toString()) }
        append("]")
    }

    /** "pick_contact" or "pickContact" → "Pick contact". */
    fun words(name: String): String {
        val w = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase()
        return w.replaceFirstChar { it.uppercase() }.ifEmpty { "Tapped" }
    }

    /**
     * What the model hears of local action [r] in its next turn, with what
     * the phone said about it ([said]).
     */
    fun note(r: Route.Local, said: String = ""): String {
        val c = r.context
        val who = c.text("name") ?: c.text("to") ?: c.text("title")
        val number = c.text("number") ?: c.text("address")
        val whom = listOfNotNull(who, number?.takeIf { it != who }).joinToString(" ").ifEmpty { "someone" }
        val what = when (r.name) {
            CALL -> "called $whom from a card"
            MESSAGE -> "opened a new text to $whom from a card"
            REPLY -> "opened a reply to $whom from the conversation's card"
            OPEN_ALARMS -> "opened the Clock app's alarms from the card of the alarm${alarm(r.card)} (what they changed there, if anything, the phone can't tell)"
            OPEN_TIMERS -> "opened the Clock app's timers from the card of the timer${(r.card as? TimerCard)?.label?.takeIf { it.isNotEmpty() }?.let { " \"$it\"" } ?: ""} (what they changed there, if anything, the phone can't tell)"
            OPEN_EVENT -> "opened the calendar event${c.text("title")?.let { " \"$it\"" } ?: ""}"
            OPEN_T3 -> "opened the T3 agent${c.text("agentId")?.let { " $it" } ?: ""} in T3"
            else -> words(r.name).lowercase() + " on a card"
        }
        return "The owner $what by hand" + (said.trim().takeIf { it.isNotEmpty() }?.let { "; the phone says: \"$it\"" } ?: "") + "."
    }

    private fun alarm(card: Card?) = (card as? AlarmCard)?.let { " for ${ClockTime.format(it.hour, it.minute)}" } ?: ""

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

/** What one show_ui call does: the surface it creates, the ones it changes, those it deletes. */
data class UiChange(
    val created: Surface? = null,
    val updated: List<Surface> = emptyList(),
    val deleted: List<String> = emptyList(),
) {
    /** What the model is told it did. */
    fun summary(): String = listOfNotNull(
        created?.let { "Shown surface \"${it.surfaceId}\" (${it.components.size} components); the owner's actions on it come back as your next input." },
        updated.takeIf { it.isNotEmpty() }?.let { u -> "Updated ${u.joinToString { "\"${it.surfaceId}\"" }}." },
        deleted.takeIf { it.isNotEmpty() }?.let { d -> "Removed ${d.joinToString { "\"$it\"" }}." },
    ).joinToString(" ").ifEmpty { "Nothing changed." }
}

/** show_ui's arguments: A2UI v1.0 messages, checked against the catalog. */
object ShowUi {
    /** The most a surface's JSON may be, in UTF-8 bytes. */
    const val MAX_BYTES = 48 * 1024
    const val MAX_ID = 128

    /**
     * The change [args] ask for (`messages`: A2UI v1.0 messages) given the
     * surfaces the model already shows ([existing], by surfaceId); [taken]
     * says which IDs a tool's cards have, which it may neither create nor
     * change. Throws [UiError] with everything that is wrong, for the model
     * to fix.
     */
    fun plan(
        args: JsonObject,
        existing: (String) -> Surface?,
        newId: () -> String = { "s" + System.currentTimeMillis().toString(36) },
        taken: (String) -> Boolean = { false },
    ): UiChange {
        val list: List<JsonElement> = when (val m = args["messages"]) {
            is JsonArray -> m
            is JsonObject -> listOf(m)
            null -> if (args.keys.any { it in OPS }) listOf(args) else throw UiError("\"messages\" is required: a list of A2UI v1.0 messages such as {\"createSurface\": {\"surfaceId\": …, \"components\": […], \"dataModel\": {…}}}")
            else -> throw UiError("\"messages\" must be a list of A2UI messages")
        }
        if (list.isEmpty()) throw UiError("\"messages\" is empty")
        val errors = ArrayList<String>()
        var created: Surface? = null
        val updated = LinkedHashMap<String, Surface>()
        val deleted = ArrayList<String>()
        for ((i, e) in list.withIndex()) {
            val m = try {
                UiMessage.parse(withId(e, newId))
            } catch (x: UiError) {
                errors += "messages[$i]: ${x.message}"; continue
            }
            val id = m.surfaceId
            when {
                // Each of a surface's stored rows repeats its ID (Conversations).
                id.length > MAX_ID -> errors += "messages[$i]: a surfaceId has at most $MAX_ID characters"
                taken(id) || id.startsWith(Transcript.TOOL_SURFACE) -> errors += "messages[$i]: \"$id\" is a tool's card; show_ui can't create, change or delete it (use a surfaceId of your own)"
                m is UiMessage.Create -> when {
                    created != null -> errors += "messages[$i]: one createSurface per call"
                    existing(id) != null && id !in deleted -> errors += "messages[$i]: surface \"$id\" is already shown; change it with updateComponents or updateDataModel"
                    else -> created = m.surface
                }
                created?.surfaceId == id -> created = if (m is UiMessage.Delete) null else safely(errors, i) { created!!.after(m) } ?: created
                m is UiMessage.Delete -> if (existing(id) == null) errors += "messages[$i]: no surface \"$id\" to delete" else { deleted += id; updated.remove(id) }
                else -> {
                    val base = updated[id] ?: existing(id)
                    if (base == null) errors += "messages[$i]: no surface \"$id\"; create it first with createSurface"
                    else safely(errors, i) { base.after(m) }?.let { updated[id] = it }
                }
            }
        }
        for (s in listOfNotNull(created) + updated.values) {
            PrismCatalog.validate(s).forEach { errors += "surface \"${s.surfaceId}\": $it" }
            val size = UiMessage.encode(UiMessage.Create(s)).toString().encodeToByteArray().size
            if (size > MAX_BYTES) errors += "surface \"${s.surfaceId}\" is too large ($size bytes, at most $MAX_BYTES)"
        }
        if (errors.isNotEmpty()) throw UiError("The surface wasn't shown:\n- " + errors.take(20).joinToString("\n- "))
        return UiChange(created, updated.values.toList(), deleted)
    }

    private val OPS = setOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface")

    /** A createSurface without a surfaceId gets one. */
    private fun withId(e: JsonElement, newId: () -> String): JsonElement {
        val o = e as? JsonObject ?: return e
        val body = o["createSurface"] as? JsonObject ?: return e
        if (body.str("surfaceId")?.isNotBlank() == true) return e
        return JsonObject(o + ("createSurface" to JsonObject(body + ("surfaceId" to JsonPrimitive(newId())))))
    }

    private fun <T> safely(errors: MutableList<String>, i: Int, f: () -> T): T? =
        try { f() } catch (x: UiError) { errors += "messages[$i]: ${x.message}"; null }
}
