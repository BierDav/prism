// Prism as the phone's digital assistant app (Settings → Apps → Default apps
// → Digital assistant app): a long press on the power button or a swipe up
// from a bottom corner opens this through PrismAssistantSession.
//
// The app on the screen stays live under a see-through overlay. On a Pixel,
// a long press on the power button first squeezes the whole screen in and
// lets it spring back (SystemUI's invocation effect, which
// PrismAssistantService asks for). The overlay then plays Gemini's edge glow
// once (EdgeGlow.kt): waves of colour rise from the edges, sparks cross them,
// a pulsing rim runs round the display, and after four seconds it is gone.
//
// After a send the overlay stays and follows the agent (AssistConversation.kt);
// the composer then sends follow-ups into its thread, by text or by voice
// (Voice.kt, VoiceWave.kt).
//
// Dragged up, the card grows with the finger; past a threshold it grows on
// into the conversation full screen (assistant/AssistHistory.kt's HistoryChat,
// in this window, a shared-bounds transition), whose Back (predictive) or
// handle dragged down shrinks it into the card again and whose hamburger opens
// the chats in a drawer.
//
// Opened within a minute of the last time, the overlay goes on with that
// conversation; later it begins a new one and offers the last as a chip
// beside New chat.
//
// Predictive Back and a tap beside the card dismiss it (Gemini's entrance,
// played back: the card goes back into its circle, the chips sink); the
// navigation bar stays, and the live app remains underneath. The composer
// alone asks: an agent starts on the machine and project Prism picks from the
// request, with the assistant's placement profile (Phone › Profiles ›
// Actions). The composer sends its request directly after unlock. While the
// keyboard is up or an answer shows, the card spans the screen, as Gemini's.
//
// The card's + opens Gemini's attach menu (AssistAttach.kt): a photo from the
// camera, taken in the menu itself (AssistCamera.kt), photos from the Photo
// Picker, any file. What is attached shows at the top of the card and goes
// with the next send (assistant/AttachmentReader.kt). The assistant looks at
// the screen under the overlay itself when it needs to (its screenshot tool,
// assistant/PhoneScreenCapture.kt).

package at.quickme.prism

import android.Manifest
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.quickme.prism.shared.AgentActivity
import at.quickme.prism.shared.AgentOptions
import at.quickme.prism.shared.CatalogProject
import at.quickme.prism.shared.Machines
import at.quickme.prism.shared.ModelChoice
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismError
import at.quickme.prism.shared.ProjectCatalog
import at.quickme.prism.shared.ProjectChoice
import at.quickme.prism.shared.ProjectIcon
import at.quickme.prism.shared.ProfileChoice
import at.quickme.prism.assistant.AssistAttachment
import at.quickme.prism.assistant.AssistHistory
import at.quickme.prism.assistant.AssistantSession
import at.quickme.prism.assistant.AssistantTranscript
import at.quickme.prism.assistant.ConversationStore
import at.quickme.prism.assistant.HistoryChat
import at.quickme.prism.assistant.FollowedAgent
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.Attached
import at.quickme.prism.assistant.AttachmentReader
import at.quickme.prism.assistant.Grants
import at.quickme.prism.assistant.OverlayWindow
import at.quickme.prism.assistant.PhoneScreenCapture
import at.quickme.prism.assistant.Read
import at.quickme.prism.core.AttachmentRules
import at.quickme.prism.ui.GeminiIcons
import at.quickme.prism.ui.HostColor
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.ProjectGlyph
import at.quickme.prism.ui.rememberScreenCorners
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.json.JSONArray
import org.json.JSONObject

class AssistActivity : ComponentActivity(), OverlayWindow {
    private var session: PrismAssistantSession? = null

    /** A picker or the camera is open for the overlay, which waits for what it gives. */
    private var awaitingResult = false

    /** The screen under the overlay, for "Screen" and the harness's screenshots. */
    private val capture = PhoneScreenCapture(this) { session?.screenshot }

