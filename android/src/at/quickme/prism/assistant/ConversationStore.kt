// The assistant's conversations on this phone and in Rocky. A save goes to
// the outbox first (a file per conversation in the app's files: core/Outbox),
// then to the replica (PUT /v1/assistant/conversations/{id}), naming the
// revision it builds on and the one it makes. One save is in flight at a
// time and is sent as it is until its answer is known; the latest version
// waits next to it and then builds on what it committed.
// While the replica is busy, closed or not provisioned the file stays and is
// sent again later (backing off), on the next save, on the history screen
// and whenever the replica opens. A save the replica refuses for good (the
// conversation changed on another device, or can't be stored) stays on the
// phone with why, and the history screen shows it, until a newer version
// replaces it, which is tried in turn. A delete leaves a
// tombstone file, written before the copy goes, and the replica a tombstone
// row: no later save brings the conversation back. The replica syncs to the
// workers on its own, as every table does.

package at.quickme.prism.assistant

import android.content.Context
import android.util.Log
import at.quickme.prism.PrismApi
import at.quickme.prism.Sample
import at.quickme.prism.assistant.core.Outbox
import at.quickme.prism.shared.AssistantConversation
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismError
import at.quickme.prism.shared.StoredEntry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A conversation as it is saved: what the history lists, and its rows (Conversations). Times in Unix seconds. */
@Serializable
data class ConversationSnapshot(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** The committed revision this builds on (0: new). */
    override val revision: Long = 0,
    /** The ID of the revision this version makes, new for every version queued: its retry is answered as the first time. */
    override val saveId: String = "",
    val entries: List<StoredEntry>,
    val history: List<String>,
    /** Why the replica refused it for good; it then stays on the phone, unsent. */
    override val problem: String = "",
) : Outbox.Version<ConversationSnapshot> {
    val summary: AssistantConversation get() = AssistantConversation(id, "", title, createdAt, updatedAt, revision)

    override fun with(revision: Long, saveId: String, problem: String) = copy(revision = revision, saveId = saveId, problem = problem)
}

object ConversationStore {
    private const val TAG = "PrismConversations"
    private const val DIR = "assistant-outbox"
    /** The conversation's Outbox: what was committed, what is in flight and what waits. */
    private const val JSON = ".json"
    /** A delete still to send. */
    private const val DELETED = ".deleted"
    /** A delete sent: kept so no save here brings it back. */
    private const val GONE = ".gone"

