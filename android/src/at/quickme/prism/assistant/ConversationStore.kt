// The assistant's conversations on this phone and in Rocky. A save goes to
// the outbox first (a file per conversation in the app's files), then to the
// replica (PUT /v1/assistant/conversations/{id}), naming the revision it
// builds on; the file goes once the replica committed that very version.
// While the replica is busy, closed or not provisioned the file stays and is
// sent again later (backing off), on the next save, on the history screen
// and whenever the replica opens. A save the replica refuses for good (the
// conversation changed on another device, or can't be stored) stays on the
// phone with why, and the history screen shows it. A delete leaves a
// tombstone file, written before the copy goes, and the replica a tombstone
// row: no later save brings the conversation back. The replica syncs to the
// workers on its own, as every table does.

package at.quickme.prism.assistant

import android.content.Context
import android.util.Log
import at.quickme.prism.PrismApi
import at.quickme.prism.Sample
import at.quickme.prism.shared.AssistantConversation
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
    val revision: Long = 0,
    val entries: List<StoredEntry>,
    val history: List<String>,
    /** Why the replica refused it for good; it then stays on the phone, unsent. */
    val problem: String = "",
) {
    val summary: AssistantConversation get() = AssistantConversation(id, "", title, createdAt, updatedAt, revision)
}

object ConversationStore {
    private const val TAG = "PrismConversations"
    private const val DIR = "assistant-outbox"
    private const val JSON = ".json"
    /** A delete still to send. */
    private const val DELETED = ".deleted"
    /** A delete sent: kept so no save here brings it back. */
    private const val GONE = ".gone"
    /** The last revision the replica committed for this phone. */
    private const val REVISION = ".rev"

    private val json = Json { ignoreUnknownKeys = true }
    private val failures = MutableStateFlow<String?>(null)
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

    private fun dir(c: Context) = File(c.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** A file for [id] (IDs are letters, digits and `-_.:`). */
    private fun file(c: Context, id: String, suffix: String) = File(dir(c), id.replace(':', '_') + suffix)

    private fun tombstoned(c: Context, id: String) = file(c, id, DELETED).exists() || file(c, id, GONE).exists()

    private fun committed(c: Context, id: String): Long =
        runCatching { file(c, id, REVISION).readText().trim().toLong() }.getOrDefault(0L)

    /**
     * Keeps [s] on the phone now and sends it to the replica. It builds on
     * the revision the replica last committed for it, unless a save of it
     * still waits (then on that one's); a deleted conversation is not kept.
     */
    fun save(c: Context, s: ConversationSnapshot) {
        val app = c.applicationContext
        scope.launch {
            files.withLock {
                if (tombstoned(app, s.id)) return@launch
                val waiting = read(file(app, s.id, JSON))
                val base = waiting?.revision ?: maxOf(s.revision, committed(app, s.id))
                write(file(app, s.id, JSON), s.copy(revision = base, problem = waiting?.problem.orEmpty()))
            }
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
            send(app)
        }
    }

    /** Conversations kept on the phone (waiting, or refused with why), and those deleted here. */
    suspend fun pending(c: Context): Pair<List<ConversationSnapshot>, Set<String>> = withContext(Dispatchers.IO) {
        files.withLock {
            val all = dir(c).listFiles().orEmpty()
            val kept = all.filter { it.name.endsWith(JSON) }.mapNotNull { read(it) }
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
                read(file(c, id, JSON))
            }
        }
        if (local != null) return local
        val s = PrismApi.call(c) { assistantConversation(id) }
        return ConversationSnapshot(s.conversation.id, s.conversation.title, s.conversation.createdAt, s.conversation.updatedAt, s.conversation.revision, s.entries, s.history)
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
                    if (f.name.endsWith(DELETED)) sendDelete(app, f) else sendSave(app, f)
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
    }

    private suspend fun sendSave(app: Context, f: File) {
        val (text, s) = files.withLock {
            val text = if (f.exists()) f.readText() else return
            text to (runCatching { json.decodeFromString(ConversationSnapshot.serializer(), text) }.getOrNull() ?: run {
                f.delete()
                return
            })
        }
        if (s.problem.isNotEmpty()) return
        val revision = try {
            PrismApi.call(app) { saveAssistantConversation(s.id, s.revision, s.title, s.createdAt, s.updatedAt, s.entries, s.history) }
        } catch (e: PrismError) {
            when {
                e.status == 410 -> files.withLock {
                    writeText(file(app, s.id, GONE), s.id)
                    f.delete()
                }
                e.status == 409 -> hold(f, "Changed on another device since; this copy stays on the phone.")
                e.status == 400 -> hold(f, "Not saved: ${e.message}")
                else -> throw e
            }
            return
        }
        files.withLock {
            writeText(file(app, s.id, REVISION), revision.toString())
            val now = if (f.exists()) f.readText() else null
            when {
                now == text -> f.delete()
                // Saved again meanwhile: that version builds on what was just committed.
                now != null -> read(f)?.let { write(f, it.copy(revision = revision)) }
            }
        }
    }

    /** Keeps [f]'s conversation on the phone, unsent, with why. */
    private suspend fun hold(f: File, why: String) = files.withLock {
        Log.w(TAG, "a conversation was refused: $why")
        read(f)?.let { write(f, it.copy(problem = why)) }
    }

    private fun read(f: File): ConversationSnapshot? =
        if (!f.exists()) null else runCatching { json.decodeFromString(ConversationSnapshot.serializer(), f.readText()) }.getOrNull()

    private fun write(f: File, s: ConversationSnapshot) = writeText(f, json.encodeToString(ConversationSnapshot.serializer(), s))

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