    override fun onCreate(savedInstanceState: Bundle?) {
        val active = PrismAssistantSession.current
        // SystemUI can retain the old explicit component across an update.
        // Keep accepting that launch until Android selects our service.
        val legacyLaunch = intent.action == Intent.ACTION_ASSIST
        if (active == null && !legacyLaunch) {
            super.onCreate(savedInstanceState)
            finishAndRemoveTask()
            return
        }
        session = active
        active?.attach(this)
        // The veil behind the bars is dark in either theme.
        enableEdgeToEdge(
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The overlay draws its own way in; the window itself appears as is.
        noWindowAnimation(open = true)
        setContent {
            PrismTheme {
                GeminiColors {
                Assist(
                    reducedMotion = !ValueAnimator.areAnimatorsEnabled(),
                    onClose = ::close,
                    unlock = ::unlock,
                    capture = capture,
                    onExternal = { awaitingResult = it },
                )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a picker: its result came just before.
        awaitingResult = false
    }

    /**
     * Left for anything but a picker the overlay opened (another app came to
     * the front): gone, as noHistory would have it, which the manifest can't
     * say any more because the overlay must outlive the camera and the
     * document picker it opens. Not while the screen sleeps.
     */
    override fun onStop() {
        super.onStop()
        val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        if (!awaitingResult && !isChangingConfigurations && interactive) close()
    }

    override fun onDestroy() {
        session?.detach(this)
        super.onDestroy()
    }

    /** The overlay's window fully transparent while [block] captures the screen under it. */
    override suspend fun <T> withoutOverlay(block: suspend () -> T): T = withContext(Dispatchers.Main) {
        val before = window.attributes.alpha
        window.attributes = window.attributes.apply { alpha = 0f }
        try {
            // SurfaceFlinger takes the new alpha with the next frames.
            repeat(2) { awaitFrame() }
            delay(32)
            block()
        } finally {
            window.attributes = window.attributes.apply { alpha = before }
        }
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { done ->
        android.view.Choreographer.getInstance().postFrameCallback { done.resume(Unit) }
    }

    /** Gone, after the overlay has taken itself back. */
    internal fun close() {
        if (isFinishing) return
        finishAndRemoveTask()
        noWindowAnimation(open = false)
    }

    /** Keep the draft on the lock screen if Android cancels the unlock. */
    private suspend fun unlock(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) return true
        return suspendCancellableCoroutine { result ->
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = result.resume(true)
                override fun onDismissCancelled() = result.resume(false)
                override fun onDismissError() = result.resume(false)
            })
        }
    }

    private fun noWindowAnimation(open: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

private val Night = Color(0xFF0B0730)

/**
 * The overlay in the wallpaper's colours, as Gemini's Floaty takes them
 * (Material's dynamic scheme), dark or light as Prism is; Gemini's card and
 * chips are black or white whatever the palette (GeminiPlate.kt).
 */
@Composable
internal fun GeminiColors(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val context = LocalContext.current
    val scheme = remember(dark, context) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** Whether the overlay is dark. */
@Composable
private fun overlayDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Gemini's send button: the primary container. */
@Composable
private fun sendFill(): Color = MaterialTheme.colorScheme.primaryContainer

/** M3's emphasized curves, for what appears (fast, then gently) and leaves. */
private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** The composer rises in this long (ms) after the overlay appears. */

/**
 * Gemini's side margin: 40 dp on a phone of 390 dp or wider, down to 16 dp
 * on narrower ones.
 */
private fun sideMargin(screenWidthDp: Int) = ((screenWidthDp - 310) / 2).coerceIn(16, 40).dp

/** Milliseconds since the overlay opened, for the chips' entrance; elsewhere long past it. */
internal val LocalOverlayAge = staticCompositionLocalOf<() -> Float> { { Float.MAX_VALUE } }

/** The overlay closing (ms): all of it as one, on from where Back took it. */
private const val EXIT_MS = 250

private val ChipEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Gemini's chips come in after its card (the Google app's suggestion item
 * animator): each slides 75 dp up and 20 dp in from the left, and fades in,
 * over 433 ms, from 200 ms after opening plus 83 ms per chip before it.
 * [order] counts from the first in (0): the bottom row first, right to left.
 */
@Composable
internal fun Modifier.chipEnter(order: Int): Modifier {
    val age = LocalOverlayAge.current
    return graphicsLayer {
        val p = ((age() - 200f - 83f * order) / 433f).coerceIn(0f, 1f)
        if (p < 1f) {
            val e = 1f - ChipEnterEasing.transform(p)
            alpha = p
            translationX = -20.dp.toPx() * e
            translationY = 75.dp.toPx() * e
        }
    }
}

/** The input card's corners: a pill at one row (80 dp, as Gemini's); taller, it keeps these. */
private val CardRadius = 40.dp

/** The halo's rounding round the card. */
private val PlateRadius = 28.dp

/** The agent's card above the composer, in the chips' look. */
private val ConversationRadius = 24.dp
private val ConversationShape = androidx.compose.foundation.shape.RoundedCornerShape(ConversationRadius)

private fun phase(t: Float, start: Float, length: Float) = ((t - start) / length).coerceIn(0f, 1f)

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun Assist(
    reducedMotion: Boolean,
    onClose: () -> Unit,
    unlock: suspend () -> Boolean,
    capture: PhoneScreenCapture,
    /** A picker or the camera is about to cover the overlay (true), or couldn't (false). */
    onExternal: (Boolean) -> Unit,
) {
    // Milliseconds since the overlay appeared, to the glow's end.
    val clock = remember { Animatable(if (reducedMotion) GLOW_MS else 0f) }
    val leave = remember { Animatable(0f) }
    // Back, as far as the finger has gone (0..1).
    val back = remember { Animatable(0f) }
    val context = LocalContext.current
    val memory = remember { AssistMemory(context) }
    // The draft lasts until a send.
    var text by remember { mutableStateOf(memory.draft()) }
    var sending by remember { mutableStateOf(false) }
    // The agent this overlay follows once a request is sent (the last one,
    // if it was a few minutes ago), its live activity, and follow-ups sent
    // that its thread doesn't show yet.
    var agentId by remember { mutableStateOf(memory.conversation()) }
    var agentTitle by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf<AgentActivity?>(null) }
    var activityError by remember { mutableStateOf<String?>(null) }
    // A start the leading worker hasn't run yet: asked again with the same
    // request ID until its agent exists (the replica answers 202 with an
    // agent without an ID until then).
    var starting by remember { mutableStateOf<AssistantRequest?>(null) }
    val pending = remember { mutableStateListOf<String>() }
    var refresh by remember { mutableIntStateOf(0) }
    val voice = remember { VoiceInput(context) }
    var editVoice by remember { mutableStateOf(false) }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    var sendError by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<AssistantRequest?>(null) }
    // The on-device assistant (assistant/): it answers and acts on the phone,
    // and hands computer work to a T3 agent, which the overlay then follows.
    // Without its key, requests start a T3 agent directly, as before.
    // Shared with the history screen when it holds the same conversation.
    // Another chat (the last one's chip, the drawer) swaps it.
    var session by remember { mutableStateOf(AssistantSession.forOverlay(context, capture)) }
    DisposableEffect(session) {
        val shown = session
        onDispose { shown.detach() }
    }
    // Closed, the resume window counts from now.
    DisposableEffect(Unit) {
        onDispose {
            session.touch()
            agentId?.let { memory.conversation(it) }
        }
    }
    // The transcript entry of the hand-off whose agent the overlay follows.
    var handoffEntry by remember { mutableStateOf(session.transcript.latestHandoff()?.first) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // What the owner attached with the +; it goes with the next send. The
    // overlay keeps it (and the draft) while a picker or the camera is open.
    val attached = remember { mutableStateListOf<Attached>() }
    var menuOpen by remember { mutableStateOf(false) }
    var reading by remember { mutableIntStateOf(0) }
    // The card dragged up (or the panel's open button): the conversation full
    // screen, in this window, the card growing into it (a shared transition).
    var full by remember { mutableStateOf(false) }
    // Whether the card that appears next takes the focus (and the keyboard).
    var focusCard by remember { mutableStateOf(false) }
    // What the mic hears goes into the field (it was started with the keyboard up).
    var voiceInline by remember { mutableStateOf(false) }
    // The card came back from the full screen (it doesn't appear again).
    var returned by remember { mutableStateOf(false) }
    // A gesture is taking the full-screen chat back to the card: what is
    // above the card shows behind it; and did when it went back (it then
    // stays, without coming in again).
    var peeking by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val imeShown = androidx.compose.runtime.rememberUpdatedState(WindowInsets.isImeVisible)

    fun attach(items: List<Attached>) {
        val room = AttachmentRules.MAX_ATTACHMENTS - attached.size
        if (items.size > room) sendError = AttachmentRules.tooMany()
        attached.addAll(items.take(room.coerceAtLeast(0)))
    }

    /** Reads what a picker gave, in order, and says what couldn't be attached. */
    fun attachUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        reading++
        scope.launch {
            try {
                val read = uris.map { AttachmentReader.read(context, it) }
                attach(read.filterIsInstance<Read.Ok>().map { it.attached })
                read.filterIsInstance<Read.Failed>().firstOrNull()?.let { sendError = it.message }
            } finally {
                reading--
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentRules.MAX_ATTACHMENTS),
    ) { attachUris(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attachUris(it) }
    // The camera in the + menu (AssistCamera.kt), open while the menu is.
    var cameraOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { if (!menuOpen) cameraOpen = false }

    /**
     * Into or out of the full-screen chat. Each layout has its own card, so
     * the camera and the + menu close first: only one camera is ever bound.
     */
    fun showFull(on: Boolean) {
        cameraOpen = false
        menuOpen = false
        // Full screen, nothing listens: the chat has no bars to show it.
        if (on) voice.cancel()
        // The other layout's field takes the focus as it appears: the keyboard
        // stays up when it was, and comes up for a new chat dragged full screen.
        val fresh = session.transcript.isEmpty && agentId == null && starting == null
        focusCard = imeShown.value || (on && fresh)
        if (!on) returned = true
        revealed = !on && peeking
        full = on
    }

    /** A button on a card or a surface: like sending, only once the phone is unlocked. */
    val act: suspend (String, at.quickme.prism.assistant.core.UiAction) -> String? = { id, action ->
        if (!unlock()) "Unlock the phone first." else session.act(id, action)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraOpen = true else sendError = "Prism may not use the camera."
    }

    /** Opens a picker over the overlay, once the phone is unlocked (none shows over the lock screen). */
    fun pick(open: () -> Unit) {
        menuOpen = false
        if (attached.size >= AttachmentRules.MAX_ATTACHMENTS) {
            sendError = AttachmentRules.tooMany()
            return
        }
        keyboard?.hide()
        voice.cancel()
        scope.launch {
            if (!unlock()) {
                sendError = "Unlock the phone to add photos and files."
                return@launch
            }
            sendError = null
            onExternal(true)
            try {
                open()
            } catch (e: android.content.ActivityNotFoundException) {
                onExternal(false)
                sendError = "No app on this phone can do that."
            }
        }
    }

    val attachActions = AttachActions(
        camera = {
            if (attached.size >= AttachmentRules.MAX_ATTACHMENTS) {
                menuOpen = false
                sendError = AttachmentRules.tooMany()
            } else {
                keyboard?.hide()
                voice.cancel()
                sendError = null
                // As the phone's tools: the Device Owner grants itself CAMERA
                // (Grants); without that, Android asks the owner.
                val granted = runCatching { Grants.require(context, Manifest.permission.CAMERA) }.isSuccess
                if (granted) cameraOpen = true else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
        photos = {
            pick { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        },
        files = { pick { filePicker.launch(arrayOf("*/*")) } },
        cameraSheet = if (!cameraOpen) null else ({
            InlineCamera(
                onPhoto = { uri ->
                    cameraOpen = false
                    menuOpen = false
                    attachUris(listOf(uri))
                },
                onClose = { cameraOpen = false },
            )
        }),
    )
    fun progress(): Float = if (reducedMotion) 0f else back.value

    LaunchedEffect(Unit) {
        if (!reducedMotion) clock.animateTo(GLOW_MS, tween(GLOW_MS.toInt(), easing = LinearEasing))
    }

    // While shown, the agent's thread as it goes: often while it works.
    LaunchedEffect(agentId, refresh) {
        val id = agentId ?: return@LaunchedEffect
        while (true) {
            try {
                val fresh = PrismApi.call(context) { agentActivity(id) }
                activity = fresh
                activityError = null
                val said = fresh.items.filter { it.type == "user_message" }.map { it.text.trim() }.toSet()
                pending.removeAll { it.trim() in said }
            } catch (e: PrismError) {
                activityError = e.message
            }
            delay(if (activity?.agent?.active != false) 1500 else 5000)
        }
    }

    /** Follows the agent [agent] once it has a thread. */
    fun follow(agent: at.quickme.prism.shared.Agent) {
        starting = null
        activity = AgentActivity(agent)
        agentId = agent.id
        memory.started()
        memory.conversation(agent.id)
        handoffEntry?.let { session.started(it, agent.id, agent.title) }
    }

    /** The assistant handed a request to T3: follow its agent, or its start until it runs. */
    fun handedOff(entryId: String, card: HandoffCard) {
        handoffEntry = entryId
        pending.clear()
        agentTitle = card.title.ifEmpty { card.request }
        if (card.agentId.isNotEmpty()) {
            if (card.followUp) pending.add(card.request)
            follow(at.quickme.prism.shared.Agent(id = card.agentId, title = card.title))
            refresh++
        } else {
            agentId = null
            activity = null
            starting = AssistantRequest(card.requestId, AUTO, AgentProfile.model(context, card.profile.ifEmpty { null }), card.project, card.request)
        }
    }
    session.onHandoff = ::handedOff
    session.followed = { agentId?.let { it to (activity?.agent?.displayTitle ?: agentTitle) } }

    // Reopened while a start was still waiting for the leader: keep asking
    // for its agent (it is newer than any agent followed before it).
    LaunchedEffect(Unit) {
        if (starting != null) return@LaunchedEffect
        val (entry, card) = session.transcript.latestHandoff() ?: (null to null)
        if (card != null && card.agentId.isEmpty() && card.requestId.isNotEmpty() && card.failed.isEmpty()) {
            handoffEntry = entry
            agentId = null
            starting = AssistantRequest(card.requestId, AUTO, AgentProfile.model(context, card.profile.ifEmpty { null }), card.project, card.request)
            return@LaunchedEffect
        }
        val (id, request, project) = memory.starting() ?: return@LaunchedEffect
        if (!session.configured) {
            agentId = null
            agentTitle = request
            pending.add(request)
            starting = AssistantRequest(id, AUTO, AgentProfile.model(context), project, request)
        }
    }

    // A start still waiting for the leader: ask again until it ran.
    LaunchedEffect(starting) {
        val request = starting ?: return@LaunchedEffect
        var wait = 2000L
        while (true) {
            delay(wait)
            try {
                val agent = PrismApi.call(context) {
                    startAgent("do", request.target, request.text, request.id, request.model, request.project)
                }
                if (agent.id.isNotEmpty()) {
                    follow(agent)
                    return@LaunchedEffect
                }
                activity = AgentActivity(agent)
                activityError = null
                wait = 2000L
            } catch (e: PrismError) {
                // The request's own refusal ends it; anything else (no
                // replica, the workers busy) is asked again, more slowly.
                if (e.code == "failed" || e.status in 400..499 && e.status != 408 && e.status != 429) {
                    starting = null
                    memory.started()
                    handoffEntry?.let { session.failedStart(it, e.message.orEmpty()) }
                    // A new send gets a new request ID; the prompt comes back to edit.
                    previous = null
                    if (handoffEntry == null && text.isEmpty()) text = request.text
                    activity = null
                    sendError = e.message
                    return@LaunchedEffect
                }
                activityError = e.message
                wait = minOf(wait * 2, 15_000L)
            }
        }
    }

    /** Leaves the agent followed for a new request (the transcript's new-conversation control calls it). */
    fun newRequest() {
        if (sending) return
        previous = null
        session.clear()
        handoffEntry = null
        agentId = null
        starting = null
        activity = null
        activityError = null
        pending.clear()
        sendError = null
        memory.forgetConversation()
    }

    /** Shows [s]'s conversation (another chat): the agent followed is the one its last hand-off started, if any. */
    fun showSession(s: AssistantSession) {
        s.attach(context, capture)
        previous = null
        handoffEntry = s.transcript.latestHandoff()?.first
        agentId = null
        starting = null
        activity = null
        activityError = null
        pending.clear()
        sendError = null
        memory.forgetConversation()
        session = s
        s.transcript.latestHandoff()?.second?.takeIf { it.agentId.isNotEmpty() }?.let { card ->
            agentTitle = card.title.ifEmpty { card.request }
            agentId = card.agentId
            activity = AgentActivity(at.quickme.prism.shared.Agent(id = card.agentId, title = card.title))
        }
    }

    var opening by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** Opens stored conversation [id] here (the last chat's chip, the drawer), once unlocked. */
    fun openChat(id: String) {
        if (id == session.conversationId) return
        opening?.cancel()
        opening = scope.launch {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                if (!unlock()) {
                    sendError = "Unlock the phone to open a chat."
                    return@launch
                }
                val unlocked = android.os.SystemClock.elapsedRealtime()
                val s = AssistantSession.live(id)
                    ?: ConversationStore.load(context, id).let { loaded ->
                        // Opened meanwhile elsewhere? Then that session.
                        AssistantSession.live(id) ?: AssistantSession(context, capture, resume = false).also { it.open(loaded) }
                    }
                showSession(s)
                val done = android.os.SystemClock.elapsedRealtime()
                android.util.Log.i("PrismAssist", "chat opened in ${done - started} ms (unlock ${unlocked - started} ms)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = e.message ?: "The chat did not open."
            } finally {
                opening = null
            }
        }
    }

    // The chat before this one, for its chip: not while it is the one shown.
    val last = remember(session, session.transcript.isEmpty, session.conversationId) {
        at.quickme.prism.assistant.AssistantMemory(context).last()
    }?.takeIf { it.first != session.conversationId && session.configured }
    // Every chat kept on the phone (each opens from it), in the background.
    LaunchedEffect(Unit) {
        if (!session.configured) return@LaunchedEffect
        try {
            val stored = PrismApi.call(context) { assistantConversations() }
            ConversationStore.keepAll(context, stored.map { it.id })
        } catch (e: PrismError) {
            android.util.Log.i("PrismAssist", "chats not kept now: ${e.message}")
        }
    }
    // Its chip opens it at once: loaded ahead (the replica can take seconds).
    LaunchedEffect(last?.first) { last?.first?.let { ConversationStore.prefetch(context, it) } }

    LaunchedEffect(text) { memory.saveDraft(text) }

    fun resetDismissal() {
        scope.launch {
            back.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
    }

    fun close() {
        if (sending) {
            resetDismissal()
            return
        }
        if (leave.isRunning || leave.value > 0f) return
        keyboard?.hide()
        voice.cancel()
        scope.launch {
            // All of it as one: on from where Back left it, smaller towards
            // the bottom and gone (the halo and the glow with it).
            if (!reducedMotion) leave.animateTo(1f, tween(EXIT_MS, easing = Accelerate))
            onClose()
        }
    }

    /** A follow-up into the agent's thread, with [files] attached. */
    fun reply(id: String, request: String, files: List<AssistAttachment>) {
        val payload = previous?.takeIf { it.text == request && it.target == id && it.attachments == files }
            ?: AssistantRequest(PrismClient.requestId(), id, null, "", request, files)
        previous = payload
        sending = true
        sendError = null
        keyboard?.hide()
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        scope.launch {
            try {
                if (!unlock()) {
                    sendError = "Unlock the phone to send."
                    return@launch
                }
                if (request.isNotEmpty()) pending.add(request)
                text = ""
                // The attachments go with payload; the harness takes them.
                val agent = PrismApi.call(context) { sendToAgent(id, payload.text, payload.id) }
                activity = activity?.copy(agent = agent) ?: AgentActivity(agent)
                memory.conversation(id)
                attached.removeAll { it.attachment in payload.attachments }
                refresh++
            } catch (e: PrismError) {
                if (request.isNotEmpty()) pending.remove(request)
                text = request
                sendError = e.message
            } finally {
                sending = false
            }
        }
    }

    /**
     * Sends [spoken] or the typed text, with what is attached: a follow-up to
     * the agent shown, or a new agent on the machine and project Prism picks,
     * with the assistant's profile (Phone › Profiles › Actions). Attachments
     * alone are a request too.
     */
    fun send(spoken: String? = null) {
        val request = (spoken ?: text).trim()
        val files = attached.map { it.attachment }
        if (request.isEmpty() && files.isEmpty()) return
        if (session.configured) {
            if (session.running || reading > 0 || leave.isRunning || leave.value > 0f) return
            menuOpen = false
            sendError = null
            keyboard?.hide()
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            scope.launch {
                if (!unlock()) {
                    sendError = "Unlock the phone to send."
                    return@launch
                }
                memory.clearDraft()
                text = ""
                attached.removeAll { it.attachment in files }
                session.send(request, files)
            }
            return
        }
        if (sending || reading > 0 || starting != null || leave.isRunning || leave.value > 0f) return
        menuOpen = false
        agentId?.let { return reply(it, request, files) }
        val model = AgentProfile.model(context)
        val payload = previous?.takeIf { it.target == AUTO && it.text == request && it.model == model && it.attachments == files }
            ?: AssistantRequest(PrismClient.requestId(), AUTO, model, AUTO, request, files)
        previous = payload
        sending = true
        sendError = null
        keyboard?.hide()
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        scope.launch {
            try {
                if (!unlock()) {
                    sendError = "Unlock the phone to send."
                    return@launch
                }
                val agent = PrismApi.call(context) {
                    startAgent("do", payload.target, payload.text, payload.id, payload.model, payload.project)
                }
                PrismPush.changed()
                memory.clearDraft()
                text = ""
                attached.removeAll { it.attachment in payload.attachments }
                // The overlay stays and follows the agent.
                pending.clear()
                if (request.isNotEmpty()) pending.add(request)
                agentTitle = request.ifEmpty { payload.attachments.firstOrNull()?.name.orEmpty() }
                if (agent.id.isNotEmpty()) follow(agent) else {
                    activity = AgentActivity(agent)
                    memory.starting(payload.id, payload.text, payload.project)
                    starting = payload
                }
            } catch (e: PrismError) {
                sendError = e.message
            } finally {
                sending = false
            }
        }
    }

    /** Gemini sends what it heard, unless the bars were tapped to edit it. */
    fun heard(spoken: String) {
        // Spoken with the keyboard up: added to what is typed, not sent.
        if (voiceInline) {
            voiceInline = false
            text = listOf(text.trimEnd(), spoken.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            return
        }
        text = spoken
        if (editVoice) editVoice = false else send(spoken)
    }

    fun listenToEdit() {
        editVoice = true
        voice.stop()
    }

    /** Listens, and meanwhile opens the connection to the gateway, so what is heard goes out at once. */
    fun startListening() {
        session.warm()
        voice.start(::heard)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else sendError = "Prism may not use the microphone."
    }

    fun listen() {
        when {
            // The pulsing mic, before anyone spoke: stop listening.
            voice.active && !voice.speaking -> voice.cancel()
            voice.active -> voice.stop()
            voice.permitted() -> {
                // With the keyboard up or something typed, the field stays and
                // what is heard is added to it.
                voiceInline = imeShown.value || text.isNotBlank()
                if (!voiceInline) keyboard?.hide()
                sendError = null
                startListening()
            }
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Gemini listens as soon as it opens: its mic pulses until speech begins.
    // Not over a kept draft (what it heard would replace and send it), and
    // only with the microphone already allowed (no prompt on every open).
    LaunchedEffect(Unit) {
        if (text.isEmpty() && voice.permitted()) startListening()
    }

    fun openThread() {
        val agent = activity?.agent ?: return
        val intent = T3Activity.intent(context, agent) ?: return
        scope.launch {
            if (!unlock()) return@launch
            context.startActivity(intent)
            onClose()
        }
    }

    fun stopAgent() {
        val id = agentId ?: return
        scope.launch {
            try {
                PrismApi.call(context) { stopAgent(id, PrismClient.requestId()) }
                refresh++
            } catch (e: PrismError) {
                activityError = e.message
            }
        }
    }
    // Predictive back: the overlay follows the gesture and goes on release.
    PredictiveBackHandler { progress ->
        try {
            progress.collect { back.snapTo(Decelerate.transform(it.progress)) }
            close()
        } catch (e: CancellationException) {
            resetDismissal()
            throw e
        }
    }
    // Full screen, Back takes the chat back into the card (HistoryChat, predictively).
    // With the + menu open, Back closes the menu first.
    BackHandler(enabled = menuOpen) { menuOpen = false }

    val corners = rememberScreenCorners()
    // The glow in Gemini's energy colour: on a near-grey palette the primary
    // alone turned its rim and waves into a grey vignette round the screen.
    val primary = energyColor(MaterialTheme.colorScheme.primary)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // What the halo goes round: one component, the composer as a whole (the
    // card, and the panel and chips above it, as they are laid out frame by
    // frame, also as they morph), or the full-screen chat while that shows.
    var plate by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var haloTick by remember { mutableLongStateOf(0L) }
    val haloRoundFrom = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val haloRoundLast = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    // When it began, and for which state of [full] the halo last drew (it
    // starts the blend in the very frame [full] changes).
    val haloRoundStart = remember { LongArray(1) }
    val haloDrawnFull = remember { arrayOfNulls<Boolean>(1) }
    LaunchedEffect(full) {
        val start = android.os.SystemClock.uptimeMillis()
        // Into the full screen, until the chat has grown past where the halo was.
        val ms = if (full) FULL_MS else HALO_EASE_MS
        while (android.os.SystemClock.uptimeMillis() - start < ms + 32) withFrameMillis { haloTick = it }
        haloRoundFrom[0] = null
        haloTick = 0L
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val glow = if (reducedMotion) null else edgeGlow()
                // Gemini stacks two halos round its card: the page's and the background's.
                val halos = listOfNotNull(haloScrim(), haloScrim())
                // Gemini rounds the rim 24 dp; here it follows the display's own corners.
                val radius = maxOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
                onDrawWithContent {
                    val t = clock.value
                    val gone = (1f - leave.value) * (1f - 0.7f * progress())
                    // Gemini fades its scrim in linearly over 450 ms.
                    val shown = phase(t, 0f, 450f) * gone
                    // Gemini's layers, back to front: the background page's halo,
                    // the glow, the input page's halo, then the card and chips.
                    // Round the composer: the card, and the panel and chips above it.
                    haloTick
                    val live = plate
                    // Into or out of the full screen, what the halo goes round
                    // changes hands (the chat, the card): blended, not swapped.
                    if (haloDrawnFull[0] != full) {
                        if (haloDrawnFull[0] != null) {
                            haloRoundFrom[0] = haloRoundLast[0]
                            haloRoundStart[0] = android.os.SystemClock.uptimeMillis()
                        }
                        haloDrawnFull[0] = full
                    }
                    val roundFrom = haloRoundFrom[0]
                    // Into the full screen the chat grows from the card over all
                    // the halo was round (the panel too, dragged up with the
                    // card, drops back behind it): the halo stays round that
                    // until the chat is past it, never shrinking on the way.
                    val round = when {
                        roundFrom == null || live.isEmpty -> live
                        full -> androidx.compose.ui.geometry.Rect(
                            minOf(roundFrom.left, live.left), minOf(roundFrom.top, live.top),
                            maxOf(roundFrom.right, live.right), maxOf(roundFrom.bottom, live.bottom),
                        )
                        else -> androidx.compose.ui.geometry.lerp(
                            roundFrom, live,
                            EmphasizedEasing.transform(((android.os.SystemClock.uptimeMillis() - haloRoundStart[0]) / HALO_EASE_MS.toFloat()).coerceIn(0f, 1f)),
                        )
                    }
                    if (!round.isEmpty) haloRoundLast[0] = round
                    fun halo(h: HaloScrim) = drawIntoCanvas { c ->
                        h.draw(
                            c.nativeCanvas, size.width, size.height, round.left, round.top, round.right, round.bottom,
                            PlateRadius.toPx(), density, dark, shown,
                        )
                    }
                    if (halos.isEmpty()) drawVeil(t, gone)
                    else if (!plate.isEmpty) halo(halos[0])
                    val fade = glowFade(t) * gone
                    if (glow != null && fade > 0f) drawIntoCanvas {
                        glow.draw(it.nativeCanvas, size.width, size.height, t / GLOW_MS, fade, radius, primary)
                    }
                    if (halos.size > 1 && !plate.isEmpty) halo(halos[1])
                    drawContent()
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close Prism",
                    // A tap beside the + menu closes the menu, not the overlay.
                    onClick = { if (menuOpen) menuOpen = false else close() },
                ),
        )
        val following = agentId != null || starting != null
        val chatting = session.configured && !session.transcript.isEmpty
        // Gemini's card spans the screen while an answer shows (its response
        // page, 16 dp from the sides) or the keyboard is up, and sits back in
        // its margins otherwise.
        val answering = following || chatting
        val side = sideMargin(LocalConfiguration.current.screenWidthDp)
        val marginSpec: FiniteAnimationSpec<androidx.compose.ui.unit.Dp> =
            if (reducedMotion) snap() else tween(350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
        // How far the keyboard is up (0..1), frame by frame with its own
        // animation: the card widens as it rises and narrows as it goes.
        val density = LocalDensity.current
        val imeNow = WindowInsets.ime.getBottom(density)
        val imeFull = maxOf(WindowInsets.imeAnimationSource.getBottom(density), WindowInsets.imeAnimationTarget.getBottom(density))
        val keyboardUp = if (reducedMotion || imeFull <= 0) (if (imeNow > 0) 1f else 0f) else (imeNow.toFloat() / imeFull).coerceIn(0f, 1f)
        val restMargin by androidx.compose.animation.core.animateDpAsState(
            // Not for the attach menu: it takes the card's width.
            if (answering) AnswerMargin else side,
            marginSpec,
            label = "margin",
        )
        val margin = androidx.compose.ui.unit.lerp(restMargin, if (answering) AnswerMargin else WideMargin, keyboardUp)
        // Where the margin is going: what is above the card is laid out there
        // at once (the shared panel and chips morph to where they end up;
        // only the card follows the margin).
        val targetMargin = androidx.compose.ui.unit.lerp(if (answering) AnswerMargin else side, if (answering) AnswerMargin else WideMargin, keyboardUp)
        // With an answer the panel spans the screen, and the card too while
        // it has something in it (or the keyboard is up); empty, it sits back
        // in Gemini's margins.
        val empty = text.isEmpty() && attached.isEmpty()
        val restInset by androidx.compose.animation.core.animateDpAsState(
            if (answering && empty) (side - AnswerMargin).coerceAtLeast(0.dp) else 0.dp,
            marginSpec,
            label = "cardInset",
        )
        val cardInset = restInset * (1f - keyboardUp)
        // What went wrong, as Gemini's snackbar: above the answer panel while
        // one shows, else above the card.
        val error = sendError ?: voice.error
        fun dismissError() {
            sendError = null
            voice.clearError()
        }
        /** The card, in the overlay (Gemini's plate) and at the bottom of the full-screen chat (Gemini's app's field, [flat]). */
        @Composable
        fun InputCard(
            cardModifier: Modifier, expand: (() -> Unit)?, selectors: @Composable () -> Unit, modifier: Modifier, flat: Boolean, showError: Boolean,
            plateModifier: Modifier = Modifier, contentModifier: Modifier = Modifier, parts: ComposerParts = ComposerParts.None,
            /** Only there for what is above it (behind the full-screen chat): unseen, it neither takes nor gives anything. */
            ghost: Boolean = false,
        ) = AssistComposer(
            text = text,
            onText = { text = it; sendError = null },
            canSend = (if (session.configured) !session.running else starting == null) && reading == 0,
            sending = sending || session.running,
            onSend = { send() },
            animate = !reducedMotion,
            flat = flat,
            error = if (showError) error else null,
            onDismissError = ::dismissError,
            placeholder = if (following && !session.configured) "Reply to the agent" else "Ask Prism",
            voice = voice,
            onMic = ::listen,
            onWave = ::listenToEdit,
            attachments = attached,
            onRemove = { attached.remove(it) },
            // Only the assistant reads attachments; a T3 agent gets text.
            attach = if (session.configured && !ghost) attachActions else null,
            menuOpen = menuOpen,
            onMenu = { menuOpen = it },
            // The halo goes round the composer as a whole while it is (or
            // becomes) what shows, and the full-screen chat while that is: one
            // writer at a time.
            onBounds = { if (!flat && !ghost && !full) plate = it },
            plateModifier = plateModifier,
            contentModifier = contentModifier,
            parts = parts,
            voiceInline = voiceInline && voice.active,
            onExpand = expand,
            expanded = full,
            cardModifier = cardModifier,
            cardInset = if (flat) 0.dp else cardInset,
            focusOnStart = focusCard && !ghost,
            // Back from the full screen, the card is there already: no entrance.
            settled = flat || returned,
            selectors = selectors,
            modifier = modifier,
        )
        val followed = (agentId ?: starting?.let { "" })?.let { id ->
            FollowedAgent(
                agentId = id,
                title = agentTitle.ifEmpty { activity?.agent?.title.orEmpty() },
                activity = activity,
                pending = pending,
                error = activityError,
                onOpen = if (activity?.agent?.link != null || activity?.agent?.t3 != null) ::openThread else null,
                onStop = ::stopAgent,
            )
        }
        // The chat in the panel, as it is now; a panel that leaves keeps what
        // it showed (New chat empties the session before it has gone).
        val shownChat = if (chatting) ShownChat(session.conversationId, session.transcript, session.running, session) else null
        val above = Above(shownChat, last)
        val selectors: @Composable () -> Unit = {
            // A chat's panel and its chip (the last chat's) are one element:
            // New chat shrinks the panel into the chip that now offers it, the
            // chip grows into its panel, and from one chat to another both at
            // once. Each side keeps what it showed (its own last chat) while
            // it goes. The room above the card grows or shrinks with the
            // morph, both sides kept at its bottom: what goes stays where it
            // was, and what is in the shared containers is where it ends up
            // (pinned) as only their edges move.
            SharedTransitionLayout(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = above,
                    contentKey = { it.chat?.id },
                    contentAlignment = Alignment.BottomCenter,
                    transitionSpec = {
                        (EnterTransition.None togetherWith ExitTransition.None)
                            .using(
                                androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                    if (reducedMotion) snap() else tween(MORPH_MS, easing = EmphasizedEasing)
                                },
                            )
                    },
                    label = "chat",
                ) { state ->
                    val shown = state.chat
                    val morphBounds: FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> =
                        if (reducedMotion) snap() else tween(MORPH_MS, easing = EmphasizedEasing)
                    // What is shown now (or arrives), not what goes.
                    val now = shown?.id == above.chat?.id
                    val gone = if (reducedMotion) ExitTransition.None else fadeOut(tween(MORPH_MS / 3, 0, LinearEasing))
                    val stay = if (reducedMotion) ExitTransition.None else fadeOut(tween(MORPH_MS), targetAlpha = 1f)
                    // One container (both ends are Gemini's black or white
                    // chip): the end that arrives lies beneath, its background
                    // there at once; the end that leaves lies on it, its
                    // background gone at once, so what it showed stays visible
                    // where it was and fades over the first third while what
                    // arrives fades in beneath it. Both are cut to the same
                    // moving edges. One that becomes nothing fades as a whole.
                    @Composable
                    fun morph(id: String): at.quickme.prism.assistant.Morph {
                        val alone = !now && above.chat?.id != id && above.last?.first != id
                        val leave = if (alone) gone else stay
                        val instant = if (reducedMotion) ExitTransition.None else fadeOut(tween(16, 0, LinearEasing))
                        return at.quickme.prism.assistant.Morph(
                            container = Modifier.sharedBounds(
                                rememberSharedContentState("chat:$id"),
                                this@AnimatedContent,
                                enter = EnterTransition.None,
                                exit = leave,
                                boundsTransform = { _, _ -> morphBounds },
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                zIndexInOverlay = if (now) 0f else 1f,
                                clipInOverlayDuringTransition = OverlayClip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
                            ),
                            background = Modifier.animateEnterExit(
                                enter = EnterTransition.None,
                                exit = if (alone) gone else instant,
                            ),
                            content = Modifier.pinned(this@SharedTransitionLayout).animateEnterExit(
                                enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(MORPH_MS * 3 / 5, MORPH_MS / 5, LinearEasing)),
                                exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(MORPH_MS / 3, 0, LinearEasing)),
                            ),
                        )
                    }
                    // What has no counterpart fades: in late, out early.
                    val rest = if (reducedMotion) Modifier else Modifier.animateEnterExit(
                        fadeIn(tween(MORPH_MS / 2, MORPH_MS / 2, LinearEasing)), fadeOut(tween(MORPH_MS / 3, 0, LinearEasing)),
                    )
                    val lastChat = state.last
                    val lastMorph = if (lastChat != null) morph(lastChat.first) else at.quickme.prism.assistant.Morph.None
                    if (shown != null) {
                        AssistantTranscript(
                            transcript = shown.transcript,
                            running = shown.running,
                            agent = if (now) followed else null,
                            onNew = ::newRequest,
                            onAction = act,
                            lastChat = lastChat?.second,
                            onLastChat = { lastChat?.let { openChat(it.first) } },
                            banner = { if (now) ErrorSnackbar(error, ::dismissError, Modifier.padding(bottom = 12.dp)) },
                            panelMorph = morph(shown.id),
                            lastChatMorph = lastMorph,
                            newChatModifier = rest,
                            host = shown.session.surfaces,
                            modifier = Modifier.fillMaxWidth().chipEnter(0),
                        )
                    } else if (!following) {
                        if (!session.configured) NotSetUp(Modifier.chipEnter(0).then(rest))
                        // Gemini's chip above its card: here, the last chat.
                        else if (lastChat != null) at.quickme.prism.assistant.AssistControls(
                            lastChat = lastChat.second, onLastChat = { openChat(lastChat.first) }, onNew = null,
                            modifier = Modifier.chipEnter(0),
                            lastChatMorph = lastMorph,
                        )
                    } else AgentConversation(
                        title = agentTitle.ifEmpty { activity?.agent?.title?.takeIf { it.isNotEmpty() } ?: "Agent" },
                        activity = activity,
                        pending = pending,
                        error = activityError,
                        shape = ConversationShape,
                        onOpen = if (activity?.agent?.link != null || activity?.agent?.t3 != null) ::openThread else null,
                        onStop = ::stopAgent,
                        onNew = ::newRequest,
                        modifier = Modifier.fillMaxWidth().chipEnter(0).then(rest),
                    )
                }
            }
        }
        val transition: FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> =
            if (reducedMotion) snap() else tween(FULL_MS, easing = EmphasizedEasing)
        val chatShape = androidx.compose.foundation.shape.RoundedCornerShape(CardRadius)
        CompositionLocalProvider(LocalOverlayAge provides { clock.value }) { Box(
            Modifier.fillMaxSize().graphicsLayer {
                // All of it as one (the card, the panel and the chips, and what
                // a transition draws over them): Back draws it back towards
                // the bottom, closing takes it on from there and fades it.
                // Faded per drawing, not through an offscreen layer, which
                // would cut the card's shader (48 dp round it) where it ends.
                // Round the shared layout, not on it: its lookahead root is
                // its parent, so on it this layer lay between the two, and
                // what is placed by its lookahead (the panel and the chips,
                // [placedAtRest], [pinned]) undid the scale's move towards
                // the bottom, shrinking where it was instead of with the card.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val l = leave.value
                val b = progress()
                val s = (1f - 0.1f * b) * (1f - 0.1f * l)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = 24.dp.toPx() * l
                alpha = (1f - 0.35f * b) * (1f - l)
            },
        ) { SharedTransitionLayout(Modifier.fillMaxSize()) {
            // The card and the full-screen chat are one element (shared
            // bounds): up, the card grows to fill the screen, its contents
            // giving way to the chat's; down, the whole chat shrinks into the
            // card (scaled, its bottom, where its field is, on the card's)
            // and gives way to the card late, as the answer panel and the
            // chips come back. Whatever the card's width at either end.
            // Nothing else fades as a whole: each end brings its own.
            AnimatedContent(
                targetState = full,
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                label = "full",
            ) { fullScreen ->
                fun fade(ms: Int, delay: Int) = if (reducedMotion) EnterTransition.None else fadeIn(tween(ms, delay, LinearEasing))
                fun out(ms: Int, delay: Int) = if (reducedMotion) ExitTransition.None else fadeOut(tween(ms, delay, LinearEasing))
                // Whole to the end of the transition.
                val stay = if (reducedMotion) ExitTransition.None else fadeOut(tween(FULL_MS), targetAlpha = 1f)
                // One container, the card's and the chat's: its bounds go from
                // one to the other, the end that arrives on top with its
                // background fading in over the other's, which stays beneath it
                // whole; only what is in them crossfades. The handle is one
                // element too: from the card's top to the screen's and back.
                val container = Modifier.sharedBounds(
                    rememberSharedContentState(FullChatKey),
                    this@AnimatedContent,
                    enter = EnterTransition.None,
                    exit = stay,
                    boundsTransform = { _, _ -> transition },
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    // The chat is drawn over the card both ways: growing, it
                    // covers the card as it comes; shrinking, its background
                    // fades over the whole way into the card's plate beneath.
                    zIndexInOverlay = if (fullScreen) 1f else 0f,
                    clipInOverlayDuringTransition = OverlayClip(chatShape),
                )
                val background = Modifier.animateEnterExit(
                    fade(FULL_MS * 2 / 5, 0),
                    if (!fullScreen || reducedMotion) stay else fadeOut(tween(FULL_MS, easing = Accelerate)),
                )
                val content = Modifier.animateEnterExit(fade(FULL_MS * 2 / 5, FULL_MS * 3 / 5), out(FULL_MS / 5, 0))
                // The handle, the +, the text and the buttons are one element
                // each too: from the card to the chat's field (the handle to
                // the chat's top) and back, above the containers.
                @Composable
                fun part(key: String) = Modifier.sharedElement(
                    rememberSharedContentState(key),
                    this@AnimatedContent,
                    boundsTransform = { _, _ -> transition },
                    zIndexInOverlay = 2f,
                )
                val parts = ComposerParts(
                    handle = part(HandleKey),
                    plus = part("prism-plus"),
                    field = part("prism-field"),
                    mic = part("prism-mic"),
                    send = part("prism-send"),
                )
                // The panel and the chips leave as the card grows and come back
                // as the chat has shrunk; already there when the gesture that
                // took it back showed them. Going, they go up with the card;
                // coming back, they are where they rest all the while (not on
                // the card as it shrinks): behind the chat, never above it,
                // also when the chat goes back before they had gone.
                val leaving = this@AnimatedContent.transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                val others = Modifier.animateEnterExit(
                    if (revealed) EnterTransition.None else fade(FULL_MS * 2 / 5, FULL_MS * 3 / 5), out(FULL_MS / 4, 0),
                )
                @Composable
                fun card(ghost: Boolean) = InputCard(
                    cardModifier = if (ghost) Modifier.graphicsLayer { alpha = 0f } else container,
                    plateModifier = if (ghost) Modifier else background,
                    contentModifier = if (ghost) Modifier else content,
                    parts = if (ghost) ComposerParts.None else parts,
                    expand = if (ghost) null else ({ showFull(true) }),
                    selectors = {
                        Column(
                            (if (ghost) Modifier else if (leaving) others else others.placedAtRest(this@SharedTransitionLayout)).fillMaxWidth().atMargin(margin - targetMargin),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { selectors() }
                    },
                    flat = false,
                    showError = !chatting && !ghost,
                    ghost = ghost,
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).union(WindowInsets.statusBars))
                        .padding(top = 8.dp)
                        // Gemini's card: 40 dp from the sides (less on a narrow phone), 8 dp from the bottom, at most 696 dp wide.
                        .padding(horizontal = margin)
                        .padding(bottom = 8.dp)
                        .widthIn(max = 696.dp)
                        .fillMaxWidth()

                )
                if (!fullScreen) card(ghost = false)
                else Box(Modifier.fillMaxSize()) {
                    // Taken back by a gesture, the chat shows what is above
                    // the card behind it (where it will be).
                    if (peeking && full) card(ghost = true)
                    HistoryChat(
                        transcript = session.transcript,
                        running = session.running,
                        loading = false,
                        error = null,
                        configured = session.configured,
                        canSend = true,
                        text = text,
                        onText = { text = it; sendError = null },
                        onSend = { send() },
                        onAction = act,
                        onNew = ::newRequest,
                        onBack = { showFull(false) },
                        chats = { close ->
                            at.quickme.prism.assistant.ChatsDrawer(
                                current = session.conversationId,
                                onOpen = { id -> close(); openChat(id) },
                                onNew = { close(); newRequest() },
                                newEnabled = !session.running,
                            )
                        },
                        onCollapse = { showFull(false) },
                        collapseOnBack = !menuOpen,
                        agent = followed,
                        handoff = handoffEntry,
                        composer = {
                            InputCard(Modifier, null, {}, Modifier.fillMaxWidth().widthIn(max = 696.dp), flat = true, showError = true, parts = parts)
                        },
                        modifier = container,
                        backgroundModifier = background,
                        contentModifier = content,
                        handleModifier = parts.handle,
                        host = session.surfaces,
                        onPeek = { peeking = it },
                        // The halo follows the chat as a gesture takes it back.
                        onBounds = { if (full) plate = it },
                    )
                }
            }
        } } }
    }
}

/** Without assistant_api_key: what the overlay does instead. */
@Composable
private fun NotSetUp(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Text(
        "The assistant isn't set up on this phone (no assistant_api_key): requests start a T3 agent.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = modifier
            .background(colors.surfaceContainerHigh.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A light indigo veil, heavier at the bottom where the composer sits. */
private fun DrawScope.drawVeil(t: Float, gone: Float) {
    val a = Decelerate.transform(phase(t, 0f, 300f)) * gone
    if (a <= 0f) return
    drawRect(Night.copy(alpha = 0.16f * a))
    val top = size.height * 0.6f
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Night.copy(alpha = 0.5f * a), startY = top, endY = size.height),
        topLeft = Offset(0f, top),
    )
}

/**
 * A request as sent, to send it again with the same [id]: [target] is the
 * agent's ID for a follow-up; [attachments] what the owner attached.
 */
private data class AssistantRequest(
    val id: String,
    val target: String,
    val model: ModelChoice?,
    val project: String,
    val text: String,
    val attachments: List<AssistAttachment> = emptyList(),
)

/**
 * The placement profile the overlay's assistant itself runs on (Phone ›
 * Profiles › Actions › Assistant): it asks that profile's best model through
 * the gateway (assistant/AssistantModel.kt). Unset, the published
 * assistant_model.
 */
internal object AssistantProfile {
    private const val KEY = "profile"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotEmpty() }

    fun set(context: Context, id: String?) {
        prefs(context).edit().apply { if (id.isNullOrEmpty()) remove(KEY) else putString(KEY, id) }.apply()
    }
}

/**
 * The placement profile the T3 agents the overlay starts run on by default
 * (Phone › Profiles › Actions › Assistant's agents); the assistant takes
 * another when the owner names one (start_t3_agent's profile). The machine
 * and the project are always Prism's pick. Unset, the worker's own default
 * model applies.
 */
internal object AgentProfile {
    private const val KEY = "agent_profile"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotEmpty() }

    fun set(context: Context, id: String?) {
        prefs(context).edit().apply { if (id.isNullOrEmpty()) remove(KEY) else putString(KEY, id) }.apply()
    }

    /** The launch's model: profile [id] (else the default one) on T3's placement instance, or null for the worker's default. */
    fun model(context: Context, id: String? = null): ModelChoice? = (id ?: get(context))?.let { ProfileChoice(it).model }
}

/** What the overlay keeps on this phone: the draft and the agent it follows. */
internal class AssistMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    /** The draft's text; earlier versions kept it in a JSON object with the chips' picks. */
    fun draft(): String = try {
        JSONObject(prefs.getString(DRAFT, null) ?: return "").optString("text")
    } catch (_: org.json.JSONException) {
        ""
    }

    fun saveDraft(text: String) {
        prefs.edit().putString(DRAFT, JSONObject().put("text", text).toString()).apply()
    }

    fun clearDraft() {
        prefs.edit().remove(DRAFT).apply()
    }

    /** The agent the overlay followed last, if that was less than [RESUME_MS] ago. */
    fun conversation(): String? {
        val at = prefs.getLong(CONVERSATION_AT, 0L)
        if (System.currentTimeMillis() - at > RESUME_MS) return null
        return prefs.getString(CONVERSATION, null)?.takeIf { it.isNotEmpty() }
    }

    fun conversation(id: String) {
        if (id.isEmpty()) return
        prefs.edit().putString(CONVERSATION, id).putLong(CONVERSATION_AT, System.currentTimeMillis()).apply()
    }

    fun forgetConversation() {
        prefs.edit().remove(CONVERSATION).remove(CONVERSATION_AT).remove(STARTING).apply()
    }

    /** A direct start the leader hadn't run yet when the overlay closed: its request ID, text and project. */
    fun starting(): Triple<String, String, String>? {
        if (System.currentTimeMillis() - prefs.getLong(CONVERSATION_AT, 0L) > RESUME_MS) return null
        return try {
            val o = JSONObject(prefs.getString(STARTING, null) ?: return null)
            Triple(o.getString("id"), o.getString("text"), o.getString("project"))
        } catch (_: org.json.JSONException) {
            null
        }
    }

    fun starting(id: String, text: String, project: String) {
        prefs.edit()
            .putString(STARTING, JSONObject().put("id", id).put("text", text).put("project", project).toString())
            .remove(CONVERSATION)
            .putLong(CONVERSATION_AT, System.currentTimeMillis())
            .apply()
    }

    fun started() {
        prefs.edit().remove(STARTING).apply()
    }

    companion object {
        private const val STARTING = "starting"
        private const val DRAFT = "draft"
        private const val CONVERSATION = "conversation"
        private const val CONVERSATION_AT = "conversation_at"
        /** Opened again within this, the overlay shows the same agent (and the assistant's conversation, AssistantMemory). */
        private const val RESUME_MS = 60 * 1000L
    }
}

/** The magic pick, as machine and project: Prism chooses from the request. */
private const val AUTO = "auto"

/** The card's margin while it spans the screen. */
private val WideMargin = 8.dp

/** The card's and the panel's margin while an answer shows: Gemini's response page (floaty_popup_horizontal_margin). */
private val AnswerMargin = 16.dp

/** The card's growing into the full-screen chat and back (ms), on M3's emphasized curve. */
private const val FULL_MS = 500

/** The halo's part round the panel coming or going (ms). */
private const val HALO_EASE_MS = 300


/**
 * Laid out [extra] wider at each side than it is given (centred): at the
 * margin the card is going to, while the card's own still animates.
 */
private fun Modifier.atMargin(extra: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val more = (2 * extra.roundToPx()).coerceAtLeast(-constraints.maxWidth)
    if (!constraints.hasBoundedWidth || more == 0) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val w = (constraints.maxWidth + more).coerceAtLeast(0)
    val p = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, p.height) { p.place(-more / 2, 0) }
}

/** A chat's panel and its chip morphing into each other (ms). */
private const val MORPH_MS = 450

/** The chat the overlay's panel shows: [transcript] and [running] as they were last, of [session]. */
private data class ShownChat(val id: String, val transcript: at.quickme.prism.assistant.core.Transcript, val running: Boolean, val session: AssistantSession)

/** What is above the card: the chat in the panel, if any, and the last chat offered as a chip (its ID and title), if any. */
private data class Above(val chat: ShownChat?, val last: Pair<String, String>?)

/**
 * The composer's parts that the card and the full-screen chat's field share
 * (each one element in the transition between them): the handle (the
 * card's; the chat's is at its top), the +, the text, the mic and send.
 */
internal class ComposerParts(
    val handle: Modifier = Modifier,
    val plus: Modifier = Modifier,
    val field: Modifier = Modifier,
    val mic: Modifier = Modifier,
    val send: Modifier = Modifier,
) {
    companion object {
        val None = ComposerParts()
    }
}

/**
 * Laid out as and where it ends up (its lookahead) whatever its container
 * does meanwhile: in a shared container only the edges move, what is in it
 * stays put.
 */
private fun Modifier.pinned(scope: androidx.compose.ui.layout.LookaheadScope): Modifier = with(scope) {
    approachLayout(
        isMeasurementApproachInProgress = { true },
        isPlacementApproachInProgress = { true },
    ) { measurable, constraints ->
        val p = measurable.measure(Constraints.fixed(lookaheadSize.width, lookaheadSize.height))
        layout(p.width.coerceIn(constraints.minWidth, constraints.maxWidth), p.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            val c = coordinates
            if (c == null) p.place(0, 0) else {
                val target = lookaheadScopeCoordinates.localLookaheadPositionOf(c)
                val current = lookaheadScopeCoordinates.localPositionOf(c, Offset.Zero)
                p.place((target.x - current.x).roundToInt(), (target.y - current.y).roundToInt())
            }
        }
    }
}

/**
 * Placed where the layout ends up in [scope] (its bottom there), measured as
 * it is now: its own size still animates, where it is does not follow what
 * it is laid out on as that moves.
 */
private fun Modifier.placedAtRest(scope: androidx.compose.ui.layout.LookaheadScope): Modifier = with(scope) {
    approachLayout(
        isMeasurementApproachInProgress = { true },
        isPlacementApproachInProgress = { true },
    ) { measurable, constraints ->
        val p = measurable.measure(constraints)
        layout(p.width, p.height) {
            val c = coordinates
            if (c == null) p.place(0, 0) else {
                val target = lookaheadScopeCoordinates.localLookaheadPositionOf(c)
                val current = lookaheadScopeCoordinates.localPositionOf(c, Offset.Zero)
                p.place((target.x - current.x).roundToInt(), (target.y - current.y).roundToInt() + lookaheadSize.height - p.height)
            }
        }
    }
}

/** The card's handle and the full-screen chat's are one element under this key. */
private const val HandleKey = "prism-handle"

/** The card and the full-screen chat share their bounds under this key. */
private const val FullChatKey = "prism-chat"
/** Floating selectors and the text field move together above the keyboard. */
@Composable
internal fun AssistComposer(
    text: String,
    onText: (String) -> Unit,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    animate: Boolean,
    /** Gemini's app's field (the full-screen chat): flat, no glow, no handle; else Gemini's overlay plate. */
    flat: Boolean = false,
    /** Shown above the card as Gemini's snackbar until [onDismissError]. */
    error: String? = null,
    onDismissError: () -> Unit = {},
    placeholder: String = "Ask Prism",
    voice: VoiceInput? = null,
    onMic: (() -> Unit)? = null,
    onWave: (() -> Unit)? = null,
    /** What is attached, shown at the top of the card. */
    attachments: List<Attached> = emptyList(),
    onRemove: (Attached) -> Unit = {},
    /** The + menu's actions; without them the + is off. */
    attach: AttachActions? = null,
    menuOpen: Boolean = false,
    onMenu: (Boolean) -> Unit = {},
    onCard: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** The card dragged up past [ExpandDrag] (or flung up); without it the card doesn't drag. */
    onExpand: (() -> Unit)? = null,
    /** On the card itself, first: the overlay's shared bounds with the full-screen chat. */
    cardModifier: Modifier = Modifier,
    /** How far the card is dragged up to begin with; for previews. */
    initialPull: androidx.compose.ui.unit.Dp = 0.dp,
    /** The full-screen chat shows: a card dragged into it stays as dragged until this is false again. */
    expanded: Boolean = false,
    /** The card narrower than what is above it by this at each side. */
    cardInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Its field takes the focus and the keyboard as it appears. */
    focusOnStart: Boolean = false,
    /** The card is shown settled, without Gemini's entrance (the circle widening, the dots). */
    settled: Boolean = false,
    /** The card's parts in the shared transition with the full-screen chat: its plate, what is in it, its handle, +, text and buttons. */
    plateModifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    parts: ComposerParts = ComposerParts.None,
    /** What is heard is added to the field (the keyboard was up), not sent: the field stays, no bars. */
    voiceInline: Boolean = false,
    selectors: @Composable () -> Unit,
    /** Where all of it is (the card and what is above it), for the halo round it. */
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (focusOnStart) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // No gaps between what is here: each brings its own below it, so the
    // menu and the snackbar grow in with theirs.
    Column(modifier.semantics { paneTitle = "Prism" }.onGloballyPositioned { onBounds(it.boundsInRoot()) }) {
        // Measured after the card, so the card always fits (the keyboard up)
        // and the transcript scrolls in what is left.
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { selectors() }
        // Gemini's attach menu rises above the card.
        AnimatedVisibility(
            visible = menuOpen && attach != null,
            enter = if (animate) androidx.compose.animation.expandVertically(tween(300, easing = Decelerate), expandFrom = Alignment.Bottom) +
                fadeIn(tween(200)) else EnterTransition.None,
            exit = if (animate) androidx.compose.animation.shrinkVertically(tween(200, easing = Accelerate), shrinkTowards = Alignment.Bottom) +
                fadeOut(tween(150)) else ExitTransition.None,
        ) {
            attach?.let { AttachMenu(it, Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
        }
        ErrorSnackbar(error, onDismissError, Modifier.padding(bottom = 12.dp))
        Composer(
            text = text,
            onText = onText,
            canSend = canSend,
            sending = sending,
            onSend = onSend,
            onFocus = {
                onMenu(false)
                focus.requestFocus()
                keyboard?.show()
            },
            focus = focus,
            animate = animate,
            flat = flat,
            placeholder = placeholder,
            voice = voice,
            onMic = onMic,
            onWave = onWave,
            attachments = attachments,
            onRemove = onRemove,
            onPlus = attach?.let { { onMenu(!menuOpen) } },
            menuOpen = menuOpen,
            onCard = onCard,
            onExpand = onExpand,
            cardModifier = cardModifier,
            initialPull = initialPull,
            expanded = expanded,
            settled = settled,
            plateModifier = plateModifier,
            contentModifier = contentModifier,
            parts = parts,
            voiceInline = voiceInline,
            modifier = Modifier.fillMaxWidth().padding(horizontal = cardInset),
        )
    }
}

/** How far the card must be dragged up to expand. */
internal val ExpandDrag = 72.dp

/** Or how fast it must be flung up (dp/s), from at least [FlingMinDrag]. */
private const val ExpandFlingDpPerS = 1200f
private val FlingMinDrag = 16.dp

/**
 * How much taller the card is for [pull] px of finger travel up: as far as
 * the finger to the [threshold], then half as fast and less and less (a
 * rubber band to twice the threshold).
 */
private fun dragGrowth(pull: Float, threshold: Float): Float {
    if (pull <= threshold || threshold <= 0f) return pull.coerceAtLeast(0f)
    return threshold + threshold * (1f - kotlin.math.exp(-(pull - threshold) / (2 * threshold)))
}

/**
 * Gemini's input card: one row (+, text, mic, send) while idle; once the
 * keyboard is up, there is text or something is attached, two rows, the text
 * across the whole card and the buttons under it, the attachments above it. The change animates: the text widens and moves,
 * the buttons drop below it and the card grows. While Prism listens, the text
 * gives way to the voice's bars and send to a pulsing stop; a tap on the bars
 * stops too but keeps the words for editing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onFocus: () -> Unit,
    focus: FocusRequester,
    animate: Boolean,
    flat: Boolean,
    placeholder: String,
    voice: VoiceInput?,
    onMic: (() -> Unit)?,
    onWave: (() -> Unit)?,
    attachments: List<Attached>,
    onRemove: (Attached) -> Unit,
    /** The +: opens or closes the attach menu; null when there is none. */
    onPlus: (() -> Unit)?,
    menuOpen: Boolean,
    onCard: (androidx.compose.ui.geometry.Rect) -> Unit,
    onExpand: (() -> Unit)?,
    cardModifier: Modifier,
    initialPull: androidx.compose.ui.unit.Dp,
    expanded: Boolean,
    settled: Boolean,
    plateModifier: Modifier,
    contentModifier: Modifier,
    parts: ComposerParts,
    voiceInline: Boolean,
    modifier: Modifier,
) {
    // Listening, Gemini first only pulses its mic; once speech begins, the
    // bars (not while what is heard goes into the field: the field stays).
    val listening = voice?.active == true && voice.speaking && !voiceInline
    val waiting = voice?.active == true && !voice.speaking
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    // Two rows for what is in the card (text, attachments): animated on its
    // own. And for the keyboard: frame by frame with its own animation, both
    // as it rises and as it goes, so the text moves up as the keyboard does.
    val filled by animateFloatAsState(
        if (!listening && (text.isNotEmpty() || attachments.isNotEmpty())) 1f else 0f,
        if (animate) tween(350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(),
        label = "rows",
    )
    val density0 = LocalDensity.current
    val imeNow = WindowInsets.ime.getBottom(density0)
    val imeFull = maxOf(WindowInsets.imeAnimationSource.getBottom(density0), WindowInsets.imeAnimationTarget.getBottom(density0))
    // The keyboard rose for this field: followed down to the end, even once
    // the field has let go of the focus.
    // (Not state: the insets recompose this every frame they move.)
    val ours = remember { BooleanArray(1) }
    if (focused && imeNow > 0) ours[0] = true else if (imeNow == 0) ours[0] = false
    val keyboard = if (!(focused || ours[0]) || listening) 0f
    else if (!animate) (if (imeNow > 0) 1f else 0f)
    else if (imeFull > 0) (imeNow.toFloat() / imeFull).coerceIn(0f, 1f) else 0f
    val rows = maxOf(filled, keyboard)
    // The keyboard is up for this field: send shows (off while there is
    // nothing to send) and the mic stays, adding what it hears.
    val typing = (focused || ours[0]) && imeNow > 0
    // No caret without the keyboard: the field lets go of the focus as it goes.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val imeInsets = WindowInsets.ime
    val focusedNow by androidx.compose.runtime.rememberUpdatedState(focused)
    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density0) > 0 }
            .drop(1)
            .collect { up -> if (!up && focusedNow) focusManager.clearFocus() }
    }
    val dark = overlayDark()
    val plateColors = remember(colors, dark) { PlateColors(colors, dark) }
    // The card's own clock: it settles as it appears, and shimmers while sending.
    // Settled, its clock starts past the entrance and the settling.
    val opened = remember {
        android.os.SystemClock.uptimeMillis() - if (settled) (PLATE_SETTLE_START_MS + PLATE_SETTLE_MS + PlateAppear.MS).toLong() else 0L
    }
    var now by remember { mutableLongStateOf(opened) }
    LaunchedEffect(sending, animate) {
        if (!animate) {
            now = opened + (PLATE_SETTLE_START_MS + PLATE_SETTLE_MS).toLong() + 1
            return@LaunchedEffect
        }
        while (sending || now - opened <= PLATE_SETTLE_START_MS + PLATE_SETTLE_MS) {
            withFrameMillis { now = android.os.SystemClock.uptimeMillis() }
        }
    }
    val processing by animateFloatAsState(if (sending) 1f else 0f, tween(300), label = "processing")
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = if (flat) 17.sp else 20.sp, lineHeight = if (flat) 24.sp else 28.sp)
    // Gemini's app's field appears as it is: nothing to settle.
    val age = if (flat) ({ Float.MAX_VALUE }) else ({ (now - opened).toFloat() })
    // Its corners: a pill at one row, 28 dp once taller (Gemini's ccix: 95 dp).
    var flatHeight by remember { mutableIntStateOf(0) }
    val flatRadius by androidx.compose.animation.core.animateDpAsState(
        if (with(LocalDensity.current) { flatHeight.toDp() } < 95.dp) 32.dp else 28.dp,
        if (animate) tween(100, easing = LinearEasing) else snap(),
        label = "corners",
    )
    // Dragged up, the card grows with the finger and, past a threshold (or
    // on a fling up), goes on into the full-screen chat; below it, it springs
    // back.
    val density = LocalDensity.current
    val view = LocalView.current
    var pull by remember { mutableFloatStateOf(with(density) { initialPull.toPx() }) }
    val grow = { with(density) { dragGrowth(pull, ExpandDrag.toPx()).roundToInt() } }
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Handed to the full screen as dragged; once the chat goes back (at once,
    // this same card coming back), the card is its own size again.
    var handedOff by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded && handedOff) {
            handedOff = false
            pull = 0f
        }
    }
    val drag = rememberDraggableState { dy ->
        val before = pull
        pull = (pull - dy).coerceAtLeast(0f)
        val threshold = with(density) { ExpandDrag.toPx() }
        if (before < threshold && pull >= threshold) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
    }
    Box(
        modifier
            .then(cardModifier)
            .onGloballyPositioned { onCard(it.boundsInRoot()) }
            .draggable(
                drag,
                Orientation.Vertical,
                enabled = onExpand != null,
                onDragStarted = { settling?.cancel() },
                onDragStopped = { velocity ->
                    settling = coroutineContext[kotlinx.coroutines.Job]
                    val (threshold, fling, least) = with(density) {
                        Triple(ExpandDrag.toPx(), ExpandFlingDpPerS.dp.toPx(), FlingMinDrag.toPx())
                    }
                    // Expanding, the card stays as it was dragged: the
                    // transition takes it from there to the full screen.
                    if (onExpand != null && (pull >= threshold || (velocity <= -fling && pull >= least))) {
                        // The transition takes the card from here, as it was
                        // dragged (what is above it stays where it is).
                        handedOff = true
                        onExpand()
                        return@draggable
                    }
                    androidx.compose.animation.core.animate(
                        pull, 0f,
                        animationSpec = if (animate) spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow) else snap(),
                    ) { value, _ -> pull = value }
                },
            )
            .then(if (flat) Modifier.onSizeChanged { flatHeight = it.height } else Modifier)
            // Taps on the card focus the field.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Type a request",
                onClick = onFocus,
            ),
        // What is in the card fills it (in the shared transition, its bounds);
        // the plate and the handle take the card's size as it is.
        propagateMinConstraints = true,
    ) {
        // The plate: Gemini's shader, or Gemini's app's flat field.
        Box(
            Modifier.matchParentSize().then(plateModifier).then(
                if (flat) Modifier.background(colors.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(flatRadius))
                else Modifier.geminiPlate(plateColors, CardRadius, ageMs = age, processing = { processing }),
            ),
        )
        // Gemini's handle: inside the card, 6 dp below its top edge, over the
        // plate and under what is in it. Full screen it is at the top (one
        // element with this one).
        if (!flat) Box(Modifier.matchParentSize()) {
            at.quickme.prism.assistant.DragHandle(
                Modifier.align(Alignment.TopCenter).padding(top = 6.dp).then(parts.handle)
                    .graphicsLayer { alpha = PlateAppear.contentAlpha(age()) },
            )
        }
        Layout(
            modifier = Modifier.then(contentModifier).graphicsLayer {
                // What is in the card grows and fades in with it.
                val t = age()
                val s = PlateAppear.contentScale(t)
                scaleX = s
                scaleY = s
                alpha = PlateAppear.contentAlpha(t)
            },
            content = {
                // Gemini's plus: the attach menu. A quarter turn to a cross while it is open.
                val turn by animateFloatAsState(
                    if (menuOpen) -45f else 0f,
                    if (animate) tween(250, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(),
                    label = "plus",
                )
                IconButton(
                    onClick = { onPlus?.invoke() },
                    enabled = onPlus != null && !listening && !sending,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface, disabledContentColor = colors.onSurface),
                    modifier = Modifier.then(parts.plus).semantics { selected = menuOpen },
                ) {
                    Icon(
                        GeminiIcons.Plus,
                        if (menuOpen) "Close the attach menu" else "Add photos, files and the screen",
                        Modifier.size(28.dp).graphicsLayer { rotationZ = turn },
                    )
                }
                if (listening) {
                    VoiceWave(
                        voice, animate,
                        // As tall as the field (one line and its 12 dp padding): the card keeps its height.
                        Modifier.height(if (flat) 48.dp else 52.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Stop listening and edit",
                            onClick = { onWave?.invoke() },
                        ),
                    )
                } else @Suppress("DEPRECATION") // Compose 1.13 deprecates the value-based field; the composer keeps it.
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    readOnly = sending,
                    textStyle = textStyle.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.then(parts.field).heightIn(max = 210.dp).padding(vertical = 12.dp)
                        .focusRequester(focus).onFocusChanged {
                            focused = it.isFocused
                            // Typing instead of speaking: stop the listening nobody used.
                            if (it.isFocused && voice?.active == true && !voice.speaking) voice.cancel()
                        },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(placeholder, style = textStyle, color = if (flat) colors.onSurfaceVariant else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
                // At the card's end: the mic (the stop while the bars show)
                // at the very end, as Prism has no Live for that place; send
                // before it once there is something to send.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The mic always (it adds what it hears to what is typed), but while sending.
                    val mic = onMic?.takeIf { !sending }
                    if (mic != null) Box(Modifier.then(parts.mic).size(48.dp), contentAlignment = Alignment.Center) {
                        if (listening) ListeningStop(onClick = mic, animate = animate)
                        else MicButton(onClick = mic, pulsing = waiting, animate = animate)
                    }
                    val something = text.isNotBlank() || attachments.isNotEmpty()
                    SendSlot(
                        visible = (something || sending || typing) && !listening,
                        sending = sending, canSend = canSend && something, onSend = onSend, animate = animate,
                        modifier = parts.send,
                    )
                }
                // Always a child, even with nothing attached (then 0 tall).
                Box { AttachmentRow(attachments, onRemove, animate) }
            },
        ) { measurables, constraints ->
            val (plusM, fieldM, trailingM) = measurables
            val attachedM = measurables[3]
            val width = constraints.maxWidth
            // Gemini's buttons: their centres 40 dp in from the card's sides
            // (the +, its send or Live) and 88 dp (its mic), 16 dp from the
            // bottom in two rows (measured on the owner's Pixel; the Google
            // app's floaty input, fsplit2). Prism's mic takes the end place.
            // Gemini's app's field is lower: 8 dp round its buttons, 64 dp at one row.
            val pad = (if (flat) 8.dp else 16.dp).roundToPx()
            val textPad = 8.dp.roundToPx()
            val gap = 4.dp.roundToPx()
            // In two rows the text keeps clear of the card's rounded corners.
            val textInset = (if (flat) 20.dp else 24.dp).roundToPx()
            // Gemini's card is 80 dp tall at one row (measured on the owner's
            // Pixel): 48 dp buttons with 16 dp round them, room for the handle.
            val minHeight = (if (flat) 64.dp else 80.dp).roundToPx()
            // In two rows, room under the handle (2 dp, 6 dp down): with it,
            // as much as the buttons have below them (the text's own 12 dp
            // padding counted).
            val headroom = if (flat) 0 else (10.dp.roundToPx() * rows).roundToInt()
            val plus = plusM.measure(Constraints())
            val trailing = trailingM.measure(Constraints(maxWidth = width))
            // What is attached goes above everything else, across the card.
            val attached = attachedM.measure(Constraints(maxWidth = width))
            val top = attached.height
            // The text's place in one row, and across the card in two.
            val x1 = pad + plus.width + gap
            val w1 = (width - x1 - gap - trailing.width - pad).coerceAtLeast(0)
            val x2 = textInset
            val w2 = (width - 2 * textInset).coerceAtLeast(0)
            fun lerp(a: Int, b: Int) = (a + (b - a) * rows).roundToInt()
            val fieldWidth = lerp(w1, w2)
            val field = fieldM.measure(Constraints.fixedWidth(fieldWidth))
            val buttons = maxOf(plus.height, trailing.height)
            val h1 = maxOf(minHeight, field.height + 2 * textPad, buttons + 2 * pad)
            val h2 = field.height + buttons + pad
            val natural = headroom + top + lerp(h1, h2)
            // Dragged up, the card grows with the finger: what is in it stays
            // at its bottom. In the shared transition it fills its bounds.
            val height = maxOf(natural + grow(), constraints.minHeight).coerceAtMost(
                if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
            )
            val below = height - natural
            layout(width, height) {
                attached.placeRelative(0, below + headroom)
                val fieldY = below + headroom + top + lerp((h1 - field.height) / 2, 0)
                field.placeRelative(lerp(x1, x2), fieldY)
                // At one row the buttons sit in the card's middle; at two, under the text.
                val buttonY = below + headroom + top + lerp((h1 - buttons) / 2, natural - headroom - top - pad - buttons)
                plus.placeRelative(pad, buttonY + (buttons - plus.height) / 2)
                trailing.placeRelative(width - pad - trailing.width, buttonY + (buttons - trailing.height) / 2)
            }
        }
    }
}

