// show_ui: the model composes an interactive A2UI surface (a2ui.org, v1.0)
// from Prism's catalog when tapping beats typing — pick one of several
// contacts or rooms, confirm or tweak what it is about to do, a small form —
// and updates it later by its surfaceId. Its messages are checked against
// the catalog (core/UiActions.kt's ShowUi, core/PrismCatalog.kt), with every
// problem in the tool's error so the model can fix them; the owner's taps
// come back as the next turn (AssistantSession.act).

package at.quickme.prism.assistant

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.PrismCatalog
import at.quickme.prism.assistant.core.ShowUi
import at.quickme.prism.assistant.core.Surface
import at.quickme.prism.assistant.core.ToolArgumentException
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.UiError

/**
 * show_ui; [shown] is the model's own surface with that surfaceId, if any,
 * and [taken] says whether a tool's card has it (show_ui can't touch those).
 */
internal fun uiTools(shown: (String) -> Surface?, taken: (String) -> Boolean): List<AssistantTool> = listOf(
    object : AssistantTool(
        Tools.SHOW_UI,
        "Show the owner an interactive surface in the chat (A2UI v1.0, catalog ${PrismCatalog.ID}) when tapping beats typing: " +
            "pick one of several contacts, rooms or options; confirm or adjust what you are about to do; a short form. " +
            "Send A2UI messages: createSurface {surfaceId, components: a flat list with one component whose id is \"root\", dataModel, " +
            "sendDataModel: true for inputs}; later updateComponents, updateDataModel {surfaceId, path, value} or deleteSurface on the same surfaceId. " +
            "Components: {\"id\", \"component\", …properties}; children and child name ids. Bind with {\"@path\": \"/x\"}; inputs write the data model. " +
            "A Button's action {\"event\": {\"name\", \"context\": {…values or bindings}, \"userMessage\": \"Picked: Anna (mobile)\"}} " +
            "comes back as the owner's next message, with the data model when sendDataModel; nothing on a surface acts by itself " +
            "(for a link, send an event and then call open_url). Keep it small. Catalog: " + PrismCatalog.describe(),
        required = listOf(
            ToolParameterDescriptor(
                "messages",
                "A2UI v1.0 messages, in order, e.g. [{\"createSurface\": {\"surfaceId\": \"pick\", \"components\": […], \"dataModel\": {…}}}]",
                ToolParameterType.List(ToolParameterType.Object(emptyList(), emptyList(), true, null)),
            ),
        ),
    ) {
        override suspend fun run(args: Args): ToolOutput {
            val change = try {
                ShowUi.plan(args.json, shown, taken = taken)
            } catch (e: UiError) {
                throw ToolArgumentException(e.message ?: "The surface wasn't shown.")
            }
            return ToolOutput(change.summary(), ui = change)
        }
    },
)
