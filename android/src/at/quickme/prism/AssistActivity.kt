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
// navigation bar stays, and the live app remains underneath. Above the
// composer, a row of chips each for the profile, the machine and the project,
// most recently used first and the last one used picked; machines and
// projects can be filtered. Projects are every machine's: one the machine
// lacks is cloned there before the agent starts. Pinned at each row's right
// edge: the magic pick (the machine by power and load, the project by the
// server's model from the request), for projects no project, and search. The composer sends its request directly after
// unlock.

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
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import at.quickme.prism.ui.GeminiIcons
import at.quickme.prism.ui.HostColor
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.ProjectGlyph
import at.quickme.prism.ui.rememberScreenCorners
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import org.json.JSONArray
import org.json.JSONObject

class AssistActivity : ComponentActivity() {
    private var session: PrismAssistantSession? = null

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
                )
                }
            }
        }
    }

    override fun onDestroy() {
        session?.detach(this)
        super.onDestroy()
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

/** The input card's corners: a pill at one row; taller, it keeps these. */
private val CardRadius = 32.dp

/** The halo's rounding round the card. */
private val PlateRadius = 28.dp

/** The agent's card above the composer, in the chips' look. */
private val ConversationRadius = 24.dp
private val ConversationShape = androidx.compose.foundation.shape.RoundedCornerShape(ConversationRadius)

private fun phase(t: Float, start: Float, length: Float) = ((t - start) / length).coerceIn(0f, 1f)

@Composable
private fun Assist(
    reducedMotion: Boolean,
    onClose: () -> Unit,
    unlock: suspend () -> Boolean,
) {
    // Milliseconds since the overlay appeared, to the glow's end.
    val clock = remember { Animatable(if (reducedMotion) GLOW_MS else 0f) }
    val leave = remember { Animatable(0f) }
    // Back, or the swipe up from the bottom, as far as the finger has gone (0..1).
    val back = remember { Animatable(0f) }
    var swipe by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val memory = remember { AssistMemory(context) }
    // The draft (prompt, picks, rows' order and scroll) lasts until a send;
    // the lists come from the phone's cache at once and refresh behind.
    val saved = remember { memory.draft() }
    var text by remember { mutableStateOf(saved.text) }
    var target by remember { mutableStateOf(saved.target ?: memory.recent(AssistMemory.MACHINES).firstOrNull() ?: "cluster") }
    var modelKey by remember { mutableStateOf(saved.model) }
    var project by remember { mutableStateOf(saved.project) }
    // What each row picked as the draft began: it leads the row, then the
    // recently used, so the pick starts at the right edge and a tap moves nothing.
    var leadTarget by remember { mutableStateOf(saved.lead[ROW_MACHINE] ?: target) }
    var leadModel by remember { mutableStateOf(saved.lead[ROW_MODEL]) }
    var leadProject by remember { mutableStateOf(saved.lead[ROW_PROJECT]) }
    val scrolls = remember { mutableStateMapOf<String, List<Int>>().apply { putAll(saved.scroll) } }
    var machines by remember { mutableStateOf(memory.cached(AssistMemory.CACHE_MACHINES, Machines.serializer())) }
    var machinesError by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf(memory.cached(AssistMemory.CACHE_PROJECTS, ProjectCatalog.serializer())) }
    var options by remember { mutableStateOf(memory.cached(AssistMemory.options(target), AgentOptions.serializer())) }
    var optionsError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    // The agent this overlay follows once a request is sent (the last one,
    // if it was a few minutes ago), its live activity, and follow-ups sent
    // that its thread doesn't show yet.
    var agentId by remember { mutableStateOf(memory.conversation()) }
    var agentTitle by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf<AgentActivity?>(null) }
    var activityError by remember { mutableStateOf<String?>(null) }
    val pending = remember { mutableStateListOf<String>() }
    var refresh by remember { mutableIntStateOf(0) }
    val voice = remember { VoiceInput(context) }
    var editVoice by remember { mutableStateOf(false) }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    var sendError by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<Pair<AssistantRequest, String>?>(null) }
    val model = options?.launchModels()?.firstOrNull { it.key == modelKey }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    fun progress(): Float = if (reducedMotion) 0f else maxOf(back.value, swipe)

    LaunchedEffect(Unit) {
        if (!reducedMotion) clock.animateTo(GLOW_MS, tween(GLOW_MS.toInt(), easing = LinearEasing))
    }

    /** The project ids there are, every machine's (or the target's until those came). */
    fun projectIds(): List<String> =
        catalog?.projects?.map { it.id } ?: options?.projects.orEmpty().map { it.id }

    /** Whether the project can be worked on from the target: a repository, or a folder there. */
    fun here(id: String, o: AgentOptions): Boolean = target == AUTO ||
        catalog?.projects?.firstOrNull { it.id == id }
            ?.let { it.repository || it.machines.isEmpty() || o.machine in it.machines } != false

    /** Keeps the picks that are still there; otherwise the last used, or the target's default. */
    fun settle() {
        val o = options ?: return
        val models = o.launchModels()
        if (models.none { it.key == modelKey }) {
            modelKey = memory.recent(AssistMemory.MODELS).firstOrNull { key -> models.any { it.key == key } }
                ?: models.firstOrNull { it.key == o.defaultModel.key }?.key
                ?: models.firstOrNull()?.key
        }
        if (leadModel == null) leadModel = modelKey
        val ids = projectIds()
        val current = project
        if (current == null || current != AUTO && (current !in ids || !here(current, o))) {
            project = memory.recent(AssistMemory.PROJECTS).firstOrNull { it == AUTO || it in ids && here(it, o) }
                ?: o.defaultProject?.takeIf { it in ids } ?: ids.lastOrNull()
        }
        if (leadProject == null) leadProject = project
    }

    LaunchedEffect(target, reload) {
        optionsError = null
        settle()
        try {
            val fresh = PrismApi.call(context) { agentOptions(target) }
            memory.cache(AssistMemory.options(target), AgentOptions.serializer(), fresh)
            options = fresh
            settle()
        } catch (e: PrismError) {
            if (options == null) optionsError = e.message
        }
    }

    LaunchedEffect(Unit) {
        try {
            val fresh = PrismApi.call(context) { projects() }
            memory.cache(AssistMemory.CACHE_PROJECTS, ProjectCatalog.serializer(), fresh)
            catalog = fresh
            settle()
        } catch (_: PrismError) {
            // The cached list, or the target's own projects, stay.
        }
    }

    fun pickTarget(next: String) {
        if (target == next) return
        options = memory.cached(AssistMemory.options(next), AgentOptions.serializer())
        optionsError = null
        sendError = null
        target = next
    }

    fun loadMachines() {
        machinesError = null
        scope.launch {
            try {
                val list = PrismApi.call(context) { machines() }
                memory.cache(AssistMemory.CACHE_MACHINES, Machines.serializer(), list)
                machines = list
                // The machine last used can be gone or asleep now.
                if (target != AUTO && list.choices().none { it.key == target }) {
                    if (leadTarget == target) leadTarget = "cluster"
                    pickTarget("cluster")
                }
            } catch (e: PrismError) {
                if (machines == null) machinesError = e.message
            }
        }
    }

    LaunchedEffect(Unit) { loadMachines() }

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

    fun newRequest() {
        agentId = null
        activity = null
        activityError = null
        pending.clear()
        sendError = null
        memory.forgetConversation()
    }

    LaunchedEffect(text, target, modelKey, project, leadTarget, leadModel, leadProject, scrolls.toMap()) {
        memory.saveDraft(
            AssistDraft(
                text, target, modelKey, project,
                buildMap {
                    put(ROW_MACHINE, leadTarget)
                    leadModel?.let { put(ROW_MODEL, it) }
                    leadProject?.let { put(ROW_PROJECT, it) }
                },
                scrolls.toMap(),
            ),
        )
    }

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

    /** A follow-up into the agent's thread. */
    fun reply(id: String, request: String) {
        val requestId = previous?.takeIf { it.first.text == request && it.first.target == id }?.second ?: PrismClient.requestId()
        previous = AssistantRequest(id, null, "", request) to requestId
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
                pending.add(request)
                text = ""
                val agent = PrismApi.call(context) { sendToAgent(id, request, requestId) }
                activity = activity?.copy(agent = agent) ?: AgentActivity(agent)
                memory.conversation(id)
                refresh++
            } catch (e: PrismError) {
                pending.remove(request)
                text = request
                sendError = e.message
            } finally {
                sending = false
            }
        }
    }

    /** Sends [spoken] or the typed text: a new agent, or a follow-up to the one shown. */
    fun send(spoken: String? = null) {
        val request = (spoken ?: text).trim()
        if (request.isEmpty() || sending || leave.isRunning || leave.value > 0f) return
        agentId?.let { return reply(it, request) }
        val m = model ?: return
        val p = project ?: return
        if (options == null) return

        val payload = AssistantRequest(target, m, p, request)
        val requestId = previous?.takeIf { it.first == payload }?.second ?: PrismClient.requestId()
        previous = payload to requestId
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
                    startAgent("do", payload.target, payload.text, requestId, m, payload.project)
                }
                PrismPush.changed()
                memory.used(AssistMemory.MODELS, m.key)
                memory.used(AssistMemory.MACHINES, payload.target)
                memory.used(AssistMemory.PROJECTS, payload.project)
                memory.clearDraft()
                text = ""
                // The overlay stays and follows the agent.
                pending.clear()
                pending.add(request)
                agentTitle = request
                activity = AgentActivity(agent)
                agentId = agent.id
                memory.conversation(agent.id)
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

    val corners = rememberScreenCorners()
    val primary = MaterialTheme.colorScheme.primary
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
                    onClick = ::close,
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
        val following = agentId
        CompositionLocalProvider(LocalOverlayAge provides { clock.value }) { AssistComposer(
            text = text,
            onText = { text = it; sendError = null },
            canSend = following != null || options != null && model != null && project != null,
            sending = sending,
            onSend = { send() },
            animate = !reducedMotion,
            error = (if (following == null) optionsError else null) ?: sendError ?: voice.error,
            onReload = if (following == null && optionsError != null) ({ reload++ }) else null,
            placeholder = if (following != null) "Reply to the agent" else "Ask Prism",
            voice = voice,
            onMic = ::listen,
            onWave = ::listenToEdit,
            onNew = if (following != null) ::newRequest else null,
            onCard = { plate = it },
            selectors = {
                if (following != null) {
                    AgentConversation(
                        title = agentTitle.ifEmpty { "Agent" },
                        activity = activity,
                        pending = pending,
                        error = activityError,
                        shape = ConversationShape,
                        onOpen = if (activity?.agent?.link != null || activity?.agent?.t3 != null) ::openThread else null,
                        onStop = ::stopAgent,
                        modifier = Modifier.fillMaxWidth().chipEnter(0),
                    )
                    return@AssistComposer
                }
                val choices = options
                val models = choices?.launchModels().orEmpty()
                val machineList = machines
                val profiles = choices?.profiles.orEmpty()
                AssistChips(
                    row = 2,
                    kind = if (profiles.isNotEmpty() || choices == null) "Profile" else "Model",
                    choices = models.map { m ->
                        AssistChoice(m.key, profiles.firstOrNull { it.model == m }?.let { it.label.ifEmpty { it.id } } ?: m.shortName)
                    }.byRecent(leadModel, memory.recent(AssistMemory.MODELS)),
                    selected = modelKey,
                    enabled = !sending,
                    onPick = { key -> modelKey = key; sendError = null },
                    scroll = scrolls[ROW_MODEL],
                    onScroll = { scrolls[ROW_MODEL] = it },
                )
                AssistChips(
                    row = 1,
                    kind = "Machine",
                    choices = machineList?.choices()?.byRecent(leadTarget, memory.recent(AssistMemory.MACHINES))
                        ?: listOf(machineChoice(target, if (target == "cluster") choices?.machine ?: "Cluster" else target)),
                    selected = target,
                    enabled = !sending,
                    searchable = true,
                    status = machinesError,
                    onRetry = if (machinesError != null) ::loadMachines else null,
                    onPick = ::pickTarget,
                    pinned = listOf(
                        PinnedChoice(
                            AUTO, "Pick the machine: the strongest one that is not busy", PrismIcons.Sparkle,
                            choices?.picked?.let { if (it == "cluster") choices.machine else it } ?: "Auto",
                        ),
                    ),
                    scroll = scrolls[ROW_MACHINE],
                    onScroll = { scrolls[ROW_MACHINE] = it },
                )
                val projectList = (catalog?.projects?.map { it.choice() }
                    ?: choices?.projects.orEmpty().map { it.choice() }).filter { it.key.isNotEmpty() }
                AssistChips(
                    row = 0,
                    kind = "Project",
                    choices = projectList.byRecent(leadProject, memory.recent(AssistMemory.PROJECTS)),
                    selected = project,
                    enabled = !sending,
                    searchable = true,
                    onPick = { id ->
                        project = id
                        sendError = null
                        // A folder without a git remote is on its machine only: go there.
                        val home = catalog?.projects?.firstOrNull { it.id == id }
                            ?.takeIf { !it.repository && it.machines.isNotEmpty() }?.machines
                        if (home != null && target != AUTO && choices?.machine !in home) {
                            val host = home.first()
                            pickTarget(if (host == machineList?.cluster?.runsOn) "cluster" else host)
                        }
                    },
                    scroll = scrolls[ROW_PROJECT],
                    onScroll = { scrolls[ROW_PROJECT] = it },
                    pinned = listOf(
                        PinnedChoice("", "No project: a scratch folder", PrismIcons.Chat, "No project"),
                        PinnedChoice(AUTO, "Pick the project from the request", PrismIcons.Sparkle, "Auto"),
                    ),
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                // Gemini's card: 40 dp from the sides (less on a narrow phone), 8 dp from the bottom, at most 696 dp wide.
                .padding(horizontal = sideMargin(LocalConfiguration.current.screenWidthDp))
                .padding(bottom = 8.dp)
                .widthIn(max = 696.dp)
                .fillMaxWidth()
                .graphicsLayer {
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

/** A request as sent, to send it again with the same ID: [target] is the agent's ID for a follow-up. */
private data class AssistantRequest(val target: String, val model: ModelChoice?, val project: String, val text: String)

/** The model of each choice the assistant offers: T3's placement profiles, or without any its models. */
internal fun AgentOptions.launchModels(): List<ModelChoice> =
    if (profiles.isNotEmpty()) profiles.map { it.model } else models

private val ModelChoice.key get() = "$instanceId|$model"

/** The machines an agent can start on now; the cluster's host first, as the cluster. */
private fun Machines.choices(): List<AssistChoice> {
    val host = cluster?.runsOn.orEmpty()
    return listOf(machineChoice("cluster", host.ifEmpty { "Cluster" })) +
        machines.filter { it.reachable && it.name != host }.map { machineChoice(it.name, it.name) }
}

/** A machine in its colour, as everywhere in Prism. */
internal fun machineChoice(key: String, host: String) = AssistChoice(
    key, host, accent = HostColor.of(host),
    leading = { Icon(PrismIcons.Server, null, Modifier.size(ChipIconSize)) },
)

/** A project with its T3 icon; the scratch folder has none. */
internal fun ProjectChoice.choice() = projectChoice(id, name, icon)

internal fun CatalogProject.choice() = projectChoice(id, name, icon)

private fun projectChoice(id: String, name: String, icon: ProjectIcon?) = AssistChoice(
    id, name,
    leading = if (id.isEmpty()) null else ({ ProjectGlyph(name, icon, size = ChipIconSize) }),
)

/** [lead] first, then the most recently used; the rest as the server lists them. */
internal fun List<AssistChoice>.byRecent(lead: String?, recent: List<String>): List<AssistChoice> {
    val order = if (lead == null) recent else listOf(lead) + recent
    return sortedBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

/** The assistant's choices on this phone, most recently used first. */
internal class AssistMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    /** The last answer for [key], or null. */
    fun <T> cached(key: String, serializer: KSerializer<T>): T? = try {
        prefs.getString("cache:$key", null)?.let { PrismClient.json.decodeFromString(serializer, it) }
    } catch (_: Exception) {
        null
    }

    fun <T> cache(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit().putString("cache:$key", PrismClient.json.encodeToString(serializer, value)).apply()
    }

    fun draft(): AssistDraft = try {
        val o = JSONObject(prefs.getString(DRAFT, null) ?: return AssistDraft())
        fun str(name: String) = if (o.has(name) && !o.isNull(name)) o.getString(name) else null
        fun obj(name: String) = o.optJSONObject(name) ?: JSONObject()
        val lead = obj("lead").let { l -> l.keys().asSequence().associateWith { l.getString(it) } }
        val scroll = obj("scroll").let { l ->
            l.keys().asSequence().associateWith { k -> l.getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } } }
        }
        AssistDraft(o.optString("text"), str("target"), str("model"), str("project"), lead, scroll)
    } catch (_: org.json.JSONException) {
        AssistDraft()
    }

    fun saveDraft(d: AssistDraft) {
        val o = JSONObject()
            .put("text", d.text)
            .put("target", d.target ?: JSONObject.NULL)
            .put("model", d.model ?: JSONObject.NULL)
            .put("project", d.project ?: JSONObject.NULL)
            .put("lead", JSONObject(d.lead))
            .put("scroll", JSONObject().apply { d.scroll.forEach { (k, v) -> put(k, JSONArray(v)) } })
        prefs.edit().putString(DRAFT, o.toString()).apply()
    }

    fun clearDraft() {
        prefs.edit().remove(DRAFT).apply()
    }

    /** The agent the overlay followed last, if that was less than [RESUME_MS] ago. */
    fun conversation(): String? {
        val at = prefs.getLong(CONVERSATION_AT, 0L)
        if (System.currentTimeMillis() - at > RESUME_MS) return null
        return prefs.getString(CONVERSATION, null)
    }

    fun conversation(id: String) {
        prefs.edit().putString(CONVERSATION, id).putLong(CONVERSATION_AT, System.currentTimeMillis()).apply()
    }

    fun forgetConversation() {
        prefs.edit().remove(CONVERSATION).remove(CONVERSATION_AT).apply()
    }

    fun recent(kind: String): List<String> = try {
        val list = JSONArray(prefs.getString(kind, null) ?: return emptyList())
        List(list.length()) { list.getString(it) }
    } catch (_: org.json.JSONException) {
        emptyList()
    }

    fun used(kind: String, key: String) {
        val list = (listOf(key) + recent(kind).filter { it != key }).take(KEEP)
        prefs.edit().putString(kind, JSONArray(list).toString()).apply()
    }

    companion object {
        const val MODELS = "models"
        const val MACHINES = "machines"
        /** A repository's ID is the same on every machine. */
        const val PROJECTS = "projects:all"
        const val CACHE_MACHINES = "machines"
        const val CACHE_PROJECTS = "projects"
        fun options(target: String) = "options:$target"
        private const val DRAFT = "draft"
        private const val CONVERSATION = "conversation"
        private const val CONVERSATION_AT = "conversation_at"
        /** Opened again within this, the overlay shows the same agent. */
        private const val RESUME_MS = 10 * 60 * 1000L
        private const val KEEP = 30
    }
}

/** What the overlay shows until a send: kept across openings. */
internal data class AssistDraft(
    val text: String = "",
    val target: String? = null,
    val model: String? = null,
    val project: String? = null,
    /** Each row's pick when the draft began, which leads it. */
    val lead: Map<String, String> = emptyMap(),
    /** Each row's first visible chip and its offset. */
    val scroll: Map<String, List<Int>> = emptyMap(),
)

/** The magic pick, as machine or project. */
private const val AUTO = "auto"

private const val ROW_MODEL = "model"
private const val ROW_MACHINE = "machine"
private const val ROW_PROJECT = "project"

/** Gemini's suggestion chips: 48 dp tall, 16 dp corners, the card's white, 14 sp text. */
private val ChipHeight = 48.dp
private val ChipIconSize = 20.dp
private val ChipRadius = 24.dp
private val ChipShape = androidx.compose.foundation.shape.RoundedCornerShape(ChipRadius)
private val ChipText = androidx.compose.ui.text.TextStyle(fontFamily = at.quickme.prism.ui.TextFamily, fontSize = 14.sp, lineHeight = 20.sp)

/** Gemini's chips are black or white whatever the palette; picked ones here are filled. */
@Composable
private fun Modifier.chipLook(fill: Color? = null): Modifier {
    val colors = MaterialTheme.colorScheme
    val dark = overlayDark()
    return geminiChip(fill ?: if (dark) Color.Black else Color.White, colors.primary, dark, ChipRadius)
}

/**
 * A chip as Gemini draws its suggestions: the card's colour, no outline or
 * shadow, 12 dp before and 16 dp after. Picked, it is Gemini's light blue,
 * or [accent] (a machine's colour) with white on it.
 */
@Composable
private fun GeminiChip(
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    accent: Color?,
    leading: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fill = when {
        !selected -> null
        accent != null -> accent
        else -> colors.primaryContainer
    }
    val content = when {
        !selected -> colors.onSurface
        accent != null -> Color.White
        else -> colors.onPrimaryContainer
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ChipShape,
        color = Color.Transparent,
        contentColor = content,
        modifier = modifier.height(ChipHeight).chipLook(fill).semantics { this.selected = selected }
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                CompositionLocalProvider(LocalContentColor provides if (selected || accent == null) content else accent) {
                    leading()
                }
                Box(Modifier.width(8.dp))
            }
            label()
        }
    }
}
private val PinnedWidth = 48.dp

/** A choice pinned at the row's right edge as an icon; its [label] shows while picked. */
internal data class PinnedChoice(val key: String, val description: String, val icon: ImageVector, val label: String? = null)

/** A chip: [accent] fills it while picked and tints its [leading] icon. */
internal data class AssistChoice(
    val key: String,
    val label: String,
    val accent: Color? = null,
    val leading: (@Composable () -> Unit)? = null,
)

/**
 * One row of chips from the right edge leftwards, scrolling sideways. A
 * [searchable] row keeps its search chip at the right edge (it becomes a
 * field filtering the row; Done picks the first match), and the chips fade
 * out as they scroll under it.
 */
@Composable
internal fun AssistChips(
    kind: String,
    choices: List<AssistChoice>,
    selected: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
    searchable: Boolean = false,
    status: String? = null,
    onRetry: (() -> Unit)? = null,
    scroll: List<Int>? = null,
    onScroll: (List<Int>) -> Unit = {},
    pinned: List<PinnedChoice> = emptyList(),
    /** Which row this is from the bottom, for the order the chips come in. */
    row: Int = 0,
) {
    var query by remember { mutableStateOf<String?>(null) }
    val shown = query?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { q -> choices.filter { it.label.contains(q, ignoreCase = true) } } ?: choices
    val state = rememberLazyListState(scroll?.getOrNull(0) ?: 0, scroll?.getOrNull(1) ?: 0)
    val index = shown.indexOfFirst { it.key == selected }
    LaunchedEffect(state) {
        snapshotFlow { listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
            .drop(1)
            .collectLatest {
                delay(300)
                onScroll(it)
            }
    }
    // A row scrolled where it was last time stays there as it opens.
    var restored by remember { mutableStateOf(scroll != null) }
    // The width of what is pinned at the right edge, which the chips pass under.
    var pinnedWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Bring the picked chip into view, as when the last one used is far along.
    LaunchedEffect(index) {
        if (restored) {
            restored = false
            return@LaunchedEffect
        }
        if (index < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (seen == null || seen.offset < info.viewportStartOffset || seen.offset + seen.size > info.viewportEndOffset) {
            state.animateScrollToItem(index)
        }
    }
    fun pick(key: String) {
        query = null
        onPick(key)
    }
    val gap = 8.dp
    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = kind }
                // Chips fade out under the search chip, and at the left edge
                // while more lie beyond it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 16.dp.toPx()
                    if (pinnedWidth > 0) {
                        val under = size.width - pinnedWidth
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Black, 1f to Color.Transparent,
                                startX = under - gap.toPx(), endX = under + minOf(fade, pinnedWidth / 2f),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (state.canScrollForward) {
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
            contentPadding = PaddingValues(end = if (pinnedWidth > 0) with(density) { pinnedWidth.toDp() } + gap else 0.dp),
            reverseLayout = true,
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(shown, key = { _, it -> "choice:" + it.key }) { i, choice ->
                GeminiChip(
                    modifier = Modifier.chipEnter(row + 1 + minOf(i, 5)),
                    selected = choice.key == selected,
                    onClick = { pick(choice.key) },
                    enabled = enabled,
                    accent = choice.accent,
                    leading = choice.leading,
                ) {
                    Text(
                        choice.label,
                        style = ChipText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
            if (status != null) item(key = "status") {
                Surface(
                    onClick = { onRetry?.invoke() },
                    enabled = onRetry != null,
                    shape = ChipShape,
                    color = Color.Transparent,
                    modifier = Modifier.height(ChipHeight).chipLook(),
                ) {
                    Text(
                        if (onRetry != null) "$status · Retry" else status,
                        Modifier.wrapContentHeight().padding(horizontal = 14.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (onRetry != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (pinned.isNotEmpty() || searchable) {
            Row(
                Modifier.align(Alignment.CenterEnd).onSizeChanged { pinnedWidth = it.width }.chipEnter(row),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // While searching, the field takes the room of the others.
                if (query == null) pinned.forEach { p ->
                    PinnedButton(p, selected = p.key == selected, enabled = enabled, onClick = { pick(p.key) })
                }
                if (searchable) {
                    ChipSearch(
                        kind = kind,
                        query = query,
                        onQuery = { query = it },
                        enabled = enabled,
                        onDone = { shown.firstOrNull()?.let { pick(it.key) } },
                    )
                }
            }
        }
    }
}

/** An icon at the row's edge; picked, it is filled and shows its label. */
@Composable
private fun PinnedButton(choice: PinnedChoice, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ChipShape,
        color = Color.Transparent,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.height(ChipHeight).chipLook(if (selected) colors.primaryContainer else null).widthIn(min = PinnedWidth).semantics {
            contentDescription = choice.description
            this.selected = selected
        },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(choice.icon, null, Modifier.size(ChipIconSize))
            if (selected && choice.label != null) {
                Text(
                    choice.label, Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
                    style = ChipText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The search chip, or once tapped the field it turns into. */
@Composable
private fun ChipSearch(
    kind: String,
    query: String?,
    onQuery: (String?) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    Surface(
        onClick = { if (query == null) onQuery("") else focus.requestFocus() },
        enabled = enabled,
        shape = ChipShape,
        color = Color.Transparent,
        modifier = modifier.height(ChipHeight).chipLook().widthIn(min = PinnedWidth)
            .semantics { if (query == null) contentDescription = "Filter ${kind.lowercase()}s" },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(ChipIconSize), tint = colors.onSurface)
            if (query != null) {
                BasicTextField(
                    value = query,
                    onValueChange = { onQuery(it) },
                    singleLine = true,
                    textStyle = ChipText.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.padding(start = 8.dp).width(140.dp).focusRequester(focus)
                        .semantics { contentDescription = "Filter ${kind.lowercase()}s" },
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(kind, style = ChipText, color = colors.outline)
                            field()
                        }
                    },
                )
                Icon(
                    Icons.Rounded.Close, "Clear filter",
                    Modifier.padding(start = 6.dp).size(ChipIconSize).clickable(onClick = { onQuery(null) }),
                    tint = colors.onSurfaceVariant,
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}

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
    onNew: (() -> Unit)? = null,
    onCard: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    selectors: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.semantics { paneTitle = "Prism" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { selectors() }
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
                focus.requestFocus()
                keyboard?.show()
            },
            focus = focus,
            animate = animate,
            placeholder = placeholder,
            voice = voice,
            onMic = onMic,
            onWave = onWave,
            onNew = onNew,
            onCard = onCard,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Gemini's input card: one row (new, text, mic, send) while idle; once the
 * keyboard is up or there is text, two rows, the text across the whole card
 * and the buttons under it. The change animates: the text widens and moves,
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
    onNew: (() -> Unit)?,
    onCard: (androidx.compose.ui.geometry.Rect) -> Unit,
    modifier: Modifier,
) {
    // Listening, Gemini first only pulses its mic; once speech begins, the bars.
    val listening = voice?.active == true && voice.speaking
    val waiting = voice?.active == true && !voice.speaking
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val typing = focused && WindowInsets.isImeVisible
    val twoRows = !listening && (typing || text.isNotEmpty())
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
    Box(
        modifier
            .onGloballyPositioned { onCard(it.boundsInRoot()) }
            // Gemini's handle, 6 dp above the card.
            .drawBehind {
                val w = 32.dp.toPx()
                val h = 2.dp.toPx()
                drawRoundRect(
                    colors.outlineVariant.copy(alpha = colors.outlineVariant.alpha * PlateAppear.contentAlpha(age())),
                    topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2, -6.dp.toPx() - h),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h),
                )
            }
            .geminiPlate(plateColors, CardRadius, ageMs = age, processing = { processing })
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
                // Gemini's plus, in the text's colour even when there is nothing to start anew.
                IconButton(
                    onClick = { onNew?.invoke() },
                    enabled = onNew != null && !listening,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface, disabledContentColor = colors.onSurface),
                ) {
                    Icon(GeminiIcons.Plus, "New request", Modifier.size(28.dp))
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
                        visible = text.isNotBlank() || sending || listening,
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
            },
        ) { measurables, constraints ->
            val (plusM, fieldM, trailingM) = measurables
            val width = constraints.maxWidth
            val pad = 8.dp.roundToPx()
            val gap = 4.dp.roundToPx()
            // In two rows the text keeps clear of the card's rounded corners.
            val textInset = 24.dp.roundToPx()
            // Gemini's card is 72 dp tall at one row: 48 dp buttons with 12 dp round them.
            val minHeight = 72.dp.roundToPx()
            val plus = plusM.measure(Constraints())
            val trailing = trailingM.measure(Constraints(maxWidth = width))
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
            val height = lerp(h1, h2)
            layout(width, height) {
                val fieldY = lerp((h1 - field.height) / 2, 0)
                field.placeRelative(lerp(x1, x2), fieldY)
                // At one row the buttons sit in the card's middle; at two, under the text.
                val buttonY = lerp((h1 - buttons) / 2, height - pad - buttons)
                plus.placeRelative(pad, buttonY + (buttons - plus.height) / 2)
                trailing.placeRelative(width - pad - trailing.width, buttonY + (buttons - trailing.height) / 2)
            }
        }
    }
}