/** Gemini's combined button's slot (Live there): send, or the progress while sending; empty with nothing to send. */
@Composable
private fun SendSlot(visible: Boolean, sending: Boolean, canSend: Boolean, onSend: () -> Unit, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // Takes no room while hidden, so the mic is at the card's end.
    AnimatedVisibility(
        visible = visible,
        enter = if (animate) androidx.compose.animation.expandHorizontally(expandFrom = Alignment.Start) + scaleIn(initialScale = 0.6f) + fadeIn() else EnterTransition.None,
        exit = if (animate) androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.Start) + scaleOut(targetScale = 0.6f) + fadeOut() else ExitTransition.None,
    ) { Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        when {
            sending -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.size(24.dp).semantics { contentDescription = "Sending" },
                    color = colors.onSurfaceVariant, strokeWidth = 2.dp,
                )
            }
            else -> Surface(
                onClick = onSend,
                enabled = canSend,
                shape = CircleShape,
                color = sendFill(),
                contentColor = colors.onPrimaryContainer,
                modifier = Modifier.size(48.dp).graphicsLayer { alpha = if (canSend) 1f else 0.38f },
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(GeminiIcons.Up, "Send to Prism", Modifier.size(28.dp)) }
            }
        }
    } }
}

/**
 * What went wrong, as Gemini's floaty shows it: its snackbar (the Google
 * app's compose snackbar host, above the answer card or the input plate, 12 dp
 * from it), here in its chips' look with the message and a close; it goes on
 * its own after 4 s, 10 s for a long one (Gemini's short and long durations,
 * bsno), longer where accessibility asks for it.
 */
