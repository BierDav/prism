// What the composer shows while Prism listens, as Gemini's Floaty does (its
// WaveView, "fixed bars"): no transcript, only eleven thin bars in the text's
// colour that follow the voice. The newest loudness sits in the centre bar and
// travels outwards to both ends, one bar per 30 ms tick; each bar eases a
// quarter of the way to its target per tick, and the outer bars reach less
// high. A pulsing stop button ends listening.

package at.quickme.prism

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
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

/** Gemini's breath (the Google app's mic pulse): out over 428 ms, back over 856 ms, a rest of 166 ms. */
@Composable
private fun pulse(animate: Boolean): Float {
    if (!animate) return 1f
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1450
                1.1724f at 428 using PulseEasing
                1f at 1284 using PulseEasing
            },
            RepeatMode.Restart,
        ),
        label = "scale",
    ).value
}

/**
 * Gemini's mic: a disc in the primary container. While it listens and no one
 * speaks yet ([pulsing]), a ring of the same colour breathes out from behind
 * it; once speech begins the composer turns to the voice's bars.
 */
@Composable
internal fun MicButton(onClick: () -> Unit, pulsing: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ring by animateFloatAsState(if (pulsing) 1f else 0f, tween(300, easing = LinearEasing), label = "ring")
    val scale = if (pulsing) pulse(animate) else 1f
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (ring > 0f) Box(
            Modifier.size(48.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = ring }
                .background(colors.primaryContainer, CircleShape),
        )
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(colors.primaryContainer)
                .clickable(onClickLabel = if (pulsing) "Stop listening" else "Speak to Prism", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GeminiIcons.Mic, if (pulsing) "Listening" else "Speak to Prism", Modifier.size(28.dp), tint = colors.onSurface)
        }
    }
}

/** Gemini's stop button while listening: a circle that breathes behind it, not with the voice. */
@Composable
internal fun ListeningStop(onClick: () -> Unit, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pulse = pulse(animate)
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(48.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .background(colors.primaryContainer.copy(alpha = 0.5f), CircleShape),
        )
        Surface(onClick = onClick, shape = CircleShape, color = colors.surfaceContainer, contentColor = colors.onSurface, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(GeminiIcons.Stop, "Stop listening and send", Modifier.size(28.dp))
            }
        }
    }
}

private val PulseEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
