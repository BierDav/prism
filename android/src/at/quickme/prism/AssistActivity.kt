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
// Predictive Back and an upward swipe on the overlay dismiss it; the
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
    // Back, or the swipe up from the bottom, as far as the finger has gone (0..1).
    val back = remember { Animatable(0f) }
    var swipe by remember { mutableFloatStateOf(0f) }
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
    fun progress(): Float = if (reducedMotion) 0f else maxOf(back.value, swipe)

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
            starting = AssistantRequest(card.requestId, AUTO, AssistantProfile.model(context), card.project, card.request)
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
            starting = AssistantRequest(card.requestId, AUTO, AssistantProfile.model(context), card.project, card.request)
            return@LaunchedEffect
        }
        val (id, request, project) = memory.starting() ?: return@LaunchedEffect
        if (!session.configured) {
            agentId = null
            agentTitle = request
            pending.add(request)
            starting = AssistantRequest(id, AUTO, AssistantProfile.model(context), project, request)
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
                if (!unlock()) {
                    sendError = "Unlock the phone to open a chat."
                    return@launch
                }
                val s = AssistantSession.live(id)
                    ?: ConversationStore.load(context, id).let { loaded ->
                        // Opened meanwhile elsewhere? Then that session.
                        AssistantSession.live(id) ?: AssistantSession(context, capture, resume = false).also { it.open(loaded) }
                    }
                showSession(s)
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

    LaunchedEffect(text) { memory.saveDraft(text) }

    fun resetDismissal() {
        scope.launch {
            back.snapTo(maxOf(back.value, swipe))
            swipe = 0f
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
            if (!reducedMotion) leave.animateTo(1f, tween(200, easing = Accelerate))
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
        val model = AssistantProfile.model(context)
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
        text = spoken
        if (editVoice) editVoice = false else send(spoken)
    }

    fun listenToEdit() {
        editVoice = true
        voice.stop()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start(::heard) else sendError = "Prism may not use the microphone."
    }

    fun listen() {
        when {
            // The pulsing mic, before anyone spoke: stop listening.
            voice.active && !voice.speaking -> voice.cancel()
            voice.active -> voice.stop()
            voice.permitted() -> {
                keyboard?.hide()
                sendError = null
                voice.start(::heard)
            }
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Gemini listens as soon as it opens: its mic pulses until speech begins.
    // Not over a kept draft (what it heard would replace and send it), and
    // only with the microphone already allowed (no prompt on every open).
    LaunchedEffect(Unit) {
        if (text.isEmpty() && voice.permitted()) voice.start(::heard)
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
    // Where the input card is, and the answer panel above it, for the halo round them.
    var plate by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var panel by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
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
                    val gone = (1f - Accelerate.transform(leave.value)) * (1f - 0.7f * progress())
                    // Gemini fades its scrim in linearly over 450 ms.
                    val shown = phase(t, 0f, 450f) * gone
                    // Gemini's layers, back to front: the background page's halo,
                    // the glow, the input page's halo, then the card and chips.
                    // Round the card, and the panel with it while an answer shows.
                    val round = if (panel.isEmpty) plate else androidx.compose.ui.geometry.Rect(
                        minOf(plate.left, panel.left), minOf(plate.top, panel.top),
                        maxOf(plate.right, panel.right), maxOf(plate.bottom, panel.bottom),
                    )
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
        val reach = with(LocalDensity.current) { 160.dp.toPx() }
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close Prism",
                    // A tap beside the + menu closes the menu, not the overlay.
                    onClick = { if (menuOpen) menuOpen = false else close() },
                )
                .pointerInput(reach, reducedMotion) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (swipe > 0.3f) close() else resetDismissal() },
                        onDragCancel = ::resetDismissal,
                    ) { change, dy ->
                        change.consume()
                        swipe = (swipe - dy / reach).coerceIn(0f, 1f)
                    }
                },
        )
        val lift = with(LocalDensity.current) { 20.dp.toPx() }
        val following = agentId != null || starting != null
        val chatting = session.configured && !session.transcript.isEmpty
        // Gemini's card spans the screen while an answer shows (its response
        // page, 16 dp from the sides) or the keyboard is up, and sits back in
        // its margins otherwise.
        val answering = following || chatting
        val ime = WindowInsets.isImeVisible
        val side = sideMargin(LocalConfiguration.current.screenWidthDp)
        val marginSpec: FiniteAnimationSpec<androidx.compose.ui.unit.Dp> =
            if (reducedMotion) snap() else tween(350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
        val margin by androidx.compose.animation.core.animateDpAsState(
            when {
                answering -> AnswerMargin
                ime || menuOpen -> WideMargin
                else -> side
            },
            marginSpec,
            label = "margin",
        )
        // With an answer the panel spans the screen, and the card too while
        // it has something in it; empty, it sits back in Gemini's margins.
        val empty = text.isEmpty() && attached.isEmpty() && !ime && !menuOpen
        val cardInset by androidx.compose.animation.core.animateDpAsState(
            if (answering && empty) (side - AnswerMargin).coerceAtLeast(0.dp) else 0.dp,
            marginSpec,
            label = "cardInset",
        )
        // What went wrong, as Gemini's snackbar: above the answer panel while
        // one shows, else above the card.
        val error = sendError ?: voice.error
        fun dismissError() {
            sendError = null
            voice.clearError()
        }
        /** The card, in the overlay (Gemini's plate) and at the bottom of the full-screen chat (Gemini's app's field, [flat]). */
        @Composable
        fun InputCard(cardModifier: Modifier, expand: (() -> Unit)?, selectors: @Composable () -> Unit, modifier: Modifier, flat: Boolean, showError: Boolean) = AssistComposer(
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
            attach = if (session.configured) attachActions else null,
            menuOpen = menuOpen,
            onMenu = { menuOpen = it },
            onCard = { plate = it },
            onExpand = expand,
            cardModifier = cardModifier,
            cardInset = if (flat) 0.dp else cardInset,
            focusOnStart = focusCard,
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
        val selectors: @Composable () -> Unit = {
            if (chatting) {
                DisposableEffect(Unit) { onDispose { panel = androidx.compose.ui.geometry.Rect.Zero } }
                AssistantTranscript(
                    transcript = session.transcript,
                    running = session.running,
                    agent = followed,
                    onNew = ::newRequest,
                    onAction = act,
                    lastChat = last?.second,
                    onLastChat = { last?.let { openChat(it.first) } },
                    banner = { ErrorSnackbar(error, ::dismissError, Modifier.padding(bottom = 12.dp)) },
                    host = session.surfaces,
                    modifier = Modifier.fillMaxWidth().chipEnter(0).onGloballyPositioned { panel = it.boundsInRoot() },
                )
            } else if (!following) {
                if (!session.configured) NotSetUp(Modifier.chipEnter(0))
                // Gemini's chip above its card: here, the last chat.
                else if (last != null) at.quickme.prism.assistant.AssistControls(
                    lastChat = last.second, onLastChat = { openChat(last.first) }, onNew = null,
                    modifier = Modifier.chipEnter(0),
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
                modifier = Modifier.fillMaxWidth().chipEnter(0),
            )
        }
        val transition: FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> =
            if (reducedMotion) snap() else tween(FULL_MS, easing = EmphasizedEasing)
        val chatShape = androidx.compose.foundation.shape.RoundedCornerShape(CardRadius)
        CompositionLocalProvider(LocalOverlayAge provides { clock.value }) { SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = full,
                transitionSpec = {
                    if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(FULL_MS / 2, FULL_MS / 4)) togetherWith fadeOut(tween(FULL_MS / 3))
                },
                label = "full",
            ) { fullScreen ->
                // Both ends of the transition: the card, and the full-screen chat.
                val chat = Modifier.sharedBounds(
                    rememberSharedContentState(FullChatKey),
                    this@AnimatedContent,
                    boundsTransform = { _, _ -> transition },
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(chatShape),
                )
                if (!fullScreen) Box(Modifier.fillMaxSize()) {
                    InputCard(
                        cardModifier = chat,
                        expand = { showFull(true) },
                        selectors = selectors,
                        flat = false,
                        showError = !chatting,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).union(WindowInsets.statusBars))
                            .padding(top = 8.dp)
                            // Gemini's card: 40 dp from the sides (less on a narrow phone), 8 dp from the bottom, at most 696 dp wide.
                            .padding(horizontal = margin)
                            .padding(bottom = 8.dp)
                            .widthIn(max = 696.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Faded per drawing, not through an offscreen layer: a
                                // layer is clipped to the composer's bounds, and the
                                // card's shader draws 48 dp round it (its soft edge and
                                // dots), which Back or the swipe cut into a dotted box.
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                // Sinks back out, and draws back with the gesture; the
                                // card and chips bring their own entrance (Gemini's).
                                val l = leave.value
                                val b = progress()
                                alpha = (1f - l) * (1f - 0.35f * b)
                                translationY = l * lift
                                val s = 1f - 0.1f * b
                                scaleX = s
                                scaleY = s
                                transformOrigin = TransformOrigin(0.5f, 1f)
                        },
                    )
                } else HistoryChat(
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
                        InputCard(Modifier, null, {}, Modifier.fillMaxWidth().widthIn(max = 696.dp), flat = true, showError = true)
                    },
                    modifier = chat,
                    host = session.surfaces,
                )
            }
        } }
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
 * The placement profile the assistant's agents start with (Phone ›
 * Profiles › Actions › Assistant); the machine and the project are always
 * Prism's pick. Unset, the worker's own default model applies.
 */
