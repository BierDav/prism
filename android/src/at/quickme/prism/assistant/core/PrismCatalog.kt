// Prism's A2UI catalog: A2UI's basic catalog (Text, Row, Button, TextField,
// ChoicePicker, …) and the assistant's cards as components of their own
// (Weather, Call, Message, Contacts, …), whose properties are the cards'
// fields. What the model sends with show_ui is checked against it here
// (validate), with errors it can fix; the renderer's catalog
// (assistant/a2ui/PrismCatalogUi.kt) draws the same components. A card made
// by a tool is a surface with its component at the root, every field bound
// to the data model, which holds the card (CardSurfaces). No Android here
// (tested on a plain JVM: android/tests/A2uiTest.kt).

package at.quickme.prism.assistant.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object PrismCatalog {
    const val ID = "https://prism.quickme.at/a2ui/catalog/v1"

    /** What a property takes. */
    enum class Kind {
        /** A string, number or boolean literal, or a binding ({"@path"} / {"@call"}). */
        DYN_STRING,
        DYN_NUMBER,
        DYN_BOOLEAN,
        DYN_STRINGS,
        DYN_ANY,
        STRING,
        NUMBER,
        BOOLEAN,
        ENUM,

        /** A component's ID. */
        COMPONENT,

        /** A list of component IDs, or a template {"componentId", "path"}. */
        CHILDREN,
        ACTION,
        OPTIONS,
        TABS,
        ICON,
    }

    data class Prop(val name: String, val kind: Kind, val required: Boolean = false, val values: List<String> = emptyList())

    /** A component: its name, what it is for, its properties; [card] for the assistant's cards. */
    class Spec(val name: String, val description: String, props: List<Prop>, val card: KSerializer<out Card>? = null) {
        val props: List<Prop> = props + COMMON
        fun prop(name: String): Prop? = props.firstOrNull { it.name == name } ?: ALIASES[name]?.let { a -> props.firstOrNull { it.name == a } }
    }

    private val COMMON = listOf(Prop("weight", Kind.NUMBER), Prop("@accessibility", Kind.DYN_ANY))
    private val ALIASES = mapOf("accessibility" to "@accessibility", "checks" to "@checks")
    private val CHECKS = Prop("@checks", Kind.DYN_ANY)

    val ICONS = listOf(
        "accountCircle", "add", "arrowBack", "arrowForward", "attachFile", "calendarToday", "call", "camera", "check", "close",
        "delete", "download", "edit", "error", "event", "fastForward", "favorite", "favoriteOff", "folder", "help", "home", "info",
        "locationOn", "lock", "lockOpen", "mail", "menu", "moreHoriz", "moreVert", "notifications", "notificationsOff", "pause",
        "payment", "person", "phone", "photo", "play", "print", "refresh", "rewind", "search", "send", "settings", "share",
        "shoppingCart", "skipNext", "skipPrevious", "star", "starHalf", "starOff", "stop", "upload", "visibility",
        "visibilityOff", "volumeDown", "volumeMute", "volumeOff", "volumeUp", "warning",
    )

    private fun p(name: String, kind: Kind, required: Boolean = false, vararg values: String) = Prop(name, kind, required, values.toList())

    /** A2UI's basic catalog, as the renderer has it. */
    val basic: List<Spec> = listOf(
        Spec("Text", "Text; simple Markdown.", listOf(p("text", Kind.DYN_STRING, true), p("variant", Kind.ENUM, false, "h1", "h2", "h3", "h4", "h5", "caption", "body"))),
        Spec("Image", "An image from a URL.", listOf(
            p("url", Kind.DYN_STRING, true), p("description", Kind.DYN_STRING),
            p("fit", Kind.ENUM, false, "contain", "cover", "fill", "none", "scaleDown"),
            p("variant", Kind.ENUM, false, "icon", "avatar", "smallFeature", "mediumFeature", "largeFeature", "header"),
        )),
        Spec("Icon", "A built-in icon.", listOf(p("name", Kind.ICON, true))),
        Spec("Video", "A video from a URL.", listOf(p("url", Kind.DYN_STRING, true))),
        Spec("AudioPlayer", "Audio from a URL.", listOf(p("url", Kind.DYN_STRING, true), p("description", Kind.DYN_STRING))),
        Spec("Row", "Children side by side.", listOf(
            p("children", Kind.CHILDREN, true),
            p("justify", Kind.ENUM, false, "start", "center", "end", "spaceBetween", "spaceAround", "spaceEvenly", "stretch"),
            p("align", Kind.ENUM, false, "start", "center", "end", "stretch"),
        )),
        Spec("Column", "Children one below the other.", listOf(
            p("children", Kind.CHILDREN, true),
            p("justify", Kind.ENUM, false, "start", "center", "end", "spaceBetween", "spaceAround", "spaceEvenly", "stretch"),
            p("align", Kind.ENUM, false, "start", "center", "end", "stretch"),
        )),
        Spec("List", "A list of children; a template repeats one per item of a data-model list.", listOf(
            p("children", Kind.CHILDREN, true), p("direction", Kind.ENUM, false, "vertical", "horizontal"),
            p("align", Kind.ENUM, false, "start", "center", "end", "stretch"),
        )),
        Spec("Card", "A tonal card around one child.", listOf(p("child", Kind.COMPONENT, true))),
        Spec("Tabs", "Tabs, each a title and a child.", listOf(p("tabs", Kind.TABS, true))),
        Spec("Modal", "A trigger that opens content in a sheet.", listOf(p("trigger", Kind.COMPONENT, true), p("content", Kind.COMPONENT, true))),
        Spec("Divider", "A line.", listOf(p("axis", Kind.ENUM, false, "horizontal", "vertical"))),
        Spec("Button", "A button; its child is its label (usually a Text).", listOf(
            p("child", Kind.COMPONENT, true), p("variant", Kind.ENUM, false, "default", "primary", "borderless"), p("action", Kind.ACTION, true), CHECKS,
        )),
        Spec("TextField", "A text input bound to the data model.", listOf(
            p("label", Kind.DYN_STRING, true), p("value", Kind.DYN_STRING),
            p("variant", Kind.ENUM, false, "shortText", "longText", "number", "obscured"), p("validationRegexp", Kind.STRING), CHECKS,
        )),
        Spec("CheckBox", "A checkbox bound to a boolean.", listOf(p("label", Kind.DYN_STRING, true), p("value", Kind.DYN_BOOLEAN, true), CHECKS)),
        Spec("ChoicePicker", "Options to pick, one or several; value is the list of picked option values.", listOf(
            p("label", Kind.DYN_STRING), p("variant", Kind.ENUM, false, "mutuallyExclusive", "multipleSelection"),
            p("options", Kind.OPTIONS, true), p("value", Kind.DYN_STRINGS, true),
            p("displayStyle", Kind.ENUM, false, "checkbox", "chips"), p("filterable", Kind.BOOLEAN), CHECKS,
        )),
        Spec("Slider", "A number in a range.", listOf(p("label", Kind.DYN_STRING), p("min", Kind.NUMBER), p("max", Kind.NUMBER, true), p("value", Kind.DYN_NUMBER, true), CHECKS)),
        Spec("DateTimeInput", "A date and/or time (ISO 8601 string).", listOf(
            p("value", Kind.DYN_STRING, true), p("enableDate", Kind.BOOLEAN), p("enableTime", Kind.BOOLEAN),
            p("min", Kind.DYN_ANY), p("max", Kind.DYN_ANY), p("label", Kind.DYN_STRING), CHECKS,
        )),
    )

    /** The assistant's cards as components: their properties are the cards' fields. */
    val cards: List<Spec> = listOf(
        card("Weather", "The weather card (get_weather).", WeatherCard.serializer()),
        card("Call", "A call placed; Call back and Message.", CallCard.serializer()),
        card("Message", "A message sent; a Matrix one can be edited.", MessageCard.serializer()),
        card("Thread", "Messages of a conversation; Reply.", ThreadCard.serializer()),
        card("ThreadList", "Recent conversations; tapping one opens it.", ThreadListCard.serializer()),
        card("Alarm", "An alarm; Snooze and Dismiss.", AlarmCard.serializer()),
        card("Timer", "A running timer; Stop and +1 min.", TimerCard.serializer()),
        card("Setting", "A setting with its switch.", SettingCard.serializer()),
        card("Contacts", "Contacts; each can be called or texted.", ContactsCard.serializer()),
        card("Events", "Calendar events; tapping one opens it.", EventsCard.serializer()),
        card("Handoff", "A request handed to a T3 agent; Open in T3.", HandoffCard.serializer()),
        card("Agents", "T3 agents.", AgentsCard.serializer()),
        card("Screenshot", "The screen was looked at.", ScreenshotCard.serializer()),
    )

    @OptIn(ExperimentalSerializationApi::class)
    private fun card(name: String, description: String, s: KSerializer<out Card>): Spec {
        val d = s.descriptor
        return Spec(name, description, (0 until d.elementsCount).map { i -> Prop(d.getElementName(i), Kind.DYN_ANY, !d.isElementOptional(i)) }, s)
    }

    val all: Map<String, Spec> = (basic + cards).associateBy { it.name }

    /** The component that shows [card]. */
    fun specOf(card: Card): Spec = cards.first { it.card!!.descriptor.serialName == cardSerializer(card).descriptor.serialName }

    @Suppress("UNCHECKED_CAST")
    internal fun cardSerializer(card: Card): KSerializer<Card> = when (card) {
        is WeatherCard -> WeatherCard.serializer()
        is CallCard -> CallCard.serializer()
        is MessageCard -> MessageCard.serializer()
        is ThreadCard -> ThreadCard.serializer()
        is ThreadListCard -> ThreadListCard.serializer()
        is AlarmCard -> AlarmCard.serializer()
        is TimerCard -> TimerCard.serializer()
        is SettingCard -> SettingCard.serializer()
        is ContactsCard -> ContactsCard.serializer()
        is EventsCard -> EventsCard.serializer()
        is HandoffCard -> HandoffCard.serializer()
        is AgentsCard -> AgentsCard.serializer()
        is ScreenshotCard -> ScreenshotCard.serializer()
    } as KSerializer<Card>

    /** The catalog in a few lines, for the model (show_ui's description). */
    fun describe(): String = buildString {
        append("Basic: ")
        append(basic.joinToString("; ") { s -> s.name + "(" + s.props.filter { it.name != "weight" && !it.name.startsWith("@") }.joinToString(", ") { pr -> describe(pr) } + ")" })
        append(". Prism cards (properties are the card's fields): ")
        append(cards.joinToString("; ") { s -> s.name + "(" + s.props.filter { it.name != "weight" && !it.name.startsWith("@") }.joinToString(", ") { pr -> pr.name + if (pr.required) "*" else "" } + ")" })
        append(". * = required. All components also take weight (in a Row or Column).")
    }

    private fun describe(p: Prop): String = p.name + (if (p.required) "*" else "") + when (p.kind) {
        Kind.ENUM -> ": " + p.values.joinToString("|")
        Kind.CHILDREN -> ": [ids] or {componentId, path}"
        Kind.COMPONENT -> ": id"
        Kind.ACTION -> ": {event:{name, context, userMessage}}"
        Kind.OPTIONS -> ": [{label, value}]"
        Kind.TABS -> ": [{title, child}]"
        else -> ""
    }

    // --- Validation ---

    /** The most components a surface may have. */
    const val MAX_COMPONENTS = 200

    /**
     * What is wrong with [s] for this phone, as the model can fix it: empty
     * when it can be drawn.
     */
    fun validate(s: Surface): List<String> {
        val errors = ArrayList<String>()
        if (s.catalogId != ID) errors += "catalogId must be \"$ID\" (got \"${s.catalogId}\")"
        if (s.components.isEmpty()) errors += "the surface has no components; one must have the id \"root\""
        if (s.components.size > MAX_COMPONENTS) errors += "too many components (${s.components.size}, at most $MAX_COMPONENTS)"
        val ids = HashSet<String>()
        for ((i, c) in s.components.withIndex()) {
            val id = c.str("id")
            if (id.isNullOrBlank()) { errors += "components[$i] has no \"id\""; continue }
            if (!ids.add(id)) errors += "two components have the id \"$id\""
        }
        if (s.components.isNotEmpty() && Surface.ROOT !in ids) errors += "no component has the id \"root\", which is the one drawn"
        val edges = HashMap<String, List<String>>()
        for (c in s.components) {
            val id = c.str("id") ?: continue
            val type = c.str("component")
            val spec = type?.let { all[it] }
            if (spec == null) {
                errors += "\"$id\": " + if (type == null) "no \"component\" type" else "unknown component \"$type\"; the catalog has ${all.keys.joinToString(", ")}"
                continue
            }
            val refs = ArrayList<String>()
            for ((key, v) in c) {
                if (key == "id" || key == "component" || key == "@catalogId" || key == "@metadata") continue
                val prop = spec.prop(key)
                if (prop == null) {
                    errors += "\"$id\" ($type) has no property \"$key\"; it takes ${spec.props.joinToString(", ") { it.name }}"
                    continue
                }
                check(prop, v, "\"$id\".$key", ids, refs, errors)
            }
            for (prop in spec.props.filter { it.required }) {
                if (prop.name !in c && ALIASES.entries.none { it.value == prop.name && it.key in c }) errors += "\"$id\" ($type) needs \"${prop.name}\""
            }
            edges[id] = refs
            if (spec.card != null) cardError(s, c, spec)?.let { errors += "\"$id\" ($type): $it" }
        }
        cycle(edges)?.let { errors += "components refer to each other in a circle: ${it.joinToString(" → ")}" }
        return errors.distinct()
    }

    private fun check(prop: Prop, v: JsonElement, at: String, ids: Set<String>, refs: MutableList<String>, errors: MutableList<String>) {
        fun bad(what: String) { errors += "$at must be $what (got ${v.toString().take(80)})" }
        val prim = v as? JsonPrimitive
        when (prop.kind) {
            Kind.DYN_STRING -> if (!(prim != null && !isNull(v)) && !binding(v, at, errors)) bad("a string or a binding {\"@path\": \"/…\"}")
            Kind.DYN_NUMBER -> if (!(prim != null && !prim.isString && prim.content.toDoubleOrNull() != null) && !binding(v, at, errors)) bad("a number or a binding")
            Kind.DYN_BOOLEAN -> if (!(prim != null && !prim.isString && (prim.content == "true" || prim.content == "false")) && !binding(v, at, errors)) bad("true, false or a binding")
            Kind.DYN_STRINGS -> if (!(v is JsonArray && v.all { it is JsonPrimitive && it.isString }) && !binding(v, at, errors)) bad("a list of strings or a binding")
            Kind.DYN_ANY -> if (v is JsonObject) binding(v, at, errors)
            Kind.STRING -> if (prim == null || !prim.isString) bad("a string")
            Kind.NUMBER -> if (prim == null || prim.isString || prim.content.toDoubleOrNull() == null) bad("a number")
            Kind.BOOLEAN -> if (prim == null || prim.isString || prim.content !in setOf("true", "false")) bad("true or false")
            Kind.ENUM -> if (prim == null || !prim.isString || prim.content !in prop.values) bad("one of ${prop.values.joinToString(", ")}")
            Kind.ICON -> if (prim != null && prim.isString) {
                if (prim.content !in ICONS) bad("one of the built-in icons (${ICONS.joinToString(", ")})")
            } else if (!binding(v, at, errors)) bad("an icon's name")
            Kind.COMPONENT -> ref(v, at, ids, refs, errors) ?: bad("a component's id")
            Kind.CHILDREN -> when (v) {
                is JsonArray -> v.forEachIndexed { i, c -> ref(c, "$at[$i]", ids, refs, errors) ?: run { errors += "$at[$i] must be a component's id" } }
                is JsonObject -> {
                    ref(v["componentId"] ?: JsonPrimitive(""), "$at.componentId", ids, refs, errors)
                    if ((v["path"] as? JsonPrimitive)?.isString != true) errors += "$at, a template, needs \"path\": the data-model list it repeats for"
                }
                else -> bad("a list of component ids or a template {\"componentId\": …, \"path\": \"/items\"}")
            }
            Kind.ACTION -> action(v, at, errors)
            Kind.OPTIONS -> when (v) {
                is JsonArray -> v.forEachIndexed { i, o ->
                    val obj = o as? JsonObject
                    if (obj == null || obj["label"] == null || (obj["value"] as? JsonPrimitive)?.isString != true) errors += "$at[$i] must be {\"label\": …, \"value\": \"…\"}"
                }
                else -> if (!binding(v, at, errors)) bad("a list of {label, value} or a binding")
            }
            Kind.TABS -> if (v !is JsonArray) bad("a list of {\"title\", \"child\"}") else v.forEachIndexed { i, t ->
                val obj = t as? JsonObject
                if (obj == null || obj["title"] == null) errors += "$at[$i] must be {\"title\": …, \"child\": \"id\"}"
                else ref(obj["child"] ?: JsonPrimitive(""), "$at[$i].child", ids, refs, errors)
            }
        }
    }

    private fun isNull(v: JsonElement) = v is kotlinx.serialization.json.JsonNull

    /** Whether [v] is a binding; a v0.9 one ({"path"}) is an error with a hint. */
    private fun binding(v: JsonElement, at: String, errors: MutableList<String>): Boolean {
        val o = v as? JsonObject ?: return false
        if ("@path" in o) {
            if ((o["@path"] as? JsonPrimitive)?.isString != true) errors += "$at: \"@path\" must be a string (a JSON Pointer such as \"/name\")"
            return true
        }
        if ("@call" in o) {
            if ((o["@call"] as? JsonPrimitive)?.isString != true) errors += "$at: \"@call\" must be a function's name"
            return true
        }
        if (o.keys == setOf("path") || "call" in o) {
            errors += "$at: A2UI v1.0 binds with {\"@path\": …} and calls with {\"@call\": …} (with the @)"
            return true
        }
        return false
    }

    private fun ref(v: JsonElement, at: String, ids: Set<String>, refs: MutableList<String>, errors: MutableList<String>): String? {
        val id = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (id !in ids) errors += "$at refers to \"$id\", which no component has as its id"
        refs += id
        return id
    }

    private fun action(v: JsonElement, at: String, errors: MutableList<String>) {
        val o = v as? JsonObject
        val event = o?.get("event") as? JsonObject
        val call = o?.get("functionCall") as? JsonObject
        when {
            event != null -> {
                if ((event["name"] as? JsonPrimitive)?.isString != true || event.str("name").isNullOrBlank()) errors += "$at.event needs a \"name\""
                event["context"]?.let { if (it !is JsonObject) errors += "$at.event.context must be an object" }
                event["userMessage"]?.let { if (it !is JsonPrimitive && !binding(it, "$at.event.userMessage", errors)) errors += "$at.event.userMessage must be a string" }
            }
            call != null -> if (call.str("@call") != "openUrl") errors += "$at.functionCall: the phone runs only {\"@call\": \"openUrl\", \"args\": {\"url\": …}}"
            else -> errors += "$at must be {\"event\": {\"name\": …, \"context\": {…}}} or {\"functionCall\": {\"@call\": \"openUrl\", …}}"
        }
    }

    /** Why the card component [c] wouldn't make a card from its properties, or null. */
    private fun cardError(s: Surface, c: JsonObject, spec: Spec): String? {
        val fields = CardSurfaces.resolve(s, c, spec) ?: return null
        return runCatching { lenient.decodeFromJsonElement(spec.card!!, fields); null }
            .getOrElse { e -> e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "its fields don't make a ${spec.name} card" }
    }

    private fun cycle(edges: Map<String, List<String>>): List<String>? {
        val state = HashMap<String, Int>()
        val stack = ArrayList<String>()
        fun visit(n: String): List<String>? {
            when (state[n]) { 1 -> return stack.dropWhile { it != n } + n; 2 -> return null }
            state[n] = 1
            stack += n
            for (m in edges[n].orEmpty()) visit(m)?.let { return it }
            stack.removeAt(stack.size - 1)
            state[n] = 2
            return null
        }
        for (n in edges.keys) visit(n)?.let { return it }
        return null
    }

    internal val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
}

