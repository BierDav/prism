// The hand-off to T3 Code: what needs a computer (coding, servers, the
// cluster, long research) goes to a T3 Code agent through Prism's API
// (PrismApi, docs/prism-api.md), on the machine Prism picks ("auto"), with
// the placement profile the owner set for the assistant's agents (Phone ›
// Profiles › Actions › Assistant's agents, AgentProfile) or the one they
// named, and the project the model chose. Follow-ups go into an existing thread. The overlay
// then follows the agent (AgentConversation) as it does a direct start.

package at.quickme.prism.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log
import at.quickme.prism.AgentProfile
import at.quickme.prism.PrismApi
import at.quickme.prism.PrismPush
import at.quickme.prism.assistant.core.AgentEntry
import at.quickme.prism.assistant.core.AgentsCard
import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.core.ToolArgumentException
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.shared.PlacementProfile
import at.quickme.prism.shared.PrismClient

/** Prism picks the machine (and, for "auto", the project) from the request. */
internal const val AUTO = "auto"

internal fun t3Tools(c: Context): List<AssistantTool> = listOf(
    object : AssistantTool(
        Tools.T3_PROJECTS,
        "The projects (repositories and folders) T3 Code agents can work in, with their IDs, for start_t3_agent.",
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val projects = PrismApi.call(c) { projects() }.projects
            if (projects.isEmpty()) return ToolOutput("No projects; use \"\" or \"auto\".")
            return ToolOutput(projects.joinToString("\n") { p ->
                "${p.id}: ${p.name}" + (if (p.repository) " (repository)" else "") +
                    (if (p.machines.isNotEmpty()) " on ${p.machines.joinToString(", ")}" else "")
            })
        }
    },
    object : AssistantTool(
        Tools.T3_PROFILES,
        "The placement profiles T3 Code agents can run on (each an ordered list of models), with their IDs, for start_t3_agent's profile, and which one is the owner's default.",
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val profiles = PrismApi.call(c) { placement() }.profiles
            if (profiles.isEmpty()) return ToolOutput("No profiles; agents start on the default model.")
            val default = AgentProfile.get(c)
            return ToolOutput(profiles.joinToString("\n") { p ->
                val models = p.configuredEntries.filter { it.enabled }.joinToString(", ") { it.displayLabel }
                "${p.id}: ${p.displayLabel}" + (if (p.id == default) " (default)" else "") + (if (models.isNotEmpty()) " — $models" else "")
            })
        }
    },
    object : AssistantTool(
        Tools.START_T3,
        "Hand a request that needs a computer (coding, repositories, servers, the cluster, long research, anything beyond your tools) to a new T3 Code agent. The machine is chosen automatically. The overlay then shows the agent's progress.",
        required = listOf(
            stringParam("request", "The task for the agent, standing alone: what to do and every detail it needs"),
            stringParam("project", "A project ID from list_t3_projects, \"\" for no project (scratch folder), or \"auto\" to let Prism choose"),
        ),
        optional = listOf(
            stringParam("profile", "Only when the owner names one: a placement profile's ID or name (list_t3_profiles); left out, the owner's default"),
        ),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val request = args.string("request")
            val project = args.optString("project")?.trim() ?: AUTO
            val profile = args.optString("profile")?.trim()?.takeIf { it.isNotEmpty() }?.let { named ->
                val known = PrismApi.call(c) { placement() }.profiles
                profileNamed(known, named)?.id
                    ?: throw ToolArgumentException("No profile \"$named\". Known: " + known.joinToString(", ") { "${it.id} (${it.displayLabel})" } + "; or leave profile out.")
            }
            val requestId = PrismClient.requestId()
            val start = SystemClock.elapsedRealtime()
            // The handoff card follows this request ID to the actual outcome.
            // Keep the assistant's turn moving once the request is queued.
            val agent = PrismApi.call(c, waitSeconds = 1) { startAgent("do", AUTO, request, requestId, AgentProfile.model(c, profile), project) }
            val status = if (agent.id.isEmpty()) "queued" else "started"
            Log.i(AssistantSession.LATENCY, "T3 handoff $status in ${SystemClock.elapsedRealtime() - start}ms (request $requestId)")
            PrismPush.changed()
            val card = HandoffCard(request, requestId, agent.id, project, agent.title, profile = profile.orEmpty())
            return ToolOutput(
                if (agent.id.isNotEmpty()) "Started T3 agent ${agent.id}" + (agent.title.takeIf { it.isNotEmpty() }?.let { " (\"$it\")" } ?: "") + ". The overlay shows its progress."
                else "The launch request is queued. The overlay will show the agent when it starts, or the error if it fails.",
                card,
            )
        }
    },
    object : AssistantTool(
        Tools.LIST_T3,
        "Recent T3 Code agents (threads) with their IDs, titles and states, newest first.",
        optional = listOf(choiceParam("state", "Which (default recent)", "active", "recent", "all")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val state = args.optChoice("state", listOf("active", "recent", "all")) ?: "recent"
            val agents = PrismApi.call(c) { agents(state) }.take(15)
            if (agents.isEmpty()) return ToolOutput("No $state agents.")
            val list = agents.map { AgentEntry(it.id, it.displayTitle, it.status, it.target) }
            return ToolOutput(list.joinToString("\n") { "${it.id}: ${it.title} [${it.state}, ${it.target}]" }, AgentsCard(list))
        }
    },
    object : AssistantTool(
        Tools.SEND_T3,
        "Post a message from the owner into an existing T3 Code thread: a new turn if the agent is idle, else it steers the running one.",
        required = listOf(stringParam("id", "The agent's ID (list_t3_threads)"), stringParam("text", "The message")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val id = args.string("id")
            val text = args.string("text")
            val agent = PrismApi.call(c) { sendToAgent(id, text, PrismClient.requestId()) }
            return ToolOutput(
                "Sent to \"${agent.displayTitle}\" (${agent.id}).",
                HandoffCard(text, "", agent.id, title = agent.displayTitle, followUp = true),
            )
        }
    },
)

/** The profile [named] means: its ID, else its name, ignoring case. */
private fun profileNamed(profiles: List<PlacementProfile>, named: String): PlacementProfile? =
    profiles.firstOrNull { it.id == named }
        ?: profiles.firstOrNull { it.id.equals(named, ignoreCase = true) || it.displayLabel.equals(named, ignoreCase = true) }
