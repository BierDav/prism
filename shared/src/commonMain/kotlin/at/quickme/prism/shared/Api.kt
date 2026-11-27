package at.quickme.prism.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Prism server's objects (docs/prism-api.md), as both apps read them.
// Every field has a default: the server leaves out what is empty, Go writes a
// nil list or map as null (PrismClient's JSON turns that into the default),
// and a newer server may add fields (ignored).

/** An alert's severity; values from custom rules count as warnings (the contract). */
enum class Severity {
    CRITICAL, WARNING, INFO;

    companion object {
        fun of(text: String): Severity = when (text) {
            "critical" -> CRITICAL
            "info" -> INFO
            else -> WARNING
        }
    }
}

/** The categories in the order the contract gives; group alerts by them. */
val alertCategories: List<String> = listOf(
    "Machines", "Cluster nodes", "etcd and DNS", "Workloads", "Storage", "Databases", "Backups",
    "Public websites", "Monitoring", "CI and updates", "Other",
)

/** One episode of something that fires: a cluster alert or a machine's failed check. */
@Serializable
data class Alert(
    /** The owner's #42. */
    val id: Long,
    /** `alert` or `machine_event`. */
    val kind: String = "alert",
    val name: String = "",
    val alertname: String = "",
    val category: String = "Other",
    /** What the alert means, from the server's catalog; may be empty. */
    val brief: String = "",
    val severity: String = "warning",
    /** The machine it concerns, or "" for the cluster. */
    val host: String = "",
    val check: String = "",
    /** Untrusted text from the alert: show it as plain text only. */
    val summary: String = "",
    val description: String = "",
    /** A machine event's command and the tail of its output: monospace. */
    val command: String = "",
    val output: String = "",
    val labels: Map<String, String> = emptyMap(),
    /** `open`, `resolved` or `gone` (`dismissed` on older episodes). An alert
     *  ends only when its condition does; nothing dismisses or resolves one. */
    val state: String = "open",
    val startsAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val endedAt: Long = 0,
    /** The agents started for it, newest first. */
    val agents: List<Agent> = emptyList(),
) {
    /** The check for a machine event, else the alert name. */
    val title: String get() = name.ifEmpty { alertname }
    val level: Severity get() = Severity.of(severity)
    val machineEvent: Boolean get() = kind == "machine_event"
    val isOpen: Boolean get() = state == "open"

    /** When the episode started; when the server first saw it if that lies
     *  in the future (a clock or time-zone slip upstream). */
    fun since(now: Long): Long = if (startsAt in 1..(now + 300)) startsAt else createdAt

    /** The category if it is one of [alertCategories], else "Other". */
    val group: String get() = if (category in alertCategories) category else "Other"
}

/** Alerts by category in the contract's order; within one the most severe, then the newest first. */
fun groupAlerts(alerts: List<Alert>, now: Long): List<AlertGroup> {
    val byCategory = alerts.groupBy { it.group }
    return alertCategories.mapNotNull { category ->
        byCategory[category]?.sortedWith(compareBy<Alert> { it.level.ordinal }.thenByDescending { it.since(now) })
            ?.let { AlertGroup(category, it) }
    }
}

data class AlertGroup(val category: String, val alerts: List<Alert>)

/** An agent the server started, in its T3 thread. */
@Serializable
data class Agent(
    /** The T3 thread's ID (percent-encode it in paths). */
    val id: String,
    val task: String = "",
    val title: String = "",
    /** The alert it works on, or 0. */
    val alertId: Long = 0,
    /** `cluster` or the machine it works on. */
    val target: String = "cluster",
    /** `running`, `waiting`, `finished`, `failed` or `stale`. */
    val state: String = "running",
    val runStatus: String = "",
    val questionPending: Boolean = false,
    /** Markdown, untrusted: links disabled or shown as text. */
    val lastMessage: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val requestedBy: String = "",
    val t3: AgentThread? = null,
) {
    /** `waiting` while a question is pending, whatever `state` says. */
    val status: String get() = if (questionPending) "waiting" else state
    val active: Boolean get() = status == "running" || status == "waiting"
    val displayTitle: String get() = title.ifEmpty { "Agent" }
    val lastChange: Long get() = if (updatedAt > 0) updatedAt else createdAt

    /** T3 Code's link to the thread (t3code://threads/…), or null without one. */
    val link: String? get() = t3?.let { T3Links.app(it.environmentId, it.threadId.ifEmpty { id }) }
}

