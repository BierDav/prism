// The phone's own plumbing: NetBird (Prism's server and T3's web app are on
// it), Prism's push and metrics, the shell (uid 2000; shell/).

package at.quickme.prism

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.quickme.prism.netbird.Netbird
import at.quickme.prism.shell.Shell
import at.quickme.prism.shared.Placement
import at.quickme.prism.shared.PlacementProfile
import at.quickme.prism.ui.Health
import at.quickme.prism.ui.listPadding
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.ReorderableColumn
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.StatusRow
import at.quickme.prism.ui.rememberChanges

/** One status line per part of the plumbing, from the live state or, in the debug build, sample data. */
internal data class PhoneStatus(
    val netbird: Pair<Health, String>,
    /** The owner keeps NetBird on. */
    val netbirdOn: Boolean,
    val netbirdNeeds: Intent?,
    val netbirdConnected: Boolean,
    val exitNodes: List<Netbird.ExitNode>,
    /** The picked exit node's id, null for none. */
    val exitNode: String?,
    /** NetBird's resources: how many are switched on, of how many offered. */
    val resources: Pair<Int, Int>,
    val push: Pair<Health, String>,
    val metrics: Pair<Health, String>,
    val shell: Pair<Health, String>,
    val owner: Boolean,
    val updates: String,
    val hotspot: Pair<Health, String> = Health.Off to "Instant",
)

/** The hotspot's line: its mode, and whether it's on. */
internal fun hotspotStatus(c: Context): Pair<Health, String> = when {
    !Hotspot.supported() -> Health.Off to "Needs Android 16"
    Hotspot.mode(c) == Hotspot.NORMAL -> if (Hotspot.on) Health.Good to "Always on" else Health.Busy to "Always on · starting"
    Hotspot.on -> Health.Good to "Instant · on"
    else -> Health.Off to "Instant · comes on when your Mac asks"
}

@Composable
fun PhoneTab(padding: PaddingValues) {
    val context = LocalContext.current
    val changes = rememberChanges(
        PrismPush::listen to PrismPush::unlisten,
        Netbird::listen to Netbird::unlisten,
        Updater::listen to Updater::unlisten,
        Shell::listen to Shell::unlisten,
        Hotspot::listen to Hotspot::unlisten,
    )
    var released by remember { mutableIntStateOf(0) }
    val s = remember(changes, released) {
        if (Sample.on(context)) {
            Sample.phoneStatus("vps-falkenstein")
        } else {
            val consent = Netbird.needsConsent(context)
            PhoneStatus(
                netbirdHealth(context, consent),
                Netbird.on(context),
                consent,
                Netbird.connected(),
                Netbird.exitNodes(),
                Netbird.exitNode(context),
                Netbird.enabledResources(context).let { on -> Netbird.resources().let { all -> all.count { it.id in on } to all.size } },
                if (!PrismApi.enrolled(context)) {
                    Health.Off to "Not connected to Prism (no rocky_provisioning)"
                } else {
                    (if (PrismPush.connected()) Health.Good else Health.Busy) to
                        "${PrismPush.statusText().replaceFirstChar { it.uppercase() }} · replica ${PrismApi.status(context)}"
                },
                (if (Metrics.statusText().startsWith("pushed")) Health.Good else Health.Busy) to
                    Metrics.statusText().replaceFirstChar { it.uppercase() },
                (if (Shell.available()) Health.Good else Health.Busy) to Shell.statusText().replaceFirstChar { it.uppercase() },
                Dpc.owner(context),
                Updater.statusText(context).replaceFirstChar { it.uppercase() },
                hotspotStatus(context),
            )
        }
    }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
    }
    PhoneContent(
        padding, s, version,
        onNetbird = { context.startActivity(NetbirdActivity.intent(context)) },
        onHotspot = { context.startActivity(HotspotActivity.intent(context)) },
        onCheckUpdates = { Updater.checkSoon(context) },
        onShell = { Shell.openSetup(context) },
        placementSettings = {
            if (PrismApi.enrolled(context)) {
                PlacementSettings(
                    onOpenProfile = { id -> context.startActivity(PlacementActivity.profileIntent(context, id)) },
                    onOpenActions = { context.startActivity(PlacementActivity.actionsIntent(context)) },
                )
            }
        },
        onRelease = {
            if (!Sample.on(context)) Dpc.release(context)
            released++
        },
    )
}

