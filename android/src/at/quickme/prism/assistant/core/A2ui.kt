// The assistant's chat as A2UI (a2ui.org, protocol v1.0): every card in the
// transcript is a surface, the A2UI JSON is how a conversation stores it, and
// the phone draws it with Google's Compose renderer (assistant/a2ui/). What
// the renderer speaks is A2UI v0.9 (androidx.a2ui 1.0.0-alpha02), so the
// surfaces are kept in v1.0 and translated on the way in (Renderer) and their
// actions on the way out (UiAction.fromRenderer). No Android here (tested on
// a plain JVM: android/tests/A2uiTest.kt).

package at.quickme.prism.assistant.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object A2ui {
    /** The protocol version surfaces are kept and exchanged in. */
    const val VERSION = "1.0"

    /** The version the renderer (androidx.a2ui 1.0.0-alpha02) parses. */
    const val RENDERER_VERSION = "v0.9"

    /**
     * Where an event's `userMessage` travels through the renderer, which
     * keeps only an event's name and context (v0.9): in the context, under
     * this key, taken out again by [UiAction.fromRenderer].
     */
    const val USER_MESSAGE = "\$userMessage"

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
}

/** An A2UI message or surface the phone can't use: [message] says why, for the model to fix. */
class UiError(message: String) : IllegalArgumentException(message)

/**
 * A surface as createSurface makes it (A2UI v1.0): its ID, the catalog its
 * components come from, the components (flat, `root` the one drawn) and the
 * data model they bind to. Stored as it is (Entry.Ui).
 */
@Serializable
data class Surface(
    val surfaceId: String,
    val catalogId: String = PrismCatalog.ID,
    val components: List<JsonObject> = emptyList(),
    val dataModel: JsonObject = JsonObject(emptyMap()),
    /** Actions carry the data model ([UiAction.dataModel]): a form's values reach the model. */
    val sendDataModel: Boolean = false,
) {
    /** The component with [id], if any. */
    fun component(id: String): JsonObject? = components.firstOrNull { it.str("id") == id }

    /** The component drawn: `root`. */
    val root: JsonObject? get() = component(ROOT)

    /** The type of component [id] ("Text", "Weather", …). */
    fun type(id: String): String? = component(id)?.str("component")

    /** This surface after [m] (one of its updates). */
    fun after(m: UiMessage): Surface = when (m) {
        is UiMessage.Create -> m.surface
        is UiMessage.UpdateComponents -> {
            val byId = m.components.associateBy { it.str("id") }
            val replaced = components.map { c -> byId[c.str("id")] ?: c }
            val added = m.components.filter { n -> components.none { it.str("id") == n.str("id") } }
            copy(components = replaced + added)
        }
        is UiMessage.UpdateDataModel ->
            copy(dataModel = JsonPointer.set(dataModel, m.path, m.value) as? JsonObject ?: JsonObject(emptyMap()))
        is UiMessage.Delete -> this
    }

    companion object {
        const val ROOT = "root"
    }
}

/** The A2UI v1.0 messages from the agent to the renderer that the phone takes. */
sealed interface UiMessage {
    val surfaceId: String

    data class Create(val surface: Surface) : UiMessage {
        override val surfaceId get() = surface.surfaceId
    }

    data class UpdateComponents(override val surfaceId: String, val components: List<JsonObject>) : UiMessage

    /** [value] at [path] (a JSON Pointer); null removes what is there. */
    data class UpdateDataModel(override val surfaceId: String, val path: String = "/", val value: JsonElement?) : UiMessage

    data class Delete(override val surfaceId: String) : UiMessage

