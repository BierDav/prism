// What the composer shows while Prism listens, as Gemini's Floaty does (its
// WaveView, "fixed bars"): no transcript, only eleven thin bars in the text's
// colour that follow the voice. The newest loudness sits in the centre bar and
// travels outwards to both ends, one bar per 30 ms tick; each bar eases a
// quarter of the way to its target per tick, and the outer bars reach less
// high. A pulsing stop button ends listening. The mic's pulse while it waits
// for speech is Gemini's own (the Google app's brls), timing, easing and all.

package at.quickme.prism

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import at.quickme.prism.ui.GeminiIcons
import kotlin.math.abs
import kotlinx.coroutines.delay

private const val BARS = 11
/** How high each bar may reach, centre highest. */
private val Reach = floatArrayOf(0.20f, 0.33f, 0.50f, 0.67f, 0.83f, 1f, 0.83f, 0.67f, 0.50f, 0.33f, 0.20f)
private val BarWidth = 2.dp
private val BarGap = 8.dp
private val MinBar = 4.dp
private val MaxBar = 28.dp
private const val TICK_MS = 30L
private const val EASE = 0.25f
/** Ticks a sample is held when no new one comes. */
private const val HOLD = 3

/** Gemini's loudness curve: quiet is the floor, most of the height by 75 %, the rest above. */
private fun height(level: Float, min: Float, max: Float): Float {
    val l = level.coerceIn(0f, 1f)
    val knee = 0.75f * max
    return when {
        l <= 0.2f -> min
        l <= 0.75f -> min + (l - 0.2f) / 0.55f * (knee - min)
        else -> knee + (l - 0.75f) / 0.25f * (max - knee)
    }
}

/** The bars for [voice]; still (at their floor) without animations. */
@Composable
internal fun VoiceWave(voice: VoiceInput, animate: Boolean, modifier: Modifier = Modifier) {
    val bars = remember { mutableStateListOf(*Array(BARS) { 0f }) }
    LaunchedEffect(voice, animate) {
        if (!animate) return@LaunchedEffect
        // Heights as fractions of MaxBar; history[0] is the newest.
        val min = MinBar.value / MaxBar.value
        val history = ArrayDeque<Float>()
        var seen = voice.samples
        var held = 0
        while (true) {
            delay(TICK_MS)
            val next = when {
                voice.samples != seen -> {
                    seen = voice.samples
                    held = 0
                    height(voice.level, min, 1f)
                }
                held < HOLD -> {
                    held++
                    history.firstOrNull() ?: min
                }
                else -> min
            }
            history.addFirst(next)
            while (history.size > BARS / 2 + 1) history.removeLast()
            for (i in 0 until BARS) {
                val h = history.getOrElse(abs(i - BARS / 2)) { min }
                val target = min + Reach[i] * (h - min)
                bars[i] += (target - bars[i]) * EASE
            }
        }
    }
    val barColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.semantics { contentDescription = "Listening" }) {
        val w = BarWidth.toPx()
        val pitch = w + BarGap.toPx()
        val total = BARS * w + (BARS - 1) * BarGap.toPx()
        val left = (size.width - total) / 2
        val floor = MinBar.toPx()
        val max = MaxBar.toPx()
        for (i in 0 until BARS) {
            val h = maxOf(floor, bars[i] * max)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left + i * pitch, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/**
 * Gemini's mic pulse (the Google app's brls, "startListeningAnimation"): the
 * disc behind the mic breathes out to 1.1724 times its size over 428 ms and
 * back over 856 ms, both on PathInterpolator(0.33, 0, 0.67, 1), then rests
 * for 166 ms; 1450 ms a round, repeated while it listens.
 */
@Composable
private fun pulse(animate: Boolean): Float {
    if (!animate) return 1f
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = PULSE_OUT_MS + PULSE_BACK_MS + PULSE_REST_MS
                // A keyframe's easing runs from it to the next one.
                1f at 0 using PulseEasing
                PULSE_SCALE at PULSE_OUT_MS using PulseEasing
                1f at PULSE_OUT_MS + PULSE_BACK_MS using LinearEasing
            },
            RepeatMode.Restart,
        ),
        label = "scale",
    ).value
}

/**
 * How visible the listening discs are: Gemini fades them in and out linearly
 * over motionDurationShort2 (150 ms).
 */
@Composable
private fun shown(on: Boolean, animate: Boolean): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(on, animate) {
        if (animate) a.animateTo(if (on) 1f else 0f, tween(LISTEN_FADE_MS, easing = LinearEasing)) else a.snapTo(if (on) 1f else 0f)
    }
    return a.value
}

/**
 * Gemini's floaty mic (the Google app's Mic, its MicPeer in the floaty's
 * mode): idle, only its 28 dp glyph in onSurface, no disc. While it listens
 * and no one speaks yet ([pulsing]), a disc in the primary container (primary
 * fixed dim in the light) fades in behind the glyph, now onPrimaryContainer,
 * and a second disc of the same colour breathes out from under it ([pulse]).
 * The discs are 43 dp: Gemini's 48 dp ovals inside their 5 dp transparent
 * stroke. Once speech begins the composer turns to the voice's bars.
 */
@Composable
internal fun MicButton(onClick: () -> Unit, pulsing: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val fill = if (dark) colors.primaryContainer else colors.primaryFixedDim
    val shown = shown(pulsing, animate)
    val scale = if (pulsing || shown > 0f) pulse(animate) else 1f
    Box(
        modifier.size(48.dp)
            .drawBehind {
                if (shown <= 0f) return@drawBehind
                val r = DiscDiameter.toPx() / 2
                drawCircle(fill.copy(alpha = fill.alpha * shown), r * scale)
                drawCircle(fill.copy(alpha = fill.alpha * shown), r)
            }
            .clip(CircleShape)
            .clickable(onClickLabel = if (pulsing) "Stop listening" else "Speak to Prism", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GeminiIcons.Mic, if (pulsing) "Listening" else "Speak to Prism", Modifier.size(28.dp),
            tint = if (pulsing) colors.onPrimaryContainer else colors.onSurface,
        )
    }
}

/**
 * Gemini's stop while the bars show (its PulsingButton, listening): a 44 dp
 * button in the highest surface container (dark; the surface container in the
 * light) with the stop in the primary colour, over a disc of the primary
 * container at half opacity that breathes as the mic's does.
 */
@Composable
internal fun ListeningStop(onClick: () -> Unit, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val scale = pulse(animate)
    val shown = shown(true, animate)
    val ring = colors.primaryContainer.copy(alpha = 0.5f)
    Box(
        modifier.size(48.dp).drawBehind {
            drawCircle(ring.copy(alpha = ring.alpha * shown), DiscDiameter.toPx() / 2 * scale)
        },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick, shape = CircleShape,
            color = if (dark) colors.surfaceContainerHighest else colors.surfaceContainer,
            contentColor = colors.primary,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(GeminiIcons.Stop, "Stop listening and send", Modifier.size(24.dp))
            }
        }
    }
}

private val PulseEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private const val PULSE_SCALE = 1.1724f
private const val PULSE_OUT_MS = 428
private const val PULSE_BACK_MS = 856
private const val PULSE_REST_MS = 166
private const val LISTEN_FADE_MS = 150
private val DiscDiameter = 43.dp
