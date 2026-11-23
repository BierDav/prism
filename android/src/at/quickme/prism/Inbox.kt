// What waits for the owner, from everywhere: approvals on this phone and
// the questions of every T3 thread on every machine Prism reaches
// (GET /v1/questions). They sit in a bar above the navigation bar, like a
// media player's mini player; tapping it or pulling it up opens them full
// screen, one page each, side by side to swipe through. Approvals are
// decided there with a fingerprint as on the approval screen
// (ApproveActivity, which notifications still open over the lock screen);
// questions are answered there, with an option or in your own words
// (POST /v1/questions/{id}/answer).

package at.quickme.prism

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.SideEffect
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.exp
import androidx.compose.runtime.withFrameNanos
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.ago
import at.quickme.prism.ui.prism
import at.quickme.prism.ui.rememberScreenCorners
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.animation.scaleIn
import androidx.compose.material.icons.rounded.Check
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.atan2
import at.quickme.prism.core.Text.visible
import at.quickme.prism.shared.Approval
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismError
import at.quickme.prism.shared.Question
import at.quickme.prism.shared.QuestionItem

/** One thing waiting for the owner: a page of the inbox. */
sealed interface InboxItem {
    val key: String

    /** Whether a key to open (MainActivity.EXTRA_INBOX) names this item. */
    fun matches(target: String): Boolean = key == target

    class Pending(val approval: Approval) : InboxItem {
        override val key = "a-" + approval.id
    }

    class Question(val question: at.quickme.prism.shared.Question) : InboxItem {
        override val key = questionKey(question.id)

        override fun matches(target: String) = key == target || target == threadKey(question.threadId)
    }

    companion object {
        fun questionKey(requestId: String) = "q-$requestId"

        /** The first question of a thread. */
        fun threadKey(threadId: String) = "t-$threadId"
    }
}

/** The open T3 questions (GET /v1/questions), kept across tab switches. */
class QuestionsState {
    var questions by mutableStateOf<List<Question>>(emptyList())

    /** Loaded at least once since the app started (or failed to). */
    var loaded by mutableStateOf(false)
    private var loading = false

    suspend fun load(c: Context) {
        if (loading || !PrismApi.enrolled(c)) return
        loading = true
        try {
            questions = PrismApi.call(c) { questions() }
        } catch (e: PrismError) {
            // The last list stays; the next push or poll repairs it.
        } finally {
            loading = false
            loaded = true
        }
    }

    /**
     * Answers every item of [question] ([answers]: an item's id to the chosen
     * labels or one text); returns an error to show, or null once it is
     * answered (or gone).
     */
    suspend fun answer(c: Context, question: Question, answers: Map<String, List<String>>): String? {
        val error = try {
            PrismApi.call(c) { answer(question, answers, PrismClient.requestId()) }
            null
        } catch (e: PrismError) {
            if (e.status == 404) null else e.message.orEmpty()
        }
        if (error == null) questions = questions.filterNot { it.id == question.id }
        load(c)
        return error
    }
}

/**
 * The inbox: the items, the pager over them, and how far the full-screen view
 * is open, from 0 (the bar) to 1 (full screen).
 *
 * Drags move it as Android 16's notification shade moves a swiped
 * notification (SystemUI's MagneticNotificationRowManagerImpl, QPR2):
 * attached, the view follows half the finger's travel, with soft ticks that
 * grow as it nears the snap point; at 144 dp (twice the shade's 72 dp:
 * a whole screen moves here, not a row) it detaches with a click and
 * springs ahead to the finger (stiffness 800, damping 0.95), every new sample
 * retargeting the spring; back under 112 dp (2 x 56) it attaches again with the same
 * click (850, 0.95). On release an attached drag completes only on a fling of
 * 500 dp/s toward the other end (with the click), else springs back (550,
 * 0.52); a detached one completes unless flung back.
 *
 * The position is plain state: a drag sets it at once, and only springs run
 * frame by frame ([frame], driven by InboxEffects), so nothing lags or races.
 */
class Inbox {
    /** How far the view is from [base], in pixels toward opening. */
    private var shown by mutableFloatStateOf(0f)
    private var base by mutableFloatStateOf(0f)
    private var height = 1f

    /** How far open it is, 0 to 1. */
    val progress: Float get() = (base + shown / height).coerceIn(0f, 1f)

    /** The largest bounce, in pixels (InboxEffects sets it from 64 dp). */
    var bounceLimit = 168f

    /**
     * A spring that overshoots its end shows it, so the user sees the move
     * absorbed: past open the content rides up (negative), past closed the
     * bar dips down (positive), as far as the speed carries it, softly capped.
     */
    val bounce: Float get() {
        val raw = base * height + shown
        val over = when {
            raw > height -> -(raw - height)
            raw < 0f -> -raw
            else -> 0f
        }
        return sign(over) * bounceLimit * (1f - exp(-abs(over) / bounceLimit))
    }

