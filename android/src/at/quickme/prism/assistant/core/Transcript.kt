// The overlay's transcript: what the owner said, what the assistant answered,
// a line per tool while it runs and its card once it ran, as an A2UI surface
// (A2ui.kt: a card is a surface with the card's component at its root; the
// model composes others with show_ui). The harness reports its progress as
// Events; Transcript.after folds each into the entries the UI shows.
// Serializable for the resume window (AssistantMemory); entries stored as
// typed cards before A2UI become surfaces when read (migrated).

package at.quickme.prism.assistant.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the harness reports while it works, in order. */
sealed interface Event {
    /** The owner's request, with the names of what was attached. */
    data class Said(val text: String, val attachments: List<String> = emptyList()) : Event

    /** A tool call began; [callId] pairs it with its [ToolFinished]. */
    data class ToolStarted(val callId: String, val tool: String, val label: String) : Event

    /** A tool call ended: what it told the model, its card or its surfaces (show_ui), whether it failed. */
    data class ToolFinished(
        val callId: String,
        val tool: String,
        val text: String,
        val card: Card? = null,
        val failed: Boolean = false,
        val ui: UiChange? = null,
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

    /** A typed card, as conversations stored before A2UI: read, it becomes a [Ui] ([Transcript.migrated]). */
    @Serializable
    @SerialName("card")
    data class Shown(override val id: String, val tool: String, val card: Card) : Entry

    /** A tool's card or the model's composition: an A2UI surface, kept as its createSurface. */
    @Serializable
    @SerialName("ui")
    data class Ui(
        override val id: String,
        val tool: String,
        @SerialName("createSurface") val surface: Surface,
        val version: String = A2ui.VERSION,
    ) : Entry

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
            val ui = event.ui?.takeIf { !event.failed }
            val changed = ui?.let { changed(it) } ?: this
            val done: Entry = when {
                event.card != null && !event.failed -> Entry.Ui(id, event.tool, CardSurfaces.surface(event.card, id))
                ui?.created != null -> Entry.Ui(id, event.tool, ui.created)
                ui != null -> Entry.Step(id, event.tool, ui.summary().substringBefore(';').trimEnd('.'), running = false)
                else -> {
                    val started = entries.firstOrNull { it.id == id } as? Entry.Step
                    Entry.Step(
                        id, event.tool, started?.label ?: Tools.label(event.tool),
                        running = false, failed = event.failed, detail = event.text.take(DETAIL_CHARS),
                    )
                }
            }
            if (changed.entries.any { it.id == id }) changed.copy(entries = changed.entries.map { if (it.id == id) done else it })
            else changed.copy(entries = changed.entries + done)
        }
    }

    /** show_ui's updates and deletions applied to the surfaces already shown. */
    private fun changed(c: UiChange): Transcript = copy(
        entries = entries.mapNotNull { e ->
            if (e !is Entry.Ui) return@mapNotNull e
            when (e.surface.surfaceId) {
                in c.deleted -> null
                else -> c.updated.firstOrNull { it.surfaceId == e.surface.surfaceId }?.let { e.copy(surface = it) } ?: e
            }
        },
    )

    /** The card entry [id] shows, if it shows one. */
    fun card(id: String): Card? = (entries.firstOrNull { it.id == id } as? Entry.Ui)?.let { CardSurfaces.card(it.surface) }

    /** The entry showing surface [surfaceId]. */
    fun surface(surfaceId: String): Entry.Ui? = entries.firstOrNull { it is Entry.Ui && it.surface.surfaceId == surfaceId } as Entry.Ui?

    /** The card of entry [id] replaced by [card] (an edited message, an agent that now has its ID). */
    fun withCard(id: String, card: Card): Transcript =
        copy(entries = entries.map { if (it.id == id && it is Entry.Ui) it.copy(surface = CardSurfaces.withCard(it.surface, card)) else it })

    /** The surface of entry [id] replaced by [surface] (its data model after the owner filled it in). */
    fun withSurface(id: String, surface: Surface): Transcript =
        copy(entries = entries.map { if (it.id == id && it is Entry.Ui) it.copy(surface = surface) else it })

    /** A line saying what the owner did on a card ("Calling Anna"), done. */
    fun did(tool: String, label: String): Transcript = add { Entry.Step(it, tool, label, running = false) }

    /** The newest hand-off to a T3 agent, with its entry's ID. */
    fun latestHandoff(): Pair<String, HandoffCard>? =
        entries.asReversed().firstNotNullOfOrNull { e -> (e as? Entry.Ui)?.let { CardSurfaces.card(it.surface) as? HandoffCard }?.let { e.id to it } }

    /** Typed cards stored before A2UI as surfaces (their entry's ID the surface's). */
    fun migrated(): Transcript =
        copy(entries = entries.map { if (it is Entry.Shown) Entry.Ui(it.id, it.tool, CardSurfaces.surface(it.card, it.id)) else it })

    /** Tool lines still marked running (a run that was cancelled), now stopped. */
    fun settled(): Transcript =
        copy(entries = entries.map { if (it is Entry.Step && it.running) it.copy(running = false, failed = true, detail = "Stopped") else it })

    private fun add(make: (String) -> Entry) = Transcript(entries + make("e$next"), next + 1)

    private fun stepId(callId: String) = "tool:$callId"
}

/** How much of a failed tool's message its line keeps. */
private const val DETAIL_CHARS = 300