    private val json = Json { ignoreUnknownKeys = true }
    private val record = Outbox.serializer(ConversationSnapshot.serializer())
    private val failures = MutableStateFlow<String?>(null)
    private val version = MutableStateFlow(0)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "conversation store: ${e.message}")
            failures.value = "Chats could not be kept on the phone: ${e.message}"
        },
    )
    /** Guards the files; held only to read or write them, never across a call to the replica. */
    private val files = Mutex()
    /** One sender at a time, so every save builds on the revision the one before committed. */
    private val sending = Mutex()
    private var retry: Job? = null
    private var backoff = FIRST_RETRY_MS

    /** Why the outbox couldn't write or send, for the history screen; null while all is well. */
    val problem: StateFlow<String?> get() = failures

    /** Counts what changed in the outbox (saved, sent, refused, deleted), for the history screen to follow. */
    val changes: StateFlow<Int> get() = version

    private fun changed() {
        version.value++
    }

    private fun dir(c: Context) = File(c.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** A file for [id] (IDs are letters, digits and `-_.:`). */
    private fun file(c: Context, id: String, suffix: String) = File(dir(c), id.replace(':', '_') + suffix)

    private fun tombstoned(c: Context, id: String) = file(c, id, DELETED).exists() || file(c, id, GONE).exists()

    /**
     * Keeps [s] on the phone now and sends it to the replica. It replaces
     * the version that waited, not the save in flight, and builds on what
     * the replica last committed (Outbox.base); a deleted conversation is
     * not kept.
     */
    fun save(c: Context, s: ConversationSnapshot) {
        val app = c.applicationContext
        scope.launch {
            files.withLock {
                if (tombstoned(app, s.id)) return@launch
                val f = file(app, s.id, JSON)
                write(f, (read(f) ?: Outbox()).queued(s, PrismClient.requestId()))
            }
            changed()
            send(app)
        }
    }

    /**
     * Deletes conversation [id] here and, now or once it can, in Rocky: a
     * session holding it lets it go, the tombstone is written, then the copy
     * goes. Call it on the main thread.
     */
    fun delete(c: Context, id: String) {
        val app = c.applicationContext
        AssistantSession.deleted(id)
        AssistantMemory(app).forget(id)
        scope.launch {
            files.withLock {
                writeText(file(app, id, DELETED), id)
                file(app, id, JSON).delete()
            }
            changed()
            send(app)
        }
    }

    /** Conversations kept on the phone (waiting, or refused with why), and those deleted here. */
    suspend fun pending(c: Context): Pair<List<ConversationSnapshot>, Set<String>> = withContext(Dispatchers.IO) {
        files.withLock {
            val all = dir(c).listFiles().orEmpty()
            val kept = all.filter { it.name.endsWith(JSON) }.mapNotNull { runCatching { read(it) }.getOrNull()?.latest }
            val deleted = all.filter { it.name.endsWith(DELETED) || it.name.endsWith(GONE) }
                .mapNotNull { runCatching { it.readText() }.getOrNull() }.toSet()
            kept to deleted
        }
    }

    /** Conversation [id]: the phone's copy if it has one, else the replica's. Throws PrismError. */
    suspend fun load(c: Context, id: String): ConversationSnapshot {
        val local = withContext(Dispatchers.IO) {
            files.withLock {
                if (tombstoned(c, id)) throw PrismError(410, "gone", "This chat was deleted.")
                runCatching { read(file(c, id, JSON)) }.getOrNull()?.latest
            }
        }
        if (local != null) return local
        val s = PrismApi.call(c) { assistantConversation(id) }
        val c = s.conversation
        return ConversationSnapshot(c.id, c.title, c.createdAt, c.updatedAt, revision = c.revision, entries = s.entries, history = s.history)
    }

    /** Sends what waits in the outbox (the replica opened, a screen asks); on failure it tries again later. */
    fun flush(c: Context) {
        val app = c.applicationContext
        scope.launch { send(app) }
    }

    private suspend fun send(app: Context) {
        if (Sample.on(app) || !PrismApi.enrolled(app)) return
        var failed = false
        sending.withLock {
            val queued = files.withLock {
                dir(app).listFiles().orEmpty().filter { it.name.endsWith(JSON) || it.name.endsWith(DELETED) }.sortedBy { it.lastModified() }
            }
            for (f in queued) {
                try {
                    // A save answered, the version made meanwhile goes next (after a restart, both wait).
                    if (f.name.endsWith(DELETED)) sendDelete(app, f) else while (sendSave(app, f)) { }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "a conversation waits for the replica: ${e.message}")
                    failed = true
                }
            }
        }
        if (!failed) {
            backoff = FIRST_RETRY_MS
            return
        }
        if (retry?.isActive == true) return
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(LAST_RETRY_MS)
        retry = scope.launch {
            delay(wait)
            retry = null
            send(app)
        }
    }

    private suspend fun sendDelete(app: Context, f: File) {
        val id = files.withLock { if (f.exists()) f.readText() else null } ?: return
        PrismApi.call(app) { deleteAssistantConversation(id) }
        files.withLock {
            writeText(file(app, id, GONE), id)
            f.delete()
        }
        changed()
    }

    /** Sends the save in flight in [f]; true when another version waits there after its answer. */
    private suspend fun sendSave(app: Context, f: File): Boolean {
        // The save in flight, or the latest version made it (on disk before
        // it is sent, so it is sent again as it was after the app is killed).
        val s = files.withLock {
            val o = read(f) ?: return false
            o.started().also { if (it != o) write(f, it) }.sending
        } ?: return false
        val revision = try {
            // A save whose answer was lost after it committed: sent again with
            // its saveId, it is answered as the first time.
            PrismApi.call(app) { saveAssistantConversation(s.id, s.revision, s.saveId, s.title, s.createdAt, s.updatedAt, s.entries, s.history) }
        } catch (e: PrismError) {
            when {
                e.status == 410 -> {
                    files.withLock {
                        writeText(file(app, s.id, GONE), s.id)
                        f.delete()
                    }
                    changed()
                    return false
                }
                e.status == 409 -> answered(f, s) { it.refused("Changed on another device since; this copy stays on the phone.") }
                e.status == 400 -> answered(f, s) { it.refused("Not saved: ${e.message}") }
                else -> throw e
            }
            changed()
            return files.withLock { read(f)?.waiting == true }
        }
        // Its answer and the next version's new base, in one write.
        answered(f, s) { it.acknowledged(revision) }
        changed()
        return files.withLock { read(f)?.waiting == true }
    }

    /** Records the answer to save [s] in [f], if [s] is still the one in flight there. */
    private suspend fun answered(f: File, s: ConversationSnapshot, how: (Outbox<ConversationSnapshot>) -> Outbox<ConversationSnapshot>) =
        files.withLock {
            val o = read(f) ?: return@withLock
            if (o.sending?.saveId != s.saveId) return@withLock
            val after = how(o)
            after.latest?.problem?.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "a conversation was refused: $it") }
            write(f, after)
        }

    /**
     * The outbox in [f]; null when there is none. A read that fails throws
     * (it is tried again later), and a file that doesn't decode is set aside
     * as `.unreadable`, never deleted: it may be the only copy of a chat.
     */
    private fun read(f: File): Outbox<ConversationSnapshot>? {
        if (!f.exists()) return null
        val text = f.readText()
        return try {
            json.decodeFromString(record, text)
        } catch (e: IllegalArgumentException) { // SerializationException too
            Log.w(TAG, "an unreadable conversation was set aside: ${f.name}")
            f.renameTo(File(f.parentFile, f.name + ".unreadable"))
            null
        }
    }

    private fun write(f: File, o: Outbox<ConversationSnapshot>) = writeText(f, json.encodeToString(record, o))

    /** Replaces [f] whole or not at all; a failure is reported ([problem]) and thrown. */
    private fun writeText(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            failures.value = null
        } catch (e: IOException) {
            tmp.delete()
            failures.value = "Chats could not be kept on the phone: ${e.message}"
            throw e
        }
    }

    private const val FIRST_RETRY_MS = 5_000L
    private const val LAST_RETRY_MS = 5 * 60_000L
}
