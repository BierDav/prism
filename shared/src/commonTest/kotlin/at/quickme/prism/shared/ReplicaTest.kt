package at.quickme.prism.shared

import at.quickme.prism.rocky.ReplicaException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The replica through the bindings, end to end: rocky-prism-ffi opens a
 * Rocky database in [directory] (empty), a profile written through it reads
 * back, an edit wakes a change watcher, rocky-prism's validation arrives as
 * a typed exception, and the data is still there after reopening.
 * Throws on the first failure.
 */
suspend fun replicaTests(directory: String) {
    var replica = PrismReplica.open(directory)
    val seeded = replica.placement()
    // Prism builds in no profiles: an empty replica has none, and no alert-fix profile.
    check(seeded.profiles.isEmpty()) { "seeded profiles: ${seeded.profiles.map { it.id }}" }
    check(seeded.effectiveAlertFixProfile.isEmpty()) { "alert fix: ${seeded.effectiveAlertFixProfile}" }

    val entries = listOf(
        PlacementEntry(instanceId = "claudeAgent", model = "claude-1/claude-opus-5-5", reasoning = "high"),
        PlacementEntry(instanceId = "codex", model = "gpt-5.5", enabled = false, sessionShareOfWeekly = 0.25),
    )
    replica.savePlacementProfile("default", "Default", entries.take(1))
    val saved = coroutineScope {
        val changed = async { withTimeout(10_000) { replica.changes(listOf("profiles")).first() } }
        // Let the watcher subscribe before the write it waits for.
        kotlinx.coroutines.delay(200)
        val saved = replica.savePlacementProfile("night", "Night", entries)
        check("profiles" in changed.await()) { "change watcher missed the profile write" }
        saved
    }
    val night = saved.profiles.single { it.id == "night" }
    check(night.label == "Night") { "label: ${night.label}" }
    check(night.entries.map { Triple(it.instanceId, it.model, it.reasoning) } ==
        entries.map { Triple(it.instanceId, it.model, it.reasoning) }) { "entries: ${night.entries}" }
    check(night.entries.map { it.enabled } == listOf(true, false)) { "enabled: ${night.entries}" }
    check(night.entries[1].sessionShareOfWeekly == 0.25) { "share: ${night.entries[1]}" }

    val ordered = replica.setPlacementOrder(listOf("night", "default"))
    check(ordered.profiles.map { it.id } == listOf("night", "default")) { "order: ${ordered.profiles.map { it.id }}" }
    val policy = replica.setPlacementPolicy(seeded.policy.copy(switchAtPercent = 80.0)).policy
    check(policy.switchAtPercent == 80.0) { "policy: $policy" }
    val limited = replica.setAccountLimit("claude-alex", PlacementAccountLimit.keeping(30, 10, "2026-11-28T08:00:00Z")).policy
    check(limited.accountLimits["claude-alex"] == PlacementAccountLimit(70.0, 90.0, "2026-11-28T08:00:00Z")) { "account limit: $limited" }
    check(replica.setPlacementPolicy(limited.copy(weeklySlackSessions = limited.weeklySlackSessions)).policy.accountLimits.keys == setOf("claude-alex")) {
        "saving the policy dropped the account limits"
    }
    check(replica.clearAccountLimit("claude-alex").policy.accountLimits.isEmpty()) { "account limit not cleared" }

    val refused = try {
        replica.savePlacementProfile("Not Valid", "x", emptyList())
        null
    } catch (e: ReplicaException.InvalidInput) {
        e
    }
    check(refused != null) { "an invalid profile id was accepted" }

    // The Prism API on the replica, through the client the apps use.
    val api = replica.client("david-pixel", waitSeconds = 0)
    check(api.placement().profiles.map { it.id } == listOf("night", "default")) { "API placement" }
    check(api.alerts("open").isEmpty()) { "alerts on an empty replica" }
    check(api.agents("active").isEmpty()) { "agents on an empty replica" }
    val missing = try {
        api.alert(999)
        null
    } catch (e: PrismError) {
        e
    }
    check(missing?.status == 404 && missing.code == "not_found") { "missing alert: ${missing?.status} ${missing?.code}" }
    // No worker leads: the start is queued, and answers with no thread yet.
    val queued = api.startAgent("do", null, "update brew", PrismClient.requestId())
    check(queued.id.isEmpty() && queued.state == "running") { "queued start: $queued" }
    val invalid = try {
        api.startAgent("paint", null, "x", PrismClient.requestId())
        null
    } catch (e: PrismError) {
        e
    }
    check(invalid?.status == 400) { "an unknown task was accepted" }
    check(PrismReplica.peers("wss://a\n wss://b ,\n") == listOf("wss://a", "wss://b")) { "peers" }

    // The assistant's conversations: stored, read back, listed, deleted.
    val stored = api.saveAssistantConversation(
        "c-1", 0, "s-1", "Weather?", 100, 200,
        listOf(StoredEntry("e0", """{"type":"user","text":"Weather?"}"""), StoredEntry("tool:1", "{}")),
        listOf("""{"m":1}""", """{"m":2}"""),
    )
    check(stored == 1L) { "stored: $stored" }
    // Its answer lost, the identical save again: the same answer.
    val retried = api.saveAssistantConversation(
        "c-1", 0, "s-1", "Weather?", 100, 200,
        listOf(StoredEntry("e0", """{"type":"user","text":"Weather?"}"""), StoredEntry("tool:1", "{}")),
        listOf("""{"m":1}""", """{"m":2}"""),
    )
    check(retried == 1L) { "the retry of a committed save: $retried" }
    val stale = try {
        api.saveAssistantConversation("c-1", 0, "s-other", "x", 1, 1, emptyList(), emptyList())
        null
    } catch (e: PrismError) {
        e
    }
    check(stale?.status == 409) { "a save on an old revision: ${stale?.status}" }
    val read = api.assistantConversation("c-1")
    check(read.conversation == AssistantConversation("c-1", "david-pixel", "Weather?", 100, 200, 1)) { "read: ${read.conversation}" }
    check(read.entries.map { it.id } == listOf("e0", "tool:1") && read.history == listOf("""{"m":1}""", """{"m":2}""")) { "read: $read" }
    check(api.assistantConversations().map { it.id } == listOf("c-1")) { "conversation list" }
    api.deleteAssistantConversation("c-1")
    val gone = try {
        api.assistantConversation("c-1")
        null
    } catch (e: PrismError) {
        e
    }
    check(gone?.status == 404) { "a deleted conversation still read: ${gone?.status}" }
    val savedAgain = try {
        api.saveAssistantConversation("c-1", 1, "s-back", "back", 1, 1, emptyList(), emptyList())
        null
    } catch (e: PrismError) {
        e
    }
    check(savedAgain?.status == 410 && savedAgain.code == "gone") { "a deleted conversation saved again: ${savedAgain?.status}" }

    replica.close()
    val closed = try {
        replica.placement()
        null
    } catch (e: Exception) {
        e
    }
    check(closed != null) { "a closed replica still answered" }

    replica = PrismReplica.open(directory)
    try {
        val reopened = replica.placement()
        check(reopened.profiles.map { it.id } == listOf("night", "default")) { "reopened: ${reopened.profiles.map { it.id }}" }
        check(reopened.policy.switchAtPercent == 80.0) { "reopened policy: ${reopened.policy}" }
    } finally {
        replica.close()
    }
}