/** GET /v1/agents/{id}/activity: the agent's latest steps, read live from its T3. */
@Serializable
data class AgentActivity(
    /** Its state follows T3 now, ahead of the server's watcher. */
    val agent: Agent,
    /** T3's thread status now. */
    val status: String = "",
    /** Oldest first, at most 40, without reasoning. */
    val items: List<ActivityItem> = emptyList(),
)

/** One step of an agent's thread: a message, a command, a file change, a tool call… */
@Serializable
data class ActivityItem(
    val id: String,
    val position: Long = 0,
    val runId: String = "",
    /** T3's item type, e.g. `user_message`, `assistant_message`, `command_execution`, `file_change`. */
    val type: String = "",
    /** `running`, `completed` or `failed`. */
    val status: String = "",
    val title: String = "",
    /** Untrusted. Messages are Markdown up to 4,000 characters; other steps are cut to 300. */
    val text: String = "",
    /** The text was cut. */
    val truncated: Boolean = false,
)

/** Where an agent's thread lives. */
@Serializable
data class AgentThread(
    val environmentId: String = "",
    val threadId: String = "",
    /** The fleet machine that runs the thread (not always the agent's target). */
    val host: String = "",
    /** T3's own link, for reference only; build links with [T3Links]. */
    val link: String = "",
)

/** An agent's question for the owner, from any T3 thread the server reaches. */
@Serializable
data class Question(
    /** T3's request ID; may contain `:` or `/`. */
    val id: String,
    val threadId: String = "",
    val environmentId: String = "",
    val host: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    /** The thread ID when Prism started the agent, else "". */
    val agentId: String = "",
    /** Answer all of them at once. */
    val questions: List<QuestionItem> = emptyList(),
)

@Serializable
data class QuestionItem(
    val id: String,
    val header: String = "",
    val question: String = "",
    val multiSelect: Boolean = false,
    /** May be empty: free text only. */
    val options: List<QuestionOption> = emptyList(),
)

@Serializable
data class QuestionOption(val label: String, val description: String = "")

/** GET /v1/machines. */
@Serializable
data class Machines(
    val cluster: ClusterInfo? = null,
    val machines: List<Machine> = emptyList(),
)

@Serializable
data class ClusterInfo(val name: String = "cluster", val runsOn: String = "", val workspace: String = "")

/** A machine agents can work on. */
@Serializable
data class Machine(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    /** It runs T3; otherwise its agents run on an SSH runner. */
    val t3: Boolean = false,
    /** `direct`, `link`, `ssh`, `none` or `reports`. */
    val route: String = "",
    val via: String = "",
    /** An agent can start there now. */
    val reachable: Boolean = false,
    /** T3's word for it; show it as is. */
    val state: String = "",
    val detail: String = "",
    val sshRunner: Boolean = false,
    val openAlerts: List<Long> = emptyList(),
    /** Its T3 web app over NetBird, or "". */
    val t3WebOrigin: String = "",
)

/** GET /v1/machines/metrics. */
@Serializable
data class HostMetricsList(
    /** When the server read the metrics. */
    val time: Long = 0,
    /** Sorted by name. */
    val hosts: List<HostMetrics> = emptyList(),
)

/** One host's performance. Current values are null or empty for a host that
 *  is offline or does not report them. */
@Serializable
data class HostMetrics(
    val name: String,
    /** It reported within the last 3 minutes. */
    val online: Boolean = false,
    val lastSeen: Long = 0,
    /** uname's system and release, e.g. "Linux 7.3.4". */
    val os: String = "",
    val arch: String = "",
    /** 0: unknown. Uptime is the list's `time` minus this. */
    val bootTime: Long = 0,
    /** The busy share of all cores over the last 5 minutes, 0–1. */
    val cpu: Double? = null,
    val cores: Int = 0,
    /** 1, 5 and 15 minutes, or empty. */
    val load: List<Double> = emptyList(),
    val memory: Usage? = null,
    val swap: Usage? = null,
    val disk: Filesystem? = null,
    /** At least 1 GiB each, `disk` first. */
    val filesystems: List<Filesystem> = emptyList(),
    val network: Network? = null,
    /** null without a battery. */
    val battery: Battery? = null,
    val history: History? = null,
) {
    @Serializable
    data class Usage(val used: Long = 0, val total: Long = 0) {
        /** used / total, or null without a total. */
        val share: Double? get() = if (total > 0) used.toDouble() / total else null
    }

    @Serializable
    data class Filesystem(val mount: String = "", val used: Long = 0, val total: Long = 0) {
        val usage: Usage get() = Usage(used, total)
    }

    /** Bytes per second over the last 5 minutes. */
    @Serializable
    data class Network(val receive: Double = 0.0, val transmit: Double = 0.0)

    @Serializable
    data class Battery(
        /** 0–1. */
        val level: Double = 0.0,
        val charging: Boolean = false,
    )

    /** The last two hours, one value per [step] seconds from [start]; null where nothing was reported. */
    @Serializable
    data class History(
        val start: Long = 0,
        val step: Long = 0,
        val cpu: List<Double?> = emptyList(),
        val memory: List<Double?> = emptyList(),
    )
}

