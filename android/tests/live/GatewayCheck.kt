// The assistant harness (assistant/Harness.kt) against the real gateway on a
// plain JVM: Koog's tool loop end to end, with the real weather tool (Open-
// Meteo) and contacts and SMS tools that only record what they were asked.
// Not part of the Nix check (it needs the network and a key); run it by hand:
//
//   ASSISTANT_API_KEY=… kotlinc … && java … GatewayCheckKt
//
// (classpath: Koog's -jvm artifacts and the app's JVM libraries of
// deps/lock.json; see the comment at the end). It fails unless the model
// called the tools it should, without asking for confirmation, and kept the
// conversation across turns.

import at.quickme.prism.assistant.AssistantTool
import at.quickme.prism.assistant.Gateway
import at.quickme.prism.assistant.Harness
import at.quickme.prism.assistant.ToolOutput
import at.quickme.prism.assistant.WeatherService
import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.ContactEntry
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.SystemPrompt
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Weather
import at.quickme.prism.assistant.stringParam
import ai.koog.prompt.message.Message
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private val sent = ArrayList<Pair<String, String>>()

private val tools = listOf(
    object : AssistantTool(Tools.WEATHER, "Current weather and forecast for a place, or for where the phone is.",
        optional = listOf(stringParam("place", "A town or city; leave out for the phone's location"))) {
        override suspend fun run(args: Args): ToolOutput {
            val card = WeatherService.forPlace(args.optString("place") ?: "Vienna")
            return ToolOutput(Weather.summary(card), card)
        }
    },
    object : AssistantTool(Tools.FIND_CONTACTS, "Find contacts by name; returns names and numbers.",
        required = listOf(stringParam("query", "Part of the name"))) {
        override suspend fun run(args: Args): ToolOutput {
            val q = args.string("query")
            val found = listOf(ContactEntry("Anna Berger", "+43 660 1234567", "Mobile")).filter { it.name.contains(q, true) }
            return ToolOutput(if (found.isEmpty()) "No contact matches \"$q\"." else found.joinToString("\n") { "${it.name}: ${it.number} (${it.label})" }, ContactsCard(found))
        }
    },
    object : AssistantTool(Tools.SEND_SMS, "Send an SMS now (no confirmation). `to` is a number or a contact's name.",
        required = listOf(stringParam("to", "Number or contact name"), stringParam("text", "The message"))) {
        override suspend fun run(args: Args): ToolOutput {
            val to = args.string("to")
            val text = args.string("text")
            sent.add(to to text)
            return ToolOutput("Sent to Anna Berger (+43 660 1234567). SMS can't be edited.", MessageCard(MessageCard.SMS, "Anna Berger", "+436601234567", text))
        }
    },
)

fun main(): Unit = runBlocking {
    val key = System.getenv("ASSISTANT_API_KEY") ?: error("set ASSISTANT_API_KEY")
    val harness = Harness(
        Gateway(System.getenv("ASSISTANT_URL") ?: "https://ai.quickme.at", key, System.getenv("ASSISTANT_MODEL") ?: "agy-pro/gemini-4-neon", "gateway-check"),
        tools,
        systemPrompt = { SystemPrompt.build(SystemPrompt.Context("Tuesday, 24 November 2026, 18:15", "Europe/Vienna", "gateway-check")) },
    )
    var history: List<Message> = emptyList()
    val events = ArrayList<Event>()
    suspend fun turn(text: String) {
        println("\n> $text")
        history = harness.run(history, text, emptyList(), { e -> events.add(e); println("  $e".take(400)) }, {})
    }
    var failed = 0
    fun check(ok: Boolean, what: String) { println((if (ok) "ok   " else "FAIL ") + what); if (!ok) failed++ }

    turn("What's the weather in Graz?")
    check(events.any { it is Event.ToolFinished && it.tool == Tools.WEATHER && !it.failed }, "the weather tool ran")
    check(events.last() is Event.Answer, "an answer came after it")

    turn("Text Anna that I'll be there at 6")
    check(sent.size == 1 && "6" in sent[0].second, "the SMS went out without asking: $sent")

    turn("Actually make it 7")
    check(sent.size == 2 && "7" in sent[1].second, "a correction was sent (SMS can't be edited): $sent")
    check(history.size > 6, "the conversation kept its turns (${history.size} messages)")
    check(Harness.decode(Harness.encode(history)).size == history.size, "the conversation survives storage")
    harness.close()
    println(if (failed == 0) "all passed" else "$failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}

// Classpath used: every .jar of deps/lock.json, with ai.koog's and
// kotlin-logging's -android artifacts replaced by their -jvm ones (the
// Android ones log through android.util.Log), plus kotlin-stdlib.
