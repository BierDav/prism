@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.experimental.ExperimentalObjCRefinement::class)

package at.quickme.prism.shared

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.native.HiddenFromObjC

/**
 * A failed call: the server's `error` text for the owner (safe to show
 * verbatim) with its HTTP status and `code`, or status 0 and code
 * `unreachable` when no answer came.
 */
class PrismError(val status: Int, val code: String, message: String) : Exception(message) {
    /** The server no longer accepts the token (or there is none): enroll again. */
    val unauthorized: Boolean get() = status == 401
}

/** One HTTP exchange; the apps' sample modes answer with their own. */
fun interface PrismTransport {
    @Throws(Exception::class)
    suspend fun send(request: PrismRequest): PrismResponse
}

/** [path] starts with `/v1/`; [body] is JSON or null. */
data class PrismRequest(val method: String, val url: String, val path: String, val token: String?, val body: String?)

data class PrismResponse(val status: Int, val body: String)

/**
 * Prism's API (docs/prism-api.md). The apps get theirs from their replica
 * ([PrismReplica.client]), whose [transport] answers every call on the
 * device; with the default transport it calls a server at [base] (e.g.
 * https://prism.quickme.at, a trailing /v1 is dropped) with [token] as the
 * bearer token. Every call throws [PrismError].
 */
class PrismClient(base: String, private val token: () -> String?, private val transport: PrismTransport) {
    constructor(base: String, token: () -> String?) : this(base, token, KtorTransport)

    private val origin = base.trimEnd('/').removeSuffix("/v1")