    /** Where it is heading: 1 while open or opening. */
    var target by mutableFloatStateOf(0f)
        private set

    /** A spring is running: InboxEffects calls [frame] every frame meanwhile. */
    var springing by mutableStateOf(false)
        private set

    /** The threshold click and the pull ticks (InboxEffects sets them). */
    var click: () -> Unit = {}
    var tick: (Float) -> Unit = {}

    /** SystemUI's numbers in pixels (InboxEffects sets them from dp). */
    var detachAt = 378f // 144 dp
    var attachAt = 294f // 112 dp
    var flingAt = 1312f // 500 dp/s

    // The drag: the finger's travel toward opening (px), its velocity (px/s),
    // whether it has detached and to which side, and the last pull tick.
    private var dragging = false
    private var travel = 0f
    private var fingerVelocity = 0f
    private var lastSample = 0L
    private var detached = false
    private var side = 0f
    private var lastTick = 0L

    // Caught mid-spring, and the travel when it was.
    private var caught = false
    private var grab = 0f

    // The spring: its goal (px from base), velocity, stiffness and damping;
    // [following] means the goal tracks the finger (detach, attach).
    private var goal = 0f
    private var velocity = 0f
    private var stiffness = 380f
    private var damping = 0.86f
    private var following = false

    fun setHeight(h: Float) {
        if (h > 0f && h != height) {
            val p = progress
            height = h
            shown = (p - base) * h
        }
    }

    private fun springTo(to: Float, k: Float, zeta: Float, v: Float, follow: Boolean) {
        goal = to
        velocity = v
        stiffness = k
        damping = zeta
        following = follow
        springing = true
    }

    /** Opens or closes from wherever it is, as a spring to that end. */
    private fun settleAt(end: Float, k: Float, zeta: Float, v: Float) {
        // Re-base on the end it leaves from, so `shown` measures from there.
        val p = progress
        base = if (end == 1f) 0f else 1f
        shown = (p - base) * height
        target = end
        springTo((end - base) * height, k, zeta, v, follow = false)
    }

    fun expand() = settleAt(1f, 380f, 0.86f, velocity)

    fun collapse() = settleAt(0f, 380f, 0.86f, velocity)

    /**
     * A drag by [delta] pixels (y, down positive) on a screen [height] pixels
     * high. Returns the part of [delta] it used: open, the view never rises
     * past the top, and what is left is the page's to scroll.
     */
    fun drag(delta: Float, height: Float): Float {
        setHeight(height)
        val now = System.nanoTime()
        if (!dragging) {
            dragging = true
            val p = progress
            // Caught on its way, it is measured from the end it was heading
            // for; at rest, from the nearer one.
            caught = springing && p > 0f && p < 1f
            base = if (caught) (if (target >= 0.5f) 1f else 0f) else if (p >= 0.5f) 1f else 0f
            shown = (p - base) * this.height
            if (base == 1f) shown = shown.coerceAtMost(0f) // a bounce past the top ends here
            travel = shown * 2f // as if attached: half of it shows
            detached = abs(travel) >= detachAt
            if (detached) {
                travel = shown
                side = sign(travel)
            }
            grab = travel
            springing = false
            fingerVelocity = 0f
            lastSample = now
        }
        var next = travel - delta
        if (base == 1f) next = next.coerceAtMost(0f)
        val used = travel - next
        travel = next
        val dt = (now - lastSample) / 1e9f
        if (dt > 0f) fingerVelocity = (-delta / dt) * 0.6f + fingerVelocity * 0.4f
        lastSample = now
        when {
            !detached && abs(travel) >= detachAt -> {
                detached = true
                side = sign(travel)
                click()
                springTo(travel, 800f, 0.95f, side * abs(fingerVelocity), follow = true)
            }
            detached && abs(travel) <= attachAt -> {
                detached = false
                click()
                springTo(travel * 0.5f, 850f, 0.95f, -side * abs(fingerVelocity), follow = true)
            }
            springing && following -> goal = if (detached) travel else travel * 0.5f
            else -> {
                springing = false
                shown = if (detached) travel else travel * 0.5f
            }
        }
        if (!detached) pullTick(now)
        target = progress
        return used
    }

    /** SystemUI's pull haptics: at most every 60 ms, stronger toward the snap point. */
    private fun pullTick(now: Long) {
        if (now - lastTick < 60_000_000L) return
        lastTick = now
        val normalized = abs(travel * 0.5f) / detachAt
        val scale = 0.2f * normalized.pow(1.27f)
        tick(scale.pow(1f / 0.89f).coerceAtMost(1f))
    }