/** The models and projects available on the selected machine's T3 environment. */
@Serializable
data class AgentOptions(
    val machine: String,
    val models: List<ModelChoice>,
    val defaultModel: ModelChoice,
    val projects: List<ProjectChoice>,
    /** Null when no default project is available; an empty ID means scratch. */
    val defaultProject: String? = null,
    /** T3's placement profiles; empty when it has none. */
    val profiles: List<ProfileChoice> = emptyList(),
    /** For the target "auto": the target the server's machine pick chose. */
    val picked: String? = null,
)

@Serializable
data class ProjectChoice(
    val id: String,
    val name: String,
    /** The icon chosen in T3; without one, the name's monogram. */
    val icon: ProjectIcon? = null,
    /** Another machine's repository: starting an agent clones it here first. */
    val clone: Boolean = false,
)

/** GET /v1/projects. */
@Serializable
data class ProjectCatalog(val projects: List<CatalogProject> = emptyList())

/**
 * A project on any machine: a repository ([repository]; ID `repo:<key>`), on
 * every machine once cloned there, or a folder on its one machine (ID
 * `host:<machine>/<id>`). The scratch folder has the empty ID.
 */
@Serializable
data class CatalogProject(
    val id: String,
    val name: String,
    val icon: ProjectIcon? = null,
    /** The machines that have it. */
    val machines: List<String> = emptyList(),
    val repository: Boolean = false,
)

/**
 * T3's project icon: an [emoji], a monogram of [text], or a Lucide icon [name]
 * (drawn as the name's monogram, as T3's own app does), the last two in a
 * Tailwind [color] ("emerald").
 */
@Serializable
data class ProjectIcon(
    val kind: String,
    val emoji: String = "",
    val text: String = "",
    val name: String = "",
    val color: String = "",
)

/** A placement profile: T3 picks the account an agent in it runs on. */
@Serializable
data class ProfileChoice(val id: String, val label: String = "") {
    /** The launch's model: a pick on T3's placement instance. */
    val model: ModelChoice get() = ModelChoice(PLACEMENT_INSTANCE, PLACEMENT_INSTANCE, id)

    companion object {
        const val PLACEMENT_INSTANCE = "placement"
    }
}

/** GET /v1/settings/model: the model and reasoning level future agents get. */
/** A phone's published settings (`GET /v1/android/settings`); a null value clears the key. */
@Serializable
data class AndroidSettings(
    val device: String = "",
    val settings: Map<String, String?> = emptyMap(),
    val updatedAt: Long = 0,
    val updatedBy: String = "",
)

@Serializable
data class ModelSettings(
    val model: ModelChoice,
    /** null: the model has no reasoning level. */
    val reasoning: Reasoning? = null,
    val models: List<ModelChoice> = emptyList(),
) {
    /** The models by provider instance, in the server's order. */
    val grouped: List<ModelGroup>
        get() = models.groupBy { it.instanceId }.map { (instance, list) -> ModelGroup(instance, list) }
}

data class ModelGroup(val instance: String, val models: List<ModelChoice>)

/** A model the server can start agents with: the T3 provider instance and the model ID. */
@Serializable
data class ModelChoice(
    val instanceId: String = "",
    val provider: String = "",
    val model: String = "",
) {
    /** The model without its account or pool prefix ("gpt-6.1-sol" for "codex-david/gpt-6.1-sol"). */
    val shortName: String get() = model.substringAfterLast('/')
}

@Serializable
data class Reasoning(
    /** "" while the model uses its default. */
    val current: String = "",
    @SerialName("default") val defaultLevel: String = "",
    val levels: List<String> = emptyList(),
) {
    val label: String get() = current.ifEmpty { defaultLevel.ifEmpty { "default" } }
}

