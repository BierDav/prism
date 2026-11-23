// The hosts' performance: CPU, memory, disk, network, load and uptime of every
// fleet host and cluster node, with the last two hours of CPU and memory, from
// the Prism server (GET /v1/machines/metrics, docs/prism-api.md). It reloads
// every 15 seconds while it is on screen and the app in front; tap a host for
// its filesystems.

package at.quickme.prism

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import at.quickme.prism.shared.HostFormat
import at.quickme.prism.ui.EmptyState
import at.quickme.prism.ui.listPadding
import at.quickme.prism.ui.Health
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.StatusDot
import at.quickme.prism.ui.ago
import at.quickme.prism.ui.prism
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import at.quickme.prism.shared.HostMetrics
import at.quickme.prism.shared.PrismError

/** What the Machines tab shows: loaded from the API, kept across tab switches. */
class MachinesState {
    var hosts by mutableStateOf<List<HostMetrics>?>(null)
    var time by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    suspend fun load(c: Context) {
        if (loading || !PrismApi.enrolled(c)) return
        loading = true
        error = try {
            val metrics = PrismApi.call(c) { hostMetrics() }
            hosts = metrics.hosts
            time = metrics.time
            null
        } catch (e: PrismError) {
            e.message
        } finally {
            loading = false
        }
    }
}

/**
 * Fresh numbers while the tab is on screen and the app in front; the server
 * caches for 15 seconds. Home runs it beside the tab.
 */
@Composable
fun MachinesPoll(state: MachinesState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        val poll = scope.launch {
            while (true) {
                state.load(context)
                delay(15_000)
            }
        }
        onPauseOrDispose { poll.cancel() }
    }
}

@Composable
fun MachinesTab(state: MachinesState, padding: PaddingValues, enrolled: Boolean = PrismApi.enrolled(LocalContext.current)) {
    // One column on a phone; side by side, as many as fit, where the window is wide.
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        val inner = listPadding(padding, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)
        val direction = LocalLayoutDirection.current
        val width = maxWidth - inner.calculateStartPadding(direction) - inner.calculateEndPadding(direction)
        val columns = ((width + 12.dp) / (MACHINE_WIDTH + 12.dp)).toInt().coerceIn(1, 3)
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Fixed(columns),
            Modifier.fillMaxSize(),
            contentPadding = inner,
            verticalItemSpacing = if (columns == 1) 4.dp else 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!enrolled) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    EmptyState(PrismIcons.Server, "Connect to Prism", "Connect on the Alerts tab to see your machines.")
                }
                return@LazyVerticalStaggeredGrid
            }
            state.error?.let { err -> item(span = StaggeredGridItemSpan.FullLine) { ErrorCard(err) } }
            val hosts = state.hosts
            when {
                hosts == null && state.loading -> item(span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator(Modifier.size(72.dp))
                    }
                }
                hosts != null && hosts.isEmpty() -> item(span = StaggeredGridItemSpan.FullLine) {
                    EmptyState(PrismIcons.Server, "No metrics", "No machine has reported metrics in the last week.")
                }
                hosts != null -> {
                    val (online, offline) = hosts.partition { it.online }
                    for ((title, list) in listOf("Online" to online, "Offline" to offline)) {
                        if (list.isEmpty()) continue
                        item(key = "h-$title", span = StaggeredGridItemSpan.FullLine) {
                            SectionHeader(title, Modifier.padding(top = 12.dp, start = 4.dp)) {
                                Text("${list.size}", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        itemsIndexed(list, key = { _, h -> "m-" + h.name }) { i, h ->
                            HostCard(h, state.time, if (columns == 1) segmentShape(i, list.size) else MaterialTheme.shapes.large)
                        }
                    }
                }
            }
        }
    }
}

/** A machine's card at least this wide: side by side where that many fit. */
private val MACHINE_WIDTH = 340.dp