    /**
     * The finger left with [velocity] pixels a second (y, down positive).
     * Returns the part of it the view took; the rest is the page's to fling.
     */
    fun settle(velocity: Float, height: Float): Float {
        setHeight(height)
        val toward = -velocity // toward opening
        if (!dragging) {
            settleAt(if (progress >= 0.5f) 1f else 0f, 380f, 0.86f, toward)
            return velocity
        }
        dragging = false
        val wasCaught = caught
        caught = false
        // Open and not pulled down (the page scrolled instead): nothing to settle.
        if (base == 1f && shown == 0f && !springing) {
            detached = false
            target = 1f
            return 0f
        }
        val other = 1f - base
        val toOther = sign(other - base)
        val flung = abs(toward) >= flingAt
        val complete = when {
            // Attached, or pulled the wrong way: only a fling to the other end completes.
            !detached || side != toOther -> flung && sign(toward) == toOther
            // Caught on its way and pushed back toward where it was going: it goes there.
            wasCaught && (travel - grab) * side < 0f -> flung && sign(toward) == toOther
            else -> !(flung && sign(toward) != side)
        }
        if (complete && !detached) click()
        detached = false
        if (complete) {
            settleAt(other, 380f, 0.86f, toward)
            return velocity
        }
        target = base
        if (base == 1f && toward > 0f) {
            // Let go moving up toward the top: it settles there without a
            // bounce, and the page takes the fling and scrolls on.
            springTo(0f, 550f, 1f, 0f, follow = false)
            return 0f
        }
        springTo(0f, 550f, 0.52f, toward, follow = false)
        return velocity
    }

    /** One frame of the running spring, [dt] seconds after the last. */
    fun frame(dt: Float) {
        if (!springing) return
        var left = dt.coerceAtMost(0.05f)
        val c = 2f * damping * sqrt(stiffness)
        while (left > 0f) {
            val h = left.coerceAtMost(0.004f)
            val a = -stiffness * (shown - goal) - c * velocity
            velocity += a * h
            shown += velocity * h
            left -= h
        }
        if (abs(shown - goal) < 0.5f && abs(velocity) < 20f) {
            shown = goal
            velocity = 0f
            springing = false
            if (!dragging && (progress <= 0f || progress >= 1f)) {
                base = if (progress >= 1f) 1f else 0f
                shown = 0f
            }
        }
    }

    /** The items on screen: the latest list, plus answered ones still animating away. */
    var items by mutableStateOf<List<InboxItem>>(emptyList())
        private set
    private var latest: List<InboxItem> = emptyList()
    private val leaving = mutableSetOf<String>()
    val pager = PagerState(pageCount = { items.size })

    /** The latest items (MainActivity, on every change). */
    fun update(list: List<InboxItem>) {
        latest = list
        items = shown()
    }

    private fun shown(): List<InboxItem> {
        if (leaving.isEmpty()) return latest
        // An answered item keeps its place until it has animated away.
        val fresh = latest.associateBy { it.key }
        val kept = items.mapNotNull { fresh[it.key] ?: if (it.key in leaving) it else null }
        val keys = kept.mapTo(HashSet()) { it.key }
        return kept + latest.filter { it.key !in keys }
    }

    /**
     * The item [key] was answered: the pager moves on to the next item (or
     * the one before, after the last), or, with nothing left, the view
     * closes to the bar. The item stays on screen until then, however soon
     * its answer removes it from the list.
     */
    fun answered(key: String, scope: CoroutineScope) {
        leaving += key
        items = shown()
        scope.launch {
            try {
                val i = items.indexOfFirst { it.key == key }
                val rest = items.count { it.key !in leaving }
                when {
                    rest == 0 -> {
                        if (target > 0f) collapse()
                        snapshotFlow { progress }.first { it <= 0f }
                    }
                    i >= 0 && target > 0f -> {
                        val next = (i + 1 until items.size).firstOrNull { items[it].key !in leaving }
                            ?: (i - 1 downTo 0).first { items[it].key !in leaving }
                        pager.animateScrollToPage(next)
                    }
                }
            } finally {
                leaving -= key
                items = shown()
            }
        }
    }

    /**
     * For the previews (android/previews) only: the view at rest, [progress]
     * open (0 the bar, 1 full screen), at [page]; no drag, no spring.
     */
    internal fun showAt(progress: Float, page: Int = 0) {
        dragging = false
        springing = false
        base = 0f
        shown = progress.coerceIn(0f, 1f) * height
        target = if (progress > 0f) 1f else 0f
        pager.requestScrollToPage(page)
    }

