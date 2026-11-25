// The owner's Matrix account for the assistant (settings matrix_homeserver
// and matrix_access_token): find a room, read it, send into it at once, and
// edit what was sent (m.replace), from the model or by hand on the message's
// card (AssistantSession.edit). No Android here.

package at.quickme.prism.assistant

import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.Matrix
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadListCard
import at.quickme.prism.assistant.core.ThreadMessage
import at.quickme.prism.assistant.core.ToolArgumentException
import at.quickme.prism.assistant.core.Tools
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** The client-server API as the owner ([token]) on [homeserver]. */
class MatrixClient(private val homeserver: String, private val token: String) {
    val configured: Boolean get() = homeserver.isNotBlank() && token.isNotBlank()

    private var me: String? = null
    private var rooms: List<Matrix.Room>? = null
    /** How many of the most recent rooms [rooms] covers (all, after a full /sync). */
    private var roomsCover = 0

    private fun headers() = mapOf("Authorization" to "Bearer $token")

    private fun requireSetUp() {
        if (!configured) throw IllegalStateException("Matrix isn't set up on this phone (settings matrix_homeserver and matrix_access_token)")
    }

    private suspend fun get(path: String) = Http.get(Matrix.base(homeserver) + path, headers())

    suspend fun me(): String = me ?: Matrix.userId(get(Matrix.whoamiPath())).also { me = it }

    /**
     * The [limit] most recently active joined rooms, most recent first (kept
     * for this conversation). Finding a room by name looks through [ALL], so
     * an older room is found and a name shared with one is seen as ambiguous.
     */
    suspend fun rooms(fresh: Boolean = false, limit: Int = RECENT): List<Matrix.Room> {
        requireSetUp()
        if (!fresh && roomsCover >= limit) rooms?.let { all -> return all.take(limit) }
        val (found, cover) = try {
            Matrix.decodeSlidingRooms(
                Http.request("POST", Matrix.root(homeserver) + Matrix.slidingSyncPath(), Matrix.slidingSyncBody(limit).toString(), headers()),
            ) to limit
        } catch (e: HttpException) {
            // A homeserver without sliding sync: the (slow) full /sync.
            if (e.status != 404 && e.status != 400) throw e
            Matrix.decodeRooms(get(Matrix.roomsSyncPath()), me()) to Int.MAX_VALUE
        }
        rooms = found
        roomsCover = cover
        return found
    }

    /** The one room [query] names (ID or name); ambiguity goes back to the model. */
    suspend fun room(query: String): Matrix.Room {
        val q = query.trim()
        // A room ID is taken as it is; the homeserver refuses one the owner isn't in.
        if (q.startsWith("!") && ':' in q) return rooms(limit = ALL).firstOrNull { it.id == q } ?: Matrix.Room(q, q)
        val found = Matrix.find(rooms(limit = ALL), query).ifEmpty { Matrix.find(rooms(fresh = true, limit = ALL), query) }
        val exact = found.filter { it.id == query.trim() || it.name.equals(query.trim(), true) }
        val pick = exact.singleOrNull() ?: found.singleOrNull().takeIf { exact.isEmpty() }
        return when {
            found.isEmpty() -> throw ToolArgumentException("No Matrix room matches \"$query\". matrix_rooms lists them.")
            pick == null ->
                throw ToolArgumentException("Several rooms match \"$query\": " + (exact.ifEmpty { found }).take(6).joinToString(", ") { "${it.name} (${it.id})" } + ". Which one?")
            else -> pick
        }
    }

    /**
     * Prism sends and reads plain events only: an end-to-end encrypted room
     * is refused. Asked of the homeserver each time (a room can turn
     * encryption on at any moment); a room it can't vouch for is refused too.
     */
    private suspend fun requirePlain(roomId: String) {
        val encrypted = try {
            get(Matrix.encryptionPath(roomId))
            true
        } catch (e: HttpException) {
            // Only Matrix's own "no such state" means unencrypted; any other
            // 404 (an endpoint it doesn't know) proves nothing.
            if (e.status == 404 && "M_NOT_FOUND" in e.message.orEmpty()) false
            else throw IllegalStateException("Matrix didn't say whether that room is encrypted (HTTP ${e.status})")
        }
        if (encrypted) throw ToolArgumentException("That room is end-to-end encrypted; Prism can't read or send there yet.")
    }

    suspend fun messages(roomId: String, limit: Int): List<Matrix.Message> {
        requireSetUp()
        requirePlain(roomId)
        val body = get(Matrix.messagesPath(roomId, limit * 2))
        return Matrix.decodeMessages(body, Matrix.decodeNames(body)).takeLast(limit)
    }

