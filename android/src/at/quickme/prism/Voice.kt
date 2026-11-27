// Speech to text for the assistant, through Google's recognizer. Gemini
// itself streams to Google's private speech service (S3), which no other app
// can reach; the public way to Google's recognition is Android's
// SpeechRecognizer with Speech Services by Google (com.google.android.tts),
// or the Google app's own GoogleRecognitionService where it is still enabled
// (it is off from Android 13). Prism, as the digital assistant app, is the
// system's default recognizer itself (PrismRecognitionService, which takes no
// speech), so it names Google's service explicitly; failing that it uses
// Android's on-device recognizer, then any other.
//
// RECORD_AUDIO is a runtime permission; as the Device Owner Prism grants it
// to itself (Dpc.setUp).

package at.quickme.prism

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The recognizers to prefer, best first: Speech Services by Google, then the Google app's. */
private val PreferredRecognizers = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")

internal class VoiceInput(private val context: Context) {
    enum class State { Idle, Starting, Listening, Thinking }

    var state by mutableStateOf(State.Idle)
        private set

    /** What has been heard so far in this utterance. */
    var heard by mutableStateOf("")
        private set

    /** The voice's loudness, 0 (silence) to 1, as last sampled (at most every 50 ms, as Gemini samples it). */
    var level by mutableFloatStateOf(0f)
        private set

    /** Counts the samples of [level], so a repeated value still counts as new. */
    var samples by mutableIntStateOf(0)
        private set
    private var sampledAt = 0L

    var error by mutableStateOf<String?>(null)
        private set

    /** The error was shown and dismissed (Gemini's snackbar timed out or was closed). */
    fun clearError() {
        error = null
    }

    /**
     * Whether speech has begun in this utterance. Until it does, Gemini only
     * pulses its mic; once it does, the voice's bars take the text's place.
     */
    var speaking by mutableStateOf(false)
        private set

    val active: Boolean get() = state != State.Idle

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null

    // For logcat's PrismLatency: when listening was asked for, and when
    // speech ended (the recognizer heard silence, or the mic was tapped).
    private var startedAt = 0L
    private var endedAt = 0L

    fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening; [onText] gets the whole utterance once speech ends (not called for nothing heard). */
    fun start(onText: (String) -> Unit) {
        if (active) return
        error = null
        heard = ""
        level = 0f
        speaking = false
        val r = create() ?: run {
            error = "No speech recognizer on this phone."
            return
        }
        recognizer = r
        onFinal = onText
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // No language named: the recognizer detects it (Android 14+), also
            // when it changes mid-utterance, so German works as well as the
            // phone's own language without picking one.
            .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        state = State.Starting
        startedAt = SystemClock.elapsedRealtime()
        endedAt = 0L
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start listening", e)
            error = "Could not listen."
            release()
        }
    }

    /** Stops listening and takes what was heard. */
    fun stop() {
        if (state == State.Listening || state == State.Starting) {
            state = State.Thinking
            if (endedAt == 0L) endedAt = SystemClock.elapsedRealtime()
            recognizer?.stopListening()
        }
    }

    /** Stops listening and drops what was heard. */
    fun cancel() {
        recognizer?.cancel()
        heard = ""
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
        onFinal = null
        state = State.Idle
        level = 0f
        speaking = false
    }

    private fun create(): SpeechRecognizer? {
        val component = googleRecognizer()
        if (component != null) return SpeechRecognizer.createSpeechRecognizer(context, component)
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return null
    }

    /** Google's recognition service, or any recognizer but Prism's own. */
    private fun googleRecognizer(): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
        val best = services.minByOrNull { PreferredRecognizers.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return best?.let { ComponentName(it.packageName, it.name) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = State.Listening
            Log.i(LATENCY, "recognizer ready after ${SystemClock.elapsedRealtime() - startedAt}ms")
        }

        override fun onBeginningOfSpeech() {
            speaking = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = SystemClock.uptimeMillis()
            if (now - sampledAt < 50) return
            sampledAt = now
            // Recognizers report about -2 dB (silence) to 10 dB (loud speech).
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            samples++
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (endedAt == 0L) endedAt = SystemClock.elapsedRealtime()
            state = State.Thinking
            level = 0f
        }

        override fun onError(code: Int) {
            error = when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // heard nothing
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Prism may not use the microphone."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the network."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Try again."
                SpeechRecognizer.ERROR_CLIENT -> null // cancelled
                else -> "Speech recognition failed ($code)."
            }
            val partial = heard.trim()
            val deliver = onFinal
            release()
            // A cut-off utterance still counts.
            if (partial.isNotEmpty() && code != SpeechRecognizer.ERROR_CLIENT) deliver?.invoke(partial)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                ?: heard.trim()
            heard = text
            if (endedAt > 0) Log.i(LATENCY, "text ${SystemClock.elapsedRealtime() - endedAt}ms after the end of speech")
            val deliver = onFinal
            release()
            if (text.isNotEmpty()) deliver?.invoke(text)
        }

        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let {
                    heard = it
                    speaking = true
                }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "PrismVoice"
        const val LATENCY = at.quickme.prism.assistant.AssistantSession.LATENCY
    }
}