    /** GET /v1/health: the server is up. Never throws. */
    suspend fun healthy(): Boolean = try {
        decode(send("GET", "/v1/health", null, authorized = false), JsonObject.serializer())["ok"]
            ?.jsonPrimitive?.booleanOrNull == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    // --- Alerts ---

    /** [state]: `open`, `recent` or `all`. */
    @Throws(Exception::class)
    suspend fun alerts(state: String): List<Alert> =
        decode(send("GET", "/v1/alerts?state=${T3Links.encode(state)}"), AlertList.serializer()).alerts

    @Throws(Exception::class)
    suspend fun alert(id: Long): Alert = decode(send("GET", "/v1/alerts/$id"), Alert.serializer())

    /** Starts an agent on the alert; without [instruction] it investigates and fixes. */
    @Throws(Exception::class)
    suspend fun fix(id: Long, instruction: String?, requestId: String): Agent {
        val body = buildJsonObject {
            put("requestId", requestId)
            if (!instruction.isNullOrBlank()) put("instruction", instruction.trim())
        }
        return decode(send("POST", "/v1/alerts/$id/fix", body), AgentAnswer.serializer()).agent
    }

    // --- Agents ---

    /** [state]: `active`, `recent` or `all`. */
    @Throws(Exception::class)
    suspend fun agents(state: String): List<Agent> =
        decode(send("GET", "/v1/agents?state=${T3Links.encode(state)}"), AgentList.serializer()).agents

    /** Asks T3 to stop a Prism agent, without removing its thread. */
    @Throws(Exception::class)
    suspend fun stopAgent(id: String, requestId: String) {
        send("POST", "/v1/agents/${T3Links.encode(id)}/stop", buildJsonObject { put("requestId", requestId) })
    }

    /** The agent's latest steps, read live from its T3, oldest first. */
    @Throws(Exception::class)
    suspend fun agentActivity(id: String): AgentActivity =
        decode(send("GET", "/v1/agents/${T3Links.encode(id)}/activity"), AgentActivity.serializer())

    /** Sends a follow-up into the agent's thread: a new turn when it is idle, else it steers the running one. */
    @Throws(Exception::class)
    suspend fun sendToAgent(id: String, text: String, requestId: String): Agent {
        val body = buildJsonObject {
            put("text", text.trim())
            put("requestId", requestId)
        }
        return decode(send("POST", "/v1/agents/${T3Links.encode(id)}/messages", body), AgentAnswer.serializer()).agent
    }

    /** [task]: `fix`, `do`, `alert`, `deploy` or `retire`; [target] a machine, or null for the cluster. */
    @Throws(Exception::class)
    suspend fun startAgent(task: String, target: String?, request: String, requestId: String): Agent =
        startAgent(task, target, request, requestId, model = null, projectId = null)

    /** An explicit model is per launch; an empty [projectId] requests a scratch folder. */
    @Throws(Exception::class)
    suspend fun startAgent(
        task: String, target: String?, request: String, requestId: String,
        model: ModelChoice?, projectId: String?,
    ): Agent {
        val body = buildJsonObject {
            put("task", task)
            if (!target.isNullOrEmpty()) put("target", target)
            put("request", request)
            put("requestId", requestId)
            if (projectId != null) put("projectId", projectId)
            if (model != null) put("model", buildJsonObject {
                put("instanceId", model.instanceId)
                put("model", model.model)
            })
        }
        return decode(send("POST", "/v1/agents", body), AgentAnswer.serializer()).agent
    }

    @Throws(Exception::class)
    suspend fun agentOptions(target: String): AgentOptions =
        decode(send("GET", "/v1/agent-options?target=${T3Links.encode(target)}"), AgentOptions.serializer())

    // --- Questions ---

    @Throws(Exception::class)
    suspend fun approvals(): List<Approval> =
        decode(send("GET", "/v1/approvals"), ApprovalList.serializer()).approvals

    @Throws(Exception::class)
    suspend fun answerApproval(approval: Approval, signature: String?) {
        send("POST", "/v1/approvals/${T3Links.encode(approval.id)}/answer", buildJsonObject {
            put("message", Base64.encode(Approvals.message(approval)))
            if (signature != null) put("signature", signature)
        })
    }

    /** The open questions, oldest first. */
    @Throws(Exception::class)
    suspend fun questions(): List<Question> =
        decode(send("GET", "/v1/questions"), QuestionList.serializer()).questions

    /**
     * Answers every item of [question]: [answers] maps an item's id to the
     * chosen option labels, or to one free text. A multiSelect item's labels
     * go as a list; anything else as one string. 404: no longer pending.
     */
    @Throws(Exception::class)
    suspend fun answer(question: Question, answers: Map<String, List<String>>, requestId: String) {
        send("POST", "/v1/questions/${T3Links.encode(question.id)}/answer", answerBody(question, answers, requestId))
    }

    // --- Machines ---

    /** Asks every machine's T3 (up to 10 s): load it when needed, never poll it. */
    @Throws(Exception::class)
    suspend fun machines(): Machines = decode(send("GET", "/v1/machines"), Machines.serializer())

    /** Every machine's projects, the same whichever machine an agent runs on. */
    @Throws(Exception::class)
    suspend fun projects(): ProjectCatalog = decode(send("GET", "/v1/projects"), ProjectCatalog.serializer())

    /** Shared by the server for 15 s; reload at most every 30 s while shown. */
    @Throws(Exception::class)
    suspend fun hostMetrics(): HostMetricsList = decode(send("GET", "/v1/machines/metrics"), HostMetricsList.serializer())

    // --- Model and reasoning ---

    @Throws(Exception::class)
    suspend fun modelSettings(): ModelSettings = decode(send("GET", "/v1/settings/model"), ModelSettings.serializer())

    @Throws(Exception::class)
    suspend fun setModel(model: ModelChoice): ModelSettings {
        val body = buildJsonObject {
            put("instanceId", model.instanceId)
            put("model", model.model)
        }
        return decode(send("PUT", "/v1/settings/model", body), ModelSettings.serializer())
    }

    /** A level, or "default". */
    @Throws(Exception::class)
    suspend fun setReasoning(level: String): ModelSettings =
        decode(send("PUT", "/v1/settings/reasoning", buildJsonObject { put("level", level) }), ModelSettings.serializer())

    // --- The phone's settings ---

    /** GET /v1/android/settings: the calling device's published settings, or null when none are. */
    @Throws(Exception::class)
    suspend fun androidSettings(): AndroidSettings? = try {
        decode(send("GET", "/v1/android/settings"), AndroidSettings.serializer())
    } catch (e: PrismError) {
        if (e.status == 404) null else throw e
    }

    // --- Placement ---

    @Throws(Exception::class)
    suspend fun placement(): Placement = decode(send("GET", "/v1/placement"), Placement.serializer())

    /** Creates or replaces a profile; entries are stored in order. */
    @Throws(Exception::class)
    suspend fun savePlacementProfile(id: String, label: String, entries: List<PlacementEntry>): Placement {
        val body = buildJsonObject {
            put("label", label)
            put("entries", JsonArray(entries.map { e ->
                buildJsonObject {
                    put("instanceId", e.instanceId)
                    put("model", e.model)
                    put("reasoning", e.reasoning)
                    put("enabled", e.enabled)
                    e.sessionShareOfWeekly?.let { put("sessionShareOfWeekly", it) }
                }
            }))
        }
        return decode(send("PUT", "/v1/placement/profiles/${T3Links.encode(id)}", body), Placement.serializer())
    }

    /** Removes a profile; the server answers 409 for the alert-fix profile. */
    @Throws(Exception::class)
    suspend fun deletePlacementProfile(id: String): Placement =
        decode(send("DELETE", "/v1/placement/profiles/${T3Links.encode(id)}"), Placement.serializer())

    /** Orders the profiles. */
    @Throws(Exception::class)
    suspend fun setPlacementOrder(ids: List<String>): Placement =
        decode(
            send("PUT", "/v1/placement/order", buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }),
            Placement.serializer(),
        )

