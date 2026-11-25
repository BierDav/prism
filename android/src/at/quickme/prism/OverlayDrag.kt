// Gemini's drag-to-open on the overlay (the Google app's floaty, ccav and
// ccbf; the numbers are core/OverlayGesture.kt's). The card under the handle
// (the answer panel while an answer shows, else the composer) grows with the
// finger, 1:1, by space that opens below its content, up to a cap; what is
// above it (the controls) fades over the first fifth. Let go short of 48 dp
// (and without a fling over 1000 px/s) it shrinks back in 100 ms, linearly;
// past it, it blows up over the whole screen in 100 ms, its content fading
// over the first fifth, and the full-screen chat cuts in. Haptics both ways
// across the threshold.

package at.quickme.prism

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import at.quickme.prism.core.OverlayGesture
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The overlay's drag-to-open, shared by whichever card has the handle. [initialPull] (px) is for previews. */
@Stable
class OverlayDrag internal constructor(initialPull: Float = 0f) {
    /** How far the finger has gone up since it went down (px). */
    var pull by mutableFloatStateOf(initialPull)
        private set

    /** Gemini's cap, fixed when a drag starts (ccbf.f); null between drags. */
    private var frozenCap by mutableStateOf<Float?>(null)

    /** The blow-up into the full screen (0..1). */
    val blowUp = Animatable(0f)

    // What the cap and the blow-up are measured on (px), kept as the
    // overlay lays out; not state, so a layout doesn't invalidate itself.
    internal var density = 1f
    internal var screen = IntSize.Zero
    internal var topInset = 0f
    internal var cardHeight = 0f
    internal var columnHeight = 0f
    internal var applied = 0f
    internal var bounds = Rect.Zero

    private var settling: Job? = null

    private fun liveCap(): Float =
        if (screen.height == 0 || cardHeight <= 0f) Float.POSITIVE_INFINITY
        else OverlayGesture.maxExtra(screen.height.toFloat(), topInset, cardHeight, columnHeight, density)

    /** How much taller the card is now (px). */
    val extra: Float get() = pull.coerceIn(0f, frozenCap ?: liveCap())

    /** How grown it is (0..1). */
    val progress: Float get() = (frozenCap ?: liveCap()).let { if (it.isFinite()) OverlayGesture.progress(pull, it) else 0f }

    /** The controls and chips above the card: they fade as it grows. */
    val controlsAlpha: Float get() = OverlayGesture.fadeOut(progress)

    /** What is in the card, and the handle: they fade as it blows up. */
    val contentAlpha: Float get() = OverlayGesture.fadeOut(blowUp.value)

    /** A finger is down and dragging: the cap is fixed from here. */
    fun start() {
        settling?.cancel()
        if (frozenCap == null) frozenCap = liveCap().takeIf { it.isFinite() }
    }

    /** The finger moved [dy] px (up is negative); haptics as it crosses the threshold either way. */
    fun by(dy: Float, view: View) {
        val before = pull
        pull = (pull - dy).coerceAtLeast(0f)
        when (OverlayGesture.crossing(before, pull, density)) {
            1 -> view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.VIRTUAL_KEY,
            )
            -1 -> view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
    }

    /** The finger is up at [velocityY] px/s: blown up and [onExpand], or back. */
    fun release(scope: CoroutineScope, velocityY: Float, animate: Boolean, onExpand: () -> Unit) {
        settling?.cancel()
        settling = scope.launch {
            if (OverlayGesture.commits(pull, velocityY, density)) {
                blowUpThen(animate, onExpand)
                return@launch
            }
            // Back from where the card is (past the cap it went no further).
            val from = extra
            pull = from
            if (animate) animate(from, 0f, animationSpec = tween(OverlayGesture.ANIM_MS, easing = LinearEasing)) { v, _ -> pull = v }
            pull = 0f
            frozenCap = null
        }
    }

    /** The card blows up over the screen, then [onExpand] (the open button, or a committed drag). */
    suspend fun blowUpThen(animate: Boolean, onExpand: () -> Unit) {
        if (animate) blowUp.animateTo(1f, tween(OverlayGesture.ANIM_MS, easing = LinearEasing)) else blowUp.snapTo(1f)
        onExpand()
    }

    /** Back from the full screen: the card as it was before the drag. */
    suspend fun reset() {
        settling?.cancel()
        blowUp.snapTo(0f)
        pull = 0f
        frozenCap = null
    }
}

/**
 * The card [drag] blows up: on its layer, scaled to the screen plus 40 dp
 * about a pivot in proportion to its distances to the edges (ccbf.e). Before
 * its background, so the background stretches with it.
 */
internal fun Modifier.overlayDragBlowUp(drag: OverlayDrag): Modifier =
    onGloballyPositioned { drag.bounds = it.boundsInRoot() }.graphicsLayer {
        val b = drag.blowUp.value
        if (b <= 0f) return@graphicsLayer
        val r = drag.bounds
        val s = drag.screen
        scaleX = 1f + (OverlayGesture.scale(s.width.toFloat(), size.width, density) - 1f) * b
        scaleY = 1f + (OverlayGesture.scale(s.height.toFloat(), size.height, density) - 1f) * b
        transformOrigin = TransformOrigin(
            OverlayGesture.pivot(r.left, s.width - r.right),
            OverlayGesture.pivot(r.top, s.height - r.bottom),
        )
    }

/**
 * The card [drag] grows: [OverlayDrag.extra] more below its content, which
 * stays at the top (Gemini's bottom padding on a bottom-anchored card), so
 * the card grows upward. After its background, so the background covers it.
 */
internal fun Modifier.overlayDragGrowth(drag: OverlayDrag): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    drag.cardHeight = p.height.toFloat()
    val height = (p.height + drag.extra.roundToInt()).coerceIn(constraints.minHeight, constraints.maxHeight)
    drag.applied = (height - p.height).toFloat()
    layout(p.width, height) { p.place(0, 0) }
}
