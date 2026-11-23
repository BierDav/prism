package at.quickme.prism.shared

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Prism's push (docs/prism-api.md, "Push"): the ntfy topic prism-events on
// the owner's relay, read with the device's relay token. A push only
// notifies; the API stays the source of truth, so a lost message is repaired
// by the next list call.

/** One prism-events message (the ntfy message's `message` field). */
@Serializable
data class PushMessage(
    val v: Int = 0,
    /** `alert.firing`, `alert.ended`, `agent.started`, `agent.question`, `question.closed`,
     *  `agent.finished`, `notice`, `app.release`, `app.settings`, `placement.changed`, or something newer. */
    val type: String = "",
    /** Drop a message whose key was handled ([SeenKeys]). */
    val key: String = "",
    val time: Long = 0,
    /** Plain text. */
    val title: String = "",
    val body: String = "",
    val severity: String = "",
    val alertId: Long = 0,
    val agentId: String = "",
    val questionId: String = "",
    val host: String = "",
    val environmentId: String = "",
    val threadId: String = "",
    val state: String = "",
    @SerialName("package") val packageName: String = "",
    val versionCode: Long = 0,
    val device: String = "",
) {
    /** Recent enough to notify: old news from a catch-up only refreshes. */
    fun fresh(now: Long): Boolean = time == 0L || now - time < 15 * 60

    /** The tag to show or remove its notification by: one per alert, per
     *  thread for agents (a question and its answer), per key otherwise. */
    val notificationTag: String
        get() = when (type) {
            "alert.firing", "alert.ended" -> "alert-$alertId"
            "agent.question", "agent.finished", "question.closed" -> "agent-" + agentId.ifEmpty { threadId }.ifEmpty { key }
            else -> key
        }

    companion object {
        /** The message in an ntfy message's body, or null when it is not one of version 1. */
        fun parse(body: String): PushMessage? = try {
            PrismClient.json.decodeFromString(serializer(), body).takeIf { it.v == 1 && it.type.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}

/** The keys of the messages already handled, at most [limit]; [onChange]
 *  keeps them (newest last) across restarts. */
class SeenKeys(initial: List<String>, private val limit: Int, private val onChange: (List<String>) -> Unit) {
    private val keys = ArrayDeque(initial.filter { it.isNotEmpty() }.takeLast(limit))

    /** Whether [key] is new, remembering it; an empty key is always new. */
    fun firstTime(key: String): Boolean {
        if (key.isEmpty()) return true
        if (key in keys) return false
        keys.addLast(key)
        while (keys.size > limit) keys.removeFirst()
        onChange(keys.toList())
        return true
    }
}

/** The stream's state, for a status line. */
data class RelayStatus(val connected: Boolean, val text: String)

/**
 * Reads prism-events on the relay at [base] with [token] (null or empty: not
 * configured, tried again every minute). [messages] runs while collected:
 * it reconnects with backoff and resumes [since] the last message seen
 * ([onSince] keeps it), or `1h` on first start. [reconnect] drops the
 * current connection at once (new network, wake, new settings).
 */
class RelayStream(
    private val base: () -> String,
    private val token: () -> String?,
    since: String,
    private val onSince: (String) -> Unit,
) {
    private val _status = MutableStateFlow(RelayStatus(false, "not started"))
    val status: StateFlow<RelayStatus> = _status.asStateFlow()
    fun watchStatus(): Watchable<RelayStatus> = Watchable(status)

    private var since = since.ifEmpty { "1h" }
    private var current: Job? = null

    fun reconnect() {
        current?.cancel()
    }

    val messages: Flow<PushMessage> = channelFlow {
        // A wait that reconnect() cuts short, like a connection.
        suspend fun pause(ms: Long) {
            val job = launch { delay(ms) }
            current = job
            job.join()
            current = null
        }
        var backoff = 2_000L
        while (true) {
            val url = base().trimEnd('/')
            val token = token()
            if (url.isEmpty() || token.isNullOrEmpty()) {
                _status.value = RelayStatus(false, "not configured (relay URL and token)")
                pause(60_000)
                continue
            }
            val started = epochSeconds()
            var wait: Long? = null
            val job = launch {
                try {
                    client.prepareGet("$url/$TOPIC/json?since=${T3Links.encode(this@RelayStream.since)}") {
                        bearerAuth(token)
                    }.execute { response ->
                        val code = response.status.value
                        if (code == 403) {
                            _status.value = RelayStatus(false, "not allowed to read $TOPIC (relay ACL)")
                            wait = 15 * 60_000L
                            return@execute
                        }
                        if (code != 200) throw IllegalStateException("HTTP $code from the relay")
                        _status.value = RelayStatus(true, "connected")
                        val input = response.bodyAsChannel()
                        while (true) {
                            val line = input.readLine() ?: break
                            val event = try {
                                PrismClient.json.parseToJsonElement(line).jsonObject
                            } catch (e: Exception) {
                                continue
                            }
                            if (event["event"]?.jsonPrimitive?.contentOrNull != "message") continue
                            val message = event["message"]?.jsonPrimitive?.contentOrNull?.let(PushMessage::parse)
                            if (message != null) send(message)
                            event["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                this@RelayStream.since = it
                                onSince(it)
                            }
                        }
                        _status.value = RelayStatus(false, "reconnecting: the relay closed the stream")
                    }
                } catch (e: CancellationException) {
                    _status.value = RelayStatus(false, "reconnecting")
                    throw e
                } catch (e: Exception) {
                    _status.value = RelayStatus(false, "reconnecting: ${e.message ?: e::class.simpleName}")
                }
            }
            current = job
            job.join()
            current = null
            wait?.let {
                pause(it)
                continue
            }
            if (epochSeconds() - started > 60) backoff = 2_000
            pause(backoff)
            backoff = minOf(backoff * 2, 120_000)
        }
    }

    /** [messages] for Swift. */
    fun watch(): Watchable<PushMessage> = Watchable(messages)

    companion object {
        const val TOPIC = "prism-events"

        // ntfy sends a keepalive every 45 s; longer silence means a dead link.
        private val client by lazy {
            platformHttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 120_000
                }
            }
        }
    }
}
