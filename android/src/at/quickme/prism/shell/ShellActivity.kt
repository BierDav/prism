// Shell access: whether the shell server runs, whether adbd listens on TCP
// 5555, pairing the cluster's adb key (Pairing.kt), which apps may use the
// server through Shizuku's API (and taking that back), what the owner must
// keep set for the cluster to start it, and a test. Opened with
// Shell.openSetup(), from the Phone tab; with Pairing.EXTRA_PAIR (the pairing
// notification's "Try again") it starts pairing at once.

package at.quickme.prism.shell

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.quickme.prism.Sample
import at.quickme.prism.ui.CodeBlock
import at.quickme.prism.ui.CodeStyle
import at.quickme.prism.ui.Health
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.StatusRow
import at.quickme.prism.ui.dpadStop
import at.quickme.prism.ui.prism
import at.quickme.prism.ui.rememberChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PrismTheme { ShellScreen(onBack = ::finish) } }
        if (savedInstanceState == null) pairIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairIfAsked(intent)
    }

    private fun pairIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(Pairing.EXTRA_PAIR, false) != true) return
        intent.removeExtra(Pairing.EXTRA_PAIR)
        if (!Sample.on(this)) Shell.pairCluster(this)
    }

    override fun onResume() {
        super.onResume()
        Shell.front()
    }
}

/** adb as the screen shows it: Wireless debugging, and adbd on TCP 5555; null where unknown. */
internal data class AdbState(val wireless: Boolean?, val tcp5555: Boolean?)

private val STEPS = listOf(
    "Settings › System › Developer options › Wireless debugging: on. Android turns it off when you leave the Wi-Fi " +
        "network; Prism turns it back on on networks you allowed with \"Always allow on this network\". On a new " +
        "network, turn it on once and allow the network.",
    "Wi-Fi and NetBird connected.",
    "After a reboot, unlock the phone once on an allowed Wi-Fi; the server is back within a minute or two, and the " +
        "cluster turns on adb over TCP 5555 (until the next reboot). Prism keeps asking the cluster until it runs.",
    "Only once (and after a reset): \"Pair with the cluster\" above. In Wireless debugging, tap \"Pair device with " +
        "pairing code\" and type the code into Prism's notification, without leaving the dialog.",
    "Agents use the cluster's adb server (phone-adb), so the cluster's key is the only one to allow. If the phone " +
        "asks to allow another computer, deny it.",
)

@Composable
private fun ShellScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val changes = rememberChanges(Shell::listen to Shell::unlisten)
    val sample = remember { Sample.on(context) }
    var reload by remember { mutableIntStateOf(0) }
    var adb by remember { mutableStateOf(AdbState(null, null)) }
    var apps by remember { mutableStateOf<List<ShellApps.App>>(emptyList()) }
    val running = remember(changes) { sample || Shell.available() }
    val status = remember(changes) { if (sample) Sample.SHELL_STATUS else Shell.statusText() }
    val pairing = remember(changes) { Pairing.state }
    LaunchedEffect(changes, reload) {
        if (sample) {
            adb = AdbState(true, true)
            apps = Sample.shellApps()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val wireless = try {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) != 0
            } catch (e: RuntimeException) {
                null
            }
            val tcp = Signal.adbTcp() != null
            adb = AdbState(wireless, if (tcp || Shell.available()) tcp else null)
            apps = ShellApps.list(context)
        }
    }
    val scope = rememberCoroutineScope()
    ShellContent(
        running, status, adb, apps, onBack,
        pairing = pairing,
        onAsk = { Shell.askCluster() },
        onPair = { if (!sample) (context as? android.app.Activity)?.let { Shell.pairCluster(it) } },
        onDecision = { pkg, state ->
            scope.launch {
                withContext(Dispatchers.IO) { if (!sample) ShellApps.set(context, pkg, state) }
                reload++
            }
        },
    )
}

