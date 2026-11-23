// The pieces every screen is built from.

package at.quickme.prism.ui

import at.quickme.prism.shared.Severity as AlertLevel
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

// --- Listening to the app's state holders ---

/**
 * A number that grows whenever one of `sources` reports a change, while this
 * composable is on screen: read it as the key of whatever reads that state.
 * Each source is a listen/unlisten pair as PrismPush, Netbird, Updater etc. have.
 */
@Composable
fun rememberChanges(vararg sources: Pair<(Runnable) -> Unit, (Runnable) -> Unit>): Int {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val r = Runnable { version++ }
        sources.forEach { it.first(r) }
        onDispose { sources.forEach { it.second(r) } }
    }
    return version
}

// --- Hosts and severities ---

@Composable
fun HostBadge(host: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = HostColor.of(host)
    Row(
        modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.92f), color)))
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 5.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 4.dp),
    ) {
        Icon(PrismIcons.Server, null, Modifier.size(if (large) 16.dp else 12.dp), tint = Color.White)
        Text(
            host,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class Severity { Critical, Warning, Info;

    companion object {
        /** The look of an alert's level; a warning while it is unknown. */
        fun of(level: AlertLevel?): Severity = when (level) {
            AlertLevel.CRITICAL -> Critical
            AlertLevel.INFO -> Info
            else -> Warning
        }
    }
}

@Composable
fun Severity.tone(): Tone = when (this) {
    Severity.Critical -> MaterialTheme.prism.critical
    Severity.Warning -> MaterialTheme.prism.warning
    Severity.Info -> MaterialTheme.prism.info
}

fun Severity.icon(): ImageVector = when (this) {
    Severity.Critical -> PrismIcons.Octagon
    Severity.Warning -> PrismIcons.Triangle
    Severity.Info -> PrismIcons.InfoCircle
}

/** Severity at a glance: its own Expressive shape, colour and symbol. */
@Composable
fun SeverityBadge(severity: Severity, modifier: Modifier = Modifier, size: Dp = 44.dp, strong: Boolean = false) {
    val tone = severity.tone()
    val shape = when (severity) {
        Severity.Critical -> MaterialShapes.SoftBurst
        Severity.Warning -> MaterialShapes.Cookie6Sided
        Severity.Info -> MaterialShapes.Circle
    }
    // Strong: the full colour, for a badge that sits on the tone's container.
    ShapedIcon(severity.icon(), shape, if (strong) tone.color else tone.container, if (strong) tone.onColor else tone.onContainer,
        modifier, size, contentDescription = severity.name)
}

/** An icon on an Expressive shape (MaterialShapes). */
@Composable
fun ShapedIcon(
    icon: ImageVector,
    polygon: RoundedPolygon,
    container: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    rotation: Float = 0f,
    contentDescription: String? = null,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = rotation }
                .clip(polygon.toShape())
                .background(container),
        )
        Icon(icon, contentDescription, Modifier.size(size * 0.5f), tint = tint)
    }
}

/** A shape between two polygons, at `progress` (0 = start, 1 = end). */
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    private val matrix = Matrix()
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path: Path = morph.toPath(progress)
        matrix.reset()
        matrix.translate(size.width / 2, size.height / 2)
        matrix.rotateZ(rotation)
        matrix.translate(-size.width / 2, -size.height / 2)
        matrix.scale(size.width, size.height)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

// --- Layout pieces ---

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { contentDescription = text },
        )
        trailing?.invoke()
    }
}

/** A grouped surface: the Expressive list look (big outer corners). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = MaterialTheme.shapes.large,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = shape, color = color) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A page title that keeps the top bar's responsive type size. */
@Composable
fun PageTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold)
}

/** One focus and accessibility target for the label, description, and switch. */
@Composable
fun SettingToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
) {
    Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Settings that belong together share tighter corners on their adjoining edges. */
@Composable
fun SettingGroup(count: Int, content: @Composable (Int, Shape) -> Unit) {
    val outer = MaterialTheme.shapes.large
    val inner = CornerSize(4.dp)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { index ->
            val shape = outer.copy(
                topStart = if (index == 0) outer.topStart else inner,
                topEnd = if (index == 0) outer.topEnd else inner,
                bottomStart = if (index == count - 1) outer.bottomStart else inner,
                bottomEnd = if (index == count - 1) outer.bottomEnd else inner,
            )
            content(index, shape)
        }
    }
}

/** Monospace text in a well, selectable, scrolling sideways for long lines. */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    prompt: String? = null,
    style: androidx.compose.ui.text.TextStyle = CodeStyle,
    wrap: Boolean = true,
) {
    // Its selection takes D-pad focus (to copy with a keyboard): a ring shows it.
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.secondary) else null,
    ) {
        SelectionContainer {
            val inner = if (wrap) Modifier.padding(16.dp) else Modifier.horizontalScroll(rememberScrollState()).padding(16.dp)
            Row(inner) {
                if (prompt != null) {
                    Text(prompt, style = style, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = style, color = MaterialTheme.colorScheme.onSurface, softWrap = wrap)
            }
        }
    }
}

