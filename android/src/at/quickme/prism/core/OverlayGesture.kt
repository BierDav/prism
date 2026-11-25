// Gemini's drag-to-open as the Google app 17.76.14 decides it (its floaty's
// gesture layout, ccav, and the card's growth, ccbf), and its composer's
// corners (ccix): pure numbers, in px unless a name says dp. The overlay
// (OverlayDrag.kt) applies them.

package at.quickme.prism.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OverlayGesture {
    /** How far the finger must go up to open the full screen (flag 45720028). */
    const val COMMIT_DP = 48f

    /** Or how fast it must fling up, in px/s (GestureDetector's velocity, not dp). */
    const val FLING_PX_PER_S = 1000f

    /** The card may grow by at least this much (flag 45704502)... */
    const val MIN_GROWTH_DP = 100f

    /** ...or to a third of the screen less the top inset and this. */
    const val TOP_GAP_DP = 8f

    /** Blown up, the card overshoots the screen by this, which hides its corners. */
    const val OVERSCAN_DP = 40f

    /** What fades as the card grows or blows up does so over this first share of it. */
    const val FADE_SPAN = 0.2f

    /** Gemini's motionDurationShort2: the return, the blow-up, the composer's corners. */
    const val ANIM_MS = 100

    /** The composer's corners: [ROUND_DP] while it is less than [TALL_DP] tall, [SQUARER_DP] from there. */
    const val TALL_DP = 95f
    const val ROUND_DP = 40f
    const val SQUARER_DP = 28f

    /**
     * How much the dragged card may grow (ccbf.f): to a third of the space
     * below the top inset or by [MIN_GROWTH_DP], whichever is more, but never
     * past the space the overlay's column leaves.
     */
    fun maxExtra(screenHeight: Float, topInset: Float, card: Float, column: Float, density: Float): Float {
        val avail = screenHeight - topInset - TOP_GAP_DP * density
        return min(max(avail / 3f, MIN_GROWTH_DP * density + card) - card, max(avail - column, 1f))
    }

    /** How grown the card is (0..1) for [distance] px up: linear, 1:1, nothing past [maxExtra]. */
    fun progress(distance: Float, maxExtra: Float): Float =
        if (maxExtra <= 0f) 0f else (distance / maxExtra).coerceIn(0f, 1f)

    /** What fades over the first [FADE_SPAN] of [progress]: 1 to 0, linear. */
    fun fadeOut(progress: Float): Float = 1f - (progress / FADE_SPAN).coerceIn(0f, 1f)

    /** Whether the finger, lifted after [distance] px up at [velocityY] px/s (up is negative), opens the full screen (ccav.a). */
    fun commits(distance: Float, velocityY: Float, density: Float): Boolean =
        abs(distance) > COMMIT_DP * density || -velocityY > FLING_PX_PER_S

    /** +1 when [before] → [after] crosses the threshold going up, -1 coming back below it, else 0 (ccav.onScroll). */
    fun crossing(before: Float, after: Float, density: Float): Int {
        val t = COMMIT_DP * density
        return when {
            before <= t && after > t -> 1
            before > t && after <= t -> -1
            else -> 0
        }
    }

    /** The blow-up's end scale for a card [size] px across a screen of [screen] px (ccbf.e). */
    fun scale(screen: Float, size: Float, density: Float): Float =
        if (size <= 0f) 1f else (screen + OVERSCAN_DP * density) / size

    /** The pivot as a share of the card: so it grows towards each edge as far as that edge is ([before] and [after] px away). */
    fun pivot(before: Float, after: Float): Float =
        if (before + after > 0f) (before / (before + after)).coerceIn(0f, 1f) else 0.5f

    /** The composer's corner radius (dp) at [heightDp] tall (ccix, cciv). */
    fun composerRadiusDp(heightDp: Float): Float = if (heightDp >= TALL_DP) SQUARER_DP else ROUND_DP
}
