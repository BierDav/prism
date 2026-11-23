package at.quickme.prism.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** The approval queue and the enrollment message, for every platform's runner. */
suspend fun approvalTests() {
    val answered = CompletableDeferred<Pair<Outcome, String?>>()
    val channel = object : ApprovalChannel {
        override suspend fun answer(approval: Approval, outcome: Outcome, signature: String?) {
            answered.complete(outcome to signature)
        }
    }
    var stored: List<Decision> = emptyList()
    val queue = ApprovalQueue(onHistory = { stored = it })
    val a = Approval(id = "a", title = "Deploy main", source = "david-server", requestedAt = 100, expiresAt = 200)
    check(queue.offer(a, channel, now = 150)) { "offer" }
    check(!queue.offer(a, channel, now = 150)) { "a repeated ID is refused" }
    check(!queue.offer(a.copy(id = "late"), channel, now = 250)) { "an expired approval is refused" }
    check(queue.pending.value == listOf(a)) { "pending: ${queue.pending.value}" }
    check(queue.decide("a", Outcome.DENIED, now = 160)) { "decide" }
    check(!queue.decide("a", Outcome.DENIED, now = 160)) { "decided once" }
    check(withTimeout(5_000) { answered.await() } == (Outcome.DENIED to null)) { "the channel got the answer" }
    check(stored == listOf(Decision(160, Outcome.DENIED, "Deploy main", "david-server"))) { "history: $stored" }
    check(Approvals.decodeHistory(Approvals.encodeHistory(stored)) == stored) { "history round trip" }
    check(Approvals.decodeHistory("garbage").isEmpty()) { "an unreadable history is empty" }

    queue.offer(a.copy(id = "b"), channel, now = 150)
    queue.expire(now = 300)
    check(queue.pending.value.isEmpty() && queue.history.value.first().outcome == Outcome.EXPIRED) { "expiry" }

    val message = Approvals.message(a).decodeToString()
    check(message.startsWith("{\"id\":\"a\",\"title\":\"Deploy main\"") && "\"thread\":null" in message) { "message: $message" }

    val remote = a.copy(options = listOf(
        ApprovalOption("accept", "Allow once"),
        ApprovalOption("acceptForSession", "Allow for this session"),
        ApprovalOption("decline", "Deny"),
    ))
    val session = Approvals.choosing(remote, "acceptForSession")
    check(!Approvals.message(remote).contentEquals(Approvals.message(session))) { "approval scope must change the signed bytes" }
    check("\"decision\":\"acceptForSession\"" in Approvals.message(session).decodeToString())
    check(runCatching { Approvals.choosing(remote, "acceptAlways") }.isFailure) { "unoffered decisions must be refused" }
    queue.offer(remote, channel, now = 150)
    check(queue.choose(remote.id, "acceptForSession") == session)
    check(queue.get(remote.id) == session) { "the channel must receive the scope that was signed" }

    val enrollment = Enrollment.message("david-mb\"x", now = 5).decodeToString()
    check(Regex("""\{"created":5,"device":"david-mb\\"x","namespace":"prism@quickme.at","nonce":"[0-9a-f]{64}","v":1\}""")
        .matches(enrollment)) { "enrollment: $enrollment" }
}