@Composable
internal fun ErrorSnackbar(error: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // The message stays while it leaves.
    var shown by remember { mutableStateOf(error.orEmpty()) }
    if (error != null) shown = error
    val context = LocalContext.current
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        val base = if (message.length > SNACKBAR_LONG_CHARS) SNACKBAR_LONG_MS else SNACKBAR_MS
        val a11y = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val ms = a11y?.getRecommendedTimeoutMillis(
            base,
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS,
        ) ?: base
        delay(ms.toLong())
        onDismiss()
    }
    AnimatedVisibility(
        visible = error != null,
        enter = androidx.compose.animation.expandVertically(tween(250, easing = Decelerate), expandFrom = Alignment.Bottom) + fadeIn(tween(200)),
        exit = androidx.compose.animation.shrinkVertically(tween(200, easing = Accelerate), shrinkTowards = Alignment.Bottom) + fadeOut(tween(150)),
        modifier = modifier,
    ) {
        val colors = MaterialTheme.colorScheme
        val dark = overlayDark()
        Row(
            Modifier
                .fillMaxWidth()
                .geminiChip(if (dark) Color.Black else Color.White, colors.primary, dark, 24.dp)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                .heightIn(min = 56.dp)
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(24.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

private const val SNACKBAR_MS = 4000
private const val SNACKBAR_LONG_MS = 10_000
private const val SNACKBAR_LONG_CHARS = 80
