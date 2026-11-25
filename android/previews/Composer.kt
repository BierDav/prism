// Gemini's input card in the overlay's three states, dark, as the owner's
// screenshots show them: idle (one row, in its margins), typing (the card
// across the screen with the text above the buttons) and listening (the
// voice's bars and the stop). The halo round the card is drawn as the
// overlay draws it (AssistActivity's Assist), over the live app.

package at.quickme.prism.previews

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import at.quickme.prism.AssistComposer
import at.quickme.prism.Tab
import at.quickme.prism.VoiceInput
import at.quickme.prism.haloScrim

/** The owner's phone, dark. */
@Preview(name = "dark", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class DarkScreenPreview

/** A voice input that is listening and has heard speech, without a microphone. */
@Composable
private fun listeningVoice(): VoiceInput {
    val context = LocalContext.current
    return remember {
        VoiceInput(context).apply {
            // Its state is set only by the recognizer; the preview sets the
            // Compose state behind it.
            fun set(field: String, value: Any) {
                val f = VoiceInput::class.java.getDeclaredField("$field\$delegate").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (f.get(this) as androidx.compose.runtime.MutableState<Any>).value = value
            }
            set("state", VoiceInput.State.Listening)
            set("speaking", true)
        }
    }
}

/** The overlay over the live app with the card at [margin] from the sides. */
@Composable
private fun ComposerSample(text: String, margin: Int, voice: VoiceInput? = null) = Themed {
    var card by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize()) {
        SampleHome(Tab.Alerts)
        // The overlay's two halos round the card, as Assist draws them.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                if (!card.isEmpty) repeat(2) {
                    drawIntoCanvas { c ->
                        haloScrim()?.draw(
                            c.nativeCanvas, size.width, size.height, card.left, card.top, card.right, card.bottom,
                            28.dp.toPx(), density, dark = true, alpha = 1f,
                        )
                    }
                }
                drawContent()
            },
        ) {
            at.quickme.prism.GeminiColors {
                AssistComposer(
                    text = text, onText = {}, canSend = true, sending = false, onSend = {}, animate = false,
                    onMic = {}, voice = voice, onCard = { card = it },
                    attach = remember { at.quickme.prism.AttachActions({}, {}, {}) },
                    selectors = {},
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = margin.dp).padding(bottom = 8.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** Idle: one row, "Ask Prism", in Gemini's 40 dp margins (the owner's phone is wider than 390 dp). */
@DarkScreenPreview
@Composable
private fun ComposerIdlePreview() = ComposerSample("", margin = 40)

/** Typing: the card across the screen (8 dp margins), the text above the buttons. */
@DarkScreenPreview
@Composable
private fun ComposerTypingPreview() = ComposerSample("Fix the stale Paperless backup on david-server", margin = 8)

/** Listening: the voice's bars and the stop, in the margins. */
@DarkScreenPreview
@Composable
private fun ComposerListeningPreview() = ComposerSample("", margin = 40, voice = listeningVoice())