/** GET /v1/placement: placement profiles, available models, actions, roles, policy, and recent moves (docs/placement.md). */
@Serializable
data class Placement(
    val profiles: List<PlacementProfile> = emptyList(),
    val models: List<PlacementModel> = emptyList(),
    val alertFixProfile: String = "",
    val actions: PlacementActions = PlacementActions(),
    val roles: List<String> = emptyList(),
    val policy: PlacementPolicy = PlacementPolicy(),
    val moves: List<PlacementMove> = emptyList(),
) {
    /** The profile alert fixes use, preferring [actions] with fallback to [alertFixProfile]. */
    val effectiveAlertFixProfile: String get() = actions.alertFix.ifEmpty { alertFixProfile }
    /** The profile text generation uses, or null when none. */
    val effectiveTextGenerationProfile: String? get() = actions.textGeneration?.takeIf { it.isNotEmpty() }

    /** The gateway accounts the hosts' models run on (`claude-alex` for `claude-alex/claude-opus-5-5`),
     *  sorted, for temporary account limits; profile references don't count. */
    val accounts: List<String>
        get() = models.asSequence()
            .filter { it.instanceId != PlacementEntry.PROFILE_INSTANCE && '/' in it.model }
            .map { it.model.substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct().sorted().toList()

    /** The profiles [profileId] names as entries, directly. */
    fun referencedProfiles(profileId: String): List<String> =
        profiles.firstOrNull { it.id == profileId }?.configuredEntries.orEmpty()
            .filter { it.isProfileReference }.map { it.model }

    /** [from] uses [to]: it is [to], or one of its entries references it, directly or through other profiles. */
    fun reaches(from: String, to: String): Boolean {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == to) return true
            if (seen.add(id)) queue.addAll(referencedProfiles(id))
        }
        return false
    }

    /** The profiles [profileId] can take as an entry: not itself, not one it holds already, and none
     *  that uses it (that would be a cycle, which the server refuses). */
    fun referenceCandidates(profileId: String, held: List<PlacementEntry>): List<PlacementProfile> {
        val taken = held.filter { it.isProfileReference }.map { it.model }.toSet()
        return profiles.filter { it.id != profileId && it.id !in taken && !reaches(it.id, profileId) }
    }

    /** What keeps [profileId] from being deleted: the actions, roles and profiles that use it. */
    fun profileUsedBy(profileId: String): List<String> = buildList {
        if (profileId == effectiveAlertFixProfile) add("Alert fixes")
        if (profileId == effectiveTextGenerationProfile) add("Text generation")
        policy.roleProfiles.filterValues { it == profileId }.keys.sorted().forEach { role ->
            add("Delegated agents (${role.replaceFirstChar { it.uppercase() }})")
        }
        profiles.filter { it.id != profileId && profileId in referencedProfiles(it.id) }.forEach {
            add("the profile ${it.displayLabel}")
        }
    }

    /** The label of profile [id], or [id] when there is none. */
    fun profileLabel(id: String): String = profiles.firstOrNull { it.id == id }?.displayLabel ?: id
}

@Serializable
data class PlacementActions(
    val alertFix: String = "",
    val textGeneration: String? = null,
)

@Serializable
data class CallRequest(
    @SerialName("request_id") val requestId: String,
    val number: String,
    @SerialName("requested_by") val requestedBy: String = "",
    @SerialName("requested_at") val requestedAt: Long = 0,
)

@Serializable
internal data class CallRequestList(
    val requests: List<CallRequest> = emptyList(),
)

/** One placement profile: an ordered list of models with reasoning and on/off switches. */
@Serializable
data class PlacementProfile(
    val id: String,
    val label: String = "",
    val entries: List<PlacementEntry> = emptyList(),
    val configuredEntryCount: Int? = null,
) {
    val configuredEntries: List<PlacementEntry> get() =
        entries.take(configuredEntryCount?.coerceAtLeast(0) ?: entries.size)
    val displayLabel: String get() = label.ifEmpty { id }
}

/**
 * One entry in a placement profile, with its live ranking state when reported:
 * a model, or another profile ([isProfileReference]: instance `placement`,
 * [model] the profile's id), which ranks as that profile's enabled entries.
 */
