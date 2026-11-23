// Approvals: something asks the owner to approve or deny it, and the answer,
// signed with the device key, goes back to whoever asked. Both apps show the
// queue (pending and the recent decisions) and sign with their key; where
// approvals come from is up to the integrations (ApprovalChannel), including T3 command approvals.

package at.quickme.prism.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One request for the owner's decision, exactly as it is shown and signed. */
@Serializable
data class Approval(
    /** Unique per request; the answer names it. */
    val id: String,
    /** What is asked, in a line: "Deploy main to the cluster". */
    val title: String,
    /** Who asks: a host, a service, an agent. */
    val source: String,
    /** Unix seconds. */
    val requestedAt: Long,
    /** Unix seconds; after it the request is answered as expired. */
    val expiresAt: Long,
    val summary: String = "",
    /** Facts the owner should see before deciding, in order. */
    val details: List<ApprovalDetail> = emptyList(),
    /** The T3 thread that asks, if any. */
    val thread: ThreadLink? = null,
    val options: List<ApprovalOption> = emptyList(),
    val decision: String = "accept",
)

@Serializable
data class ApprovalOption(val decision: String, val label: String, val warning: String = "")

@Serializable
data class ApprovalDetail(val label: String, val value: String, val code: Boolean = false)

@Serializable
data class ThreadLink(val environmentId: String, val threadId: String, val title: String? = null) {
    /** The link both apps open: T3 Code's app, or its web app. */
    val url: String get() = "t3code://threads/$environmentId/$threadId"
}

@Serializable
enum class Outcome { APPROVED, DENIED, EXPIRED, WITHDRAWN }

/** A decision, newest first in [ApprovalQueue.history]. */
@Serializable
data class Decision(val time: Long, val outcome: Outcome, val title: String, val source: String)

/** Where an approval came from: it gets the answer. Integrations implement it. */
interface ApprovalChannel {
    /** [signature] is the SSHSIG of [Approvals.message] for APPROVED, null otherwise. */
    suspend fun answer(approval: Approval, outcome: Outcome, signature: String?)
}

object Approvals {
    /** Scope is part of the signed request; it cannot be widened after authentication. */
    fun choosing(approval: Approval, decision: String): Approval {
        require(decision.isEmpty() || approval.options.isEmpty() && decision == "accept" || approval.options.any { it.decision == decision })
        return approval.copy(decision = decision)
    }
    /** The SSHSIG namespace of an approval's signature. */
    const val NAMESPACE = "approval@prism.quickme.at"

    private val canonical = Json { encodeDefaults = true; explicitNulls = true }

    /** The bytes the device key signs: the approval as compact JSON, every field present. */
    fun message(approval: Approval): ByteArray =
        canonical.encodeToString(Approval.serializer(), approval).encodeToByteArray()

    /** What the device key signs for [approval] (SshSig.signedData of [message]). */
    fun signedData(approval: Approval): ByteArray = SshSig.signedData(message(approval), NAMESPACE)

    /** The armoured signature from the key's DER signature of [signedData]. */
    fun signature(point: ByteArray, derSignature: ByteArray): String = SshSig.armor(NAMESPACE, point, derSignature)

    private val historyJson = Json { ignoreUnknownKeys = true }

    /** The history as the apps store it. */
    fun encodeHistory(history: List<Decision>): String =
        historyJson.encodeToString(ListSerializer(Decision.serializer()), history)

    /** A stored history; an unreadable one is empty. */
    fun decodeHistory(text: String): List<Decision> =
        if (text.isBlank()) emptyList()
        else runCatching { historyJson.decodeFromString(ListSerializer(Decision.serializer()), text) }.getOrDefault(emptyList())
}

/**
 * The approvals waiting for the owner, oldest first, and the last decisions.
 * Thread-safe; every answer goes to its channel off the caller's thread.
 * [onHistory] is called with the new history after every decision, for the
 * app to store ([Approvals.encodeHistory]).
 */
class ApprovalQueue(
    history: List<Decision> = emptyList(),
    private val onHistory: (List<Decision>) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channels = MutableStateFlow<Map<String, ApprovalChannel>>(emptyMap())
    private val pendingState = MutableStateFlow<List<Approval>>(emptyList())
    private val historyState = MutableStateFlow(history.take(HISTORY_LENGTH))

    val pending: StateFlow<List<Approval>> = pendingState.asStateFlow()
    val history: StateFlow<List<Decision>> = historyState.asStateFlow()

    /** For Swift: [pending] and [history] as Watchables. */
    fun watchPending(): Watchable<List<Approval>> = Watchable(pending)
    fun watchHistory(): Watchable<List<Decision>> = Watchable(history)

    fun get(id: String): Approval? = pendingState.value.firstOrNull { it.id == id }

    /** Updates the displayed scope before authenticating and signing it. */
    fun choose(id: String, decision: String): Approval? {
        val approval = get(id) ?: return null
        val chosen = Approvals.choosing(approval, decision)
        pendingState.update { list -> list.map { if (it.id == id) chosen else it } }
        return chosen
    }

    /** Adds [approval]; false for a repeated ID or one that has expired already. */
    fun offer(approval: Approval, channel: ApprovalChannel, now: Long = epochSeconds()): Boolean {
        if (approval.expiresAt != 0L && approval.expiresAt <= now) return false
        val before = channels.getAndUpdate { if (approval.id in it) it else it + (approval.id to channel) }
        if (approval.id in before) return false
        pendingState.update { it + approval }
        return true
    }

    /**
     * Decides [id]: removes it, records it and sends the answer. [signature]
     * comes with APPROVED. False when it is no longer pending.
     */
    fun decide(id: String, outcome: Outcome, signature: String? = null, now: Long = epochSeconds()): Boolean {
        require((outcome == Outcome.APPROVED) == (signature != null)) { "a signature comes with APPROVED, and only then" }
        val before = pendingState.getAndUpdate { list -> list.filterNot { it.id == id } }
        val approval = before.firstOrNull { it.id == id } ?: return false
        val channel = channels.getAndUpdate { it - id }[id]
        historyState.update { (listOf(Decision(now, outcome, approval.title, approval.source)) + it).take(HISTORY_LENGTH) }
        onHistory(historyState.value)
        if (channel != null) scope.launch { runCatching { channel.answer(approval, outcome, signature) } }
        return true
    }

    /** Answers every approval past its expiry as EXPIRED; the apps call it every few seconds. */
    fun expire(now: Long = epochSeconds()) {
        pendingState.value.filter { it.expiresAt != 0L && it.expiresAt <= now }.forEach { decide(it.id, Outcome.EXPIRED, now = now) }
    }

    /** The asker took [id] back: it goes without an answer. */
    fun withdraw(id: String) {
        pendingState.update { list -> list.filterNot { it.id == id } }
        channels.update { it - id }
    }

    companion object {
        const val HISTORY_LENGTH = 20
    }
}

/** Unix seconds now (each platform's source set has the actual). */
expect fun epochSeconds(): Long

/** [count] cryptographically random bytes (each platform's source set has the actual). */
internal expect fun secureRandom(count: Int): ByteArray
