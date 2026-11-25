// Matrix's client-server API as the assistant uses it (spec.matrix.org,
// v3): the owner's rooms with their names, a room's recent messages with
// edits applied, a text message, and an edit of one (m.replace). The
// requests and their JSON; the HTTP is assistant/MatrixClient.kt.

package at.quickme.prism.assistant.core

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object Matrix {
    /** A joined room: its ID, its name as a client would show it, whether it is a direct chat. */
    data class Room(val id: String, val name: String, val direct: Boolean = false, val lastActive: Long = 0, val encrypted: Boolean = false)

    /** A text message in a room, edits applied. */
    data class Message(val eventId: String, val sender: String, val senderName: String, val text: String, val at: Long, val edited: Boolean = false)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun base(homeserver: String): String = root(homeserver) + "/_matrix/client/v3"

    fun root(homeserver: String): String = homeserver.trim().trimEnd('/')

    fun whoamiPath() = "/account/whoami"

    /**
     * A /sync that only names the rooms: no timeline beyond the last event,
     * only the state that names a room, members lazily (the heroes name a
     * room without a name), the m.direct list.
     */
    fun roomsSyncPath(): String {
        val filter = buildJsonObject {
            putJsonObject("room") {
                putJsonObject("timeline") { put("limit", 1) }
                putJsonObject("state") {
                    put("types", JsonArray(listOf("m.room.name", "m.room.canonical_alias", "m.room.member", "m.room.encryption").map(::JsonPrimitive)))
                    put("lazy_load_members", true)
                }
                putJsonObject("ephemeral") { put("not_types", JsonArray(listOf(JsonPrimitive("*")))) }
                putJsonObject("account_data") { put("not_types", JsonArray(listOf(JsonPrimitive("*")))) }
            }
            putJsonObject("presence") { put("not_types", JsonArray(listOf(JsonPrimitive("*")))) }
        }
        return "/sync?timeout=0&filter=" + enc(filter.toString())
    }

    /**
     * Simplified sliding sync (MSC4186), as Element X uses it: the [limit]
     * most recently active rooms with the names the server computes, whether
     * each is a direct chat, and its m.room.encryption. One request of a few
     * seconds, where a first /sync of hundreds of bridged rooms takes over a
     * minute (measured: 570 rooms, 84 s).
     */
    fun slidingSyncPath() = "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync?timeout=0"

    fun slidingSyncBody(limit: Int): JsonObject = buildJsonObject {
        putJsonObject("lists") {
            putJsonObject("rooms") {
                put("ranges", JsonArray(listOf(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(limit - 1))))))
                put("timeline_limit", 0)
                put("required_state", JsonArray(listOf(JsonArray(listOf(JsonPrimitive("m.room.encryption"), JsonPrimitive(""))))))
            }
        }
    }

    /** The rooms of a sliding sync answer (slidingSyncBody), in its order: most recently active first. */
    fun decodeSlidingRooms(body: String): List<Room> {
        val root = Json.parseToJsonElement(body).jsonObject
        val rooms = root.obj("rooms") ?: JsonObject(emptyMap())
        val order = root.obj("lists")?.obj("rooms")?.arr("ops").orEmpty()
            .mapNotNull { it as? JsonObject }
            .flatMap { op -> op.arr("room_ids").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
            .ifEmpty { rooms.keys.toList() }
            .distinct()
        return order.map { id ->
            val room = rooms.obj(id)
            val encrypted = room?.arr("required_state").orEmpty().any { (it as? JsonObject)?.str("type") == "m.room.encryption" }
            // A room without a name (most direct chats) is named by its heroes, as clients show it.
            val heroes = room?.arr("heroes").orEmpty().mapNotNull { it as? JsonObject }
                .mapNotNull { h -> h.str("displayname")?.takeIf(String::isNotBlank) ?: h.str("user_id")?.let(::localpart) }
            val name = room?.str("name")?.takeIf { it.isNotBlank() } ?: heroes.joinToString(", ").takeIf { it.isNotBlank() } ?: id
            // The list's order is its recency; it has no event times.
            Room(id, name, direct = room?.get("is_dm") == JsonPrimitive(true), encrypted = encrypted)
        }
    }

    fun messagesPath(roomId: String, limit: Int): String {
        val filter = buildJsonObject { put("types", JsonArray(listOf(JsonPrimitive("m.room.message")))) }
        return "/rooms/${enc(roomId)}/messages?dir=b&limit=$limit&filter=" + enc(filter.toString())
    }

    /** The room's m.room.encryption state: 404 while it isn't encrypted. */
    fun encryptionPath(roomId: String) = "/rooms/${enc(roomId)}/state/m.room.encryption/"

    fun sendPath(roomId: String, txnId: String) = "/rooms/${enc(roomId)}/send/m.room.message/${enc(txnId)}"

    fun memberPath(roomId: String, userId: String) = "/rooms/${enc(roomId)}/state/m.room.member/${enc(userId)}"

    /** A plain text message. */
    fun text(body: String): JsonObject = buildJsonObject {
        put("msgtype", "m.text")
        put("body", body)
    }

    /**
     * An edit of [eventId] to [body]: clients that know edits show the new
     * text in place of the old; older ones see "* new text".
     */
    fun edit(eventId: String, body: String): JsonObject = buildJsonObject {
        put("msgtype", "m.text")
        put("body", "* $body")
        putJsonObject("m.new_content") {
            put("msgtype", "m.text")
            put("body", body)
        }
        putJsonObject("m.relates_to") {
            put("rel_type", "m.replace")
            put("event_id", eventId)
        }
    }

    fun eventId(body: String): String =
        (Json.parseToJsonElement(body).jsonObject["event_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("the homeserver returned no event ID")

    fun userId(body: String): String =
        (Json.parseToJsonElement(body).jsonObject["user_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("the homeserver returned no user ID")

    /** The joined rooms of a /sync answer (roomsSyncPath), most recently active first. */
    fun decodeRooms(body: String, me: String): List<Room> {
        val root = Json.parseToJsonElement(body).jsonObject
        val join = root.obj("rooms")?.obj("join") ?: return emptyList()
        val direct = root.obj("account_data")?.arr("events")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("type") == "m.direct" }
            ?.obj("content")?.values?.flatMap { (it as? JsonArray).orEmpty() }
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        return join.mapNotNull { (id, value) ->
            val room = value as? JsonObject ?: return@mapNotNull null
            val state = room.obj("state")?.arr("events").orEmpty().mapNotNull { it as? JsonObject } +
                room.obj("timeline")?.arr("events").orEmpty().mapNotNull { it as? JsonObject }
            fun stateContent(type: String) = state.lastOrNull { it.str("type") == type && it.containsKey("state_key") }?.obj("content")
            val heroes = room.obj("summary")?.arr("m.heroes").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it != me }
            val memberNames = state.filter { it.str("type") == "m.room.member" }
                .associate { (it.str("state_key") ?: "") to (it.obj("content")?.str("displayname") ?: "") }
            val name = stateContent("m.room.name")?.str("name")?.takeIf { it.isNotBlank() }
                ?: stateContent("m.room.canonical_alias")?.str("alias")?.takeIf { it.isNotBlank() }
                ?: heroes.joinToString(", ") { memberNames[it]?.takeIf(String::isNotBlank) ?: localpart(it) }.takeIf { it.isNotBlank() }
                ?: id
            val last = room.obj("timeline")?.arr("events").orEmpty().mapNotNull { (it as? JsonObject)?.long("origin_server_ts") }.maxOrNull() ?: 0
            val isDirect = id in direct || (heroes.size == 1 && stateContent("m.room.name") == null)
            Room(id, name, isDirect, last, encrypted = stateContent("m.room.encryption") != null)
        }.sortedByDescending { it.lastActive }
    }

    /**
     * The text messages of a /messages answer (newest first, dir=b), oldest
     * first, with each edit's new text in place of the original's.
     */
    fun decodeMessages(body: String, names: Map<String, String> = emptyMap()): List<Message> {
        val root = Json.parseToJsonElement(body).jsonObject
        val events = root.arr("chunk").orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("type") == "m.room.message" }
        // Edits: the newest replacement of each event wins.
        val edits = HashMap<String, String>()
        for (e in events.sortedBy { it.long("origin_server_ts") ?: 0 }) {
            val rel = e.obj("content")?.obj("m.relates_to") ?: continue
            if (rel.str("rel_type") != "m.replace") continue
            val target = rel.str("event_id") ?: continue
            val text = e.obj("content")?.obj("m.new_content")?.str("body") ?: continue
            edits[target] = text
        }
        return events.mapNotNull { e ->
            val content = e.obj("content") ?: return@mapNotNull null
            if (content.obj("m.relates_to")?.str("rel_type") == "m.replace") return@mapNotNull null
            val id = e.str("event_id") ?: return@mapNotNull null
            val sender = e.str("sender").orEmpty()
            val original = when (content.str("msgtype")) {
                "m.text", "m.notice", "m.emote" -> content.str("body")
                "m.image" -> "[image] " + content.str("body").orEmpty()
                "m.file" -> "[file] " + content.str("body").orEmpty()
                else -> content.str("body")
            } ?: return@mapNotNull null
            val text = edits[id]
            Message(id, sender, names[sender] ?: localpart(sender), text ?: original, e.long("origin_server_ts") ?: 0, text != null)
        }.sortedBy { it.at }
    }

    /** The display names in a /messages answer's state (lazy-loaded members). */
    fun decodeNames(body: String): Map<String, String> {
        val root = Json.parseToJsonElement(body).jsonObject
        return root.arr("state").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "m.room.member" }
            .mapNotNull { e -> e.str("state_key")?.let { it to (e.obj("content")?.str("displayname") ?: localpart(it)) } }
            .toMap()
    }

    /** "@david:quickme.at" → "david". */
    fun localpart(userId: String): String = userId.removePrefix("@").substringBefore(':')

    /**
     * The rooms that best match [query]: an exact room ID or name first, then
     * names that contain it, then names that contain all its words.
     */
    fun find(rooms: List<Room>, query: String): List<Room> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return rooms
        rooms.firstOrNull { it.id == query.trim() }?.let { return listOf(it) }
        val exact = rooms.filter { it.name.lowercase() == q }
        if (exact.isNotEmpty()) return exact
        val contains = rooms.filter { q in it.name.lowercase() }
        if (contains.isNotEmpty()) return contains
        val words = q.split(Regex("\\s+")).filter { it.length > 1 }
        return rooms.filter { r -> words.isNotEmpty() && words.all { it in r.name.lowercase() } }
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
}
