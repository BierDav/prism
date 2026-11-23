// Sample data, only in the debug build (application ID *.debug), which has
// no managed configuration and so reaches neither the relay nor Prism: the
// Prism API answers from here (Sample.client, typed objects sent as the
// server's JSON), and a sample approval can be raised (its
// answer goes to the log). The real app never sees any of it. The previews
// (android/previews) show the same data.

package at.quickme.prism

import android.content.Context
import at.quickme.prism.netbird.Netbird
import at.quickme.prism.shell.Protocol
import at.quickme.prism.shell.ShellApps
import at.quickme.prism.shared.ActivityItem
import at.quickme.prism.shared.Agent
import at.quickme.prism.shared.AgentActivity
import at.quickme.prism.shared.AgentOptions
import at.quickme.prism.shared.ClusterInfo
import at.quickme.prism.shared.AgentThread
import at.quickme.prism.shared.Alert
import at.quickme.prism.shared.Approval
import at.quickme.prism.shared.ApprovalDetail
import at.quickme.prism.shared.Decision
import at.quickme.prism.shared.HostMetrics
import at.quickme.prism.shared.HostMetricsList
import at.quickme.prism.shared.Machine
import at.quickme.prism.shared.Machines
import at.quickme.prism.shared.ModelChoice
import at.quickme.prism.shared.ModelSettings
import at.quickme.prism.shared.Outcome
import at.quickme.prism.shared.Placement
import at.quickme.prism.shared.PlacementActions
import at.quickme.prism.shared.PlacementEntry
import at.quickme.prism.shared.PlacementModel
import at.quickme.prism.shared.PlacementMove
import at.quickme.prism.shared.PlacementPolicy
import at.quickme.prism.shared.PlacementProfile
import at.quickme.prism.shared.PlacementReasoning
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismRequest
import at.quickme.prism.shared.PrismResponse
import at.quickme.prism.shared.PrismTransport
import at.quickme.prism.shared.ProfileChoice
import at.quickme.prism.shared.CatalogProject
import at.quickme.prism.shared.ProjectCatalog
import at.quickme.prism.shared.ProjectChoice
import at.quickme.prism.shared.ProjectIcon
import at.quickme.prism.shared.Question
import at.quickme.prism.shared.QuestionItem
import at.quickme.prism.shared.QuestionOption
import at.quickme.prism.shared.Reasoning
import at.quickme.prism.shared.ThreadLink
import at.quickme.prism.ui.Health
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object Sample {
    private const val PREFS = "sample"

    /** Whether this build may show sample data at all. */
    @JvmStatic
    fun available(c: Context): Boolean = c.packageName.endsWith(".debug")

    /** Sample data is shown (default on in the debug build). */
    @JvmStatic
    fun on(c: Context): Boolean =
        available(c) && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        PrismPush.changed()
    }

    private fun now() = System.currentTimeMillis() / 1000

    private val alertState = mutableMapOf<Long, String>()
    private val startedAgents = mutableListOf<Agent>()

    /** Each started agent's turns: what was asked and when (ms). */
    private val turns = mutableMapOf<String, MutableList<Pair<String, Long>>>()

    /** A scripted run per turn: a few steps over five seconds, then an answer. */
    private fun activity(id: String): AgentActivity? {
        val agent = (startedAgents + initialAgents()).firstOrNull { it.id == id }?.withStopState() ?: return null
        val now = System.currentTimeMillis()
        val items = mutableListOf<ActivityItem>()
        var running = false
        turns[id].orEmpty().forEachIndexed { t, (asked, at) ->
            val run = "run$t"
            fun item(n: Int, type: String, text: String, after: Long, title: String = "") {
                if (now - at < after) return
                val done = now - at >= after + 1200 || type == "user_message"
                items += ActivityItem("$id-$t-$n", (t * 10 + n).toLong(), run, type, if (done) "completed" else "running", title, text)
            }
            item(0, "user_message", asked, 0)
            item(1, "command_execution", "$ kubectl get pods -A --field-selector=status.phase!=Running", 800)
            item(2, "command_execution", "$ df -h /var/lib/containerd", 2200)
            item(3, "file_change", "", 3400, "Edited deploy/apps/cleanup.yaml")
            item(4, "assistant_message", "Done: removed 3 GB of old container images and added a weekly cleanup job.", 5000)
            if (t == turns[id]!!.lastIndex && now - at < 5000) running = true
        }
        val state = when {
            agent.id in stoppedAgents -> agent.state
            running -> "running"
            turns.containsKey(id) -> "finished"
            else -> agent.state
        }
        return AgentActivity(agent.copy(state = state), if (running) "running" else "completed", items)
    }
    private val stoppedAgents = mutableSetOf<String>()

    private fun agent(id: String, title: String, state: String, question: Boolean, last: String, ago: Long, host: String) =
        Agent(
            id, task = "fix", title = title, target = host, state = state, questionPending = question,
            lastMessage = last, updatedAt = now() - ago,
            t3 = AgentThread(environmentId = "sample-env", threadId = "sample-$id", host = host),
        )

    private fun initialAgents(): List<Agent> = listOf(
        agent("a1", "Fix #412 KubeNodeNotReady", "running", true,
            "kubelet on david-nuc-2 crashes with a cgroup error since the last NixOS switch. Shall I roll the node back to the previous generation and open a fix in fleet?",
            120, "david-server"),
        agent("a2", "Update Xcode's command line tools on david-mb", "finished", false,
            "Installed Command Line Tools 26.1; brew upgrade passes again.", 3600 * 3, "david-mb"),
    )

    private fun Agent.withStopState() = if (id in stoppedAgents) {
        copy(state = "failed", runStatus = "interrupted", questionPending = false, updatedAt = now())
    } else this

    internal fun alerts(): List<Alert> = listOf(
        Alert(
            412, kind = "alert", name = "KubeNodeNotReady", severity = "critical", category = "Cluster nodes", host = "david-nuc-2",
            summary = "Node david-nuc-2 has been NotReady for 12 minutes",
            brief = "The kubelet stopped posting its status. Pods on the node are being evicted.",
            description = "A node that is NotReady runs no new pods, and the scheduler moves its pods elsewhere after five minutes. Check the kubelet and the container runtime on the node.",
            startsAt = now() - 780, agents = listOf(initialAgents()[0]),
        ),
        Alert(
            409, kind = "machine_event", name = "brew-update", severity = "warning", category = "Machines", host = "david-mb",
            summary = "brew-update failed on david-mb (exit 1)",
            brief = "Your Command Line Tools are too outdated.",
            command = "/opt/homebrew/bin/brew upgrade --greedy",
            output = "==> Upgrading 3 outdated packages:\nnode 24.9.0 -> 24.10.0\nError: Your Command Line Tools (CLT) are too outdated.\nUpdate them from Software Update in System Settings.\n\nIf that doesn't show you any updates, run:\n  sudo rm -rf /Library/Developer/CommandLineTools\n  sudo xcode-select --install",
            startsAt = now() - 3 * 3600, agents = listOf(initialAgents()[1]),
        ),
        Alert(
            405, kind = "alert", name = "ResticBackupStale", severity = "warning", category = "Backups",
            summary = "The restic backup of paperless is 30 hours old", startsAt = now() - 2 * 86400,
        ),
        Alert(
            398, kind = "alert", name = "CertificateExpiresSoon", severity = "info", category = "Public websites",
            summary = "The certificate of quickme.at expires in 14 days", startsAt = now() - 5 * 3600,
        ),
    ).map { a ->
        a.copy(state = alertState[a.id] ?: a.state,
            agents = (startedAgents.filter { it.alertId == a.id }.asReversed() + a.agents).map { it.withStopState() })
    }

    private val models = listOf(
        ModelChoice("claude-alex", model = "claude-alex/claude-opus-5-5"),
        ModelChoice("claude-alex", model = "claude-alex/claude-sonnet-5-5"),
        ModelChoice("codex-david", model = "codex-david/gpt-6.1-sol"),
    )
    private var model = models[0]
    private var reasoning = ""

    internal fun modelSettings() = ModelSettings(
        model, Reasoning(current = reasoning, defaultLevel = "high", levels = listOf("low", "medium", "high", "xhigh")), models,
    )

    private val placementModels = listOf(
        PlacementModel(
            instanceId = "claudeAgent",
            model = "claude-david/claude-opus-5-5",
            label = "Opus 5.5 · claude-david",
            driver = "claudeAgent",
            provider = "Claude",
            reasoning = PlacementReasoning(levels = listOf("low", "medium", "high", "xhigh", "max"), defaultLevel = "high"),
            machines = listOf("david-mb", "david-server"),
        ),
        PlacementModel(
            instanceId = "claudeAgent",
            model = "claude-1/claude-opus-5-5",
            label = "Opus 5.5 · claude-1",
            driver = "claudeAgent",
            provider = "Claude",
            reasoning = PlacementReasoning(levels = listOf("low", "medium", "high", "xhigh", "max"), defaultLevel = "high"),
            machines = listOf("david-mb", "david-server"),
        ),
        PlacementModel(
            instanceId = "codex",
            model = "codex-david/gpt-6.2-sol",
            label = "GPT-6.2 Sol · codex-david",
            driver = "codex",
            provider = "Codex",
            reasoning = PlacementReasoning(levels = listOf("low", "medium", "high", "xhigh", "max", "ultra"), defaultLevel = "high"),
            machines = listOf("david-mb", "david-server"),
        ),
        PlacementModel(
            instanceId = "agy-pooled",
            model = "agy/gemini-4-neon",
            label = "Gemini 4 Neon · agy",
            driver = "antigravity",
            provider = "Antigravity · pooled",
            reasoning = null,
            machines = listOf("david-mb", "david-server"),
        ),
    )

    private fun fullSelectionEntries(entries: List<PlacementEntry>): List<PlacementEntry> {
        val seen = entries.map { it.instanceId to it.model }.toSet()
        val missing = placementModels.filter { (it.instanceId to it.model) !in seen }.map { m ->
            PlacementEntry(
                instanceId = m.instanceId,
                model = m.model,
                label = m.label,
                driver = m.driver,
                provider = m.provider,
                reasoning = "",
                enabled = false,
                state = "disabled",
                reason = "Disabled.",
            )
        }
        return entries + missing
    }

    private var placementProfiles = mutableListOf(
        PlacementProfile(
            id = "coding",
            label = "Coding",
            entries = fullSelectionEntries(listOf(
                PlacementEntry(
                    instanceId = "claudeAgent", model = "claude-david/claude-opus-5-5", label = "Opus 5.5 · claude-david",
                    driver = "claudeAgent", provider = "Claude",
                    reasoning = "high", enabled = true, sessionShareOfWeekly = 13.7,
                    state = "ok", reason = "Session window at 42%.", runningThreads = 2,
                ),
                PlacementEntry(
                    instanceId = "claudeAgent", model = "claude-1/claude-opus-5-5", label = "Opus 5.5 · claude-1",
                    driver = "claudeAgent", provider = "Claude",
                    reasoning = "high", enabled = true, sessionShareOfWeekly = 13.7,
                    state = "ok", reason = "Session window at 18%.", runningThreads = 0,
                ),
                PlacementEntry(
                    instanceId = "codex", model = "codex-david/gpt-6.2-sol", label = "GPT-6.2 Sol · codex-david",
                    driver = "codex", provider = "Codex",
                    reasoning = "xhigh", enabled = false,
                    state = "disabled", reason = "Disabled.", runningThreads = 0,
                ),
            )),
        ),
        PlacementProfile(
            id = "review",
            label = "Review",
            entries = fullSelectionEntries(listOf(
                PlacementEntry(
                    instanceId = "codex", model = "codex-david/gpt-6.2-sol", label = "GPT-6.2 Sol · codex-david",
                    driver = "codex", provider = "Codex",
                    reasoning = "high", enabled = true,
                    state = "ok", reason = "Session window at 12%.", runningThreads = 1,
                ),
                PlacementEntry(
                    instanceId = "claudeAgent", model = "claude-david/claude-opus-5-5", label = "Opus 5.5 · claude-david",
                    driver = "claudeAgent", provider = "Claude",
                    reasoning = "medium", enabled = true, sessionShareOfWeekly = 13.7,
                    state = "ok", reason = "Session window at 42%.", runningThreads = 2,
                ),
            )),
        ),
        PlacementProfile(
            id = "minor",
            label = "Minor",
            entries = fullSelectionEntries(listOf(
                PlacementEntry(
                    instanceId = "agy-pooled", model = "agy/gemini-4-neon", label = "Gemini 4 Neon · agy",
                    driver = "antigravity", provider = "Antigravity · pooled",
                    reasoning = "", enabled = true,
                    state = "ok", reason = "Available.", runningThreads = 0,
                ),
            )),
        ),
    )

    private var alertFixProfile = "coding"
    private var textGenerationProfile: String? = "minor"
    private val placementRoles = listOf("implementation", "research", "review", "design", "test", "general")
    private var placementPolicy = PlacementPolicy(
        switchAtPercent = 95.0,
        weeklySlackSessions = 3.0,
        roleProfiles = mapOf("review" to "review"),
    )

    internal fun placement(): Placement = Placement(
        profiles = placementProfiles.toList(),
        models = placementModels,
        alertFixProfile = alertFixProfile,
        actions = PlacementActions(alertFix = alertFixProfile, textGeneration = textGenerationProfile),
        roles = placementRoles,
        policy = placementPolicy,
        moves = listOf(
            PlacementMove(
                machine = "david-mb", threadId = "thr-412",
                from = "claude-david/claude-opus-5-5", to = "claude-1/claude-opus-5-5",
                reason = "Session window at 96%, past 95% switch point.",
                at = now() - 18 * 60, state = "continued",
            ),
            PlacementMove(
                machine = "david-server", threadId = "thr-409",
                from = "claude-1/claude-opus-5-5", to = "codex-david/gpt-6.2-sol",
                reason = "Weekly slack below 3 sessions.",
                at = now() - 3 * 3600, state = "continued",
            ),
        ),
    )

    internal fun agentOptions(target: String = "cluster"): AgentOptions {
        val machine = when (target) {
            "cluster" -> "david-server"
            "auto" -> "david-mb"
            else -> target
        }
        val default = "repo:gitea.quickme.at/david-cluster-1/" + if (target == "cluster") "selfhosted-platform" else "fleet"
        val projects = projects().projects.map { ProjectChoice(it.id, it.name, it.icon) }
        val profiles = listOf(ProfileChoice("coding", "Coding"), ProfileChoice("minor", "Minor"), ProfileChoice("review", "Review"))
        return AgentOptions(machine, models, models[2], projects, default, profiles, picked = "david-mb".takeIf { target == "auto" })
    }

    internal fun projects() = ProjectCatalog(
        listOf(
            CatalogProject("repo:gitea.quickme.at/david-cluster-1/fleet", "fleet", machines = listOf("david-server", "david-mb"), repository = true),
            CatalogProject("host:david-mb/notes", "notes", machines = listOf("david-mb")),
            CatalogProject("repo:github.com/BierDav/prism", "prism", ProjectIcon("emoji", emoji = "🔷"), listOf("david-mb"), true),
            CatalogProject("repo:gitea.quickme.at/david-cluster-1/selfhosted-platform", "selfhosted-platform", machines = listOf("david-server", "david-mb"), repository = true),
            CatalogProject("", "Scratch folder"),
        ),
    )

    internal fun machines() = Machines(
        cluster = ClusterInfo(runsOn = "david-server"),
        machines = listOf(
            Machine("david-server", reachable = true, state = "online"),
            Machine("david-mb", reachable = true, state = "online"),
            Machine("david-nuc-2", reachable = true, state = "online"),
            Machine("david-mm", reachable = false, state = "asleep"),
        ),
    )

    /** A host's last two hours: a wave around `base`, with a gap where `gap` says. */
    private fun series(base: Double, swing: Double, phase: Double, gap: IntRange? = null): List<Double?> = (0..60).map { i ->
        if (gap != null && i in gap) null
        else (base + swing * kotlin.math.sin(i / 6.0 + phase) + swing / 2 * kotlin.math.sin(i / 1.7)).coerceIn(0.0, 1.0)
    }

    private fun usage(used: Double, total: Double) = HostMetrics.Usage(used.toLong(), total.toLong())

    private fun fs(mount: String, used: Double, total: Double) = HostMetrics.Filesystem(mount, used.toLong(), total.toLong())

    private const val STEP = 120L

    private fun host(
        name: String, os: String, arch: String, cpu: Double, cores: Int, load: List<Double>,
        memory: Pair<Double, Double>, disks: List<HostMetrics.Filesystem>, rx: Double, tx: Double, up: Long,
        battery: Pair<Double, Boolean>? = null, swap: Pair<Double, Double>? = null,
    ) = HostMetrics(
        name, online = true, lastSeen = now() - 20, os = os, arch = arch, bootTime = now() - up, cpu = cpu, cores = cores,
        load = load, memory = usage(memory.first, memory.second), swap = swap?.let { usage(it.first, it.second) },
        disk = disks.first(), filesystems = disks, network = HostMetrics.Network(rx, tx),
        battery = battery?.let { HostMetrics.Battery(it.first, it.second) },
        history = HostMetrics.History(
            now() / STEP * STEP - 7200, STEP,
            cpu = series(cpu * 0.8, 0.18, name.length.toDouble()),
            memory = series(memory.first / memory.second, 0.03, 1.0),
        ),
    )

    internal fun hostMetrics(): HostMetricsList {
        val gb = 1024.0 * 1024 * 1024
        return HostMetricsList(
            now(),
            listOf(
                host("ccs-oracle-1", "Linux 7.3.4", "aarch64", 0.49, 4, listOf(4.09, 3.92, 3.93), 5.7 * gb to 23.4 * gb,
                    listOf(fs("/", 131 * gb, 195 * gb)), 9.9e6, 5.7e6, 36 * 3600L, swap = 0.0 to 4 * gb),
                host("ccs-oracle-2", "Linux 7.3.4", "aarch64", 0.71, 4, listOf(12.0, 12.1, 12.3), 7.5 * gb to 23.4 * gb,
                    listOf(fs("/", 182 * gb, 195 * gb)), 7.2e6, 9.7e6, 36 * 3600L),
                host("david-mb", "Darwin 27.2.0", "arm64", 0.64, 18, listOf(17.6, 26.1, 31.7), 29.6 * gb to 48 * gb,
                    listOf(fs("/System/Volumes/Data", 595 * gb, 926 * gb)), 325e3, 967e3, 6 * 86400L + 3600,
                    battery = 0.59 to true, swap = 0.7 * gb to 2 * gb),
                host("david-server", "Linux 7.3.4", "x86_64", 0.93, 12, listOf(9.88, 10.4, 9.7), 5.2 * gb to 11.7 * gb,
                    listOf(fs("/", 254 * gb, 393 * gb), fs("/mnt/backup", 110 * gb, 125 * gb)), 11e6, 82e3, 20 * 3600L),
                HostMetrics(
                    "david-tp", online = false, lastSeen = now() - 5 * 3600, bootTime = now() - 2 * 86400,
                    history = HostMetrics.History(now() / STEP * STEP - 7200, STEP, List(61) { null }, List(61) { null }),
                ),
            ),
        )
    }

    private val answered = mutableSetOf<String>()

    private fun ask(header: String, question: String, multi: Boolean, vararg options: Pair<String, String>) =
        QuestionItem(header.lowercase(), header, question, multi, options.map { (l, d) -> QuestionOption(l, d) })

    internal fun questions(): List<Question> = listOf(
        Question(
            "req-rollback", threadId = "sample-a1", environmentId = "sample-env", host = "david-server",
            title = "Fix #412 KubeNodeNotReady", createdAt = now() - 140, agentId = "a1",
            questions = listOf(
                ask("Rollback", "Roll david-nuc-2 back to the previous NixOS generation?", false,
                    "Roll back" to "Boot generation 412 and open a fix in fleet",
                    "Keep it" to "Debug the cgroup error on the running generation"),
            ),
        ),
        Question(
            "req-deploy", threadId = "sample-t2", environmentId = "sample-env", host = "david-mb",
            title = "Deploy Immich", createdAt = now() - 900,
            questions = listOf(
                ask("Storage", "Where should the photo library live?", false,
                    "Longhorn" to "Replicated across the nodes, 3 × the space",
                    "NFS on david-server" to "One copy on the RAID"),
                ask("Features", "Which features should be on?", true,
                    "Face recognition" to "", "Smart search" to "CLIP on the GPU node", "Public sharing" to ""),
            ),
        ),
    ).filter { it.id !in answered }

    /** The Prism API in sample mode: the client the app uses, answered from here. */
    internal val client = PrismClient(
        "https://prism.sample.invalid", { "sample" },
        object : PrismTransport {
            override suspend fun send(request: PrismRequest) = answer(request)
        },
    )

    private inline fun <reified T> encode(value: T): JsonElement = PrismClient.json.encodeToJsonElement(value)

    /** Answers a Prism API call as the server would, after a short delay. */
    private suspend fun answer(r: PrismRequest): PrismResponse {
        delay(if (r.method == "GET") 450 else 900)
        val path = r.path
        val body = r.body?.let { PrismClient.json.parseToJsonElement(it).jsonObject }
        fun field(name: String) = body?.get(name)?.jsonPrimitive?.contentOrNull.orEmpty()
        val result: JsonElement? = synchronized(this) {
            when {
                r.method == "GET" && path.startsWith("/v1/alerts?") ->
                    buildJsonObject { put("alerts", encode(alerts().filter { it.isOpen })) }
                r.method == "GET" && path.startsWith("/v1/agents?") ->
                    buildJsonObject { put("agents", encode((startedAgents.asReversed() + initialAgents()).map { it.withStopState() })) }
                r.method == "GET" && path.startsWith("/v1/agents/") && path.endsWith("/activity") ->
                    activity(java.net.URLDecoder.decode(path.split("/")[3], "UTF-8"))?.let { encode(it) }
                r.method == "POST" && path.startsWith("/v1/agents/") && path.endsWith("/messages") -> {
                    val id = java.net.URLDecoder.decode(path.split("/")[3], "UTF-8")
                    turns.getOrPut(id) { mutableListOf() }.add(field("text") to System.currentTimeMillis())
                    stoppedAgents.remove(id)
                    activity(id)?.let { buildJsonObject { put("agent", encode(it.agent)) } }
                }
                r.method == "POST" && path.startsWith("/v1/agents/") && path.endsWith("/stop") -> {
                    val id = path.split("/")[3]
                    if ((startedAgents + initialAgents()).none { it.id == id }) null
                    else {
                        stoppedAgents.add(id)
                        buildJsonObject { put("ok", true) }
                    }
                }
                r.method == "GET" && path == "/v1/questions" -> buildJsonObject { put("questions", encode(questions())) }
                r.method == "POST" && path.startsWith("/v1/questions/") && path.endsWith("/answer") -> {
                    answered.add(java.net.URLDecoder.decode(path.split("/")[3], "UTF-8"))
                    buildJsonObject { put("ok", true) }
                }
                r.method == "GET" && path == "/v1/settings/model" -> encode(modelSettings())
                r.method == "GET" && path.startsWith("/v1/agent-options?target=") ->
                    encode(agentOptions(path.substringAfter("?target=")))
                r.method == "GET" && path == "/v1/machines" -> encode(machines())
                r.method == "GET" && path == "/v1/projects" -> encode(projects())
                r.method == "GET" && path == "/v1/machines/metrics" -> encode(hostMetrics())
                r.method == "GET" && path.startsWith("/v1/alerts/") ->
                    alerts().firstOrNull { it.id == path.removePrefix("/v1/alerts/").toLongOrNull() }?.let { encode(it) }
                r.method == "PUT" && path == "/v1/settings/model" -> {
                    model = models.firstOrNull { it.instanceId == field("instanceId") && it.model == field("model") } ?: model
                    encode(modelSettings())
                }
                r.method == "PUT" && path == "/v1/settings/reasoning" -> {
                    reasoning = field("level").takeUnless { it == "default" } ?: ""
                    encode(modelSettings())
                }
                r.method == "GET" && path == "/v1/placement" -> encode(placement())
                r.method == "PUT" && path.startsWith("/v1/placement/profiles/") -> {
                    val id = java.net.URLDecoder.decode(path.removePrefix("/v1/placement/profiles/"), "UTF-8")
                    val label = field("label").ifEmpty { id }
                    val entries = body?.get("entries")?.jsonArray?.map { el ->
                        val o = el.jsonObject
                        val inst = o["instanceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val mod = o["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val known = placementModels.firstOrNull { it.instanceId == inst && it.model == mod }
                        val enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                        PlacementEntry(
                            instanceId = inst,
                            model = mod,
                            label = known?.label.orEmpty(),
                            driver = known?.driver.orEmpty(),
                            provider = known?.provider.orEmpty(),
                            reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            enabled = enabled,
                            sessionShareOfWeekly = o["sessionShareOfWeekly"]?.jsonPrimitive?.doubleOrNull,
                            state = if (enabled) "ok" else "disabled",
                            reason = if (enabled) "Available." else "Disabled.",
                        )
                    }.orEmpty()
                    val updated = PlacementProfile(id = id, label = label, entries = fullSelectionEntries(entries))
                    val idx = placementProfiles.indexOfFirst { it.id == id }
                    if (idx >= 0) placementProfiles[idx] = updated else placementProfiles.add(updated)
                    encode(placement())
                }
                r.method == "DELETE" && path.startsWith("/v1/placement/profiles/") -> {
                    val id = java.net.URLDecoder.decode(path.removePrefix("/v1/placement/profiles/"), "UTF-8")
                    val usedBy = buildList {
                        if (id == alertFixProfile) add("alert fixes")
                        if (id == textGenerationProfile) add("text generation")
                        placementPolicy.roleProfiles.filterValues { it == id }.keys.forEach { add("role $it") }
                    }
                    if (usedBy.isNotEmpty()) {
                        return PrismResponse(409, buildJsonObject {
                            put("error", "Cannot delete profile used by ${usedBy.joinToString(", ")}.")
                            put("code", "conflict")
                        }.toString())
                    }
                    placementProfiles.removeAll { it.id == id }
                    encode(placement())
                }
                r.method == "PUT" && path == "/v1/placement/order" -> {
                    val ids = body?.get("ids")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    val byId = placementProfiles.associateBy { it.id }
                    val reordered = ids.mapNotNull { byId[it] } + placementProfiles.filter { it.id !in ids }
                    placementProfiles = reordered.toMutableList()
                    encode(placement())
                }
                r.method == "PUT" && path == "/v1/placement/alert-fix-profile" -> {
                    val profile = field("profile")
                    if (placementProfiles.any { it.id == profile }) alertFixProfile = profile
                    encode(placement())
                }
                r.method == "PUT" && path == "/v1/placement/actions" -> {
                    body?.get("alertFix")?.jsonPrimitive?.contentOrNull?.let { af ->
                        if (placementProfiles.any { it.id == af }) alertFixProfile = af
                    }
                    if (body?.containsKey("textGeneration") == true) {
                        val tg = body["textGeneration"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        textGenerationProfile = tg.takeIf { it.isNotEmpty() && placementProfiles.any { p -> p.id == it } }
                    }
                    body?.get("roleProfiles")?.jsonObject?.let { rp ->
                        val next = mutableMapOf<String, String>()
                        for ((role, value) in rp) {
                            val pid = value.jsonPrimitive.contentOrNull.orEmpty()
                            if (pid.isNotEmpty() && placementProfiles.any { it.id == pid }) next[role] = pid
                        }
                        placementPolicy = placementPolicy.copy(roleProfiles = next)
                    }
                    encode(placement())
                }
                r.method == "PUT" && path == "/v1/placement/policy" -> {
                    body?.let { placementPolicy = PrismClient.json.decodeFromString(PlacementPolicy.serializer(), it.toString()) }
                    encode(placement())
                }
                r.method == "POST" && (path.endsWith("/dismiss") || path.endsWith("/resolve")) -> {
                    val id = path.split("/")[3].toLong()
                    alertState[id] = if (path.endsWith("/dismiss")) "dismissed" else "resolved"
                    buildJsonObject { put("alert", encode(alerts().first { it.id == id })) }
                }
                r.method == "POST" -> {
                    val request = field("request").ifEmpty { field("instruction") }
                    val title = if (request.isEmpty()) "Fix the alert" else "Do: " + request.take(60)
                    val alertId = path.takeIf { it.startsWith("/v1/alerts/") }?.split("/")?.get(3)?.toLongOrNull() ?: 0L
                    val host = field("target").takeUnless { it.isEmpty() || it == "cluster" } ?: "david-server"
                    val started = agent("a${startedAgents.size + 3}", title, "running", false, "", 0, host)
                        .copy(alertId = alertId)
                    startedAgents.add(started)
                    if (request.isNotEmpty()) turns[started.id] = mutableListOf(request to System.currentTimeMillis())
                    buildJsonObject { put("agent", encode(started)) }
                }
                else -> null
            }
        }
        return if (result == null) {
            PrismResponse(404, buildJsonObject { put("error", "Sample data has no answer for ${r.method} $path.") }.toString())
        } else {
            PrismResponse(200, result.toString())
        }
    }

    /** A sample approval from david-server, asked now: a deploy, or [long] a rebuild with a long command. */
    internal fun approval(long: Boolean = false): Approval {
        val now = now()
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val thread = ThreadLink("9dd291d8-eb05", "thread:fix-412", "Fix #412 KubeNodeNotReady")
        return if (long) {
            Approval(
                id, "Rebuild david-server", "david-server", now, now + 300,
                summary = "Switches the host to the new configuration; the kubelet restarts with it.",
                details = listOf(
                    ApprovalDetail("Command", "nixos-rebuild switch --flake /home/owner/AgentWorkspaces/" +
                        "selfhosted-platform#david-server --option substituters 'https://cache.nixos.org " +
                        "https://nix-community.cachix.org' --show-trace", code = true),
                    ApprovalDetail("Directory", "/home/owner/AgentWorkspaces/selfhosted-platform"),
                ),
                thread = thread,
            )
        } else {
            Approval(
                id, "Deploy main to the cluster", "david-server", now, now + 300,
                summary = "The kubelet on this node lost its cgroup driver after the switch; the fix ships with main.",
                details = listOf(
                    ApprovalDetail("Change", "selfhosted-platform main, 3 commits ahead of production"),
                    ApprovalDetail("Command", "argodeploy sync platform", code = true),
                ),
                thread = thread,
            )
        }
    }

    /** NetBird's resources: networks and domains the sample network offers. */
    internal val resources = listOf(
        Netbird.Resource("home-lan", "192.168.1.0/24", "david-server.netbird.cloud", true, false),
        Netbird.Resource("cluster-pods", "10.42.0.0/16", "david-server.netbird.cloud", true, true),
        Netbird.Resource("office-printers", "10.20.30.0/24", "david-nuc-2.netbird.cloud", false, false),
        Netbird.Resource("internal-git", "git.internal.example, *.lab.example", "david-vps.netbird.cloud", true, false),
    )

    /** The resources switched on in the sample: one offered, one the network no longer offers. */
    internal val enabledResources = setOf("cluster-pods", "old-vpn")

    /** The Phone tab's status lines, with [exitNode] picked. */
    internal fun phoneStatus(exitNode: String?) = PhoneStatus(
        Health.Good to "Connected, 7 peers · 100.123.88.14", true, null, true,
        listOf(
            Netbird.ExitNode("home", "home", "david-server.netbird.cloud", true, exitNode == "home"),
            Netbird.ExitNode("vps-falkenstein", "vps-falkenstein", "david-vps.netbird.cloud", true, exitNode == "vps-falkenstein"),
            Netbird.ExitNode("office", "office", "david-nuc-2.netbird.cloud", false, exitNode == "office"),
        ),
        exitNode,
        resources.count { it.id in enabledResources } to resources.size,
        Health.Good to "Connected",
        Health.Good to "Pushed 412 series at 21:45",
        Health.Good to SHELL_STATUS.replaceFirstChar { it.uppercase() },
        true,
        "Up to date: 1.0 (1)",
    )

    /** The shell server's status line. */
    const val SHELL_STATUS = "running as uid 2000 (pid 23817, u:r:shell:s0) since 08:12"

    /** Apps on Shizuku's API, for the Shell access screen. */
    internal fun shellApps() = listOf(
        ShellApps.App("com.aistra.hail", "Hail", Protocol.DECISION_ALLOWED, true),
        ShellApps.App("io.github.muntashirakon.AppManager", "App Manager", Protocol.DECISION_NONE, false),
        ShellApps.App("com.example.tracker", "Tracker", Protocol.DECISION_DENIED, false),
    )

    /** The last decisions, for the previews. */
    internal fun history(): List<Decision> {
        val now = now()
        return listOf(
            Decision(now - 180, Outcome.APPROVED, "Deploy main to the cluster", "david-server"),
            Decision(now - 1680, Outcome.DENIED, "Delete the CommandLineTools", "david-mb"),
            Decision(now - 6120, Outcome.EXPIRED, "Rebuild david-nuc-2", "david-nuc-2"),
        )
    }
}
