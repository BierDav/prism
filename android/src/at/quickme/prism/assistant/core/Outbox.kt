// What the outbox keeps of one conversation (ConversationStore), one file
// written whole: the revision the replica last committed for it, the save in
// flight and the latest version queued after it. The save in flight is sent
// with its own saveId until its answer is known, however often the
// conversation is saved meanwhile and whether or not the app was killed: a
// retry of a save that committed is answered as the first time. Its answer
// and the successor's new base are then one write. No Android here
// (android/tests).

package at.quickme.prism.assistant.core

import kotlinx.serialization.Serializable

@Serializable
data class Outbox<S : Outbox.Version<S>>(
    /** The revision the replica last committed (0: none known). */
    val committed: Long = 0,
    /** The save sent, its answer not known yet. */
    val sending: S? = null,
    /** The latest version, sent once [sending] is answered; never while it carries a problem. */
    val next: S? = null,
) {
    /** One version of a conversation as it is saved. */
    interface Version<S> {
        /** The committed revision it builds on. */
        val revision: Long
        /** The ID of the revision it makes. */
        val saveId: String
        /** Why the replica refused it for good (it is then kept, unsent). */
        val problem: String
        fun with(revision: Long, saveId: String, problem: String): S
    }

    /** The newest version on the phone, if any. */
    val latest: S? get() = next ?: sending

    /** The base for a version saved now, by a session that loaded [loaded]. */
    fun base(loaded: Long): Long = next?.revision ?: sending?.revision ?: maxOf(loaded, committed)

    /** [s] queued as the latest version, with [saveId]: it replaces the one queued before, never the one in flight. */
    fun queued(s: S, saveId: String): Outbox<S> = copy(next = s.with(base(s.revision), saveId, ""))

    /** The version to send now, made the one in flight (unchanged when one is already). */
    fun started(): Outbox<S> = when {
        sending != null -> this
        next != null && next.problem.isEmpty() -> copy(sending = next, next = null)
        else -> this
    }

    /** The save in flight committed revision [r]: the next version builds on it. */
    fun acknowledged(r: Long): Outbox<S> = Outbox(committed = r, next = next?.let { it.with(r, it.saveId, it.problem) })

    /**
     * The save in flight was refused for good with [why]. A newer version
     * still goes (it may well be stored); with none, the refused one stays
     * on the phone with why, unsent until the conversation is saved again.
     */
    fun refused(why: String): Outbox<S> = when {
        sending == null -> this
        next != null -> copy(sending = null)
        else -> copy(sending = null, next = sending.with(sending.revision, sending.saveId, why))
    }

    /** Something waits to be sent. */
    val waiting: Boolean get() = sending != null || (next != null && next.problem.isEmpty())
}
