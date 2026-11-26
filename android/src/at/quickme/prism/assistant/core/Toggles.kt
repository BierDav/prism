// The switches on the transcript's cards: a setting the assistant changed
// (Gemini's "Settings • Do Not Disturb" card with its switch) can be switched
// back, or again, by a tap on the card. The tap runs the same tool the model
// called, with the arguments that set the new state (Toggles.call); the card
// then shows what the tool reports, the conversation keeps it, and the model
// hears of it at the start of its next turn (HandNotes), as it hears of a
// message edited by hand. No Android here (tested on a plain JVM:
// android/tests/AssistantTest.kt).

package at.quickme.prism.assistant.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object Toggles {
    /** The volumes a volume card can be for (PhoneTools' set_volume). */
    private val STREAMS = listOf("media", "ring", "notification", "alarm", "call")

    /**
     * The tool call that switches [card] [on]: the tool's name and its
     * arguments; null when its switch only shows a state (a card without
     * one, or a kind no tool switches).
     */
    fun call(card: SettingCard, on: Boolean): Pair<String, JsonObject>? {
        if (card.on == null) return null
        return when (card.kind) {
            "flashlight" -> Tools.FLASHLIGHT to args("on" to JsonPrimitive(on))
            "dnd" -> Tools.DND to args("on" to JsonPrimitive(on))
            "media" -> Tools.MEDIA to args("action" to JsonPrimitive(if (on) "play" else "pause"))
            "volume" -> stream(card)?.let { Tools.VOLUME to args("stream" to JsonPrimitive(it), "change" to JsonPrimitive(if (on) "unmute" else "mute")) }
            else -> null
        }
    }

    /** Whether [card] has a switch the owner can tap. */
    fun switchable(card: Card): Boolean = card is SettingCard && call(card, card.on != true) != null

    /** Which volume a volume card is for: "Media volume" is media's. */
    fun stream(card: SettingCard): String? =
        card.title.lowercase().removeSuffix(" volume").trim().takeIf { it in STREAMS }

    /**
     * What the model hears in its next turn: the owner switched [before]
     * to [after] by hand, and what the tool said about it ([said]).
     */
    fun note(before: SettingCard, after: SettingCard, said: String): String {
        val what = before.title
        val now = state(after.on)
        val was = state(before.on)
        return "The owner switched $what $now by hand on its card (it was $was)" +
            (said.trim().takeIf { it.isNotEmpty() }?.let { "; the phone says: \"$it\"" } ?: "") + "."
    }

    private fun state(on: Boolean?) = when (on) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }

    private fun args(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*pairs))
}

/**
 * What the owner did by hand since the model's last turn (a message edited,
 * a switch flipped on a card), in order; the next turn takes them all and
 * the model reads them before the owner's words ([framed]).
 */
class HandNotes {
    private val notes = ArrayList<String>()

    val isEmpty: Boolean get() = notes.isEmpty()

    fun add(note: String) {
        if (note.isNotBlank()) notes.add(note.trim())
    }

    /** All notes as one line, and none left. */
    fun take(): String = notes.joinToString(" ").also { notes.clear() }

    fun clear() = notes.clear()

    companion object {
        /** A turn's [note] as the model reads it ahead of the owner's words; null for none. */
        fun framed(note: String): String? = note.takeIf { it.isNotBlank() }?.let { "[Since the last turn: ${it.trim()}]" }
    }
}