internal object AssistantProfile {
    private const val KEY = "profile"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotEmpty() }

    fun set(context: Context, id: String?) {
        prefs(context).edit().apply { if (id.isNullOrEmpty()) remove(KEY) else putString(KEY, id) }.apply()
    }

    /** The launch's model: the profile on T3's placement instance, or null for the default. */
    fun model(context: Context): ModelChoice? = get(context)?.let { ProfileChoice(it).model }
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
    /** The card narrower than what is above it by this at each side. */
    cardInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Its field takes the focus and the keyboard as it appears. */
    focusOnStart: Boolean = false,
    selectors: @Composable () -> Unit,
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
    Column(modifier.semantics { paneTitle = "Prism" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            attach?.let { AttachMenu(it, Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
        }
        ErrorSnackbar(error, onDismissError, Modifier.padding(bottom = 8.dp))
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
    modifier: Modifier,
) {
    // Listening, Gemini first only pulses its mic; once speech begins, the bars.
    val listening = voice?.active == true && voice.speaking
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
    val dark = overlayDark()
    val plateColors = remember(colors, dark) { PlateColors(colors, dark) }
    // The card's own clock: it settles as it appears, and shimmers while sending.
    val opened = remember { android.os.SystemClock.uptimeMillis() }
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
                        onExpand()
                        return@draggable
                    }
                    androidx.compose.animation.core.animate(
                        pull, 0f,
                        animationSpec = if (animate) spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow) else snap(),
                    ) { value, _ -> pull = value }
                },
            )
            .then(
                if (flat) Modifier
                    .onSizeChanged { flatHeight = it.height }
                    .background(colors.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(flatRadius))
                else Modifier.geminiPlate(plateColors, CardRadius, ageMs = age, processing = { processing }),
            )
            // Gemini's handle: inside the card, 6 dp below its top edge, over
            // the plate and under what is in it. Full screen it is at the top.
            .drawBehind {
                if (flat) return@drawBehind
                val w = 32.dp.toPx()
                val h = 2.dp.toPx()
                drawRoundRect(
                    colors.outlineVariant.copy(alpha = colors.outlineVariant.alpha * PlateAppear.contentAlpha(age())),
                    topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2, 6.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h),
                )
            }
            // Taps on the card focus the field.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Type a request",
                onClick = onFocus,
            ),
    ) {
        Layout(
            modifier = Modifier.graphicsLayer {
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
                    if (menuOpen) 45f else 0f,
                    if (animate) tween(250, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(),
                    label = "plus",
                )
                IconButton(
                    onClick = { onPlus?.invoke() },
                    enabled = onPlus != null && !listening && !sending,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface, disabledContentColor = colors.onSurface),
                    modifier = Modifier.semantics { selected = menuOpen },
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
                        Modifier.height(66.dp).clickable(
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
                    modifier = Modifier.heightIn(max = 210.dp).padding(vertical = 12.dp)
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
                // once there is something to send, the mic giving way to it
                // while there is text (Gemini hides its mic then too).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val mic = onMic?.takeIf { !sending && (listening || waiting || text.isBlank()) }
                    if (mic != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (listening) ListeningStop(onClick = mic, animate = animate)
                        else MicButton(onClick = mic, pulsing = waiting, animate = animate)
                    }
                    SendSlot(
                        visible = (text.isNotBlank() || attachments.isNotEmpty() || sending) && !listening,
                        sending = sending, canSend = canSend, onSend = onSend, animate = animate,
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
            val natural = top + lerp(h1, h2)
            // Dragged up, the card grows with the finger: what is in it stays
            // at its bottom. In the shared transition it fills its bounds.
            val height = maxOf(natural + grow(), constraints.minHeight).coerceAtMost(
                if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
            )
            val below = height - natural
            layout(width, height) {
                attached.placeRelative(0, below)
                val fieldY = below + top + lerp((h1 - field.height) / 2, 0)
                field.placeRelative(lerp(x1, x2), fieldY)
                // At one row the buttons sit in the card's middle; at two, under the text.
                val buttonY = below + top + lerp((h1 - buttons) / 2, natural - top - pad - buttons)
                plus.placeRelative(pad, buttonY + (buttons - plus.height) / 2)
                trailing.placeRelative(width - pad - trailing.width, buttonY + (buttons - trailing.height) / 2)
            }
        }
    }
}

/** Gemini's combined button's slot (Live there): send, or the progress while sending; empty with nothing to send. */
@Composable
private fun SendSlot(visible: Boolean, sending: Boolean, canSend: Boolean, onSend: () -> Unit, animate: Boolean) {
    val colors = MaterialTheme.colorScheme
    // Takes no room while hidden, so the mic is at the card's end.
    AnimatedVisibility(
        visible = visible,
        enter = if (animate) androidx.compose.animation.expandHorizontally(expandFrom = Alignment.Start) + scaleIn(initialScale = 0.6f) + fadeIn() else EnterTransition.None,
        exit = if (animate) androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.Start) + scaleOut(targetScale = 0.6f) + fadeOut() else ExitTransition.None,
    ) { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