    /** Sends [text] to [roomId]; the new event's ID. */
    suspend fun send(roomId: String, text: String): String {
        requireSetUp()
        requirePlain(roomId)
        return Matrix.eventId(
            Http.request("PUT", Matrix.base(homeserver) + Matrix.sendPath(roomId, "prism-" + UUID.randomUUID()), Matrix.text(text).toString(), headers()),
        )
    }

    /** Replaces the text of [eventId] (one of the owner's) in [roomId] with [text]. */
    suspend fun edit(roomId: String, eventId: String, text: String): String {
        requireSetUp()
        requirePlain(roomId)
        return Matrix.eventId(
            Http.request("PUT", Matrix.base(homeserver) + Matrix.sendPath(roomId, "prism-" + UUID.randomUUID()), Matrix.edit(eventId, text).toString(), headers()),
        )
    }

    companion object {
        /** The rooms a list shows from (300 take about 6 s). */
        const val RECENT = 300

        /** The rooms a name is looked up in: all of them, in practice (570 take about 13 s, once per conversation). */
        const val ALL = 5000
    }
}

internal fun matrixTools(matrix: MatrixClient): List<AssistantTool> = listOf(
    object : AssistantTool(
        Tools.MATRIX_ROOMS,
        "List the owner's Matrix rooms and direct chats (most recently active first), or those whose name matches `query`.",
        optional = listOf(stringParam("query", "Part of a room's or person's name")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val query = args.optString("query")?.takeIf { it.isNotBlank() }
            val all = matrix.rooms(fresh = true, limit = if (query != null) MatrixClient.ALL else MatrixClient.RECENT)
            val rooms = query?.let { Matrix.find(all, it) } ?: all
            if (rooms.isEmpty()) return ToolOutput("No room matches.")
            val shown = rooms.take(20)
            return ToolOutput(
                shown.joinToString("\n") { "${it.name} (${it.id})" + if (it.direct) ", direct chat" else "" },
                ThreadListCard(MessageCard.MATRIX, shown.map { ThreadMessage(it.name, if (it.direct) "Direct chat" else "Room", it.lastActive) }),
            )
        }
    },
    object : AssistantTool(
        Tools.MATRIX_READ,
        "Read the latest messages of a Matrix room or direct chat (oldest first, edits applied). `room` is a room's or person's name, or a room ID.",
        required = listOf(stringParam("room", "The room's or person's name, or the room ID")),
        optional = listOf(intParam("limit", "How many messages (default 15, at most 50)")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val room = matrix.room(args.string("room"))
            val me = matrix.me()
            val msgs = matrix.messages(room.id, (args.optInt("limit") ?: 15).coerceIn(1, 50))
            if (msgs.isEmpty()) return ToolOutput("No messages in ${room.name}.")
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            return ToolOutput(
                "${room.name} (${room.id}), oldest first:\n" + msgs.joinToString("\n") {
                    "[${time.format(Date(it.at))}] ${if (it.sender == me) "Me" else it.senderName}: ${it.text}" +
                        (if (it.edited) " (edited)" else "") + if (it.sender == me) " {event ${it.eventId}}" else ""
                },
                ThreadCard(MessageCard.MATRIX, room.name, msgs.map { ThreadMessage(if (it.sender == me) "Me" else it.senderName, it.text, it.at, it.sender == me) }, address = room.id),
            )
        }
    },
    object : AssistantTool(
        Tools.MATRIX_SEND,
        "Send a Matrix message right away (no confirmation) to a room or person. Returns the event ID, which matrix_edit needs to change it later.",
        required = listOf(stringParam("room", "The room's or person's name, or the room ID"), stringParam("text", "The message")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val room = matrix.room(args.string("room"))
            val text = args.string("text")
            val event = matrix.send(room.id, text)
            return ToolOutput(
                "Sent to ${room.name} (room ${room.id}, event $event). It can be edited with matrix_edit.",
                MessageCard(MessageCard.MATRIX, room.name, room.id, text, eventId = event, at = System.currentTimeMillis()),
            )
        }
    },
    object : AssistantTool(
        Tools.MATRIX_EDIT,
        "Change the text of a Matrix message the owner sent (shown edited in place). Use the room ID and event ID from matrix_send or matrix_read.",
        required = listOf(stringParam("room", "The room ID (or name)"), stringParam("event_id", "The original message's event ID"), stringParam("text", "The whole new text")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val room = matrix.room(args.string("room"))
            val event = args.string("event_id")
            val text = args.string("text")
            matrix.edit(room.id, event, text)
            return ToolOutput(
                "Edited the message in ${room.name} to: $text",
                MessageCard(MessageCard.MATRIX, room.name, room.id, text, eventId = event, edited = true, at = System.currentTimeMillis()),
            )
        }
    },
)