    /** Where the bar is on screen: the full-screen view grows out of it. */
    var barBounds by mutableStateOf<Rect?>(null)

    /** The item to open once it is there (from a notification). */
    var openAt by mutableStateOf<String?>(null)

    /**
     * Hands vertical scrolling between a page and the view: at the top of a
     * page, pulling further down closes the view (held back first, like a
     * drag on the header); while it is not all the way open (pulled down,
     * or caught while opening), scrolling moves it first, up to the top and
     * never past it: the rest of the scroll and of the fling is the page's.
     */
    fun scrolling(height: () -> Float) = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f || progress >= 1f) return Offset.Zero
            return Offset(0f, drag(available.y, height()))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            return Offset(0f, drag(available.y, height()))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            return Velocity(0f, settle(available.y, height()))
        }
    }
}

/**
 * Keeps the inbox in step with its items: the page in view stays on its item
 * when others come or go, the view closes when nothing is left, and an item a
 * notification named opens once it is loaded.
 */
@Composable
fun InboxEffects(inbox: Inbox, questionsLoaded: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    LaunchedEffect(inbox) {
        snapshotFlow { inbox.springing }.collect { running ->
            if (!running) return@collect
            var last = withFrameNanos { it }
            while (inbox.springing) {
                withFrameNanos { now ->
                    inbox.frame((now - last) / 1e9f)
                    last = now
                }
            }
        }
    }
    SideEffect {
        inbox.click = { thresholdClick(context) }
        inbox.tick = { scale -> pullTick(context, scale) }
        with(density) {
            inbox.detachAt = (72 * SNAP_SCALE).dp.toPx()
            inbox.attachAt = (56 * SNAP_SCALE).dp.toPx()
            inbox.flingAt = 500.dp.toPx()
            inbox.bounceLimit = 64.dp.toPx()
        }
    }
    var currentKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inbox) {
        snapshotFlow { inbox.items.getOrNull(inbox.pager.settledPage)?.key }.collect { currentKey = it }
    }

    LaunchedEffect(inbox.items) {
        val i = inbox.items.indexOfFirst { it.key == currentKey }
        if (i >= 0 && i != inbox.pager.currentPage) inbox.pager.scrollToPage(i)
        if (inbox.items.isEmpty() && inbox.target > 0f) inbox.collapse()
    }
    LaunchedEffect(inbox.openAt, inbox.items, questionsLoaded) {
        val target = inbox.openAt ?: return@LaunchedEffect
        val i = if (target.isEmpty()) 0 else inbox.items.indexOfFirst { it.matches(target) }
        when {
            i >= 0 && inbox.items.isNotEmpty() -> inbox.pager.scrollToPage(i)
            !questionsLoaded -> return@LaunchedEffect // it may still come
        }
        inbox.openAt = null
        if (inbox.items.isNotEmpty()) inbox.expand()
    }
    BackHandler(enabled = inbox.target > 0f) { inbox.collapse() }
}

/**
 * The notification shade's threshold click (MSDL's SWIPE_THRESHOLD_INDICATOR):
 * one PRIMITIVE_CLICK at 0.7, or the predefined click where the vibrator has
 * no primitives, as touch feedback.
 */
private fun thresholdClick(c: Context) {
    val vibrator = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    if (!vibrator.hasVibrator()) return
    val effect = if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
        VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0).compose()
    } else {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    }
    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
}

/**
 * The notification shade's pull haptics (MSDL's DRAG_INDICATOR_CONTINUOUS):
 * five low ticks at [scale], or its fallback waveform without primitives.
 */
private fun pullTick(c: Context, scale: Float) {
    if (scale <= 0f) return
    val vibrator = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    if (!vibrator.hasVibrator()) return
    val effect = if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
        val composition = VibrationEffect.startComposition()
        repeat(5) { composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, 0) }
        composition.compose()
    } else {
        VibrationEffect.createWaveform(longArrayOf(10, 20, 20, 10), intArrayOf(10, 30, 50, 10), -1)
    }
    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
}

/**
 * An answer went through. Approved or answered: a quick swell that lands
 * on a full click, then a light tick as it settles, felt as the page moves
 * on. Denied: one low thud. Each falls back to the predefined heavy click
 * where the vibrator has no primitives.
 */
private fun answeredHaptic(c: Context, positive: Boolean) {
    val vibrator = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    if (!vibrator.hasVibrator()) return
    val effect = when {
        positive && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK) ->
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.45f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 70)
                .compose()
        !positive && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) ->
            VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.9f).compose()
        else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }
    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
}

/** The snap point and its way back are SystemUI's, twice as far. */
private const val SNAP_SCALE = 2