/** A label and its value on one line. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor)
    }
}

enum class Health { Good, Busy, Bad, Off }

@Composable
fun Health.color(): Color = when (this) {
    Health.Good -> MaterialTheme.prism.success.color
    Health.Busy -> MaterialTheme.prism.warning.color
    Health.Bad -> MaterialTheme.colorScheme.error
    Health.Off -> MaterialTheme.colorScheme.outline
}

@Composable
fun StatusDot(health: Health, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = health.color()
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** A row in a status panel: shaped icon, title, one line of detail, and an optional action. */
@Composable
fun StatusRow(
    icon: ImageVector,
    title: String,
    detail: String,
    health: Health,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val tone = when (health) {
        Health.Good -> MaterialTheme.prism.success
        Health.Busy -> MaterialTheme.prism.warning
        Health.Bad -> MaterialTheme.prism.critical
        Health.Off -> Tone(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShapedIcon(icon, MaterialShapes.Cookie9Sided, tone.container, tone.onContainer, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                StatusDot(health, size = 8.dp)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

/**
 * A stop for D-pad focus (a TV's remote, a keyboard) on something that
 * isn't clickable: focus can reach it, which scrolls it into view, and the
 * theme's focus ring shows it in [shape]. Only for what holds nothing
 * focusable itself: focus that lands on it doesn't move on into it.
 */
@Composable
fun Modifier.dpadStop(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return indication(interaction, ripple(focusRingShape = shape)).focusable(interactionSource = interaction)
}

/** A window less than 600 dp tall (a phone on its side, a TV): top bars stay small. */
@Composable
fun shortWindow(): Boolean {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return height > 0.dp && height < 600.dp
}

/**
 * A list's content padding inside the screen's [padding] (its bars and, on
 * a wide window, the gutters that keep the list readable): 16 dp more to
 * the sides, [top] and [bottom] as given.
 */
@Composable
fun listPadding(padding: PaddingValues, top: Dp, bottom: Dp): PaddingValues {
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction) + 16.dp, end = padding.calculateEndPadding(direction) + 16.dp,
        top = top, bottom = bottom,
    )
}

/** Lines of text no wider than this: the gutters' width on a wide window. */
val READABLE_WIDTH = 840.dp

/** The side gutters that keep content in [width] at most [READABLE_WIDTH] wide. */
fun readableGutter(width: Dp): Dp = ((width - READABLE_WIDTH) / 2).coerceAtLeast(0.dp)

/** [focusable]: a stop for D-pad focus (dpadStop) while it shows nothing to act on. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth()
            .then(if (focusable && action == null) Modifier.dpadStop() else Modifier)
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val spin = rememberInfiniteTransition(label = "empty")
        val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "angle")
        ShapedIcon(icon, MaterialShapes.Cookie12Sided, MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer, size = 88.dp, rotation = angle)
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        action?.invoke()
    }
}

/** The agent's own words, which nothing verified: a dashed outline. */
fun Modifier.dashedBorder(color: Color, radius: Dp = 20.dp): Modifier = drawBehind {
    val stroke = Stroke(
        width = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
    )
    drawRoundRect(color, style = stroke, cornerRadius = CornerRadius(radius.toPx()))
}

// --- The brand glyph ---

/**
 * Prism's mark, as in the launcher icon and the Mac's menu bar: a beam into a
 * prism and three coloured rays out of it, which slowly breathe. The prism
 * and beam take `glass` (the content colour); the rays keep their colours.
 * Same geometry as android/icon.py.
 */
@Composable
fun SpectrumGlyph(modifier: Modifier = Modifier, animate: Boolean = true, glass: Color = LocalContentColor.current) {
    val t = if (animate) {
        val transition = rememberInfiniteTransition(label = "spectrum")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Reverse), label = "t").value
    } else 1f
    Canvas(modifier) {
        // The glyph's box in the icon's 108 canvas: x 21..86, y 25..74.
        val s = minOf(size.width / 65f, size.height / 49f)
        val ox = (size.width - 65f * s) / 2 - 21f * s
        val oy = (size.height - 49f * s) / 2 - 25f * s
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        val stroke = 5.2f * s
        val exit = p(59f, 50.5f)
        val rays = listOf(
            Triple(p(80.5f, 41f), Color(0xFFFF6B6B), 0f),
            Triple(p(82.5f, 50.5f), Color(0xFFFFC94A), 0.33f),
            Triple(p(80.5f, 60f), Color(0xFF4FE0D0), 0.66f),
        )
        for ((end, color, phase) in rays) {
            // Each ray reaches out and back a little, one after the other.
            val reach = 0.86f + 0.14f * kotlin.math.abs(kotlin.math.sin(Math.PI * (t + phase))).toFloat()
            drawLine(color, exit, exit + (end - exit) * reach, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawLine(glass, p(24f, 57f), p(41f, 50.5f), strokeWidth = stroke, cap = StrokeCap.Round)
        val apex = p(50f, 28f)
        val bl = p(26.5f, 70.5f)
        val br = p(73.5f, 70.5f)
        val tri = Path().apply { moveTo(apex.x, apex.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close() }
        // Filled, with round corners from a stroke of the same colour.
        drawPath(tri, glass)
        drawPath(tri, glass, style = Stroke(3f * s, join = StrokeJoin.Round))
        val faceBase = p(26.5f + 47f * 0.6f, 70.5f)
        val face = Path().apply { moveTo(apex.x, apex.y); lineTo(br.x, br.y); lineTo(faceBase.x, faceBase.y); close() }
        drawPath(face, Color.Black.copy(alpha = 0.16f))
    }
}

/** Time since a Unix timestamp, as the Mac says it. */
fun ago(unix: Long, now: Long = System.currentTimeMillis() / 1000): String {
    if (unix <= 0) return ""
    val s = now - unix
    return when {
        s < 90 -> "just now"
        s < 5400 -> "${s / 60} min ago"
        s < 172800 -> "${s / 3600} h ago"
        else -> "${s / 86400} days ago"
    }
}

/** A thin rounded outline in the scheme's outline variant. */
fun Modifier.outlined(shape: Shape = RoundedCornerShape(20.dp), color: Color? = null): Modifier =
    this.then(Modifier.border(1.dp, color ?: Color(0x22808080), shape))
