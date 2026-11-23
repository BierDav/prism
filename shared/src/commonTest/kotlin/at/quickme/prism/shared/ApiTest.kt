package at.quickme.prism.shared

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/** The API's objects, the client against a fake server, and push: for every
 *  platform's runner. [relayUrl] serves `/prism-events/json` (pushStreamLines). */
suspend fun apiTests(relayUrl: String) {
    objectTests()
    clientTests()
    pushTests(relayUrl)
}

/** What the relay test server streams: a keepalive, two messages and junk. */
val pushStreamLines = listOf(
    """{"id":"k1","event":"keepalive","topic":"prism-events"}""",
    """{"id":"m1","event":"message","topic":"prism-events","message":"{\"v\":1,\"type\":\"alert.firing\",\"key\":\"alert.firing:42\",\"time\":100,\"title\":\"#42 brew-update\",\"severity\":\"warning\",\"alertId\":42}"}""",
    """not json""",
    """{"id":"m2","event":"message","topic":"prism-events","message":"{\"v\":1,\"type\":\"app.release\",\"key\":\"r\",\"package\":\"at.quickme.prism\",\"versionCode\":7}"}""",
)

private fun objectTests() {
    val json = PrismClient.json
    // The contract's examples, with Go's nulls for empty lists and maps.
    val alert = json.decodeFromString(
        Alert.serializer(),
        """{"id":42,"kind":"machine_event","name":"brew-update","alertname":"HostCheckFailed","category":"Machines",
           "severity":"bogus","host":"david-mb","labels":null,"state":"open","startsAt":9999999,"createdAt":100,
           "agents":[{"id":"mcp:launch:9e47","title":"","alertId":42,"state":"running","questionPending":true,
                      "t3":{"environmentId":"65bb","threadId":"mcp:launch:9e47","host":"david-mb"}}],"new":1}""",
    )
    check(alert.title == "brew-update" && alert.level == Severity.WARNING && alert.labels.isEmpty()) { "alert: $alert" }
    check(alert.canResolve && alert.since(now = 200) == 100L) { "a start in the future falls back to createdAt" }
    val agent = alert.agents.single()
    check(agent.status == "waiting" && agent.active && agent.displayTitle == "Agent") { "agent: $agent" }
    check(agent.link == "t3code://threads/65bb/mcp%3Alaunch%3A9e47") { "link: ${agent.link}" }
    check(json.decodeFromString(Alert.serializer(), """{"id":1,"name":"","alertname":"X","agents":null}""").title == "X")

    val groups = groupAlerts(
        listOf(
            Alert(id = 1, category = "Storage", severity = "warning", createdAt = 10),
            Alert(id = 2, category = "Storage", severity = "critical", createdAt = 5),
            Alert(id = 3, category = "Nonsense", createdAt = 1),
            Alert(id = 4, category = "Machines", severity = "warning", createdAt = 20),
            Alert(id = 5, category = "Storage", severity = "warning", createdAt = 30),
        ),
        now = 100,
    )
    check(groups.map { g -> g.category to g.alerts.map { it.id } } ==
        listOf("Machines" to listOf(4L), "Storage" to listOf(2L, 5L, 1L), "Other" to listOf(3L))) { "groups: $groups" }

    val metrics = json.decodeFromString(
        HostMetricsList.serializer(),
        """{"time":5,"hosts":[{"name":"david-mb","online":true,"cpu":null,"cores":18,"load":null,
            "memory":{"used":1,"total":4},"swap":null,"disk":{"mount":"/","used":3,"total":4},
            "network":{"receive":1.5,"transmit":2},"battery":{"level":0.5,"charging":true},
            "history":{"start":1,"step":120,"cpu":[0.2,null],"memory":[]}}]}""",
    )
    val host = metrics.hosts.single()
    check(host.cpu == null && host.load.isEmpty() && host.memory?.share == 0.25 && host.disk?.usage?.share == 0.75) { "host: $host" }
    check(host.history?.cpu == listOf(0.2, null) && host.network?.transmit == 2.0) { "history: ${host.history}" }

    val model = json.decodeFromString(
        ModelSettings.serializer(),
        """{"model":{"instanceId":"codex","provider":"codex","model":"codex-david/gpt-6.1-sol"},
            "reasoning":{"current":"","default":"medium","levels":["low","medium"]},
            "models":[{"instanceId":"codex","model":"a/x"},{"instanceId":"claude","model":"y"},{"instanceId":"codex","model":"z"}]}""",
    )
    check(model.model.shortName == "gpt-6.1-sol" && model.reasoning?.label == "medium") { "model: $model" }
    check(model.grouped.map { g -> g.instance to g.models.map { it.model } } == listOf("codex" to listOf("a/x", "z"), "claude" to listOf("y")))

    val q = Question(
        id = "c0/b6", title = "Clean up",
        questions = listOf(
            QuestionItem(id = "colour", multiSelect = false, options = listOf(QuestionOption("Red"), QuestionOption("Blue"))),
            QuestionItem(id = "parts", multiSelect = true, options = listOf(QuestionOption("A"), QuestionOption("B"))),
            QuestionItem(id = "note"),
            QuestionItem(id = "free", multiSelect = true, options = listOf(QuestionOption("A"))),
        ),
    )
    val body = PrismClient.answerBody(q, mapOf("colour" to listOf("Blue"), "parts" to listOf("A", "B"), "note" to listOf(" hi "), "free" to listOf("mine")), "r1")
    check(body.toString() == """{"answers":{"colour":"Blue","parts":["A","B"],"note":"hi","free":"mine"},"requestId":"r1"}""") { "answer: $body" }

    check(T3Links.encode("mcp:launch/ä b~") == "mcp%3Alaunch%2F%C3%A4%20b~")
    check(T3Links.web("david-mb", "e", "t:1") == "https://david-mb.netbird.cloud:3774/e/t%3A1")
    check(T3Links.app("", "t") == null)
    val id = PrismClient.requestId()
    check(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id) && id != PrismClient.requestId()) { "requestId: $id" }

    val pushed = PushMessage.parse("""{"v":1,"type":"agent.question","key":"q:1","threadId":"t","agentId":"","time":1000,"package":"p"}""")
    check(pushed != null && pushed.notificationTag == "agent-t" && pushed.packageName == "p" && pushed.fresh(1000 + 60) && !pushed.fresh(1000 + 3600)) { "push: $pushed" }
    val placementPush = PushMessage.parse("""{"v":1,"type":"placement.changed","key":"placement:1","time":1000}""")
    check(placementPush != null && placementPush.type == "placement.changed") { "placement push: $placementPush" }
    check(PushMessage.parse("""{"v":2,"type":"x"}""") == null && PushMessage.parse("nope") == null)

    val placement = json.decodeFromString(
        Placement.serializer(),
        """{"profiles":[{"id":"coding","label":"Coding","entries":[{"instanceId":"claudeAgent","model":"claude-david/claude-opus-5-5","label":"Opus 5.5 · claude-david","driver":"claudeAgent","provider":"Claude","reasoning":"high","enabled":true,"sessionShareOfWeekly":13.7,"state":"ok","reason":"Session window at 42%.","runningThreads":2}]}],
            "models":[{"instanceId":"claudeAgent","model":"claude-david/claude-opus-5-5","label":"Opus 5.5 · claude-david","driver":"claudeAgent","provider":"Claude","reasoning":{"levels":["low","medium","high","max"],"default":"high"},"machines":["david-mb"]}],
            "alertFixProfile":"coding",
            "actions":{"alertFix":"coding","textGeneration":"minor"},
            "roles":["implementation","research","review","design","test","general"],
            "policy":{"switchAtPercent":95,"weeklySlackSessions":2.5,"activeHours":{"start":8,"end":23},"roleProfiles":{"review":"review"}},
            "moves":[{"machine":"david-mb","threadId":"thr-1","from":"claude-david/claude-opus-5-5","to":"claude-1/claude-opus-5-5","reason":"limit","at":1763749200,"state":"continued"}]}""",
    )
    check(placement.profiles.single().displayLabel == "Coding" && placement.effectiveAlertFixProfile == "coding") { "placement: $placement" }
    check(placement.effectiveTextGenerationProfile == "minor" && placement.roles.size == 6) { "placement actions/roles: $placement" }
    check(placement.profiles.single().entries.single().let { it.driver == "claudeAgent" && it.displayProvider == "Claude" }) { "entry driver/provider: $placement" }
    check(placement.models.single().let { it.driver == "claudeAgent" && it.provider == "Claude" && it.reasoning?.defaultLevel == "high" }) { "model: $placement" }
    check(placement.policy.activeHours?.end == 23 && placement.policy.weeklySlackSessions == 2.5) { "placement: $placement" }
    check(placement.moves.single().state == "continued") { "placement moves: ${placement.moves}" }

    var kept: List<String> = emptyList()
    val seen = SeenKeys(listOf("a", "b"), limit = 3) { kept = it }
    check(!seen.firstTime("a") && seen.firstTime("c") && seen.firstTime("d") && kept == listOf("b", "c", "d")) { "seen: $kept" }
    check(seen.firstTime("a") && seen.firstTime("") && seen.firstTime("")) { "a dropped key and empty keys are new" }
}