/**
 * The bar is a neutral surface, as Material's mini players and toasts are:
 * the highest surface container with onSurface text (and onSurfaceVariant
 * for the second line), the pairs the scheme guarantees contrast for with any
 * wallpaper. Dynamic tertiary containers can come out pale, and their text
 * then washes out. The item's colour stays on its icon and the expiry bar.
 */
@Composable
private fun barColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/** The item's accent: amber for an approval, tertiary for a question. */
@Composable
private fun accent(item: InboxItem): Color =
    if (item is InboxItem.Pending) MaterialTheme.prism.warning.color else MaterialTheme.colorScheme.tertiary

/**
 * The mini player, always there above the navigation bar: the item in view
 * and its count, swipe sideways for the next, pull up or tap to open. Open,
 * it is the full-screen view (InboxPlayer), which grows out of where the bar
 * is. With nothing waiting it says so, and stays put.
 */
@Composable
fun InboxBar(inbox: Inbox, screenHeight: Float) {
    val items = inbox.items
    val index = inbox.pager.currentPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val item = items.getOrNull(index)
    val vertical = rememberDraggableState { inbox.drag(it, screenHeight) }
    var sideways by remember { mutableStateOf(0f) }
    val horizontal = rememberDraggableState { sideways += it }
    Surface(
        onClick = { inbox.expand() },
        enabled = item != null,
        shape = RoundedCornerShape(BAR_CORNER),
        color = barColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 6.dp)
            .onGloballyPositioned { inbox.barBounds = it.boundsInRoot() }
            // Open, the full-screen view draws the bar where it grows from.
            .graphicsLayer {
                alpha = if (inbox.progress > 0f) 0f else 1f
                translationY = inbox.bounce.coerceAtLeast(0f)
            }
            .draggable(vertical, Orientation.Vertical, enabled = item != null, onDragStopped = { inbox.settle(it, screenHeight) })
            .draggable(
                horizontal, Orientation.Horizontal,
                enabled = items.size > 1,
                onDragStarted = { sideways = 0f },
                onDragStopped = { v ->
                    val next = when {
                        sideways < -80f || v < -800f -> index + 1
                        sideways > 80f || v > 800f -> index - 1
                        else -> index
                    }.coerceIn(0, items.size - 1)
                    if (next != index) inbox.pager.animateScrollToPage(next)
                },
            ),
    ) { BarContent(inbox, item, index, items.size) }
}

private val BAR_CORNER = 28.dp

@Composable
private fun BarContent(inbox: Inbox, item: InboxItem?, index: Int, count: Int) {
    val onBar = MaterialTheme.colorScheme.onSurface
    val onBarVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.92f)) togetherWith
                    fadeOut(tween(120))
            },
            contentKey = { it?.key ?: "" },
            label = "bar-item",
            modifier = Modifier.weight(1f),
        ) { it ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ItemIcon(it, 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (it == null) "All caught up" else title(it), style = MaterialTheme.typography.titleSmallEmphasized,
                        color = onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (it == null) "Approvals and T3 questions wait here" else subtitle(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBarVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (count > 1) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("${index + 1}/$count", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        if (item != null) {
            IconButton(onClick = { inbox.expand() }) { Icon(Icons.Rounded.KeyboardArrowUp, "Open", tint = onBarVariant) }
        }
    }
}

/** How much of an approval's time is left, 0 to 1, ticking down. */
@Composable
private fun expiry(a: Approval): Float {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val total = (a.expiresAt - a.requestedAt).coerceAtLeast(1).toFloat()
    // In milliseconds as a Long first: a Float of epoch seconds only resolves 128 s.
    val left = ((a.expiresAt * 1000 - now) / 1000f).coerceIn(0f, total)
    val progress by animateFloatAsState(left / total, tween(250, easing = LinearEasing), label = "expiry")
    return progress
}

/**
 * The item's icon, a slowly turning Expressive shape. An approval's time
 * left runs around it as a ring that hugs the same shape, turning with it,
 * from the top clockwise, as a media player shows a track's progress.
 */
@Composable
private fun ItemIcon(item: InboxItem?, size: Dp) {
    val spin = rememberInfiniteTransition(label = "icon")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "angle")
    when (item) {
        is InboxItem.Pending -> {
            val tone = MaterialTheme.prism.warning
            val left = expiry(item.approval)
            val ring = 3.dp
            val gap = 3.dp
            Box(
                Modifier.size(size).drawBehind {
                    drawShapeProgress(MaterialShapes.Cookie12Sided, angle, left, tone.color, tone.container, ring.toPx())
                },
                contentAlignment = Alignment.Center,
            ) {
                ShapedIcon(PrismIcons.Shield, MaterialShapes.Cookie12Sided, tone.color, tone.onColor,
                    size = size - (ring + gap) * 2, rotation = angle)
            }
        }
        is InboxItem.Question -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            ShapedIcon(PrismIcons.Chat, MaterialShapes.Cookie9Sided, MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.onTertiary, size = size - 12.dp, rotation = angle)
        }
        null -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            val tone = MaterialTheme.prism.success
            ShapedIcon(Icons.Rounded.Check, MaterialShapes.Cookie9Sided, tone.container, tone.onContainer,
                size = size - 12.dp, rotation = angle)
        }
    }
}

