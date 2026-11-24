package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

// Invocation values sent by SystemUI's AssistManager.
private const val INVOCATION_TYPE_CORNER_SWIPE = 1

class PrismAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // No view structure of the app on the screen; its screenshot, though,
        // which Android sends only with Settings › Apps › Default apps ›
        // Digital assistant › Use screenshot on (it then also flashes the
        // screen's edges: Prism is no preinstalled assistant). The overlay's
        // "Screen" attaches it (assistant/PhoneScreenCapture.kt).
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST)
        enableInvocationEffect()
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onShutdown() {
        main.removeCallbacksAndMessages(null)
        super.onShutdown()
    }

    /**
     * The squeeze on a Pixel: holding the power button shrinks the whole
     * screen in (about 12 dp a side, the display's corners kept), and on
     * release it springs back as Prism opens. SystemUI plays it for the
     * assistant that asks, exactly as the Google app asks, and keeps the
     * answer per user and assistant. Other phones ignore the hint.
     *
     * SystemUI (InvocationEffectPreferencesImpl) works out whether the effect
     * is on only when the assistant changes or the stored "enabled" value
     * changes. Switched to Prism, it finds nothing stored for Prism (off);
     * Prism's "on" then is stored for Prism, but if the value stored before
     * (another assistant's) was already on, nothing changed and SystemUI keeps
     * the effect off. So Prism says off, then on, and only once SystemUI has
     * had a moment to see Prism as the assistant.
     */
    private fun enableInvocationEffect() {
        main.postDelayed({ hint(false) }, 1000)
        main.postDelayed({ hint(true) }, 1400)
    }

    private fun hint(enabled: Boolean) {
        try {
            setUiHints(Bundle().apply {
                putString("action", "set_invocation_effect_parameters")
                putBoolean("is_invocation_effect_enabled", enabled)
                // SystemUI reads both as longs.
                putLong("invocation_effect_animation_in_duration_padding_ms", 450L)
                putLong("invocation_effect_animation_out_duration_ms", 400L)
            })
        } catch (e: RuntimeException) {
            Log.w("PrismAssistant", "Could not ask for the invocation effect", e)
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(null, 0)
    }
}

class PrismAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PrismAssistantSession(this)
}

internal class PrismAssistantSession(private val context: Context) : VoiceInteractionSession(context) {
    companion object {
        var current: PrismAssistantSession? = null
            private set
    }

    private var overlay: AssistActivity? = null

    /** The screen as it was when this invocation began, without the overlay, if Android sent it. */
    @Volatile var screenshot: Bitmap? = null
        private set

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false) // The activity draws the overlay in the assistant layer.
        // A new invocation: the last one's screen is stale; this one's comes after onShow.
        screenshot = null
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        this.screenshot = screenshot
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        // A new invocation can arrive before the previous overlay reaches onDestroy.
        if (overlay?.isFinishing == true) overlay = null
        val invocation = args?.getInt("invocation_type", 0)
        val visibleOverlay = overlay?.takeIf { it.hasWindowFocus() }
        if (invocation == INVOCATION_TYPE_CORNER_SWIPE && visibleOverlay != null) {
            visibleOverlay.close()
            return
        }

        current = this
        val intent = Intent(context, AssistActivity::class.java)
        try {
            startAssistantActivity(intent)
        } catch (e: RuntimeException) {
            Log.w("PrismAssistant", "Could not open the assistant overlay", e)
            hide()
        }
    }

    fun attach(activity: AssistActivity) {
        overlay = activity
    }

    fun detach(activity: AssistActivity) {
        if (overlay !== activity) return
        overlay = null
        if (activity.isFinishing && current === this) hide()
    }

    override fun onHide() {
        dismiss()
        super.onHide()
    }

    override fun onDestroy() {
        dismiss()
        super.onDestroy()
    }

    private fun dismiss() {
        screenshot = null
        if (current === this) current = null
        val activity = overlay
        overlay = null
        activity?.close()
    }
}

/** Android requires a recognizer in the metadata. Prism's assistant only takes text. */
class PrismRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