    companion object {
        /** [e] as a message; [UiError] says what is wrong with it. */
        fun parse(e: JsonElement): UiMessage {
            val o = e as? JsonObject ?: throw UiError("each message must be an object such as {\"createSurface\": {…}}")
            o["version"]?.let { v ->
                val s = (v as? JsonPrimitive)?.contentOrNull
                if (s != A2ui.VERSION && s != "v1.0") throw UiError("\"version\" must be \"${A2ui.VERSION}\" (got $v)")
            }
            val ops = o.keys - "version"
            if (ops.size != 1) throw UiError("each message has exactly one of createSurface, updateComponents, updateDataModel, deleteSurface (got ${ops.joinToString()})")
            val op = ops.single()
            val body = o[op] as? JsonObject ?: throw UiError("\"$op\" must be an object")
            val id = body.str("surfaceId")?.takeIf { it.isNotBlank() } ?: throw UiError("$op needs a \"surfaceId\"")
            return when (op) {
                "createSurface" -> {
                    if ("theme" in body) throw UiError("createSurface has no \"theme\" in A2UI v1.0")
                    val catalog = body.str("catalogId") ?: PrismCatalog.ID
                    Create(
                        Surface(
                            surfaceId = id,
                            catalogId = catalog,
                            components = components(body["components"] ?: JsonArray(emptyList()), op),
                            dataModel = body["dataModel"]?.let { it as? JsonObject ?: throw UiError("createSurface's \"dataModel\" must be an object") } ?: JsonObject(emptyMap()),
                            sendDataModel = (body["sendDataModel"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        ),
                    )
                }
                "updateComponents" -> UpdateComponents(id, components(body["components"] ?: throw UiError("updateComponents needs \"components\""), op))
                "updateDataModel" -> {
                    val path = body.str("path") ?: "/"
                    JsonPointer.check(path)
                    if ("value" !in body) throw UiError("updateDataModel needs a \"value\" (null removes)")
                    UpdateDataModel(id, path, body["value"].takeIf { it !is JsonNull })
                }
                "deleteSurface" -> Delete(id)
                else -> throw UiError("unknown message \"$op\"; the phone takes createSurface, updateComponents, updateDataModel and deleteSurface")
            }
        }

        private fun components(e: JsonElement, op: String): List<JsonObject> {
            val list = e as? JsonArray ?: throw UiError("$op's \"components\" must be a list")
            return list.mapIndexed { i, c -> c as? JsonObject ?: throw UiError("$op's components[$i] must be an object") }
        }

        /** [m] as A2UI v1.0 JSON. */
        fun encode(m: UiMessage): JsonObject = JsonObject(
            mapOf(
                "version" to JsonPrimitive(A2ui.VERSION),
                when (m) {
                    is Create -> "createSurface" to JsonObject(buildMap {
                        put("surfaceId", JsonPrimitive(m.surface.surfaceId))
                        put("catalogId", JsonPrimitive(m.surface.catalogId))
                        if (m.surface.sendDataModel) put("sendDataModel", JsonPrimitive(true))
                        put("components", JsonArray(m.surface.components))
                        put("dataModel", m.surface.dataModel)
                    })
                    is UpdateComponents -> "updateComponents" to JsonObject(mapOf("surfaceId" to JsonPrimitive(m.surfaceId), "components" to JsonArray(m.components)))
                    is UpdateDataModel -> "updateDataModel" to JsonObject(mapOf("surfaceId" to JsonPrimitive(m.surfaceId), "path" to JsonPrimitive(m.path), "value" to (m.value ?: JsonNull)))
                    is Delete -> "deleteSurface" to JsonObject(mapOf("surfaceId" to JsonPrimitive(m.surfaceId)))
                },
            ),
        )
    }
}

/** JSON Pointers (RFC 6901) into a data model. */
object JsonPointer {
    fun segments(path: String): List<String> {
        check(path)
        if (path == "/" || path.isEmpty()) return emptyList()
        return path.removePrefix("/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    fun check(path: String) {
        if (path.isNotEmpty() && !path.startsWith("/")) throw UiError("\"$path\" isn't a JSON Pointer: it must start with /")
    }

    /** What is at [path] in [root], or null. */
    fun get(root: JsonElement, path: String): JsonElement? {
        var at: JsonElement = root
        for (s in segments(path)) {
            at = when (at) {
                is JsonObject -> at[s] ?: return null
                is JsonArray -> at.getOrNull(s.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return at
    }

    /** [root] with [value] at [path] (objects made on the way), or without it when [value] is null. */
    fun set(root: JsonElement, path: String, value: JsonElement?): JsonElement {
        val segs = segments(path)
        if (segs.isEmpty()) return value ?: JsonObject(emptyMap())
        return setAt(root, segs, value)
    }

    private fun setAt(at: JsonElement?, segs: List<String>, value: JsonElement?): JsonElement {
        val s = segs.first()
        val rest = segs.drop(1)
        if (at is JsonArray) {
            val list = at.toMutableList()
            val i = if (s == "-") list.size else s.toIntOrNull() ?: throw UiError("\"$s\" isn't an index of a list")
            if (i < 0 || i > list.size) throw UiError("index $i is past the end of a list of ${list.size}")
            if (rest.isEmpty()) {
                if (value == null) { if (i < list.size) list.removeAt(i) }
                else if (i == list.size) list.add(value) else list[i] = value
            } else {
                val child = setAt(list.getOrNull(i), rest, value)
                if (i == list.size) list.add(child) else list[i] = child
            }
            return JsonArray(list)
        }
        val map = (at as? JsonObject)?.toMutableMap() ?: LinkedHashMap()
        if (rest.isEmpty()) {
            if (value == null) map.remove(s) else map[s] = value
        } else map[s] = setAt(map[s], rest, value)
        return JsonObject(map)
    }
}

/**
 * Surfaces for the renderer, which parses A2UI v0.9: createSurface without
 * components or data model (they follow as updateDataModel and
 * updateComponents), bindings as `path` and `call` (v1.0's `@path`,
 * `@call`), `accessibility` and `checks` without their `@`, and an event's
 * `userMessage` in its context ([A2ui.USER_MESSAGE]).
 */
object Renderer {
    /** The messages that draw [s] from nothing, as JSON strings. */
    fun create(s: Surface): List<String> = buildList {
        add(message("createSurface", buildMap {
            put("surfaceId", JsonPrimitive(s.surfaceId))
            put("catalogId", JsonPrimitive(s.catalogId))
            if (s.sendDataModel) put("sendDataModel", JsonPrimitive(true))
        }))
        if (s.dataModel.isNotEmpty()) add(dataModel(s.surfaceId, "/", s.dataModel))
        if (s.components.isNotEmpty()) add(components(s.surfaceId, s.components))
    }

    /** What turns [old] (drawn) into [new]: the components that changed, then the data model if it did. */
    fun update(old: Surface, new: Surface): List<String> = buildList {
        if (new.dataModel != old.dataModel) add(dataModel(new.surfaceId, "/", new.dataModel))
        val changed = new.components.filter { c -> old.component(c.str("id").orEmpty()) != c }
        if (changed.isNotEmpty()) add(components(new.surfaceId, changed))
    }

    /**
     * What brings the renderer from [old] (drawn for entry [oldEntry]) to
     * [new] of entry [entry]: an update, or, when it is another surface under
     * the same ID (another entry, catalog or sendDataModel), its deletion and
     * creation, so nothing of the old one carries over.
     */
    fun sync(old: Surface?, oldEntry: String?, new: Surface, entry: String): List<String> = when {
        old == null -> create(new)
        oldEntry != entry || old.catalogId != new.catalogId || old.sendDataModel != new.sendDataModel -> listOf(delete(old.surfaceId)) + create(new)
        old != new -> update(old, new)
        else -> emptyList()
    }

    fun delete(surfaceId: String): String = message("deleteSurface", mapOf("surfaceId" to JsonPrimitive(surfaceId)))

    private fun dataModel(id: String, path: String, value: JsonElement) =
        message("updateDataModel", mapOf("surfaceId" to JsonPrimitive(id), "path" to JsonPrimitive(path), "value" to value))

    private fun components(id: String, list: List<JsonObject>) =
        message("updateComponents", mapOf("surfaceId" to JsonPrimitive(id), "components" to JsonArray(list.map(::component))))

    private fun message(op: String, body: Map<String, JsonElement>) =
        JsonObject(mapOf("version" to JsonPrimitive(A2ui.RENDERER_VERSION), op to JsonObject(body))).toString()

    /** One component as v0.9 has it. */
    fun component(c: JsonObject): JsonObject = JsonObject(
        c.entries.mapNotNull { (k, v) ->
            when (k) {
                "@catalogId", "@metadata" -> null
                "@accessibility" -> "accessibility" to value(v)
                "@checks" -> "checks" to value(v)
                "action" -> k to action(v)
                else -> k to value(v)
            }
        }.toMap(),
    )

    private fun action(v: JsonElement): JsonElement {
        val o = v as? JsonObject ?: return v
        val event = o["event"] as? JsonObject ?: return value(v)
        val context = (event["context"] as? JsonObject).orEmpty()
        val user = event["userMessage"]
        val ctx = if (user != null) context + (A2ui.USER_MESSAGE to user) else context
        return JsonObject(mapOf("event" to JsonObject(mapOf("name" to (event["name"] ?: JsonPrimitive("")), "context" to value(JsonObject(ctx))))))
    }

    /** Bindings and calls renamed, everywhere in [v]. */
    private fun value(v: JsonElement): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.entries.associate { (k, x) ->
            when (k) {
                "@path" -> "path" to x
                "@call" -> "call" to x
                else -> k to value(x)
            }
        })
        is JsonArray -> JsonArray(v.map(::value))
        else -> v
    }
}

/**
 * What the owner did on a surface: a component's action (A2UI v1.0's
 * `action` message, from the renderer to the agent), its context resolved,
 * and the surface's data model when it sends it.
 */
@Serializable
data class UiAction(
    val name: String,
    val surfaceId: String,
    val sourceComponentId: String,
    val timestamp: Long = 0,
    val context: JsonObject = JsonObject(emptyMap()),
    val userMessage: String? = null,
    val dataModel: JsonObject? = null,
) {
    /** As A2UI v1.0 sends it (timestamp in ISO 8601). */
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "version" to JsonPrimitive(A2ui.VERSION),
            "action" to JsonObject(buildMap {
                put("name", JsonPrimitive(name))
                put("surfaceId", JsonPrimitive(surfaceId))
                put("sourceComponentId", JsonPrimitive(sourceComponentId))
                put("timestamp", JsonPrimitive(java.time.Instant.ofEpochMilli(timestamp).toString()))
                put("context", context)
                userMessage?.let { put("userMessage", JsonPrimitive(it)) }
            }),
        ),
    )

    companion object {
        /**
         * The renderer's event (v0.9: its type, the surface, the component,
         * its resolved context and the surfaces' data models, as plain Kotlin
         * values) as a v1.0 action.
         */
        fun fromRenderer(
            name: String,
            surfaceId: String,
            componentId: String,
            timestamp: Long,
            context: Map<String, Any?>,
            dataModel: Any? = null,
        ): UiAction {
            val ctx = toJson(context) as? JsonObject ?: JsonObject(emptyMap())
            val user = (ctx[A2ui.USER_MESSAGE] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return UiAction(name, surfaceId, componentId, timestamp, JsonObject(ctx - A2ui.USER_MESSAGE), user, dataModel?.let(::toJson) as? JsonObject)
        }

        /** A plain Kotlin value (the renderer's maps, lists, strings, numbers, booleans) as JSON. */
        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Int, is Long, is Short, is Byte -> JsonPrimitive((v as Number).toLong())
            is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d) }
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
            is Iterable<*> -> JsonArray(v.map(::toJson))
            is Array<*> -> JsonArray(v.map(::toJson))
            else -> JsonPrimitive(v.toString())
        }

        /** JSON as plain Kotlin values, as the renderer takes them. */
        fun toPlain(e: JsonElement?): Any? = when (e) {
            null, JsonNull -> null
            is JsonObject -> e.mapValues { toPlain(it.value) }
            is JsonArray -> e.map(::toPlain)
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.doubleOrNull
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
