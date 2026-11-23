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
    check(seeded.profiles.map { it.id } == listOf("default", "review", "minor")) { "seeded profiles: ${seeded.profiles.map { it.id }}" }
    check(seeded.effectiveAlertFixProfile == "default") { "alert fix: ${seeded.effectiveAlertFixProfile}" }

    val entries = listOf(
        PlacementEntry(instanceId = "claudeAgent", model = "claude-1/claude-opus-5-5", reasoning = "high"),
        PlacementEntry(instanceId = "codex", model = "gpt-5.5", enabled = false, sessionShareOfWeekly = 0.25),
    )
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

    val ordered = replica.setPlacementOrder(listOf("night", "default", "review", "minor"))
    check(ordered.profiles.map { it.id } == listOf("night", "default", "review", "minor")) { "order: ${ordered.profiles.map { it.id }}" }
    val policy = replica.setPlacementPolicy(seeded.policy.copy(switchAtPercent = 80.0)).policy
    check(policy.switchAtPercent == 80.0) { "policy: $policy" }

    val refused = try {
        replica.savePlacementProfile("Not Valid", "x", emptyList())
        null
    } catch (e: ReplicaException.InvalidInput) {
        e
    }
    check(refused != null) { "an invalid profile id was accepted" }

    // The Prism API on the replica, through the client the apps use.
    val api = replica.client("david-pixel", waitSeconds = 0)
    check(api.placement().profiles.map { it.id } == listOf("night", "default", "review", "minor")) { "API placement" }
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
        check(reopened.profiles.map { it.id } == listOf("night", "default", "review", "minor")) { "reopened: ${reopened.profiles.map { it.id }}" }
        check(reopened.policy.switchAtPercent == 80.0) { "reopened policy: ${reopened.policy}" }
    } finally {
        replica.close()
    }
}