/**
 * The assistant's cards as A2UI surfaces and back: a card is a surface
 * whose root is the card's component, each field bound to the data model
 * ({"@path": "/field"}), which holds the card. Reading one back resolves the
 * root's properties against the data model, so updateDataModel on
 * "/temperature" changes the card as much as a new card would.
 */
object CardSurfaces {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** [card] as surface [surfaceId]. */
    fun surface(card: Card, surfaceId: String): Surface {
        val spec = PrismCatalog.specOf(card)
        val data = json.encodeToJsonElement(PrismCatalog.cardSerializer(card), card) as JsonObject
        val root = buildMap<String, JsonElement> {
            put("id", JsonPrimitive(Surface.ROOT))
            put("component", JsonPrimitive(spec.name))
            for (prop in spec.props) if (prop.kind == PrismCatalog.Kind.DYN_ANY && prop.name in data) {
                put(prop.name, JsonObject(mapOf("@path" to JsonPrimitive("/" + prop.name))))
            }
        }
        return Surface(surfaceId, PrismCatalog.ID, listOf(JsonObject(root)), JsonObject(data - "type"))
    }

    /** The card [s] shows at its root, or null (a surface the model composed). */
    fun card(s: Surface): Card? {
        val root = s.root ?: return null
        val spec = PrismCatalog.all[root.str("component")]?.takeIf { it.card != null } ?: return null
        val fields = resolve(s, root, spec) ?: return null
        return runCatching { PrismCatalog.lenient.decodeFromJsonElement(spec.card!!, fields) }.getOrNull()
    }

    /** The fields of card component [c]: literals as they are, bindings from the data model; null if one can't be resolved here (a call, a relative path). */
    internal fun resolve(s: Surface, c: JsonObject, spec: PrismCatalog.Spec): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>()
        for (prop in spec.props) {
            if (prop.name == "weight" || prop.name.startsWith("@")) continue
            val v = c[prop.name] ?: continue
            val o = v as? JsonObject
            when {
                o != null && "@path" in o -> {
                    val path = o.str("@path") ?: return null
                    if (!path.startsWith("/")) return null
                    JsonPointer.get(s.dataModel, path)?.let { out[prop.name] = it }
                }
                o != null && "@call" in o -> return null
                else -> out[prop.name] = v
            }
        }
        return JsonObject(out)
    }

    /** Surface [s] showing [card] instead, as a card's surface (its ID kept). */
    fun withCard(s: Surface, card: Card): Surface = surface(card, s.surfaceId)
}
