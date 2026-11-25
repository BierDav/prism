// The overlay's + menu (AssistAttach.kt) with its three tiles, the camera it
// opens in place (AssistCamera.kt) over the live app, and a setting card
// whose switch the owner flipped back by hand (core/Toggles.kt). The camera's
// preview is a picture here: no camera on the build machine.

package at.quickme.prism.previews

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import at.quickme.prism.AssistComposer
import at.quickme.prism.AttachActions
import at.quickme.prism.AttachMenu
import at.quickme.prism.CameraSheet
import at.quickme.prism.Tab
import at.quickme.prism.assistant.AssistantTranscript
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript

/** What the camera might see: a desk by a window, drawn. */
@Composable
private fun SampleViewfinder(modifier: Modifier) {
    Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFF9FB4C7), Color(0xFF5E6E7E), Color(0xFF3B2F2A))))) {
        val w = size.width
        val h = size.height
        // The window's light, the desk, a mug and a notebook.
        drawRect(Color(0xFFE8EEF3).copy(alpha = 0.55f), Offset(w * 0.08f, h * 0.06f), Size(w * 0.5f, h * 0.36f))
        drawRect(Color(0xFF6D4C3D), Offset(0f, h * 0.62f), Size(w, h * 0.38f))
        drawRoundRect(Color(0xFFF1EDE4), Offset(w * 0.14f, h * 0.68f), Size(w * 0.42f, h * 0.2f), CornerRadius(12f))
        drawRoundRect(Color(0xFFD94F3D), Offset(w * 0.64f, h * 0.55f), Size(w * 0.18f, h * 0.17f), CornerRadius(18f))
        drawCircle(Color(0xFF2B1E18), w * 0.07f, Offset(w * 0.73f, h * 0.565f))
    }
}

/** The + menu: Camera, Photos and Files. */
@PartPreviews
@Composable
private fun AttachMenuPreview() = Part {
    at.quickme.prism.GeminiColors {
        Box(Modifier.background(BottomSheetDefaults.ScrimColor).padding(12.dp)) {
            AttachMenu(remember { AttachActions({}, {}, {}) }, Modifier.fillMaxWidth())
        }
    }
}

/** The camera in the + menu, above the input card, over the live app. */
@ScreenPreviews
@Composable
private fun CameraSheetPreview() = Themed {
    Box(Modifier.fillMaxSize()) {
        SampleHome(Tab.Alerts)
        Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor))
        at.quickme.prism.GeminiColors {
            AssistComposer(
                text = "", onText = {}, canSend = true, sending = false, onSend = {}, animate = false, onMic = {},
                attach = remember {
                    AttachActions({}, {}, {}, cameraSheet = {
                        CameraSheet(
                            viewfinder = { m -> SampleViewfinder(m) },
                            busy = false, error = null, onShutter = {}, onFlip = {}, onClose = {},
                        )
                    })
                },
                menuOpen = true,
                selectors = {},
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp).padding(bottom = 8.dp).fillMaxWidth(),
            )
        }
    }
}

/**
 * Do Not Disturb, which the assistant switched on and the owner switched
 * back off on its card: the card shows the state now, its switch still
 * live; the flashlight's card as the assistant left it.
 */
@PartPreviews
@Composable
private fun ToggledCardPreview() = Part {
    val t = remember {
        val ran = listOf(
            Event.Said("Flashlight on and do not disturb"),
            Event.ToolStarted("f", Tools.FLASHLIGHT, Tools.label(Tools.FLASHLIGHT)),
            Event.ToolFinished("f", Tools.FLASHLIGHT, "Flashlight on.", SettingCard("flashlight", "Flashlight", "On", true)),
            Event.ToolStarted("d", Tools.DND, Tools.label(Tools.DND)),
            Event.ToolFinished("d", Tools.DND, "Do Not Disturb is on.", SettingCard("dnd", "Do Not Disturb", "On", true)),
            Event.Answer("The flashlight is on and Do Not Disturb too."),
        ).fold(Transcript()) { acc, e -> acc.after(e) }
        // The owner's tap on the Do Not Disturb card (AssistantSession.toggle).
        ran.withCard("tool:d", SettingCard("dnd", "Do Not Disturb", "Off", false))
    }
    at.quickme.prism.GeminiColors {
        Box(Modifier.background(BottomSheetDefaults.ScrimColor).padding(12.dp)) {
            AssistantTranscript(
                t, running = false, agent = null, onNew = {}, onEdit = { _, _ -> null },
                onToggle = { _, _ -> null }, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