/**
 * Strokes [polygon]'s outline (the unit-square shapes of MaterialShapes),
 * fitted to this area minus half the stroke and turned [rotation] degrees,
 * as a progress ring: [track] all around, [color] for the first [progress]
 * of it, starting where the outline crosses straight up from the centre and
 * running clockwise.
 */
private fun DrawScope.drawShapeProgress(
    polygon: RoundedPolygon,
    rotation: Float,
    progress: Float,
    color: Color,
    track: Color,
    width: Float,
) {
    val side = size.minDimension - width
    val path = polygon.toPath().asComposePath()
    path.transform(
        Matrix().apply {
            translate(center.x, center.y)
            rotateZ(rotation)
            translate(-side / 2f, -side / 2f)
            scale(side, side)
        },
    )
    val stroke = Stroke(width, cap = StrokeCap.Round)
    drawPath(path, track, style = stroke)
    if (progress <= 0f) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return
    // The outline's point nearest straight up, and which way is clockwise.
    var start = 0f
    var best = Float.MAX_VALUE
    val samples = 120
    for (i in 0 until samples) {
        val d = length * i / samples
        val p = measure.getPosition(d)
        val off = abs(atan2(p.x - center.x, center.y - p.y))
        if (off < best) {
            best = off
            start = d
        }
    }
    val ahead = measure.getPosition((start + length / samples) % length)
    val clockwise = ahead.x >= measure.getPosition(start).x
    val span = length * progress.coerceAtMost(1f)
    val from = if (clockwise) start else (start - span + length) % length
    val arc = Path()
    if (from + span <= length) {
        measure.getSegment(from, from + span, arc, true)
    } else {
        measure.getSegment(from, length, arc, true)
        measure.getSegment(0f, from + span - length, arc, true)
    }
    drawPath(arc, color, style = stroke)
}

private fun title(item: InboxItem): String = when (item) {
    is InboxItem.Pending -> visible(item.approval.title)
    is InboxItem.Question -> item.question.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() } ?: "A question from T3"
}