    /** Sets the profile used for alert fixes. */
    @Throws(Exception::class)
    suspend fun setAlertFixProfile(profile: String): Placement =
        decode(
            send("PUT", "/v1/placement/alert-fix-profile", buildJsonObject { put("profile", profile) }),
            Placement.serializer(),
        )

    /**
     * Sets which profile each kind of work uses (PUT /v1/placement/actions).
     * Null arguments leave that setting alone; pass `""` for [textGeneration] to clear it,
     * and map a role to `""` in [roleProfiles] to remove it (inherit parent thread's profile).
     */
    @Throws(Exception::class)
    suspend fun setPlacementActions(
        alertFix: String? = null,
        textGeneration: String? = null,
        roleProfiles: Map<String, String>? = null,
    ): Placement {
        val body = buildJsonObject {
            if (alertFix != null) put("alertFix", alertFix)
            if (textGeneration != null) put("textGeneration", textGeneration)
            if (roleProfiles != null) {
                put("roleProfiles", buildJsonObject {
                    for ((role, profile) in roleProfiles) put(role, profile)
                })
            }
        }
        return decode(send("PUT", "/v1/placement/actions", body), Placement.serializer())
    }

    /** Updates the placement policy; its [PlacementPolicy.accountLimits] are not sent (the server keeps its own). */
    @Throws(Exception::class)
    suspend fun setPlacementPolicy(policy: PlacementPolicy): Placement =
        decode(
            send("PUT", "/v1/placement/policy", json.encodeToJsonElement(PlacementPolicy.serializer(), policy.copy(accountLimits = emptyMap()))),
            Placement.serializer(),
        )

    // --- The assistant's conversations ---

    /** Every device's assistant conversations, the newest first. */
    @Throws(Exception::class)
    suspend fun assistantConversations(): List<AssistantConversation> =
        decode(send("GET", "/v1/assistant/conversations"), ConversationList.serializer()).conversations

    /** Conversation [id] with its entries and history; 404 when there is none. */
    @Throws(Exception::class)
    suspend fun assistantConversation(id: String): StoredConversation =
        decode(send("GET", "/v1/assistant/conversations/${T3Links.encode(id)}"), StoredConversation.serializer())

    /**
     * Stores conversation [id] as it is now, as the revision after [revision]
     * (0 for a new one), and returns the committed revision. Only what changed
     * is written, and a save cut short is never seen, so the same call again
     * after a failure is safe. Each entry's and message's JSON is at most
     * 16 KiB. [saveId] names the revision this save makes (one per version):
     * sent again after its answer was lost, a save that committed gets the
     * same answer. Fails with 409 (`conflict`) when the conversation moved on
     * since [revision], 410 (`gone`) once it was deleted.
     */
    @Throws(Exception::class)
    suspend fun saveAssistantConversation(
        id: String,
        revision: Long,
        saveId: String,
        title: String,
        createdAt: Long,
        updatedAt: Long,
        entries: List<StoredEntry>,
        history: List<String>,
    ): Long {
        val body = buildJsonObject {
            put("revision", revision)
            put("saveId", saveId)
            put("title", title)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("entries", JsonArray(entries.map { e -> buildJsonObject { put("id", e.id); put("body", e.body) } }))
            put("history", JsonArray(history.map { JsonPrimitive(it) }))
        }
        return decode(send("PUT", "/v1/assistant/conversations/${T3Links.encode(id)}", body), ConversationAnswer.serializer()).conversation.revision
    }

    /** Deletes conversation [id] everywhere. */
    @Throws(Exception::class)
    suspend fun deleteAssistantConversation(id: String) {
        send("DELETE", "/v1/assistant/conversations/${T3Links.encode(id)}")
    }

    /** Sets [account]'s temporary limit (PUT /v1/placement/account-limits/{account}). */
    @Throws(Exception::class)
    suspend fun setAccountLimit(account: String, limit: PlacementAccountLimit): Placement =
        decode(
            send(
                "PUT", "/v1/placement/account-limits/${T3Links.encode(account)}",
                json.encodeToJsonElement(PlacementAccountLimit.serializer(), limit),
            ),
            Placement.serializer(),
        )