private suspend fun clientTests() {
    val sent = ArrayList<PrismRequest>()
    var answer = PrismResponse(200, "{}")
    var fail: Exception? = null
    var token: String? = "prism_t"
    val client = PrismClient("https://prism.example/v1/", { token }) { request ->
        sent += request
        fail?.let { throw it }
        answer
    }

    answer = PrismResponse(200, """{"alerts":[{"id":7,"name":"x"}],"more":true}""")
    check(client.alerts("open").single().id == 7L)
    check(sent.last().let { it.url == "https://prism.example/v1/alerts?state=open" && it.method == "GET" && it.token == "prism_t" && it.body == null }) { "GET: ${sent.last()}" }

    answer = PrismResponse(201, """{"agent":{"id":"a1","title":"Fix"}}""")
    check(client.fix(7, " look ", "r1").id == "a1")
    check(sent.last().body == """{"requestId":"r1","instruction":"look"}""") { "fix: ${sent.last()}" }
    client.startAgent("do", null, "update", "r2")
    check(sent.last().let { it.path == "/v1/agents" && it.body == """{"task":"do","request":"update","requestId":"r2"}""" }) { "start: ${sent.last()}" }

    answer = PrismResponse(200, """{"machine":"oracle","models":[{"instanceId":"codex","model":"a/b"}],
        "defaultModel":{"instanceId":"codex","model":"a/b"},"projects":[{"id":"","name":"Scratch folder"}],"defaultProject":""}""")
    val options = client.agentOptions("runner/oracle")
    check(sent.last().path == "/v1/agent-options?target=runner%2Foracle" && options.defaultProject == "")
    answer = PrismResponse(201, """{"agent":{"id":"a2"}}""")
    client.startAgent("do", "runner/oracle", "check", "r5", options.defaultModel, options.defaultProject)
    check(sent.last().body == """{"task":"do","target":"runner/oracle","request":"check","requestId":"r5","projectId":"","model":{"instanceId":"codex","model":"a/b"}}""") {
        "start with selections: ${sent.last()}"
    }

    answer = PrismResponse(409, """{"error":"Alert #7 is already resolved.","code":"conflict"}""")
    val conflict = runCatching { client.dismiss(7, "r3") }.exceptionOrNull()
    check(conflict is PrismError && conflict.status == 409 && conflict.code == "conflict" && conflict.message == "Alert #7 is already resolved.") { "409: $conflict" }

    answer = PrismResponse(502, "<html>bad gateway</html>")
    val gateway = runCatching { client.questions() }.exceptionOrNull()
    check(gateway is PrismError && gateway.message == "The Prism server answered HTTP 502.") { "502: $gateway" }

    answer = PrismResponse(200, "not json")
    check((runCatching { client.machines() }.exceptionOrNull() as? PrismError)?.code == "invalid")

    fail = IllegalStateException("no route")
    val unreachable = runCatching { client.hostMetrics() }.exceptionOrNull()
    check(unreachable is PrismError && unreachable.status == 0 && unreachable.message == "Prism is not reachable (no route).") { "down: $unreachable" }
    check(!client.healthy())
    fail = null

    token = null
    val before = sent.size
    val anonymous = runCatching { client.modelSettings() }.exceptionOrNull()
    check(anonymous is PrismError && anonymous.unauthorized && sent.size == before) { "no token, no request: $anonymous" }
    answer = PrismResponse(200, """{"ok":true}""")
    check(client.healthy())

    token = "prism_t"
    answer = PrismResponse(200, """{"model":{"model":"m"},"reasoning":null}""")
    check(client.setReasoning("default").reasoning == null && sent.last().let { it.method == "PUT" && it.body == """{"level":"default"}""" })
    answer = PrismResponse(200, """{"ok":true}""")
    client.answer(Question(id = "a:b/c"), emptyMap(), "r4")
    check(sent.last().path == "/v1/questions/a%3Ab%2Fc/answer") { "answer: ${sent.last()}" }

    val placementJson = """{"profiles":[{"id":"coding","label":"Coding","entries":[]}],"models":[],"alertFixProfile":"coding"}"""
    answer = PrismResponse(200, placementJson)
    check(client.placement().alertFixProfile == "coding" && sent.last().let { it.method == "GET" && it.path == "/v1/placement" })
    client.savePlacementProfile("coding", "Coding", listOf(PlacementEntry("claudeAgent", "claude-david/claude-opus-5-5", reasoning = "high", enabled = true)))
    check(sent.last().let {
        it.method == "PUT" && it.path == "/v1/placement/profiles/coding" &&
            it.body == """{"label":"Coding","entries":[{"instanceId":"claudeAgent","model":"claude-david/claude-opus-5-5","reasoning":"high","enabled":true}]}"""
    }) { "savePlacementProfile: ${sent.last()}" }
    client.deletePlacementProfile("minor")
    check(sent.last().let { it.method == "DELETE" && it.path == "/v1/placement/profiles/minor" })
    client.setPlacementOrder(listOf("coding", "review"))
    check(sent.last().let { it.method == "PUT" && it.path == "/v1/placement/order" && it.body == """{"ids":["coding","review"]}""" })
    client.setAlertFixProfile("review")
    check(sent.last().let { it.method == "PUT" && it.path == "/v1/placement/alert-fix-profile" && it.body == """{"profile":"review"}""" })
    client.setPlacementActions(alertFix = "coding", textGeneration = "", roleProfiles = mapOf("review" to "review", "test" to ""))
    check(sent.last().let {
        it.method == "PUT" && it.path == "/v1/placement/actions" &&
            it.body == """{"alertFix":"coding","textGeneration":"","roleProfiles":{"review":"review","test":""}}"""
    }) { "setPlacementActions: ${sent.last()}" }
    client.setPlacementPolicy(PlacementPolicy(switchAtPercent = 90.0, weeklySlackSessions = 2.5))
    check(sent.last().let { it.method == "PUT" && it.path == "/v1/placement/policy" })
}

private suspend fun pushTests(relayUrl: String) {
    var since = ""
    val stream = RelayStream({ relayUrl }, { "relay-token" }, since = "") { since = it }
    val got = withTimeout(10_000) { stream.messages.take(2).toList() }
    check(got.map { it.type } == listOf("alert.firing", "app.release")) { "pushed: $got" }
    check(got[0].alertId == 42L && got[0].title == "#42 brew-update" && got[1].versionCode == 7L) { "pushed: $got" }
    check(since == "m1" || since == "m2") { "since: $since" }

    val unconfigured = RelayStream({ relayUrl }, { null }, since = "1h") {}
    coroutineScope {
        val job = launch { unconfigured.messages.collect {} }
        withTimeout(5_000) { while (unconfigured.status.value.text == "not started") delay(10) }
        job.cancel()
    }
    check(!unconfigured.status.value.connected && unconfigured.status.value.text.startsWith("not configured")) { "status: ${unconfigured.status.value}" }
}