private fun subtitle(item: InboxItem): String = when (item) {
    is InboxItem.Pending -> visible(listOf(item.approval.source, item.approval.summary).filter { it.isNotBlank() }.joinToString(" · "))
    is InboxItem.Question -> listOf(item.question.title, item.question.host)
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The full-screen view. It is the bar, grown: the shape starts at the bar's
 * bounds, corners and colour and opens to the whole screen as the bar is
 * pulled up, while the bar's content fades out and the pages fade in, their
 * top on the shape's top edge and their bottom cropped by it. The
 * pages run behind the transparent header and the system bars; scrolling
 * hands over between them and the view (Inbox.scrolling).
 */
@Composable
fun InboxPlayer(inbox: Inbox, questions: QuestionsState, screenWidth: Float, screenHeight: Float, t3Linked: Boolean) {
    val p = inbox.progress
    val items = inbox.items
    if (p <= 0f || items.isEmpty()) return
    val density = LocalDensity.current
    val index = inbox.pager.currentPage.coerceIn(0, items.size - 1)
    val item = items[index]
    val bar = inbox.barBounds ?: with(density) {
        Rect(12.dp.toPx(), screenHeight - 180.dp.toPx(), screenWidth - 12.dp.toPx(), screenHeight - 100.dp.toPx())
    }
    val rect = Rect(lerp(bar.left, 0f, p), lerp(bar.top, 0f, p), lerp(bar.right, screenWidth, p), lerp(bar.bottom, screenHeight, p))
    // From the bar's corners to exactly the screen's; square once fully open,
    // so screen recordings show no rounded inner corners.
    val barCorner = with(density) { BAR_CORNER.toPx() }
    val screen = rememberScreenCorners()
    val corners = if (p >= 1f) {
        null
    } else {
        listOf(screen.topLeft, screen.topRight, screen.bottomRight, screen.bottomLeft).map { CornerRadius(lerp(barCorner, it, p)) }
    }
    val color = lerp(barColor(), MaterialTheme.colorScheme.surface, (p * 1.6f).coerceAtMost(1f))
    val vertical = rememberDraggableState { inbox.drag(it, screenHeight) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrolling = remember(inbox) { inbox.scrolling { screenHeight } }
    var header by remember { mutableStateOf(0.dp) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                clip = true
                shape = object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                        if (corners == null) {
                            Outline.Rectangle(rect)
                        } else {
                            Outline.Rounded(RoundRect(rect, corners[0], corners[1], corners[2], corners[3]))
                        }
                }
            }
            .drawBehind { drawRect(color) },
    ) {
        Box(
            // Laid out full screen, its top rides the shape's top edge: the
            // shape crops the bottom, as a growing window onto the page.
            Modifier.fillMaxSize().graphicsLayer {
                alpha = ((p - 0.15f) / 0.45f).coerceIn(0f, 1f)
                translationX = rect.left
                translationY = rect.top + inbox.bounce.coerceAtMost(0f)
            },
        ) {
            HorizontalPager(
                state = inbox.pager,
                key = { i -> items.getOrNull(i)?.key ?: "gone-$i" },
                modifier = Modifier.fillMaxSize().nestedScroll(scrolling),
            ) { i ->
                when (val it = items.getOrNull(i)) {
                    is InboxItem.Pending -> ApprovalPage(it.approval, header, t3Linked) { approved ->
                        answeredHaptic(context, approved)
                        inbox.answered(it.key, scope)
                    }
                    is InboxItem.Question -> QuestionPage(it.question, questions, header) {
                        answeredHaptic(context, true)
                        inbox.answered(it.key, scope)
                    }
                    null -> {}
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .onSizeChanged { header = with(density) { it.height.toDp() } }
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .draggable(vertical, Orientation.Vertical, onDragStopped = { inbox.settle(it, screenHeight) }),
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { inbox.collapse() }, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Close")
                    }
                    Dots(items.size, inbox.pager)
                }
            }
        }
        // The bar as it was, riding the shape's top edge until it fades.
        if (p < 0.4f) {
            Box(
                Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .width(with(density) { bar.width.toDp() })
                    .graphicsLayer { alpha = (1f - p / 0.3f).coerceIn(0f, 1f) },
            ) { BarContent(inbox, item, index, items.size) }
        }
    }
}

/**
 * One dot per page, the one in view stretched. They follow the pager's
 * scroll position, so the stretch moves with the finger from one dot to the
 * next instead of jumping at the halfway point.
 */
@Composable
private fun Dots(count: Int, pager: PagerState) {
    if (count < 2) return
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            Box(
                Modifier.height(6.dp)
                    .layout { measurable, constraints ->
                        val position = pager.currentPage + pager.currentPageOffsetFraction
                        val near = (1f - abs(position - i)).coerceIn(0f, 1f)
                        val width = lerp(6.dp.toPx(), 20.dp.toPx(), near).roundToInt()
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
                    .drawBehind {
                        val position = pager.currentPage + pager.currentPageOffsetFraction
                        val near = (1f - abs(position - i)).coerceIn(0f, 1f)
                        drawRoundRect(lerp(off, on, near), cornerRadius = CornerRadius(size.height / 2))
                    },
            )
        }
    }
}

/** An approval as on the approval screen; [t3Linked]: T3's web app is set (Settings.t3Url), so its thread opens. */
@Composable
private fun ApprovalPage(approval: Approval, top: Dp, t3Linked: Boolean, onAnswered: (approved: Boolean) -> Unit) {
    val context = LocalContext.current
    var notice by remember(approval.id) { mutableStateOf<String?>(null) }
    ApproveScreen(
        approval, position = 1, count = 1, notice = notice,
        onDeny = {
            Approver.deny(context, it)
            onAnswered(false)
        },
        onApprove = {
            notice = null
            (context as? ComponentActivity)?.let { a ->
                Approver.approve(a, it, onNotice = { n -> notice = n }, onDone = { onAnswered(true) })
            }
        },
        onOpenThread = { Approver.openThread(context, it) },
        topPadding = top,
        canOpenThread = t3Linked,
    )
}

