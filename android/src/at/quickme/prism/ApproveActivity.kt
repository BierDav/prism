// The approval screen: one pending approval exactly as it will be signed, in
// the layout of the Mac dialog. Approve asks for a fingerprint (BiometricPrompt
// with the device key's Signature, so the signature needs that touch), signs
// the approval (Approver.approve) and decides it; Deny says no. It shows over
// the lock screen; signing still needs the fingerprint. The inbox (Inbox.kt)
// shows the same screen as one of its pages.

package at.quickme.prism

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.quickme.prism.core.Text.visible
import at.quickme.prism.shared.Approval
import at.quickme.prism.shared.ApprovalDetail
import at.quickme.prism.shared.Approvals
import at.quickme.prism.shared.ThreadLink
import at.quickme.prism.ui.CodeStyle
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.HostColor
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.prism
import kotlinx.coroutines.delay

class ApproveActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "id"
    }

    private var id by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        id = intent.getStringExtra(EXTRA_ID)
        val queue = Approver.queue(this)
        setContent {
            PrismTheme {
                val pending by queue.pending.collectAsState()
                val approval = pending.firstOrNull { it.id == id }
                if (approval == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    ApproveScreen(
                        approval,
                        position = pending.indexOf(approval) + 1,
                        count = pending.size,
                        notice = notice,
                        onDeny = {
                            Approver.deny(this, it)
                            finish()
                        },
                        onApprove = {
                            notice = null
                            Approver.approve(this, it, onNotice = { n -> notice = n }, onDone = ::finish)
                        },
                        onOpenThread = { Approver.openThread(this, it) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Approver.setInFront(this, true)
    }

    override fun onPause() {
        Approver.setInFront(this, false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notice = null
        id = intent.getStringExtra(EXTRA_ID)
    }
}

/**
 * One approval with Deny and Approve, on a background that runs behind the
 * system bars. Also a page of the inbox pager, which passes the height of
 * its own header as [topPadding]; null pads for the status bar.
 */
@Composable
fun ApproveScreen(
    a: Approval,
    position: Int,
    count: Int,
    notice: String?,
    onDeny: (Approval) -> Unit,
    onApprove: (Approval) -> Unit,
    onOpenThread: (ThreadLink) -> Unit,
    topPadding: Dp? = null,
    canOpenThread: Boolean = Settings.t3Url(androidx.compose.ui.platform.LocalContext.current).isNotEmpty(),
) {
    val accent = HostColor.of(a.source)
    var decision by remember(a.id) { mutableStateOf(a.decision) }
    val chosen = Approvals.choosing(a, decision)

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .background(Brush.verticalGradient(0f to accent.copy(alpha = 0.28f), 0.45f to accent.copy(alpha = 0.06f), 1f to Color.Transparent)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .then(if (topPadding == null) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier.padding(top = topPadding))
                    .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Header(a, position, count)
                if (a.summary.isNotBlank()) {
                    Text(visible(a.summary), style = MaterialTheme.typography.bodyLarge)
                }
                if (a.details.isNotEmpty()) Details(a.details)
                for (option in a.options.filter { it.decision.startsWith("accept") }) {
                    FilledTonalButton(onClick = { decision = option.decision }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (decision == option.decision) "✓ " else "") + option.label)
                    }
                    if (decision == option.decision && option.warning.isNotBlank()) {
                        Text(visible(option.warning), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                a.thread?.let { ThreadCard(it, a.source, canOpenThread, onOpenThread) }
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
            Actions(chosen, onDeny = { onDeny(a) }, onApprove = { onApprove(chosen) })
        }
    }
}

@Composable
private fun Header(a: Approval, position: Int, count: Int) {
    val accent = HostColor.of(a.source)
    val spin = rememberInfiniteTransition(label = "badge")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "angle")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapedIcon(PrismIcons.Shield, MaterialShapes.Cookie9Sided, accent, Color.White, size = 64.dp, rotation = angle)
            Spacer(Modifier.weight(1f))
            if (count > 1) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text("$position of $count", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Text(visible(a.title), style = MaterialTheme.typography.headlineMediumEmphasized)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("from", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HostBadge(visible(a.source), large = true)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMediumEmphasized.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The facts to see before deciding, in order; code big enough to read at arm's length. */
@Composable
private fun Details(details: List<ApprovalDetail>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (d in details) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(d.label)
                if (d.code) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        SelectionContainer(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                visible(d.value, lines = true),
                                style = CodeStyle.copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.padding(18.dp),
                            )
                        }
                    }
                } else {
                    Text(visible(d.value), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/** The T3 thread that asks, with a way to open it. */
@Composable
private fun ThreadCard(t: ThreadLink, source: String, canOpen: Boolean, onOpen: (ThreadLink) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(PrismIcons.Chat, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Asked from T3 Code", style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary)
            }
            Text(t.title?.takeIf { it.isNotBlank() }?.let(::visible) ?: ("T3 thread " + t.threadId.takeLast(8)),
                style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("from", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HostBadge(visible(source))
            }
            FilledTonalButton(
                onClick = { onOpen(t) }, enabled = canOpen, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(PrismIcons.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in T3")
            }
        }
    }
}

/** The countdown and the two answers, always on screen, on the page's own background. */
@Composable
private fun Actions(a: Approval, onDeny: () -> Unit, onApprove: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val remaining = maxOf(0L, a.expiresAt - now / 1000)
    val total = maxOf(1L, a.expiresAt - a.requestedAt).toFloat()
    val urgent = remaining < 60
    val tint by animateColorAsState(if (urgent) MaterialTheme.prism.warning.color else MaterialTheme.colorScheme.primary, label = "urgent")
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (a.expiresAt != 0L) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularWavyProgressIndicator(progress = { remaining / total }, modifier = Modifier.size(32.dp), color = tint)
                Text(
                    "Expires in %d:%02d".format(remaining / 60, remaining % 60),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (urgent) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDeny,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(64.dp),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                ) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Deny", style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    onClick = onApprove,
                    enabled = a.decision.startsWith("accept"),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1.4f).height(64.dp),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                ) {
                    Icon(PrismIcons.Fingerprint, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Approve", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
