// The assistant overlay's on-device agent, built with JetBrains Koog: a
// Koog agent with a tool registry (the phone's tools: PhoneTools.kt,
// MatrixTools.kt, T3Tools.kt) and a functional strategy that runs the tool
// loop itself, so the overlay sees each step as it happens (Event: a line
// while a tool runs, its card when it ran, the answer).
//
// The model is behind the owner's gateway (setting assistant_url, by default
// https://ai.quickme.at: CLIProxyAPI, OpenAI's chat completions with tools
// and image input), reached with Koog's OpenAI client on a custom base URL
// over Ktor and OkHttp. Every request names this phone in X-Fleet-Host, as
// fleet's agents name their host, so the gateway logs usage per device.
//
// A run continues the conversation it is given (the messages of earlier runs,
// tool calls and results included) and returns it with the new turn; the
// overlay keeps it for its resume window (AssistantSession). No Android in
// this file: it runs on a plain JVM too (the gateway check).

package at.quickme.prism.assistant

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.Tools
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Where the model is: the gateway's base URL, its key, the model, and this phone's name for X-Fleet-Host. */
data class Gateway(val url: String, val apiKey: String, val model: String, val host: String)

/** What a tool did: [text] for the model, a [card] for the transcript, an [image] the model should see. */
data class ToolOutput(val text: String, val card: Card? = null, val image: AssistAttachment? = null)

/**
 * A tool of the assistant: its name, what it does (the model reads this),
 * its parameters, and what it does on this phone. A [ToolOutput]'s text is
 * the result the model gets; throw (ToolArgumentException for arguments)
 * and the model gets the message instead.
 */
abstract class AssistantTool(
    val name: String,
    val description: String,
    val required: List<ToolParameterDescriptor> = emptyList(),
    val optional: List<ToolParameterDescriptor> = emptyList(),
) {
    abstract suspend fun run(args: Args): ToolOutput
}

fun stringParam(name: String, description: String) = ToolParameterDescriptor(name, description, ToolParameterType.String)
fun intParam(name: String, description: String) = ToolParameterDescriptor(name, description, ToolParameterType.Integer)
fun boolParam(name: String, description: String) = ToolParameterDescriptor(name, description, ToolParameterType.Boolean)
fun choiceParam(name: String, description: String, vararg choices: String) =
    ToolParameterDescriptor(name, description, ToolParameterType.Enum(arrayOf(*choices)))
fun intsParam(name: String, description: String) =
    ToolParameterDescriptor(name, description, ToolParameterType.List(ToolParameterType.Integer))