/** A T3 question: each of its questions with its options and a field for your own answer. */
@Composable
private fun QuestionPage(q: Question, state: QuestionsState, top: Dp, onAnswered: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = q.id
    val asks = q.questions
    val picks = remember(id) { mutableStateMapOf<String, Set<String>>() }
    val texts = remember(id) { mutableStateMapOf<String, String>() }
    var sending by remember(id) { mutableStateOf(false) }
    var notice by remember(id) { mutableStateOf<String?>(null) }
    val key = { a: QuestionItem -> a.id.ifEmpty { a.question } }
    val complete = asks.isNotEmpty() && asks.all { a -> picks[key(a)].orEmpty().isNotEmpty() || texts[key(a)].orEmpty().isNotBlank() }
    val tint = MaterialTheme.colorScheme.tertiary
    val chat = T3Activity.intent(context, q.host, q.environmentId, q.threadId)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.22f), 0.45f to tint.copy(alpha = 0.05f), 1f to Color.Transparent)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = top + 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ItemIcon(InboxItem.Question(q), 64.dp)
                    Text("T3 asks", style = MaterialTheme.typography.headlineMediumEmphasized)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (q.host.isNotEmpty()) HostBadge(q.host, large = true)
                        Text(ago(q.createdAt), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (q.title.isNotEmpty()) {
                        Text(q.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                for (a in asks) {
                    val k = key(a)
                    val multi = a.multiSelect
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a.header.isNotEmpty()) {
                            Text(a.header.uppercase(), style = MaterialTheme.typography.labelMediumEmphasized.copy(letterSpacing = 1.2.sp),
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text(a.question, style = MaterialTheme.typography.titleLarge)
                        val options = a.options
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            options.forEachIndexed { i, o ->
                                val label = o.label
                                val chosen = label in picks[k].orEmpty()
                                Option(
                                    label, o.description, chosen, multi, i, options.size,
                                    onClick = {
                                        if (multi) {
                                            picks[k] = picks[k].orEmpty().let { if (chosen) it - label else it + label }
                                        } else {
                                            picks[k] = setOf(label)
                                            texts[k] = ""
                                        }
                                    },
                                )
                            }
                        }
                        OwnWords(
                            value = texts[k].orEmpty(),
                            onValueChange = { v ->
                                texts[k] = v
                                if (!multi && v.isNotBlank()) picks[k] = emptySet()
                            },
                            label = if (options.isEmpty()) "Your answer" else "Or in your own words",
                        )
                    }
                }
                AnimatedVisibility(notice != null, enter = fadeIn() + expandVertically()) {
                    val tone = MaterialTheme.prism.critical
                    Surface(shape = MaterialTheme.shapes.large, color = tone.container) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(PrismIcons.Octagon, null, tint = tone.onContainer)
                            Text(notice.orEmpty(), color = tone.onContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            // On the page's own background, which runs behind the navigation bar.
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { chat?.let { context.startActivity(it) } },
                        enabled = chat != null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) {
                        Icon(PrismIcons.OpenInNew, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open in T3", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            sending = true
                            notice = null
                            // Several choices go with your own words; one choice or your own words alone.
                            val answers = asks.associate { a ->
                                val text = texts[key(a)].orEmpty().trim()
                                val chosen = picks[key(a)].orEmpty().toList()
                                a.id to when {
                                    a.multiSelect -> if (text.isEmpty()) chosen else chosen + text
                                    text.isNotEmpty() -> listOf(text)
                                    else -> chosen.take(1)
                                }
                            }
                            scope.launch {
                                val err = state.answer(context, q, answers)
                                sending = false
                                notice = err
                                if (err == null) onAnswered()
                            }
                        },
                        enabled = complete && !sending,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1.4f).height(64.dp),
                    ) {
                        if (sending) {
                            LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Answer", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One option, as a row of Material 3 Expressive's segmented list: selected,
 * the row takes the secondary container and its text and control
 * onSecondaryContainer (ListItemDefaults.segmentedColors), the pairing
 * Material specifies for a selected list item, and its shape rounds fully.
 */
@Composable
private fun Option(
    label: String,
    description: String,
    chosen: Boolean,
    multi: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val supporting: (@Composable () -> Unit)? = if (description.isEmpty()) null else ({ Text(description) })
    if (multi) {
        SegmentedListItem(
            checked = chosen,
            onCheckedChange = { onClick() },
            shapes = shapes,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { Checkbox(checked = chosen, onCheckedChange = null) },
            supportingContent = supporting,
        ) { Text(label) }
    } else {
        SegmentedListItem(
            selected = chosen,
            onClick = onClick,
            shapes = shapes,
            modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
            leadingContent = { RadioButton(selected = chosen, onClick = null) },
            supportingContent = supporting,
        ) { Text(label) }
    }
}

/**
 * The field for an answer in your own words. The window neither pans nor
 * resizes for the keyboard (adjustResize under edge-to-edge only reports it
 * as an inset): the page's actions ride on the keyboard, the page above them
 * gets shorter, and this keeps the focused field in view as it does.
 */
@Composable
private fun OwnWords(value: String, onValueChange: (String) -> Unit, label: String) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collect { requester.bringIntoView() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        shape = MaterialTheme.shapes.large,
        minLines = 1,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .bringIntoViewRequester(requester)
            .onFocusChanged { focused = it.isFocused },
    )
}
