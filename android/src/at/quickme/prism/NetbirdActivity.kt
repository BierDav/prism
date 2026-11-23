// NetBird, from the Phone tab: the switch that turns it off and on, the exit
// node, and the resources routed through the tunnel (NetbirdResources.kt).

package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.quickme.prism.netbird.Netbird
import at.quickme.prism.ui.Health
import at.quickme.prism.ui.PageTitle
import at.quickme.prism.ui.SettingToggle
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.rememberChanges

class NetbirdActivity : ComponentActivity() {
    companion object {
        fun intent(c: Context): Intent = Intent(c, NetbirdActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PrismTheme { NetbirdScreen(onBack = ::finish) } }
    }
}

@Composable
private fun NetbirdScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val changes = rememberChanges(Netbird::listen to Netbird::unlisten)
    val sample = Sample.on(context)
    var sampleOn by remember { mutableStateOf(true) }
    var sampleExitNode by remember { mutableStateOf<String?>("vps-falkenstein") }
    // Turned on without the VPN consent: ask for it, then start.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) Netbird.start(context)
    }
    val onToggle = { on: Boolean ->
        if (sample) {
            sampleOn = on
        } else {
            Netbird.setOn(context, on)
            if (on) Netbird.needsConsent(context)?.let { consent.launch(it) }
        }
    }
    val onExitNode = { id: String? ->
        if (sample) sampleExitNode = id else Netbird.selectExitNode(context, id)
    }
    var sampleEnabled by remember { mutableStateOf(Sample.enabledResources) }
    val onResourceToggle = { id: String, on: Boolean ->
        if (sample) sampleEnabled = if (on) sampleEnabled + id else sampleEnabled - id
        else Netbird.enableResource(context, id, on)
    }
    if (sample) {
        val s = remember(sampleExitNode) { Sample.phoneStatus(sampleExitNode) }
        NetbirdContent(
            if (sampleOn) s.netbird else Health.Off to "Turned off", sampleOn, sampleOn && s.netbirdConnected,
            s.exitNodes, s.exitNode, Sample.resources, sampleEnabled, onToggle, onExitNode, onResourceToggle, onBack,
        )
    } else {
        val status = remember(changes) { netbirdHealth(context, Netbird.needsConsent(context)) }
        NetbirdContent(
            status, Netbird.on(context), Netbird.connected(), Netbird.exitNodes(), Netbird.exitNode(context),
            Netbird.resources(), Netbird.enabledResources(context), onToggle, onExitNode, onResourceToggle, onBack,
        )
    }
}

/** The page as it shows NetBird's state; the activity and the previews pass it in. */
@Composable
internal fun NetbirdContent(
    status: Pair<Health, String>,
    on: Boolean,
    connected: Boolean,
    exitNodes: List<Netbird.ExitNode>,
    exitNode: String?,
    resources: List<Netbird.Resource>,
    enabledResources: Set<String>,
    onToggle: (Boolean) -> Unit,
    onExitNode: (String?) -> Unit,
    onResourceToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle("NetBird") },
                subtitle = { Text(if (on) status.second else "Turned off") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingToggle(
                    title = "Use NetBird",
                    detail = if (on) "Prism's server and T3 Code are reached through NetBird"
                        else "Off until turned on here, also after a restart",
                    checked = on,
                    onCheckedChange = onToggle,
                )
            }
            if (on) {
                item { SectionHeader("Routing", Modifier.padding(start = 4.dp, top = 8.dp)) }
                item {
                    Panel {
                        ExitNodePicker(exitNodes, exitNode, connected, onPick = onExitNode)
                    }
                }
                netbirdResourceItems(resources, enabledResources, connected, onResourceToggle)
            }
        }
    }
}