@Serializable
data class PlacementEntry(
    val instanceId: String = "",
    val model: String = "",
    val label: String = "",
    val driver: String = "",
    val provider: String = "",
    /** A reasoning level, or "" for the model's default. */
    val reasoning: String = "",
    val enabled: Boolean = true,
    val sessionShareOfWeekly: Double? = null,
    /** Live ranking state (`ok`, `promoted`, `full`, `over_threshold`, `exhausted`, `disabled`, `unavailable`), or "". */
    val state: String = "",
    val reason: String = "",
    val runningThreads: Int = 0,
) {
    /** It names another profile, not a model. */
    val isProfileReference: Boolean get() = instanceId == PROFILE_INSTANCE
    val displayLabel: String get() = label.ifEmpty { model.substringAfterLast('/').ifEmpty { model } }
    val displayProvider: String get() = if (isProfileReference) "Profile" else provider.ifEmpty { instanceId }
    /** The same model or profile as [other], whatever its settings. */
    fun sameTarget(other: PlacementEntry): Boolean = instanceId == other.instanceId && model == other.model

    companion object {
        /** The instance of an entry that names a profile. */
        const val PROFILE_INSTANCE = "placement"

        /** An entry that ranks as [profile]'s enabled entries. */
        fun reference(profile: PlacementProfile, enabled: Boolean = true): PlacementEntry = PlacementEntry(
            instanceId = PROFILE_INSTANCE, model = profile.id, label = profile.displayLabel,
            driver = PROFILE_INSTANCE, enabled = enabled,
        )

        /** A new entry for [model], switched on, at its default reasoning. */
        fun of(model: PlacementModel): PlacementEntry = PlacementEntry(
            instanceId = model.instanceId, model = model.model, label = model.label,
            driver = model.driver, provider = model.provider, enabled = true,
        )
    }
}

/** A model reported by at least one host in the last day. */
@Serializable
data class PlacementModel(
    val instanceId: String = "",
    val model: String = "",
    val label: String = "",
    val driver: String = "",
    val provider: String = "",
    /** null for a model without a reasoning option. */
    val reasoning: PlacementReasoning? = null,
    val machines: List<String> = emptyList(),
    val hidden: Boolean = false,
) {
    val displayLabel: String get() = label.ifEmpty { model.substringAfterLast('/').ifEmpty { model } }
    val displayProvider: String get() = provider.ifEmpty { instanceId }
}

@Serializable
data class PlacementReasoning(
    val levels: List<String> = emptyList(),
    @SerialName("default") val defaultLevel: String = "",
)

@Serializable
data class PlacementPolicy(
    val switchAtPercent: Double = 95.0,
    val weeklySlackSessions: Double = 3.0,
    val activeHours: PlacementActiveHours? = null,
    val roleProfiles: Map<String, String> = emptyMap(),
    /** The second weekly threshold; null: the server's default (1.5). Kept so saving the policy keeps it. */
    val weeklyMoveSlackSessions: Double? = null,
    /** Temporary limits by gateway account (`claude-alex`). Read only here: set them with
     *  [PrismClient.setAccountLimit]; saving the policy leaves them alone. */
    val accountLimits: Map<String, PlacementAccountLimit> = emptyMap(),
)

/**
 * A temporary limit on one gateway account: placement counts its 5-hour
 * window as full at [sessionPercent] used and its weekly window at
 * [weeklyPercent] (1–100, null: no limit on that window), until [until]
 * (RFC 3339, "" for no end). "Keep 30% of the session" is `sessionPercent: 70`.
 */
