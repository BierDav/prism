// The assistant's outbox record of one conversation (assistant/core/Outbox.kt):
// the save in flight is sent with its own ID until it is answered, however
// often the conversation is saved meanwhile and across the app being killed;
// its answer rebases the next version; a refusal marks that save only.
//
//   java -cp <classes>:kotlin-stdlib.jar:kotlinx-serialization-*.jar OutboxTestKt

import at.quickme.prism.assistant.core.Outbox
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

@Serializable
private data class Saved(
    val text: String,
    override val revision: Long = 0,
    override val saveId: String = "",
    override val problem: String = "",
) : Outbox.Version<Saved> {
    override fun with(revision: Long, saveId: String, problem: String) = copy(revision = revision, saveId = saveId, problem = problem)
}

/** [o] written to its file and read back, as after the app was killed. */
private fun reopened(o: Outbox<Saved>): Outbox<Saved> {
    val record = Outbox.serializer(Saved.serializer())
    val json = Json { ignoreUnknownKeys = true }
    return json.decodeFromString(record, json.encodeToString(record, o))
}

fun main() {
    // --- Save A goes out; B is saved while A's answer is lost ---
    var o = Outbox<Saved>().queued(Saved("a"), "s-a").started()
    check(o.sending == Saved("a", 0, "s-a") && o.next == null, "A is in flight, on revision 0, as s-a")
    o = o.queued(Saved("b"), "s-b")
    check(o.sending?.saveId == "s-a" && o.next == Saved("b", 0, "s-b"), "B waits next to A; A stays in flight")
    // A committed but its answer never came; the app is killed and restarts.
    o = reopened(o).started()
    check(o.sending == Saved("a", 0, "s-a"), "A is sent again as it was, with s-a, until its answer is known")
    o = o.queued(Saved("c"), "s-c")
    check(o.sending?.saveId == "s-a" && o.next == Saved("c", 0, "s-c"), "C replaces B, never A")
    // The retry is answered: revision 1. Recorded with C's new base at once.
    o = o.acknowledged(1)
    check(o.committed == 1L && o.sending == null && o.next == Saved("c", 1, "s-c"), "A's answer and C on revision 1, in one record")
    o = reopened(o).started()
    check(o.sending == Saved("c", 1, "s-c"), "C goes next, on what A committed")
    o = o.acknowledged(2)
    check(!o.waiting && o.latest == null && o.committed == 2L, "nothing waits; revision 2 is kept")
    check(o.queued(Saved("d"), "s-d").next == Saved("d", 2, "s-d"), "a later save builds on revision 2")
    check(Outbox<Saved>(committed = 2).queued(Saved("e", revision = 5), "s-e").next?.revision == 5L, "or on what its session loaded, if newer")

    // --- A refusal marks the refused save only ---
    var r = Outbox<Saved>(committed = 3).queued(Saved("a"), "s-a").started().queued(Saved("b"), "s-b")
    r = r.refused("Not saved: too big")
    check(r.sending == null && r.next == Saved("b", 3, "s-b") && r.waiting, "a newer version than the refused one is still sent")
    r = r.started().refused("Not saved: still too big")
    check(r.next == Saved("b", 3, "s-b", "Not saved: still too big") && !r.waiting, "the last version refused stays, with why, unsent")
    check(r.started() == r, "and isn't sent again")
    r = r.queued(Saved("c"), "s-c")
    check(r.next == Saved("c", 3, "s-c") && r.waiting, "a later save doesn't inherit the refusal: it is tried")

    println(if (failed == 0) "all passed" else "$failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