/** The Phone tab as it shows [s]; the live tab (PhoneTab) and the previews pass it in. */
@Composable
internal fun PhoneContent(
    padding: PaddingValues,
    s: PhoneStatus,
    version: String,
    onNetbird: () -> Unit,
    onCheckUpdates: () -> Unit,
    onRelease: () -> Unit,
    placementSettings: @Composable () -> Unit,
    onShell: () -> Unit = {},
    onHotspot: () -> Unit = {},
) {
    var confirmRelease by remember { mutableStateOf(false) }
    LazyColumn(
        // Below the top bar, not under it: D-pad focus moves down from the bar's buttons
        // only into what lies wholly below them (the list counts as one block).
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = listPadding(padding, top = 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Network", Modifier.padding(start = 4.dp)) }
        item {
            Panel {
                StatusRow(PrismIcons.Mesh, "NetBird", netbirdSummary(s), s.netbird.first) {
                    FilledTonalIconButton(onClick = onNetbird) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "NetBird settings")
                    }
                }
                StatusRow(PrismIcons.Hotspot, "Hotspot", s.hotspot.second, s.hotspot.first) {
                    FilledTonalIconButton(onClick = onHotspot) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Hotspot settings")
                    }
                }
            }
        }
        item { SectionHeader("Connections", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            Panel {
                StatusRow(PrismIcons.Prism, "Alerts", s.push.second, s.push.first)
                StatusRow(PrismIcons.Battery, "Metrics", s.metrics.second, s.metrics.first)
                StatusRow(PrismIcons.Terminal, "Shell", s.shell.second, s.shell.first) {
                    FilledTonalIconButton(onClick = onShell) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Shell access")
                    }
                }
            }
        }
        item { placementSettings() }
        item { SectionHeader("Device", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            Panel {
                StatusRow(
                    PrismIcons.ShieldLock, "Owner",
                    if (s.owner) "Prism is the Device Owner" else "Not the Device Owner: updates need a tap",
                    if (s.owner) Health.Good else Health.Off,
                )
                StatusRow(PrismIcons.Bolt, "Updates", s.updates, Health.Off)
                Row(Modifier.padding(start = 60.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCheckUpdates, shapes = ButtonDefaults.shapes()) {
                        Text("Check for updates")
                    }
                    if (s.owner) {
                        TextButton(onClick = { confirmRelease = true }, shapes = ButtonDefaults.shapes()) {
                            Text("Release…", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Text("Version $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmRelease) {
        ReleaseOwnerDialog(
            onRelease = {
                confirmRelease = false
                onRelease()
            },
            onDismiss = { confirmRelease = false },
        )
    }
}

/**
 * "Exit node": None (only NetBird's networks through the tunnel) or one of the
 * exit nodes the network offers, which then carries all of the phone's traffic.
 */
@Composable
internal fun ExitNodePicker(
    nodes: List<Netbird.ExitNode>,
    picked: String?,
    connected: Boolean,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val current = nodes.firstOrNull { it.id == picked }
    val value = when {
        picked == null -> "None"
        current != null -> current.name
        else -> "$picked (not offered now)"
    }
    val note = when {
        !connected -> "Can be changed while NetBird is connected"
        picked == null -> "Only NetBird's networks go through the tunnel"
        current == null -> "Only NetBird's networks until the network offers it again"
        else -> "All traffic goes through ${peerName(current.peer).ifEmpty { current.name }}"
    }
    ExposedDropdownMenuBox(
        expanded = open && connected,
        onExpandedChange = { if (connected) open = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = connected,
            singleLine = true,
            label = { Text("Exit node") },
            supportingText = { Text(note) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && connected) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, connected).fillMaxWidth()
                // Read-only, it has no cursor to move: up and down move focus on.
                // (A text field keeps them from a keyboard or an injected key.)
                .onPreviewKeyEvent { e ->
                    val direction = when (e.key) {
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionUp -> FocusDirection.Up
                        else -> null
                    }
                    if (direction == null || open) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                    true
                },
        )
        ExposedDropdownMenu(
            expanded = open && connected,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.large,
        ) {
            ExitNodeItem("None", "NetBird's networks only", picked == null) {
                open = false
                onPick(null)
            }
            for (n in nodes) {
                val peer = peerName(n.peer)
                val detail = (if (peer.isEmpty()) "" else "via $peer") + if (n.online) "" else " · offline"
                ExitNodeItem(n.name, detail.removePrefix(" · ").ifEmpty { null }, n.id == picked) {
                    open = false
                    onPick(n.id)
                }
            }
        }
    }
}

@Composable
private fun ExitNodeItem(title: String, detail: String?, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingIcon = { if (checked) Icon(Icons.Rounded.Check, "Selected") },
        onClick = onClick,
    )
}

/** NetBird's status line: its health and what it says, with this phone's address. */
internal fun netbirdHealth(context: android.content.Context, consent: Intent?): Pair<Health, String> {
    val ip = Netbird.ip()
    val health = when {
        !Netbird.on(context) -> Health.Off
        Netbird.connected() -> Health.Good
        consent != null -> Health.Bad
        else -> Health.Busy
    }
    return health to (Netbird.statusText() + if (ip.isEmpty()) "" else " · $ip").replaceFirstChar { it.uppercase() }
}

/** The Phone tab's one NetBird line: off, or the status with the exit node in use. */
private fun netbirdSummary(s: PhoneStatus): String {
    if (!s.netbirdOn) return "Turned off"
    val exit = s.exitNode?.let { id -> s.exitNodes.firstOrNull { it.id == id }?.name ?: id }
    return if (exit == null) s.netbird.second else "${s.netbird.second} · exit node $exit"
}

/** A peer's NetBird name without the network's domain: david-server.netbird.cloud → david-server. */
private fun peerName(fqdn: String): String = fqdn.substringBefore('.')

/** Gives up the Device Owner role for good, after a clear warning. */
@Composable
private fun ReleaseOwnerDialog(onRelease: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(PrismIcons.ShieldLock, null) },
        title = { Text("Release device owner?") },
        text = {
            Text(
                "Prism stops being this phone's Device Owner for good: updates then need a tap, and Prism can be " +
                    "uninstalled. Only a factory reset and a new enrollment bring the role back.",
            )
        },
        confirmButton = {
            TextButton(onClick = onRelease) { Text("Release", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Placement profiles listed directly on the Phone tab, plus Add profile and Actions. */
@Composable
private fun PlacementSettings(
    onOpenProfile: (String) -> Unit,
    onOpenActions: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberPlacementSettings()
    val placement = state.placement
    val error = state.error
    val saving = state.saving

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            text = "Placement",
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            trailing = {
                if (saving) LoadingIndicator(Modifier.size(18.dp))
            },
        )
        if (error != null) {
            Panel(color = MaterialTheme.colorScheme.errorContainer) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = { state.reload(context) }) {
                    Text("Reload profiles", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        when {
            placement != null -> PlacementSection(
                placement = placement,
                onOpenProfile = onOpenProfile,
                onOpenActions = onOpenActions,
                onReorder = { ids -> state.order(context, ids) },
                onDeleteProfile = { id -> state.delete(context, id) },
                onCreateProfile = { label ->
                    val id = uniqueProfileId(label, placement.profiles.map { it.id }.toSet())
                    state.save(context, id, label.trim(), emptyList())
                    onOpenProfile(id)
                },
            )
            error == null -> Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Text("Loading profiles", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Profiles list (tap to open, hold-and-drag to reorder, long-press to delete), Add profile, and Actions. */
@Composable
internal fun PlacementSection(
    placement: Placement,
    onOpenProfile: (String) -> Unit,
    onOpenActions: () -> Unit,
    onReorder: (List<String>) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onCreateProfile: (String) -> Unit = {},
) {
    val hiddenModels = remember(placement.models) {
        placement.models.filter { it.hidden }.map { it.instanceId to it.model }.toSet()
    }
    var addingProfile by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<PlacementProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (placement.profiles.isNotEmpty()) {
                    ReorderableColumn(
                        items = placement.profiles,
                        key = { it.id },
                        enabled = true,
                        onReorder = { from, to ->
                            val ids = placement.profiles.map { it.id }.toMutableList()
                            ids.add(to, ids.removeAt(from))
                            onReorder(ids)
                        },
                        onLongPressRelease = { idx ->
                            deleteCandidate = placement.profiles.getOrNull(idx)
                        },
                    ) { _, profile, isDragging ->
                        val tags = buildList {
                            if (profile.id == placement.effectiveAlertFixProfile) add("Alert fixes")
                            if (profile.id == placement.effectiveTextGenerationProfile) add("Text generation")
                        }
                        PlacementProfileRow(
                            profile = profile,
                            hiddenModels = hiddenModels,
                            tags = tags,
                            isDragging = isDragging,
                            enabled = true,
                            onClick = { onOpenProfile(profile.id) },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { addingProfile = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Add profile",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        val fixLabel = placement.profiles.firstOrNull { it.id == placement.effectiveAlertFixProfile }?.displayLabel
            ?: placement.effectiveAlertFixProfile.ifEmpty { "None" }
        val textGenLabel = placement.effectiveTextGenerationProfile?.let { id ->
            placement.profiles.firstOrNull { it.id == id }?.displayLabel ?: id
        } ?: "None"
        Panel(modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onOpenActions)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Actions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Alert fixes: $fixLabel · Text generation: $textGenLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (addingProfile) {
        ProfileNameDialog(
            title = "New profile",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { label ->
                addingProfile = false
                onCreateProfile(label)
            },
            onDismiss = { addingProfile = false },
        )
    }

    deleteCandidate?.let { profile ->
        val usedBy = profileUsedBy(placement, profile.id)
        if (usedBy.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { deleteCandidate = null },
                title = { Text("Can't delete ${profile.displayLabel}") },
                text = {
                    Text("This profile is used by ${usedBy.joinToString(", ")}. Choose a different profile in Actions first.")
                },
                confirmButton = {
                    TextButton(onClick = { deleteCandidate = null }) { Text("OK") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { deleteCandidate = null },
                title = { Text("Delete ${profile.displayLabel}?") },
                text = { Text("Threads tagged with this profile will no longer be scheduled by it.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteCandidate = null
                        onDeleteProfile(profile.id)
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun PlacementProfileRow(
    profile: PlacementProfile,
    hiddenModels: Set<Pair<String, String>>,
    tags: List<String>,
    isDragging: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val enabledEntries = profile.entries.filter { it.enabled && (it.instanceId to it.model) !in hiddenModels }
    val supporting = when (enabledEntries.size) {
        0 -> if (profile.entries.any { it.enabled }) "No visible models enabled" else "No models enabled"
        1 -> "${enabledEntries.first().displayLabel} · 1 on"
        else -> "${enabledEntries.first().displayLabel} · ${enabledEntries.size} on"
    }
    val rowBg = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    profile.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                for (tag in tags) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun profileUsedBy(placement: Placement, profileId: String): List<String> = buildList {
    if (profileId == placement.effectiveAlertFixProfile) add("Alert fixes")
    if (profileId == placement.effectiveTextGenerationProfile) add("Text generation")
    placement.policy.roleProfiles.filterValues { it == profileId }.keys.forEach { role ->
        add("Delegated agents (${role.replaceFirstChar { it.uppercase() }})")
    }
}