@Composable
private fun HostCard(host: HostMetrics, now: Long, shape: Shape) {
    val name = host.name
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val online = host.online
    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostBadge(name)
                StatusDot(if (online) Health.Good else Health.Off, size = 8.dp)
                val about = if (online) {
                    listOfNotNull(
                        host.os.takeIf { it.isNotEmpty() },
                        host.bootTime.takeIf { it > 0 }?.let { "up " + HostFormat.duration(now - it) },
                    ).joinToString(" · ")
                } else {
                    "last seen " + ago(host.lastSeen, now)
                }
                Text(about, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (online) {
                Meter(
                    "CPU", host.cpu,
                    listOfNotNull(
                        if (host.cores > 0) "${host.cores} cores" else null,
                        host.load.firstOrNull()?.let { "load " + HostFormat.load(it) },
                    ).joinToString(" · "),
                )
                Meter("Memory", host.memory?.share, host.memory?.let(::usageText).orEmpty())
                Meter("Disk", host.disk?.usage?.share, host.disk?.usage?.let(::usageText).orEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    host.network?.let { n ->
                        Fact(PrismIcons.Mesh, "↓ " + HostFormat.rate(n.receive) + "  ↑ " + HostFormat.rate(n.transmit))
                    }
                    host.battery?.let { b ->
                        Fact(PrismIcons.Battery, HostFormat.percent(b.level) + if (b.charging) " · charging" else "")
                    }
                }
            }
            val cpuHistory = host.history?.cpu.orEmpty()
            if (cpuHistory.any { it != null }) {
                Sparkline("CPU, last 2 hours", cpuHistory, MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                val memHistory = host.history?.memory.orEmpty()
                if (memHistory.any { it != null }) {
                    Sparkline("Memory, last 2 hours", memHistory, MaterialTheme.colorScheme.tertiary)
                }
                Details(host)
            }
        }
    }
}

/** A share as a labelled bar; high and nearly full in the warning and critical colours, the number always in text. */
@Composable
private fun Meter(label: String, share: Double?, detail: String) {
    val level = share?.let { HostFormat.level(it) } ?: 0
    val fill = when (level) {
        2 -> MaterialTheme.prism.critical.color
        1 -> MaterialTheme.prism.warning.color
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
            Text(share?.let { HostFormat.percent(it) } ?: "–", style = MaterialTheme.typography.titleSmall,
                color = if (level == 0) MaterialTheme.colorScheme.onSurface else fill)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = "$label ${share?.let { HostFormat.percent(it) } ?: "unknown"}" },
        ) {
            if (share != null && share > 0) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0.02, 1.0).toFloat())
                    .clip(RoundedCornerShape(4.dp)).background(fill))
            }
        }
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One series 0–1 over time, as a 2 dp line over a faint area; gaps where the host reported nothing. */
@Composable
private fun Sparkline(title: String, points: List<Double?>, color: Color) {
    val latest = points.lastOrNull { it != null }
    val peak = points.filterNotNull().maxOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            if (peak != null) {
                Text("peak " + HostFormat.percent(peak), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val grid = MaterialTheme.colorScheme.outlineVariant
        Canvas(
            Modifier.fillMaxWidth().height(44.dp)
                .semantics { contentDescription = "$title: now ${latest?.let { HostFormat.percent(it) } ?: "unknown"}, peak ${peak?.let { HostFormat.percent(it) } ?: "unknown"}" },
        ) {
            val n = points.size
            if (n < 2) return@Canvas
            val stroke = 2.dp.toPx()
            val top = stroke
            val h = size.height - stroke * 2
            fun x(i: Int) = size.width * i / (n - 1)
            fun y(v: Double) = top + h * (1 - v.coerceIn(0.0, 1.0)).toFloat()
            // The baseline and the 50% line, recessive.
            drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, y(0.5)), Offset(size.width, y(0.5)), strokeWidth = 1.dp.toPx())
            // Each unbroken run as its own line and area.
            var i = 0
            while (i < n) {
                if (points[i] == null) {
                    i++
                    continue
                }
                val start = i
                while (i < n && points[i] != null) i++
                val run = start until i
                if (run.count() == 1) {
                    drawCircle(color, radius = stroke, center = Offset(x(start), y(points[start]!!)))
                    continue
                }
                val line = Path().apply {
                    run.forEachIndexed { k, j -> if (k == 0) moveTo(x(j), y(points[j]!!)) else lineTo(x(j), y(points[j]!!)) }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(run.last), y(0.0))
                    lineTo(x(run.first), y(0.0))
                    close()
                }
                drawPath(area, color.copy(alpha = 0.14f))
                drawPath(line, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** "29.6 GB of 48 GB". */
private fun usageText(u: HostMetrics.Usage) = HostFormat.bytes(u.used) + " of " + HostFormat.bytes(u.total)

/** Tap for more: load averages, swap, every filesystem, the architecture. */
@Composable
private fun Details(host: HostMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (host.load.size == 3) Detail("Load", host.load.joinToString("  ") { HostFormat.load(it) } + "  (1, 5, 15 min)")
        host.swap?.let { Detail("Swap", usageText(it)) }
        host.filesystems.forEach { fs ->
            Detail(fs.mount, HostFormat.percent(fs.usage.share ?: 0.0) + " · " + usageText(fs.usage))
        }
        host.arch.takeIf { it.isNotEmpty() }?.let { Detail("Arch", it) }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
    }
}
