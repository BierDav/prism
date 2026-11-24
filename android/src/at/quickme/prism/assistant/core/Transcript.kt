// The overlay's transcript: what the owner said, what the assistant answered,
// a line per tool while it runs and its card once it ran. The harness reports
// its progress as Events; Transcript.after folds each into the entries the UI
// shows. Serializable for the resume window (AssistantMemory).

package at.quickme.prism.assistant.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the harness reports while it works, in order. */
sealed interface Event {
    /** The owner's request, with the names of what was attached. */
    data class Said(val text: String, val attachments: List<String> = emptyList()) : Event

    /** A tool call began; [callId] pairs it with its [ToolFinished]. */
    data class ToolStarted(val callId: String, val tool: String, val label: String) : Event

    /** A tool call ended: what it told the model, its card, whether it failed. */
    data class ToolFinished(
        val callId: String,
        val tool: String,
        val text: String,
        val card: Card? = null,
        val failed: Boolean = false,
    ) : Event

    /** The assistant's words (Markdown). */
    data class Answer(val text: String) : Event

    /** The run failed (network, gateway, …): why. */
    data class Failed(val message: String) : Event
}

@Serializable
sealed interface Entry {
    val id: String

    @Serializable
    @SerialName("user")
    data class User(override val id: String, val text: String, val attachments: List<String> = emptyList()) : Entry

    @Serializable
    @SerialName("answer")
    data class Answer(override val id: String, val text: String) : Entry

    /** A tool at work, or one that ended without a card. */
    @Serializable
    @SerialName("step")
    data class Step(
        override val id: String,
        val tool: String,
        val label: String,
        val running: Boolean = true,
        val failed: Boolean = false,
        val detail: String = "",
    ) : Entry

    @Serializable
    @SerialName("card")
    data class Shown(override val id: String, val tool: String, val card: Card) : Entry

    @Serializable
    @SerialName("problem")
    data class Problem(override val id: String, val text: String) : Entry
}

@Serializable
data class Transcript(val entries: List<Entry> = emptyList(), val next: Int = 0) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The transcript with [event] in it. */
    fun after(event: Event): Transcript = when (event) {
        is Event.Said -> add { Entry.User(it, event.text, event.attachments) }
        is Event.Answer -> if (event.text.isBlank()) this else add { Entry.Answer(it, event.text.trim()) }
        is Event.Failed -> add { Entry.Problem(it, event.message) }
        is Event.ToolStarted -> copy(entries = entries + Entry.Step(stepId(event.callId), event.tool, event.label))
        is Event.ToolFinished -> {
            val id = stepId(event.callId)
            val done: Entry = when {
                event.card != null && !event.failed -> Entry.Shown(id, event.tool, event.card)
                else -> {
                    val started = entries.firstOrNull { it.id == id } as? Entry.Step
                    Entry.Step(
                        id, event.tool, started?.label ?: Tools.label(event.tool),
                        running = false, failed = event.failed, detail = event.text.take(DETAIL_CHARS),
                    )
                }
            }
            if (entries.any { it.id == id }) copy(entries = entries.map { if (it.id == id) done else it })
            else copy(entries = entries + done)
        }
    }

    /** The card of entry [id] replaced by [card] (an edited message, an agent that now has its ID). */
    fun withCard(id: String, card: Card): Transcript =
        copy(entries = entries.map { if (it.id == id && it is Entry.Shown) it.copy(card = card) else it })

    /** The newest hand-off to a T3 agent, with its entry's ID. */
    fun latestHandoff(): Pair<String, HandoffCard>? =
        entries.asReversed().firstNotNullOfOrNull { e -> ((e as? Entry.Shown)?.card as? HandoffCard)?.let { e.id to it } }

    /** Tool lines still marked running (a run that was cancelled), now stopped. */
    fun settled(): Transcript =
        copy(entries = entries.map { if (it is Entry.Step && it.running) it.copy(running = false, failed = true, detail = "Stopped") else it })

    private fun add(make: (String) -> Entry) = Transcript(entries + make("e$next"), next + 1)

    private fun stepId(callId: String) = "tool:$callId"
}

/** How much of a failed tool's message its line keeps. */
private const val DETAIL_CHARS = 300
