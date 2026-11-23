// Approvals on the main screen: what waits (each opens the approval screen;
// the inbox shows them too), the last decisions, and the phone's device key
// for the Prism server's configuration.

package at.quickme.prism

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.quickme.prism.core.Text.visible
import at.quickme.prism.shared.Approval
import at.quickme.prism.shared.Decision
import at.quickme.prism.shared.Outcome
import at.quickme.prism.ui.CodeStyle
import at.quickme.prism.ui.dpadStop
import at.quickme.prism.ui.listPadding
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.Tone
import at.quickme.prism.ui.prism
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Approvals tab shows, from the queue and the key; the previews pass their own. */
internal data class ApprovalsStatus(
    val pending: List<Approval>,
    val history: List<Decision>,
    /** The key's kind and public key, null while loading. */
    val key: Pair<String, String>?,
    /** Why the key can't be used, or null. */
    val keyFailure: String?,
    /** The debug build with sample data: it can raise a sample approval. */
    val sample: Boolean = false,
)

@Composable
fun ApprovalsTab(padding: PaddingValues) {
    val context = LocalContext.current
    val queue = remember { Approver.queue(context) }
    val pending by queue.pending.collectAsState()
    val history by queue.history.collectAsState()
    var key by remember { mutableStateOf<Pair<String, String>?>(null) }
    var keyFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val k = withContext(Dispatchers.IO) { DeviceKey.loadOrCreate(context) }
            key = (if (k.strongBox) "P-256 in StrongBox (Titan M)" else "P-256 in the TEE (no StrongBox)") to k.publicKeyLine()
        } catch (e: Exception) {
            keyFailure = "unusable: ${e.message}"
        }
    }
    ApprovalsContent(
        padding, ApprovalsStatus(pending, history, key, keyFailure, Sample.on(context)),
        onOpen = {
            context.startActivity(Intent(context, ApproveActivity::class.java).putExtra(ApproveActivity.EXTRA_ID, it.id))
        },
        onRaiseSample = { Approver.raiseSample(context) },
    )
}

/** The Approvals tab as it shows [s]. */
@Composable
internal fun ApprovalsContent(padding: PaddingValues, s: ApprovalsStatus, onOpen: (Approval) -> Unit, onRaiseSample: () -> Unit) {
    LazyColumn(
        // Below the top bar, not under it: D-pad focus moves down from the bar's buttons
        // only into what lies wholly below them (the list counts as one block).
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = listPadding(padding, top = 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Waiting", Modifier.padding(start = 4.dp)) }
        if (s.pending.isEmpty()) {
            item {
                Text("Nothing waits for your approval.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
        }
        itemsIndexed(s.pending, key = { _, a -> a.id }) { i, a -> PendingRow(a, segmentShape(i, s.pending.size)) { onOpen(a) } }
        if (s.sample) {
            item {
                FilledTonalButton(onClick = onRaiseSample, shapes = ButtonDefaults.shapes()) {
                    Icon(PrismIcons.Shield, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Raise a sample approval")
                }
            }
        }
        item { KeyPanel(s.key, s.keyFailure) }
        item { SectionHeader("Recent", Modifier.padding(start = 4.dp, top = 8.dp)) }
        if (s.history.isEmpty()) {
            item {
                Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
        itemsIndexed(s.history) { i, d -> HistoryRow(d, segmentShape(i, s.history.size)) }
    }
}

@Composable
private fun PendingRow(a: Approval, shape: Shape, onOpen: () -> Unit) {
    val warning = MaterialTheme.prism.warning
    Surface(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShapedIcon(PrismIcons.Shield, MaterialShapes.Cookie9Sided, warning.container, warning.onContainer, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(visible(a.title), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                HostBadge(visible(a.source))
            }
        }
    }
}

@Composable
private fun KeyPanel(key: Pair<String, String>?, failure: String?) {
    Panel(Modifier.dpadStop()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShapedIcon(PrismIcons.Key, MaterialShapes.Clover4Leaf, MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.weight(1f)) {
                Text("Device key", style = MaterialTheme.typography.titleLarge)
                Text(key?.first ?: failure ?: "Loading…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        key?.let { (_, pub) ->
            Text("Signs approvals and the Prism enrollment: the phone's key in the Prism server's configuration.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text("$pub prism@phone", style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight), modifier = Modifier.padding(14.dp))
            }
        }
    }
}

private data class OutcomeLook(val icon: ImageVector, val tone: Tone?, val text: String)

@Composable
private fun HistoryRow(d: Decision, shape: Shape) {
    val p = MaterialTheme.prism
    val look = when (d.outcome) {
        Outcome.APPROVED -> OutcomeLook(Icons.Rounded.Check, p.success, "Approved")
        Outcome.DENIED -> OutcomeLook(Icons.Rounded.Close, p.critical, "Denied")
        Outcome.EXPIRED -> OutcomeLook(PrismIcons.Clock, null, "Expired")
        Outcome.WITHDRAWN -> OutcomeLook(PrismIcons.Clock, null, "Withdrawn")
    }
    val time = remember(d.time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(d.time * 1000)) }
    Surface(Modifier.fillMaxWidth().dpadStop(shape), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShapedIcon(look.icon, MaterialShapes.Circle, look.tone?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                look.tone?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(look.text, style = MaterialTheme.typography.titleSmall, color = look.tone?.color ?: MaterialTheme.colorScheme.onSurface)
                    HostBadge(visible(d.source))
                    Spacer(Modifier.weight(1f))
                    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(visible(d.title), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
