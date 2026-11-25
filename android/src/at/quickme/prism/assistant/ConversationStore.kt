// The assistant's conversations on this phone and in Rocky. A save goes to
// the outbox first (a file per conversation in the app's files), then to the
// replica (PUT /v1/assistant/conversations/{id}, which writes only the rows
// that changed); the file goes once the replica took that very version. While
// the replica is busy, closed or not provisioned the file stays, and is sent
// again later (backing off) and on the next save or history screen. A delete
// works the same way, with a tombstone file. The replica syncs to the workers
// on its own, as every table does.

package at.quickme.prism.assistant

import android.content.Context
import android.util.Log
import at.quickme.prism.PrismApi
import at.quickme.prism.Sample
import at.quickme.prism.shared.AssistantConversation
import at.quickme.prism.shared.PrismError
import at.quickme.prism.shared.StoredEntry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    val entries: List<StoredEntry>,
    val history: List<String>,
) {
    val summary: AssistantConversation get() = AssistantConversation(id, "", title, createdAt, updatedAt)
}

object ConversationStore {
    private const val TAG = "PrismConversations"
    private const val DIR = "assistant-outbox"
    private const val DELETED = ".deleted"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var retry: Job? = null
    private var backoff = FIRST_RETRY_MS

    private fun dir(c: Context) = File(c.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** A file name for [id] (IDs are letters, digits and `-_.:`). */
    private fun file(c: Context, id: String, suffix: String = ".json") = File(dir(c), id.replace(':', '_') + suffix)

    /** Keeps [s] on the phone now and sends it to the replica. */
    fun save(c: Context, s: ConversationSnapshot) {
        val app = c.applicationContext
        scope.launch {
            lock.withLock {
                write(file(app, s.id), json.encodeToString(ConversationSnapshot.serializer(), s))
                file(app, s.id, DELETED).delete()
            }
            flush(app)
        }
    }

    /** Deletes conversation [id] here and, now or once it can, in Rocky. */
    fun delete(c: Context, id: String) {
        val app = c.applicationContext
        scope.launch {
            lock.withLock {
                file(app, id).delete()
                write(file(app, id, DELETED), id)
            }
            flush(app)
        }
    }

    /** Conversations saved on the phone that the replica doesn't have yet, and those deleted here meanwhile. */
    suspend fun pending(c: Context): Pair<List<ConversationSnapshot>, Set<String>> = withContext(Dispatchers.IO) {
        lock.withLock {
            val files = dir(c).listFiles().orEmpty()
            val saved = files.filter { it.name.endsWith(".json") }.mapNotNull { read(it) }
            val deleted = files.filter { it.name.endsWith(DELETED) }.map { it.readText() }.toSet()
            saved to deleted
        }
    }

    /** Conversation [id]: the phone's newer copy if it has one, else the replica's. Throws PrismError. */
    suspend fun load(c: Context, id: String): ConversationSnapshot {
        withContext(Dispatchers.IO) { lock.withLock { read(file(c, id)) } }?.let { return it }
        val s = PrismApi.call(c) { assistantConversation(id) }
        return ConversationSnapshot(s.conversation.id, s.conversation.title, s.conversation.createdAt, s.conversation.updatedAt, s.entries, s.history)
    }

    /** Sends what waits in the outbox; on failure it tries again later. */
    fun flush(c: Context) {
        val app = c.applicationContext
        scope.launch { send(app) }
    }

    private suspend fun send(app: Context) {
        if (Sample.on(app) || !PrismApi.enrolled(app)) return
        val failed = lock.withLock {
            var failed = false
            for (f in dir(app).listFiles().orEmpty().sortedBy { it.lastModified() }) {
                try {
                    if (f.name.endsWith(DELETED)) {
                        val id = f.readText()
                        PrismApi.call(app) { deleteAssistantConversation(id) }
                        f.delete()
                    } else if (f.name.endsWith(".json")) {
                        val text = f.readText()
                        val s = runCatching { json.decodeFromString(ConversationSnapshot.serializer(), text) }.getOrNull()
                        if (s == null) {
                            f.delete()
                            continue
                        }
                        PrismApi.call(app) { saveAssistantConversation(s.id, s.title, s.createdAt, s.updatedAt, s.entries, s.history) }
                        // Saved again meanwhile? Then that version still waits.
                        if (f.readText() == text) f.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PrismError) {
                    if (e.status == 400) {
                        // Refused for good (it can't be stored): don't ask again.
                        Log.w(TAG, "conversation refused: ${e.message}")
                        f.delete()
                    } else {
                        Log.i(TAG, "conversation waits for the replica: ${e.message}")
                        failed = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "conversation not sent: ${e.message}")
                    failed = true
                }
            }
            failed
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

    private fun read(f: File): ConversationSnapshot? =
        if (!f.exists()) null else runCatching { json.decodeFromString(ConversationSnapshot.serializer(), f.readText()) }.getOrNull()

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    private const val FIRST_RETRY_MS = 5_000L
    private const val LAST_RETRY_MS = 5 * 60_000L
}