class Harness(
    private val gateway: Gateway,
    private val tools: List<AssistantTool>,
    /** The system prompt, made for each run (it carries the time and what the overlay shows). */
    private val systemPrompt: () -> String,
    /** How attached and captured images go to the model (scaled down on the phone). */
    private val prepareImage: (AssistAttachment) -> AssistAttachment = { it },
) : AutoCloseable {
    private val http = HttpClient(OkHttp) {
        engine {
            addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Fleet-Host", gateway.host).build())
            }
        }
    }

    // A 502 from the gateway now and then is retried (Koog's retrying client:
    // three tries, backing off from a second).
    private val executor = MultiLLMPromptExecutor(
        RetryingLLMClient(
            OpenAILLMClient(
                apiKey = gateway.apiKey,
                settings = OpenAIClientSettings(
                    baseUrl = gateway.url.trimEnd('/'),
                    timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = 180_000, socketTimeoutMillis = 180_000),
                ),
                httpClientFactory = KtorKoogHttpClient.Factory(http),
            ),
            RetryConfig(),
        ),
    )

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = gateway.model,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Temperature,
            LLMCapability.Vision.Image,
            LLMCapability.Document,
            LLMCapability.OpenAIEndpoint.Completions,
        ),
        contextLength = 1_000_000,
    )

    /** A run's input: the owner's words and what they attached. */
    private class Turn(val text: String, val attachments: List<AssistAttachment>, val note: String)

    /**
     * Runs one turn after [history]: the owner's [text] with [attachments],
     * the tools the model calls, its answer. Each step goes to [emit] as it
     * happens. Returns the conversation with this turn, as far as it got:
     * after a failure it still has the tools that ran (an SMS sent stays
     * sent, and the model must know), and the failure is thrown after
     * [saved] got it.
     */
    suspend fun run(
        history: List<Message>,
        text: String,
        attachments: List<AssistAttachment>,
        emit: suspend (Event) -> Unit,
        saved: (List<Message>) -> Unit,
        /** What happened since the last turn that the model should know (an edit made by hand on a card). */
        note: String = "",
    ): List<Message> {
        emit(Event.Said(text, attachments.map { it.name }))
        // The latest output of each tool call, by the tool that made it: tools
        // run one after another, so the one that just ran wrote it.
        var last: ToolOutput? = null
        // Why the last tool call failed, as the tool said it (Koog wraps it
        // in "Tool with name … failed to execute due to the error: …").
        var why: String? = null
        val registry = ToolRegistry {
            for (t in tools) tool(KoogTool(t, { last = it }, { why = it }))
        }
        var progress = sanitize(history)
        val strategy = functionalStrategy<Turn, List<Message>>("prism-assistant") { turn ->
            suspend fun remember() {
                progress = llm.readSession { prompt.messages }.filter { it !is Message.System }
                saved(progress)
            }
            appendPrompt { user { userParts(turn) } }
            var response = llm.writeSession { requestLLM() }
            remember()
            var rounds = 0
            while (true) {
                getTextParts(response).joinToString("") { it.text }.takeIf { it.isNotBlank() }?.let { emit(Event.Answer(it)) }
                val calls = getToolCalls(response)
                if (calls.isEmpty()) break
                val results = calls.mapIndexed { i, call ->
                    val callId = (call.id ?: "call").ifEmpty { "call" } + "#" + progress.size + "." + i
                    val args = runCatching { call.argsJson }.getOrDefault(JsonObject(emptyMap()))
                    emit(Event.ToolStarted(callId, call.tool, Tools.label(call.tool, args)))
                    last = null
                    why = null
                    val result = executeTool(call)
                    val output = last?.takeIf { result.resultKind is ToolResultKind.Success }
                    emit(Event.ToolFinished(callId, call.tool, output?.text ?: why ?: result.output, output?.card, failed = output == null))
                    result to output?.image
                }
                val images = results.mapNotNull { it.second }.map(prepareImage)
                rounds++
                llm.writeSession {
                    appendPrompt {
                        user {
                            results.forEach { (result, _) -> toolResult(result.toMessagePart()) }
                            images.forEach { image ->
                                part(MessagePart.Text("The screenshot: ${image.name}"))
                                image(imageSource(image))
                            }
                        }
                    }
                }
                // Kept before the next request: an SMS sent stays sent even
                // if that request fails or the overlay closes meanwhile.
                remember()
                response = llm.writeSession {
                    // Enough rounds: the model answers with what it has.
                    if (rounds >= MAX_ROUNDS) requestLLMWithoutTools() else requestLLM()
                }
                remember()
            }
            llm.readSession { prompt.messages }.filter { it !is Message.System }
        }
        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("prism-assistant") {
                    system(systemPrompt())
                    messages(progress)
                },
                model = model,
                maxAgentIterations = 100,
            ),
            strategy = strategy,
            toolRegistry = registry,
        )
        return agent.run(Turn(text, attachments, note))
    }

    private fun ai.koog.prompt.dsl.RequestMessagePartsBuilder.userParts(turn: Turn) {
        if (turn.note.isNotBlank()) part(MessagePart.Text("[Since the last turn: ${turn.note}]"))
        part(MessagePart.Text(turn.text.ifBlank { "(no text)" }))
        for (a in turn.attachments) {
            when {
                a.isImage -> {
                    val image = prepareImage(a)
                    part(MessagePart.Text("Attached: ${image.name}"))
                    image(imageSource(image))
                }
                a.mimeType == "application/pdf" -> {
                    part(MessagePart.Text("Attached: ${a.name}"))
                    attachment(AttachmentSource.File(AttachmentContent.Binary.Bytes(a.bytes), "pdf", a.mimeType, a.name))
                }
                a.mimeType.startsWith("text/") || a.mimeType == "application/json" -> {
                    val content = a.bytes.decodeToString().let { if (it.length > MAX_TEXT_ATTACHMENT) it.take(MAX_TEXT_ATTACHMENT) + "\n[cut]" else it }
                    part(MessagePart.Text("Attached file ${a.name}:\n$content"))
                }
                else -> part(MessagePart.Text("Attached file ${a.name} (${a.mimeType}, ${a.bytes.size / 1024} KB), which can't be read here."))
            }
        }
    }

    override fun close() {
        http.close()
    }

    /** One of the assistant's tools as a Koog tool: JSON arguments in, its output's text to the model. */
    private class KoogTool(
        val tool: AssistantTool,
        val ran: (ToolOutput) -> Unit,
        val failed: (String) -> Unit,
    ) : Tool<JsonObject, ToolOutput>(
        argsType = typeToken<JsonObject>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(tool.name, tool.description, tool.required, tool.optional),
    ) {
        override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): JsonObject = rawArgs.toKotlinxJsonObject()

        override suspend fun execute(args: JsonObject): ToolOutput = try {
            tool.run(Args(args)).also(ran)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() }?.let(failed)
            throw e
        }

        override fun encodeResult(result: ToolOutput, serializer: JSONSerializer): JSONElement = JSONPrimitive(result.text)

        override fun encodeResultToString(result: ToolOutput, serializer: JSONSerializer): String = result.text
    }

    companion object {
        /** Tool rounds in one turn before the model must answer. */
        const val MAX_ROUNDS = 12
        private const val MAX_TEXT_ATTACHMENT = 60_000
        /** Messages kept between runs: the newest, from a turn's start. */
        private const val KEEP_MESSAGES = 60

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val listSerializer = ListSerializer(Message.serializer())

        fun imageSource(a: AssistAttachment) = AttachmentSource.Image(
            content = AttachmentContent.Binary.Bytes(a.bytes),
            format = a.mimeType.substringAfter('/').substringBefore(';').ifEmpty { "png" },
            mimeType = a.mimeType,
            fileName = a.name,
        )

        /**
         * The conversation as it can go to the model again: a tool call
         * without its results (a run stopped halfway) dropped with what
         * follows it, at most [KEEP_MESSAGES] messages from the start of a
         * turn.
         */
        fun sanitize(messages: List<Message>): List<Message> {
            val out = ArrayList<Message>()
            var i = 0
            while (i < messages.size) {
                val m = messages[i]
                if (m is Message.Assistant && m.parts.any { it is MessagePart.Tool.Call }) {
                    val calls = m.parts.filterIsInstance<MessagePart.Tool.Call>().mapNotNull { it.id }.toSet()
                    val next = messages.getOrNull(i + 1) as? Message.User
                    val answered = next?.parts?.filterIsInstance<MessagePart.Tool.Result>()?.mapNotNull { it.id }?.toSet().orEmpty()
                    if (next == null || !answered.containsAll(calls)) break
                }
                out.add(m)
                i++
            }
            if (out.size <= KEEP_MESSAGES) return out
            // Start at a turn: a user message that isn't tool results.
            val from = (out.size - KEEP_MESSAGES until out.size).firstOrNull { idx ->
                val m = out[idx]
                m is Message.User && m.parts.none { it is MessagePart.Tool.Result }
            } ?: return emptyList()
            return out.subList(from, out.size).toList()
        }

        /**
         * The conversation for storage: images and files replaced by their
         * names (the model saw them in their turn; they'd fill the phone's
         * preferences), then [sanitize]d.
         */
        fun encode(messages: List<Message>): String = json.encodeToString(listSerializer, light(messages))

        /**
         * The conversation for Rocky, one JSON per message (each a row,
         * Conversations.historyRows); as [encode], without images and files.
         */
        fun encodeEach(messages: List<Message>): List<String> =
            light(messages).map { json.encodeToString(Message.serializer(), it) }

        /** The conversation from its rows; messages that don't decode are left out. */
        fun decodeEach(rows: List<String>): List<Message> =
            sanitize(rows.mapNotNull { runCatching { json.decodeFromString(Message.serializer(), it) }.getOrNull() })

        private fun light(messages: List<Message>): List<Message> {
            val light = messages.map { m ->
                if (m !is Message.User) m else m.copy(parts = m.parts.map { p ->
                    when {
                        p is MessagePart.Attachment -> MessagePart.Text("[${p.source.fileName ?: "attachment"}, not kept]")
                        p is MessagePart.Tool.Result && p.parts.any { it is MessagePart.Attachment } ->
                            p.copy(parts = p.parts.filterIsInstance<MessagePart.Text>())
                        else -> p
                    }
                })
            }
            return sanitize(light)
        }

        fun decode(text: String): List<Message> =
            runCatching { sanitize(json.decodeFromString(listSerializer, text)) }.getOrDefault(emptyList())
    }
}