/** The shell screen for a server that is [running] with [status]; the live screen and the previews pass them in. */
@Composable
internal fun ShellContent(
    running: Boolean,
    status: String,
    adb: AdbState,
    apps: List<ShellApps.App>,
    onBack: () -> Unit,
    pairing: Pairing.State = Pairing.State(Pairing.Phase.IDLE),
    onAsk: () -> Unit = {},
    onPair: () -> Unit = {},
    onDecision: (String, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Shell access") },
                subtitle = { Text("Commands as uid 2000, for Prism and apps on Shizuku's API") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            // Below the top bar, not under it: D-pad focus moves down from the bar's buttons
            // only into what lies wholly below them (the list counts as one block).
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val tone = if (running) MaterialTheme.prism.success else MaterialTheme.prism.warning
                val spin = rememberInfiniteTransition(label = "shell")
                val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "angle")
                Surface(Modifier.fillMaxWidth().dpadStop(MaterialTheme.shapes.extraLarge), shape = MaterialTheme.shapes.extraLarge, color = tone.container) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ShapedIcon(PrismIcons.Terminal, MaterialShapes.Cookie9Sided, tone.color, tone.onColor, size = 64.dp,
                            rotation = if (running) 0f else angle)
                        Column(Modifier.weight(1f).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (running) "Running" else "Not running", style = MaterialTheme.typography.headlineSmallEmphasized,
                                color = tone.onContainer)
                            Text(status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium,
                                color = tone.onContainer.copy(alpha = 0.85f))
                        }
                    }
                }
            }
            item {
                Panel(Modifier.dpadStop()) {
                    StatusRow(
                        PrismIcons.Phone, "Wireless debugging",
                        when (adb.wireless) {
                            true -> "On"
                            false -> "Off: the cluster can't reach adb"
                            null -> "Unknown"
                        },
                        when (adb.wireless) {
                            true -> Health.Good
                            false -> Health.Bad
                            null -> Health.Off
                        },
                    )
                    StatusRow(
                        PrismIcons.Mesh, "adb over TCP 5555",
                        when (adb.tcp5555) {
                            true -> "On until the next reboot: adb connect david-pixel.netbird.cloud:5555"
                            false -> "Off: the cluster turns it on after it started the server"
                            null -> "Unknown while the server is down"
                        },
                        when (adb.tcp5555) {
                            true -> Health.Good
                            false -> Health.Busy
                            null -> Health.Off
                        },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            testing = true
                            output = null
                            scope.launch {
                                output = withContext(Dispatchers.IO) {
                                    try {
                                        val r = Shell.run(10_000, "id")
                                        "$r\n${r.stdout}${r.stderr}"
                                    } catch (e: IOException) {
                                        "Failed: ${e.message}"
                                    }
                                }
                                testing = false
                            }
                        },
                        enabled = !testing,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
                    ) {
                        if (testing) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.size(6.dp))
                        Text("Test: run id")
                    }
                    if (!running) {
                        FilledTonalButton(
                            onClick = {
                                asked = true
                                onAsk()
                            },
                            enabled = !asked,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
                        ) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (asked) "Asked" else "Ask the cluster now")
                        }
                    }
                }
            }
            output?.let { o -> item { CodeBlock(o.trim(), style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize)) } }
            item { PairPanel(running, pairing, onPair) }
            item { SectionHeader("Apps on Shizuku's API", Modifier.padding(start = 4.dp)) }
            item {
                // Rows with buttons take focus themselves; with none, the panel does.
                Panel(if (apps.isEmpty()) Modifier.dpadStop() else Modifier) {
                    if (apps.isEmpty()) {
                        Text(
                            if (running) "No app declares Shizuku's permission." else "The list comes from the shell server.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (a in apps) AppRow(a, onDecision)
                }
            }
            item {
                Panel(Modifier.dpadStop()) {
                    Text(
                        "Prism runs commands as the adb shell user through its own shell server, derived from Shizuku, " +
                            "and offers it to apps built on Shizuku's API as Shizuku would; each app asks once. Android " +
                            "ends the server at every reboot; the cluster starts it again over adb, through NetBird, " +
                            "when Prism reports it down.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionHeader("Keep set on this phone", Modifier.padding(start = 4.dp)) }
            items(STEPS.size) { i -> Step(i + 1, STEPS[i]) }
            item {
                FilledTonalButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (e: ActivityNotFoundException) {
                            output = "Developer options are off: Settings › About phone › tap Build number seven times."
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.SettingsIcon, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Open Developer options")
                }
            }
        }
    }
}

/** Pairing the cluster's adb key from here (Pairing.kt): what is happening, and the button. */
@Composable
private fun PairPanel(running: Boolean, pairing: Pairing.State, onPair: () -> Unit) {
    val busy = pairing.phase == Pairing.Phase.SENDING || pairing.phase == Pairing.Phase.SENT
    val (detail, health) = when (pairing.phase) {
        Pairing.Phase.IDLE -> (if (running) "The cluster's adb key is paired; pair again only after a reset or a new key."
            else "Once per phone, and after a reset: the cluster's adb key needs the phone's pairing code.") to Health.Off
        Pairing.Phase.WAITING -> pairing.text to Health.Busy
        Pairing.Phase.SENDING, Pairing.Phase.SENT -> pairing.text to Health.Busy
        Pairing.Phase.PAIRED -> pairing.text to Health.Good
        Pairing.Phase.FAILED -> pairing.text to Health.Bad
    }
    Panel {
        StatusRow(PrismIcons.Mesh, "Pair with the cluster", detail, health)
        Row(Modifier.padding(start = 60.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = when (pairing.phase) {
                Pairing.Phase.WAITING -> "Open Wireless debugging"
                Pairing.Phase.FAILED -> "Try again"
                Pairing.Phase.SENDING -> "Sending…"
                Pairing.Phase.SENT -> "Waiting for the cluster"
                else -> if (running) "Pair again" else "Pair with the cluster"
            }
            val content: @Composable () -> Unit = {
                if (busy) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Rounded.SettingsIcon, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(label)
            }
            if (running && pairing.phase == Pairing.Phase.IDLE) {
                OutlinedButton(onClick = onPair, shapes = ButtonDefaults.shapes()) { content() }
            } else {
                FilledTonalButton(onClick = onPair, enabled = !busy, shapes = ButtonDefaults.shapes()) { content() }
            }
        }
    }
}

@Composable
private fun AppRow(a: ShellApps.App, onDecision: (String, Int) -> Unit) {
    val (detail, health) = when (a.decision) {
        Protocol.DECISION_ALLOWED -> (if (a.attached) "Allowed · connected" else "Allowed") to Health.Good
        Protocol.DECISION_DENIED -> "Denied" to Health.Bad
        else -> "Not decided: it asks when it needs the shell" to Health.Off
    }
    StatusRow(PrismIcons.Terminal, a.label, a.packageName + " · " + detail, health)
    Row(Modifier.padding(start = 60.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (a.decision) {
            Protocol.DECISION_ALLOWED -> TextButton(onClick = { onDecision(a.packageName, Protocol.DECISION_NONE) }, shapes = ButtonDefaults.shapes()) {
                Text("Revoke", color = MaterialTheme.colorScheme.error)
            }
            Protocol.DECISION_DENIED -> TextButton(onClick = { onDecision(a.packageName, Protocol.DECISION_NONE) }, shapes = ButtonDefaults.shapes()) {
                Text("Forget: ask again")
            }
            else -> {
                TextButton(onClick = { onDecision(a.packageName, Protocol.DECISION_ALLOWED) }, shapes = ButtonDefaults.shapes()) { Text("Allow") }
                TextButton(onClick = { onDecision(a.packageName, Protocol.DECISION_DENIED) }, shapes = ButtonDefaults.shapes()) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.dpadStop(MaterialTheme.shapes.medium).padding(4.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 5.dp))
    }
}
