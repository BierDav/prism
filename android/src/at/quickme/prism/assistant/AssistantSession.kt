// The overlay's assistant conversation: the transcript it shows, the
// harness's history behind it, and what the owner does on the cards. It
// lives as long as the overlay; the conversation (transcript and history,
// tool results included) is kept on the phone for the overlay's resume
// window, so reopening within ten minutes continues it. Every conversation
// is also stored in Rocky (ConversationStore), where the history screen
// (AssistHistory.kt) lists it, opens it and continues it with a session of
// its own; "New chat" starts another and keeps this one.
//
// Without assistant_api_key it isn't configured, and the overlay starts T3
// agents directly, as before the assistant.

package at.quickme.prism.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.quickme.prism.Settings
import at.quickme.prism.assistant.core.Conversations
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.SystemPrompt
import at.quickme.prism.assistant.core.Transcript
import ai.koog.prompt.message.Message
import android.util.Log
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import at.quickme.prism.shared.StoredEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AssistantSession(
    private val context: Context,
    private val capture: ScreenCapture = ScreenCapture.None,
    /** Continue the overlay's conversation of the resume window; false: a new one. */
    resume: Boolean = true,
) {
    private val app = context.applicationContext
    private val memory = AssistantMemory(app)

    /** Whether the assistant can run: the gateway has a key. */
    val configured: Boolean get() = Settings.assistantApiKey(app).isNotEmpty()

    var transcript by mutableStateOf(if (resume) memory.transcript() else Transcript())
        private set

    /** A turn is running. */
    var running by mutableStateOf(false)
        private set

    /** The conversation's ID in Rocky (Conversations.newId). */
    var conversationId: String = (if (resume) memory.conversation() else null) ?: Conversations.newId()
        private set
    private var createdAt: Long = (if (resume) memory.createdAt() else 0L).takeIf { it > 0 } ?: nowSeconds()

    private var history: List<Message> = if (resume) memory.history() else emptyList()
    private var notes = ArrayList<String>()
    private var storing: Job? = null

    /** The T3 agent the overlay follows (its ID and title), for the model's context. */
    var followed: () -> Pair<String, String>? = { null }

    /** A hand-off happened: the overlay follows its agent ([HandoffCard.agentId] empty while it starts). */
    var onHandoff: (entryId: String, card: HandoffCard) -> Unit = { _, _ -> }

    private val matrix get() = MatrixClient(Settings.matrixHomeserver(app), Settings.matrixAccessToken(app))
    private var matrixClient: MatrixClient? = null

    private fun harness(): Harness {
        val m = matrix.also { matrixClient = it }
        return Harness(
            Gateway(Settings.assistantUrl(app), Settings.assistantApiKey(app), Settings.assistantModel(app), Settings.deviceName(app)),
            tools = phoneTools(context, capture) + matrixTools(m) + t3Tools(app),
            systemPrompt = {
                SystemPrompt.build(
                    SystemPrompt.Context(
                        now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)),
                        timeZone = ZonedDateTime.now().zone.id,
                        device = Settings.deviceName(app),
                        battery = batteryText(app),
                        matrix = m.configured,
                        followedAgent = followed(),
                    ),
                )
            },
            prepareImage = ::scaleImage,
        )
    }

    /**
     * One turn: [text] and [attachments] to the assistant, the transcript
     * updated as it works. Returns when the turn ended (or failed: the
     * transcript says why).
     */
    suspend fun send(text: String, attachments: List<AssistAttachment> = emptyList()) {
        if (running) return
        running = true
        val note = notes.joinToString(" ")
        notes.clear()
        val harness = harness()
        try {
            history = harness.run(
                history, text, attachments,
                emit = { e -> withContext(Dispatchers.Main) { apply(e) } },
                saved = { history = it; save() },
                note = note,
            )
        } catch (e: CancellationException) {
            transcript = transcript.settled()
            throw e
        } catch (e: Exception) {
            // Never the exception itself: a gateway's error body can echo what it was sent.
            val problem = problem(e)
            Log.w(TAG, "assistant turn failed (${e.javaClass.simpleName})")
            transcript = transcript.settled().after(Event.Failed(problem))
        } finally {
            running = false
            save()
            store(now = true)
            harness.close()
        }
    }

    private fun apply(e: Event) {
        transcript = transcript.after(e)
        if (e is Event.ToolFinished && e.card is HandoffCard) {
            val id = transcript.entries.last { it is Entry.Shown && it.card == e.card }.id
            onHandoff(id, e.card)
        }
        save()
    }

    /** The start of entry [entryId] was refused: its card says why and it isn't asked again. */
    fun failedStart(entryId: String, why: String) {
        val card = (transcript.entries.firstOrNull { it.id == entryId } as? Entry.Shown)?.card as? HandoffCard ?: return
        transcript = transcript.withCard(entryId, card.copy(failed = why))
        save()
    }

    /** The hand-off of entry [entryId] now has its agent (it was still starting). */
    fun started(entryId: String, agentId: String, title: String) {
        val card = (transcript.entries.firstOrNull { it.id == entryId } as? Entry.Shown)?.card as? HandoffCard ?: return
        transcript = transcript.withCard(entryId, card.copy(agentId = agentId, title = title.ifEmpty { card.title }))
        save()
    }

    /**
     * The owner changed the message of entry [entryId] by hand: a Matrix
     * message is edited in place (m.replace); the model hears of it in the
     * next turn. Returns why it failed, or null.
     */
    suspend fun edit(entryId: String, text: String): String? {
        val card = (transcript.entries.firstOrNull { it.id == entryId } as? Entry.Shown)?.card as? MessageCard
            ?: return "That message is gone."
        if (!card.editable) return "An SMS can't be changed once sent. Ask Prism to send a correction."
        val body = text.trim()
        if (body.isEmpty() || body == card.text) return null
        return try {
            (matrixClient ?: matrix).edit(card.address, card.eventId, body)
            transcript = transcript.withCard(entryId, card.copy(text = body, edited = true))
            notes.add("The owner edited the Matrix message to ${card.to} (event ${card.eventId}) by hand; it now says: \"$body\".")
            save()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem(e)
        }
    }

    /** A new conversation; this one stays stored. */
    fun clear() {
        store(now = true)
        transcript = Transcript()
        history = emptyList()
        notes.clear()
        conversationId = Conversations.newId()
        createdAt = nowSeconds()
        memory.forget()
    }

    /** Continues stored conversation [s] (the history screen opened it). */
    fun open(s: ConversationSnapshot) {
        if (running) return
        store(now = true)
        conversationId = s.id
        createdAt = s.createdAt
        transcript = Conversations.transcript(s.entries.map { it.id to it.body })
        history = Harness.decodeEach(s.history)
        notes.clear()
    }

    private fun save() {
        memory.save(transcript, history, conversationId, createdAt)
        store()
    }

    /**
     * Stores the conversation in Rocky: after a moment's quiet while a turn
     * runs (it changes with every step), at once when [now].
     */
    private fun store(now: Boolean = false) {
        storing?.cancel()
        if (transcript.isEmpty) return
        if (now) {
            ConversationStore.save(app, snapshot())
            return
        }
        storing = storeScope.launch {
            delay(STORE_AFTER_MS)
            ConversationStore.save(app, snapshot())
        }
    }

    /** The conversation as rows (Conversations): no images or files, long texts cut. */
    fun snapshot(): ConversationSnapshot = ConversationSnapshot(
        id = conversationId,
        title = Conversations.title(transcript).orEmpty(),
        createdAt = createdAt,
        updatedAt = nowSeconds(),
        entries = Conversations.entryRows(transcript).map { (id, body) -> StoredEntry(id, body) },
        history = Conversations.historyRows(Harness.encodeEach(history)),
    )

    private fun problem(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "401" in m || "403" in m -> "The gateway refused the assistant's key (assistant_api_key)."
            "Status code: 5" in m -> "The model's gateway had a problem. Try again in a moment."
            e is java.io.IOException || e.cause is java.io.IOException -> "No connection to the model's gateway."
            else -> "The assistant failed: ${m.lineSequence().firstOrNull().orEmpty().take(200)}"
        }
    }

    private companion object {
        const val TAG = "PrismAssistant"
        const val STORE_AFTER_MS = 1500L
        /** Outlives the overlay, so its last save still goes out. */
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

/** Images go to the model at most 1600 px on their long side, as JPEG. */
internal fun scaleImage(a: AssistAttachment): AssistAttachment {
    if (!a.isImage || a.mimeType == "image/gif") return a
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return a
        if (long <= MAX_IMAGE && a.bytes.size <= 1_500_000) return a
        var sample = 1
        while (long / (sample * 2) >= MAX_IMAGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return a
        val scale = minOf(1f, MAX_IMAGE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        AssistAttachment(out.toByteArray(), "image/jpeg", a.name, a.screenshot)
    }.getOrDefault(a)
}

private const val MAX_IMAGE = 1600

private fun nowSeconds() = System.currentTimeMillis() / 1000

/** The conversation on the phone, for the resume window. */
internal class AssistantMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun fresh() = System.currentTimeMillis() - prefs.getLong(AT, 0L) <= RESUME_MS

    fun transcript(): Transcript = if (!fresh()) Transcript() else
        runCatching { json.decodeFromString(Transcript.serializer(), prefs.getString(TRANSCRIPT, null) ?: return Transcript()) }
            .getOrDefault(Transcript()).settled()

    fun history(): List<Message> = if (!fresh()) emptyList() else Harness.decode(prefs.getString(HISTORY, null) ?: return emptyList())

    /** The conversation's ID in Rocky, within the resume window. */
    fun conversation(): String? = if (!fresh()) null else prefs.getString(ID, null)

    fun createdAt(): Long = if (!fresh()) 0L else prefs.getLong(CREATED, 0L)

    fun save(transcript: Transcript, history: List<Message>, id: String, createdAt: Long) {
        prefs.edit()
            .putString(TRANSCRIPT, json.encodeToString(Transcript.serializer(), transcript))
            .putString(HISTORY, Harness.encode(history))
            .putString(ID, id)
            .putLong(CREATED, createdAt)
            .putLong(AT, System.currentTimeMillis())
            .apply()
    }

    /** Forgets conversation [id] if it is the one kept (it was deleted). */
    fun forget(id: String) {
        if (prefs.getString(ID, null) == id) forget()
    }

    fun forget() {
        prefs.edit().remove(TRANSCRIPT).remove(HISTORY).remove(ID).remove(CREATED).remove(AT).apply()
    }

    private companion object {
        const val TRANSCRIPT = "harness_transcript"
        const val HISTORY = "harness_history"
        const val AT = "harness_at"
        const val ID = "harness_conversation"
        const val CREATED = "harness_created"
        /** As the overlay's own (AssistMemory): ten minutes. */
        const val RESUME_MS = 10 * 60 * 1000L
    }
}
