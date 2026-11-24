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

    private fun headers() = mapOf("Authorization" to "Bearer $token")

    private fun requireSetUp() {
        if (!configured) throw IllegalStateException("Matrix isn't set up on this phone (settings matrix_homeserver and matrix_access_token)")
    }

    private suspend fun get(path: String) = Http.get(Matrix.base(homeserver) + path, headers())

    suspend fun me(): String = me ?: Matrix.userId(get(Matrix.whoamiPath())).also { me = it }

    /** The joined rooms, most recently active first (kept for this conversation). */
    suspend fun rooms(fresh: Boolean = false): List<Matrix.Room> {
        requireSetUp()
        if (!fresh) rooms?.let { return it }
        return Matrix.decodeRooms(get(Matrix.roomsSyncPath()), me()).also { rooms = it }
    }

    /** The one room [query] names (ID or name); ambiguity goes back to the model. */
    suspend fun room(query: String): Matrix.Room {
        val found = Matrix.find(rooms(), query).ifEmpty { Matrix.find(rooms(fresh = true), query) }
        return when {
            found.isEmpty() -> throw ToolArgumentException("No Matrix room matches \"$query\". matrix_rooms lists them.")
            found.size > 1 && found.none { it.name.equals(query.trim(), true) } ->
                throw ToolArgumentException("Several rooms match \"$query\": " + found.take(6).joinToString(", ") { "${it.name} (${it.id})" } + ". Which one?")
            else -> found.firstOrNull { it.name.equals(query.trim(), true) } ?: found.first()
        }
    }

    suspend fun messages(roomId: String, limit: Int): List<Matrix.Message> {
        requireSetUp()
        val body = get(Matrix.messagesPath(roomId, limit * 2))
        return Matrix.decodeMessages(body, Matrix.decodeNames(body)).takeLast(limit)
    }

    /** Sends [text] to [roomId]; the new event's ID. */
    suspend fun send(roomId: String, text: String): String {
        requireSetUp()
        return Matrix.eventId(
            Http.request("PUT", Matrix.base(homeserver) + Matrix.sendPath(roomId, "prism-" + UUID.randomUUID()), Matrix.text(text).toString(), headers()),
        )
    }

    /** Replaces the text of [eventId] (one of the owner's) in [roomId] with [text]. */
    suspend fun edit(roomId: String, eventId: String, text: String): String {
        requireSetUp()
        return Matrix.eventId(
            Http.request("PUT", Matrix.base(homeserver) + Matrix.sendPath(roomId, "prism-" + UUID.randomUUID()), Matrix.edit(eventId, text).toString(), headers()),
        )
    }
}

internal fun matrixTools(matrix: MatrixClient): List<AssistantTool> = listOf(
    object : AssistantTool(
        Tools.MATRIX_ROOMS,
        "List the owner's Matrix rooms and direct chats (most recently active first), or those whose name matches `query`.",
        optional = listOf(stringParam("query", "Part of a room's or person's name")),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val all = matrix.rooms(fresh = true)
            val rooms = args.optString("query")?.takeIf { it.isNotBlank() }?.let { Matrix.find(all, it) } ?: all
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
                ThreadCard(MessageCard.MATRIX, room.name, msgs.map { ThreadMessage(if (it.sender == me) "Me" else it.senderName, it.text, it.at, it.sender == me) }),
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
