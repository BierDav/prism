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
// Predictive Back and an upward swipe on the overlay dismiss it; the
// navigation bar stays, and the live app remains underneath. The composer
// alone asks: an agent starts on the machine and project Prism picks from the
// request, with the assistant's placement profile (Phone › Profiles ›
// Actions). The composer sends its request directly after unlock. While the
// keyboard is up or an answer shows, the card spans the screen, as Gemini's.
//
// The card's + opens Gemini's attach menu (AssistAttach.kt): a photo from the
// camera, the screen under the overlay, photos from the Photo Picker, any
// file. What is attached shows at the top of the card and goes with the next
// send (assistant/AttachmentReader.kt, assistant/PhoneScreenCapture.kt).

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import at.quickme.prism.assistant.FollowedAgent
import at.quickme.prism.assistant.core.HandoffCard
import at.quickme.prism.assistant.Attached
import at.quickme.prism.assistant.AttachmentReader
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
                    onExpand = { AssistHistory.open(this@AssistActivity) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Assist(
    reducedMotion: Boolean,
    onClose: () -> Unit,
    unlock: suspend () -> Boolean,
    capture: PhoneScreenCapture,
    /** Before a picker or the camera opens over the overlay. */
    /** A picker or the camera is about to cover the overlay (true), or couldn't (false). */
    onExternal: (Boolean) -> Unit,
    /** The card dragged up: the full-screen chat history. */
    onExpand: () -> Unit,
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
    val session = remember { AssistantSession.forOverlay(context, capture) }
    DisposableEffect(session) { onDispose { session.detach() } }
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
    var cameraShot by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = cameraShot
        cameraShot = null
        if (taken && shot != null) attachUris(listOf(shot))
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
            pick {
                val shot = CameraShots.next(context)
                cameraShot = shot
                camera.launch(shot)
            }
        },
        screen = {
            menuOpen = false
            if (attached.size >= AttachmentRules.MAX_ATTACHMENTS) {
                sendError = AttachmentRules.tooMany()
            } else {
                reading++
                scope.launch {
                    try {
                        val shot = capture.captureAttached(preferInvocation = true)
                        if (shot != null) attach(listOf(shot)) else sendError = "Couldn't capture the screen."
                    } finally {
                        reading--
                    }
                }
            }
        },
        photos = {
            pick { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        },
        files = { pick { filePicker.launch(arrayOf("*/*")) } },
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
    // With the + menu open, Back closes the menu first.
    BackHandler(enabled = menuOpen) { menuOpen = false }

    val corners = rememberScreenCorners()
    // The glow in Gemini's energy colour: on a near-grey palette the primary
    // alone turned its rim and waves into a grey vignette round the screen.
    val primary = energyColor(MaterialTheme.colorScheme.primary)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Where the input card is, for the halo round it.
    var plate by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
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
                    fun halo(h: HaloScrim) = drawIntoCanvas { c ->
                        h.draw(
                            c.nativeCanvas, size.width, size.height, plate.left, plate.top, plate.right, plate.bottom,
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
        // Gemini's card spans the screen while the keyboard is up or an
        // answer shows, and sits back in its margins otherwise.
        val wide = WindowInsets.isImeVisible || following || chatting || menuOpen
        val margin by androidx.compose.animation.core.animateDpAsState(
            if (wide) WideMargin else sideMargin(LocalConfiguration.current.screenWidthDp),
            if (reducedMotion) snap() else tween(350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
            label = "margin",
        )
        CompositionLocalProvider(LocalOverlayAge provides { clock.value }) { AssistComposer(
            text = text,
            onText = { text = it; sendError = null },
            canSend = (if (session.configured) !session.running else starting == null) && reading == 0,
            sending = sending || session.running,
            onSend = { send() },
            animate = !reducedMotion,
            error = sendError ?: voice.error,
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
            onExpand = onExpand,
            selectors = {
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
                if (chatting) {
                    AssistantTranscript(
                        transcript = session.transcript,
                        running = session.running,
                        agent = followed,
                        onNew = ::newRequest,
                        onEdit = session::edit,
                        modifier = Modifier.fillMaxWidth().chipEnter(0),
                    )
                    return@AssistComposer
                }
                if (!following) {
                    if (!session.configured) NotSetUp(Modifier.chipEnter(0))
                    return@AssistComposer
                }
                AgentConversation(
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
            },
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
        ) }
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
        /** Opened again within this, the overlay shows the same agent. */
        private const val RESUME_MS = 10 * 60 * 1000L
    }
}

/**
 * Where the camera writes the photo it takes for the overlay: a file in
 * Prism's cache, shared with the camera app through Prism's FileProvider
 * (res/xml/attach_paths.xml); the ones before are deleted.
 */
internal object CameraShots {
    fun next(context: Context): android.net.Uri {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".attach", file)
    }
}

/** The magic pick, as machine and project: Prism chooses from the request. */
private const val AUTO = "auto"

/** The card's margin while it spans the screen. */
private val WideMargin = 8.dp
/** Floating selectors and the text field move together above the keyboard. */
@Composable
internal fun AssistComposer(
    text: String,
    onText: (String) -> Unit,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    animate: Boolean,
    error: String? = null,
    onReload: (() -> Unit)? = null,
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
    /** The card dragged up past [ExpandDrag] (or flung up). */
    onExpand: () -> Unit = {},
    selectors: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
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
        error?.let { message ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                onReload?.let { TextButton(onClick = it) { Text("Reload choices") } }
            }
        }
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** How far the card must be dragged up to expand. */
private val ExpandDrag = 72.dp

/** Or how fast it must be flung up (dp/s), from at least [FlingMinDrag]. */
private const val ExpandFlingDpPerS = 1200f
private val FlingMinDrag = 16.dp

/** How far the card follows the finger at most: it lags behind, then stops. */
private val DragLift = 32.dp

/** The card's lift for [pull] px of finger travel up: a rubber band to [max] px. */
private fun dragLift(pull: Float, max: Float) = if (max <= 0f) 0f else max * (1f - kotlin.math.exp(-pull / max))

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
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    // Listening, Gemini first only pulses its mic; once speech begins, the bars.
    val listening = voice?.active == true && voice.speaking
    val waiting = voice?.active == true && !voice.speaking
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val typing = focused && WindowInsets.isImeVisible
    val twoRows = !listening && (typing || text.isNotEmpty() || attachments.isNotEmpty())
    val rows by animateFloatAsState(
        if (twoRows) 1f else 0f,
        if (animate) tween(350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(),
        label = "rows",
    )
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
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp)
    val age = { (now - opened).toFloat() }
    // Dragged up, the card follows the finger a little and expands past a
    // threshold (or on a fling up); below it, it springs back.
    val density = LocalDensity.current
    val view = LocalView.current
    var pull by remember { mutableFloatStateOf(0f) }
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
            .graphicsLayer { translationY = -dragLift(pull, DragLift.toPx()) }
            .onGloballyPositioned { onCard(it.boundsInRoot()) }
            .draggable(
                drag,
                Orientation.Vertical,
                onDragStarted = { settling?.cancel() },
                onDragStopped = { velocity ->
                    settling = coroutineContext[kotlinx.coroutines.Job]
                    val (threshold, fling, least) = with(density) {
                        Triple(ExpandDrag.toPx(), ExpandFlingDpPerS.dp.toPx(), FlingMinDrag.toPx())
                    }
                    if (pull >= threshold || (velocity <= -fling && pull >= least)) onExpand()
                    androidx.compose.animation.core.animate(
                        pull, 0f,
                        animationSpec = if (animate) spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow) else snap(),
                    ) { value, _ -> pull = value }
                },
            )
            .geminiPlate(plateColors, CardRadius, ageMs = age, processing = { processing })
            // Gemini's handle: inside the card, 6 dp below its top edge, over
            // the plate and under what is in it.
            .drawBehind {
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
                } else BasicTextField(
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
                                Text(placeholder, style = textStyle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onMic != null && !listening && !sending) {
                        MicButton(onClick = onMic, pulsing = waiting, animate = animate)
                    }
                    AnimatedVisibility(
                        visible = text.isNotBlank() || attachments.isNotEmpty() || sending || listening,
                        enter = if (animate) scaleIn(initialScale = 0.6f) + fadeIn() else EnterTransition.None,
                        exit = if (animate) scaleOut(targetScale = 0.6f) + fadeOut() else ExitTransition.None,
                    ) {
                        when {
                            sending -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    Modifier.size(24.dp).semantics { contentDescription = "Sending" },
                                    color = colors.onSurfaceVariant, strokeWidth = 2.dp,
                                )
                            }
                            listening -> ListeningStop(onClick = { onMic?.invoke() }, animate = animate)
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
                    }
                }
                // Always a child, even with nothing attached (then 0 tall).
                Box { AttachmentRow(attachments, onRemove, animate) }
            },
        ) { measurables, constraints ->
            val (plusM, fieldM, trailingM) = measurables
            val attachedM = measurables[3]
            val width = constraints.maxWidth
            val pad = 8.dp.roundToPx()
            val gap = 4.dp.roundToPx()
            // In two rows the text keeps clear of the card's rounded corners.
            val textInset = 24.dp.roundToPx()
            // Gemini's card is 80 dp tall at one row (measured on the owner's
            // Pixel): 48 dp buttons with 16 dp round them, room for the handle.
            val minHeight = 80.dp.roundToPx()
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
            val h1 = maxOf(minHeight, field.height + 2 * pad, buttons + 2 * pad)
            val h2 = field.height + buttons + pad
            val height = top + lerp(h1, h2)
            layout(width, height) {
                attached.placeRelative(0, 0)
                val fieldY = top + lerp((h1 - field.height) / 2, 0)
                field.placeRelative(lerp(x1, x2), fieldY)
                // At one row the buttons sit in the card's middle; at two, under the text.
                val buttonY = top + lerp((h1 - buttons) / 2, height - top - pad - buttons)
                plus.placeRelative(pad, buttonY + (buttons - plus.height) / 2)
                trailing.placeRelative(width - pad - trailing.width, buttonY + (buttons - trailing.height) / 2)
            }
        }
    }
}