    /** Removes [account]'s temporary limit. */
    @Throws(Exception::class)
    suspend fun clearAccountLimit(account: String): Placement =
        decode(send("DELETE", "/v1/placement/account-limits/${T3Links.encode(account)}"), Placement.serializer())

    /** Asks the phone to place an outgoing call to [number]. */
    @Throws(Exception::class)
    suspend fun dialCall(number: String, requestId: String = requestId()) {
        send(
            "POST",
            "/v1/calls/dial",
            buildJsonObject {
                put("number", number)
                put("request_id", requestId)
            },
        )
    }

    /** Pending outgoing call requests from a Mac. */
    @Throws(Exception::class)
    suspend fun pendingCallRequests(): List<CallRequest> =
        decode(send("GET", "/v1/calls/pending"), CallRequestList.serializer()).requests

    /** Marks a call request done (with an optional error message). */
    @Throws(Exception::class)
    suspend fun finishCallRequest(requestId: String, error: String? = null) {
        send(
            "POST",
            "/v1/calls/${T3Links.encode(requestId)}/done",
            buildJsonObject {
                if (error != null) put("error", error)
            },
        )
    }

    // --- The exchange ---

    private suspend fun send(method: String, path: String, body: JsonElement? = null, authorized: Boolean = true): String {
        val token = if (authorized) token()?.takeIf { it.isNotEmpty() } ?: throw PrismError(401, "unauthorized", NOT_ENROLLED) else null
        val response = try {
            transport.send(PrismRequest(method, origin + path, path, token, body?.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrismError) {
            throw e
        } catch (e: Exception) {
            throw PrismError(0, "unreachable", "Prism is not reachable (${e.message ?: e::class.simpleName}).")
        }
        if (response.status / 100 != 2) {
            val error = try {
                json.parseToJsonElement(response.body).jsonObject
            } catch (e: Exception) {
                null
            }
            val text = error?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: ""
            throw PrismError(response.status, code, text ?: "The Prism server answered HTTP ${response.status}.")
        }
        return response.body
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: Exception) {
        throw PrismError(200, "invalid", "The Prism server's answer was not understood (${e.message?.lineSequence()?.firstOrNull()}).")
    }

    companion object {
        const val NOT_ENROLLED = "This device is not enrolled with Prism yet."

        /** The JSON of the server's answers: unknown fields ignored, null as the default. */
        @HiddenFromObjC
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** A fresh requestId for one tap; reuse it when retrying that tap. */
        fun requestId(): String {
            val b = secureRandom(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        /** The body of POST /v1/questions/{id}/answer. */
        @HiddenFromObjC
        fun answerBody(question: Question, answers: Map<String, List<String>>, requestId: String): JsonObject =
            buildJsonObject {
                put(
                    "answers",
                    buildJsonObject {
                        for (item in question.questions) {
                            val chosen = answers[item.id].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                            if (chosen.isEmpty()) continue
                            val labels = item.options.map { it.label }.toSet()
                            val value: JsonElement =
                                if (item.multiSelect && chosen.all { it in labels }) JsonArray(chosen.map { JsonPrimitive(it) })
                                else JsonPrimitive(chosen.joinToString(", "))
                            put(item.id, value)
                        }
                    },
                )
                put("requestId", requestId)
            }
    }
}

@Serializable
private class AlertList(val alerts: List<Alert> = emptyList())

@Serializable
private class AgentList(val agents: List<Agent> = emptyList())

@Serializable
private class AgentAnswer(val agent: Agent)

@Serializable
private class QuestionList(val questions: List<Question> = emptyList())

@Serializable
private class ConversationList(val conversations: List<AssistantConversation> = emptyList())

@Serializable
private class ConversationAnswer(val conversation: AssistantConversation)

@Serializable
private class ApprovalList(val approvals: List<Approval> = emptyList())

/** The platform's Ktor client (OkHttp, NSURLSession) for the API: 120 s per
 *  request, since actions that start agents ask the target's T3 first. */
private object KtorTransport : PrismTransport {
    private val client by lazy {
        platformHttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 120_000
            }
        }
    }

    override suspend fun send(request: PrismRequest): PrismResponse {
        val response = client.request(request.url) {
            method = HttpMethod.parse(request.method)
            header("Accept", "application/json")
            request.token?.let { bearerAuth(it) }
            request.body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        return PrismResponse(response.status.value, response.bodyAsText())
    }
}

