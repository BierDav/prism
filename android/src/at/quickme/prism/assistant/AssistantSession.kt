// The overlay's assistant conversation: the transcript it shows, the
// harness's history behind it, and what the owner does on the cards. It
// lives as long as the overlay; the conversation (transcript and history,
// tool results included) is kept on the phone for the overlay's resume
// window, so reopening within ten minutes continues it. Every conversation
// is also stored in Rocky (ConversationStore), where the history screen
// (AssistHistory.kt) lists it, opens it and continues it; "New chat" starts
// another and keeps this one. One session holds a conversation at a time in
// this process (live): the overlay and the history screen share it, so two
// never write over each other, and a turn goes on when its screen is left.
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AssistantSession(
    context: Context,
    capture: ScreenCapture = ScreenCapture.None,
    /** Continue the overlay's conversation of the resume window; false: a new one. */
    resume: Boolean = true,
) {
    private val app = context.applicationContext
    private val memory = AssistantMemory(app)
    /** The screen the phone's tools act from, and how they see the screen ([attach]). */
    private var context: Context = context
    private var capture: ScreenCapture = capture
    private var attached = 0

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
    /** The stored revision it was loaded at, which its first save builds on (ConversationStore keeps the later ones). */
    private var revision = 0L

    private var history: List<Message> = if (resume) memory.history() else emptyList()
    private var notes = ArrayList<String>()
    private var storing: Job? = null
    private var turn: Job? = null

    init {
        hold(null)
    }

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
        turn = currentCoroutineContext()[Job]
        // The conversation this turn belongs to: once it is deleted
        // ([dropDeleted]), nothing of the turn reaches the next one.
        val mine = conversationId
        val note = notes.joinToString(" ")
        notes.clear()
        val harness = harness()
        try {
            val result = harness.run(
                history, text, attachments,
                emit = { e -> withContext(Dispatchers.Main) { if (conversationId == mine) apply(e) } },
                saved = { if (conversationId == mine) { history = it; save() } },
                note = note,
            )
            if (conversationId == mine) history = result
        } catch (e: CancellationException) {
            if (conversationId == mine) transcript = transcript.settled()
            throw e
        } catch (e: Exception) {
            // Never the exception itself: a gateway's error body can echo what it was sent.
            val problem = problem(e)
            Log.w(TAG, "assistant turn failed (${e.javaClass.simpleName})")
            if (conversationId == mine) transcript = transcript.settled().after(Event.Failed(problem))
        } finally {
            running = false
            turn = null
            if (conversationId == mine) {
                save()
                store(now = true)
            }
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
        val old = conversationId
        transcript = Transcript()
        history = emptyList()
        notes.clear()
        conversationId = Conversations.newId()
        createdAt = nowSeconds()
        revision = 0
        memory.forget()
        hold(old)
    }

    /** Continues stored conversation [s] (the history screen opened it). */
    fun open(s: ConversationSnapshot) {
        if (running) return
        store(now = true)
        val old = conversationId
        conversationId = s.id
        createdAt = s.createdAt
        revision = s.revision
        transcript = Conversations.transcript(s.entries.map { it.id to it.body })
        history = Harness.decodeEach(s.history)
        notes.clear()
        hold(old)
    }

    /**
     * A screen uses this session: the phone's tools act from [c] and see the
     * screen through [capture] until it [detach]es.
     */
    fun attach(c: Context, capture: ScreenCapture = ScreenCapture.None) {
        context = c
        this.capture = capture
        attached++
    }

    /** The screen is gone; once no screen uses it, the tools act from the app. */
    fun detach() {
        attached = (attached - 1).coerceAtLeast(0)
        if (attached > 0) return
        context = app
        capture = ScreenCapture.None
        onHandoff = { _, _ -> }
        followed = { null }
    }

    /** This conversation was deleted: its turn stops and nothing of it is kept; a new one begins. */
    private fun dropDeleted() {
        storing?.cancel()
        turn?.cancel()
        val old = conversationId
        transcript = Transcript()
        history = emptyList()
        notes.clear()
        conversationId = Conversations.newId()
        createdAt = nowSeconds()
        revision = 0
        memory.forget(old)
        hold(old)
    }

    /** Takes [conversationId] in the registry, leaving [old]. */
    private fun hold(old: String?) = synchronized(live) {
        if (old != null && live[old] === this) live.remove(old)
        live[conversationId] = this
        // Sessions no screen uses and that don't run make way, the oldest first.
        val idle = live.entries.filter { (_, s) -> s.attached == 0 && !s.running && s !== this }
        idle.take((live.size - LIVE_SESSIONS).coerceAtLeast(0)).forEach { live.remove(it.key) }
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
        val id = conversationId
        if (now) {
            ConversationStore.save(app, snapshot())
            return
        }
        storing = storeScope.launch {
            delay(STORE_AFTER_MS)
            if (conversationId == id) ConversationStore.save(app, snapshot())
        }
    }

    /** The conversation as rows (Conversations): no images or files, long texts cut. */
    fun snapshot(): ConversationSnapshot = ConversationSnapshot(
        id = conversationId,
        title = Conversations.title(transcript).orEmpty(),
        createdAt = createdAt,
        updatedAt = nowSeconds(),
        revision = revision,
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

    companion object {
        private const val TAG = "PrismAssistant"
        private const val STORE_AFTER_MS = 1500L
        /** Sessions kept for their conversation when no screen uses them. */
        private const val LIVE_SESSIONS = 8
        /** Outlives the overlay, so its last save still goes out. */
        private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        /** The session holding each conversation in this process. */
        private val live = LinkedHashMap<String, AssistantSession>()

        /** The session holding conversation [id] now, if one does. */
        fun live(id: String): AssistantSession? = synchronized(live) { live[id] }

        /** The overlay's session: the one holding its conversation already (the history screen's), or one resuming it. */
        fun forOverlay(c: Context, capture: ScreenCapture): AssistantSession {
            val s = AssistantMemory(c).conversation()?.let(::live) ?: AssistantSession(c, capture)
            return s.also { it.attach(c, capture) }
        }

        /** Conversation [id] was deleted: a session holding it lets it go. */
        internal fun deleted(id: String) {
            live(id)?.dropDeleted()
        }
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