@Serializable
data class PlacementAccountLimit(
    val sessionPercent: Double? = null,
    val weeklyPercent: Double? = null,
    val until: String = "",
) {
    /** The share of the 5-hour window held back, in whole percent, or null. */
    val sessionKept: Int? get() = sessionPercent?.let { kept(it) }
    /** The share of the week held back, in whole percent, or null. */
    val weeklyKept: Int? get() = weeklyPercent?.let { kept(it) }

    /** When it ends, in Unix seconds, or null without an end (or one that can't be read). */
    val untilEpochSeconds: Long? get() = until.takeIf { it.isNotBlank() }?.let { parseRfc3339(it) }

    /** Its end has passed at [now] (Unix seconds): placement no longer applies it. */
    fun ended(now: Long): Boolean = untilEpochSeconds?.let { it <= now } ?: false

    /** "Keeps 30% of the 5-hour window and 10% of the week". */
    val summary: String
        get() = listOfNotNull(
            sessionKept?.let { "$it% of the 5-hour window" },
            weeklyKept?.let { "$it% of the week" },
        ).joinToString(" and ").let { if (it.isEmpty()) "No limit" else "Keeps $it" }

    companion object {
        /** The limit that keeps [sessionKept] and [weeklyKept] percent (0–99, null: none) until [until]. */
        fun keeping(sessionKept: Int?, weeklyKept: Int?, until: String = ""): PlacementAccountLimit =
            PlacementAccountLimit(
                sessionPercent = sessionKept?.let { (100 - it.coerceIn(0, 99)).toDouble() },
                weeklyPercent = weeklyKept?.let { (100 - it.coerceIn(0, 99)).toDouble() },
                until = until,
            )

        private fun kept(percent: Double): Int = (100 - percent).let { kotlin.math.round(it).toInt() }.coerceIn(0, 100)

        /** Unix seconds for `2026-11-28T08:00:00Z`, `…+01:00` or with fractions; null when it isn't one. */
        fun parseRfc3339(text: String): Long? {
            val m = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?\s*([Zz]|[+-]\d{2}:?\d{2})?""")
                .matchEntire(text.trim()) ?: return null
            val (y, mo, d, h, mi) = m.destructured
            val sec = m.groupValues[6].ifEmpty { "0" }.toLong()
            val zone = m.groupValues[7]
            val offset = if (zone.isEmpty() || zone.equals("Z", ignoreCase = true)) 0L else {
                val digits = zone.drop(1).replace(":", "")
                val sign = if (zone[0] == '-') -1 else 1
                sign * (digits.take(2).toLong() * 3600 + digits.drop(2).toLong() * 60)
            }
            return daysFromCivil(y.toLong(), mo.toLong(), d.toLong()) * 86400 +
                h.toLong() * 3600 + mi.toLong() * 60 + sec - offset
        }

        /** `2026-11-28T08:00:00Z` for Unix seconds [epoch]. */
        fun formatRfc3339(epoch: Long): String {
            val days = epoch.floorDiv(86400L)
            val rest = epoch - days * 86400
            val (y, m, d) = civilFromDays(days)
            fun two(n: Long) = n.toString().padStart(2, '0')
            return "${y.toString().padStart(4, '0')}-${two(m)}-${two(d)}T${two(rest / 3600)}:${two(rest % 3600 / 60)}:${two(rest % 60)}Z"
        }

        // Howard Hinnant's days_from_civil / civil_from_days.
        private fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = y.floorDiv(400L)
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        private fun civilFromDays(z0: Long): Triple<Long, Long, Long> {
            val z = z0 + 719468
            val era = z.floorDiv(146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            return Triple(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
        }
    }
}

@Serializable
data class PlacementActiveHours(
    val start: Int = 8,
    val end: Int = 23,
)

/** A thread move Prism asked for. */
@Serializable
data class PlacementMove(
    val machine: String = "",
    val threadId: String = "",
    val from: String = "",
    val to: String = "",
    val reason: String = "",
    val at: Long = 0,
    /** `asked`, `continued`, `failed` or `dropped`. */
    val state: String = "",
)

/** Links into T3 Code (docs/prism-api.md, "Open the thread"). */
object T3Links {
    /** `t3code://threads/<environment>/<thread>`, each part percent-encoded; null without both. */
    fun app(environmentId: String, threadId: String): String? =
        if (environmentId.isEmpty() || threadId.isEmpty()) null
        else "t3code://threads/${encode(environmentId)}/${encode(threadId)}"

    /** T3's web app over NetBird on [host]: `https://<host>.netbird.cloud:3774/<environment>/<thread>`. */
    fun web(host: String, environmentId: String, threadId: String): String? =
        if (host.isEmpty() || environmentId.isEmpty() || threadId.isEmpty()) null
        else "https://${encode(host)}.netbird.cloud:3774/${encode(environmentId)}/${encode(threadId)}"

    /** Percent-encodes everything but RFC 3986's unreserved characters. */
    fun encode(part: String): String = buildString {
        for (b in part.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code ||
                c == '.'.code || c == '_'.code || c == '~'.code
            ) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * A conversation of the phone's assistant, as the history lists it
 * (`/v1/assistant/conversations`); times in Unix seconds.
 */
@Serializable
data class AssistantConversation(
    val id: String,
    /** The device it was started on. */
    val device: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** The committed revision, which the next save names as its base. */
    val revision: Long = 0,
)

/** One stored transcript entry: its ID and the app's JSON of it. */
@Serializable
data class StoredEntry(val id: String, val body: String)

/** A whole stored conversation: its entries and the model's history (each message's JSON), in order. */
@Serializable
data class StoredConversation(
    val conversation: AssistantConversation,
    val entries: List<StoredEntry> = emptyList(),
    val history: List<String> = emptyList(),
)
