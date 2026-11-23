// Placement detail pages, opened from the Phone tab:
// 1. Profile page: one profile's full-selection model list (drag to reorder,
//    compact rows, reasoning sheet, on/off switch, rename in top bar).
// 2. Actions page: alert fixes, text generation, and recent moves.
//    recent moves (docs/placement.md).

package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.quickme.prism.shared.Placement
import at.quickme.prism.shared.PlacementEntry
import at.quickme.prism.shared.PlacementModel
import at.quickme.prism.shared.PlacementMove
import at.quickme.prism.shared.PlacementProfile
import at.quickme.prism.ui.PageTitle
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.ProviderIcon
import at.quickme.prism.ui.ReorderableColumn
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.ago
import at.quickme.prism.ui.prism

class PlacementActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val PAGE_PROFILE = "profile"
        private const val PAGE_ACTIONS = "actions"

        fun profileIntent(c: Context, profileId: String): Intent =
            Intent(c, PlacementActivity::class.java)
                .putExtra(EXTRA_PAGE, PAGE_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)

        fun actionsIntent(c: Context): Intent =
            Intent(c, PlacementActivity::class.java)
                .putExtra(EXTRA_PAGE, PAGE_ACTIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_ACTIONS
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        setContent {
            PrismTheme {
                PlacementHost(page = page, profileId = profileId, onBack = ::finish)
            }
        }
    }
}

@Composable
private fun PlacementHost(page: String, profileId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val state = rememberPlacementSettings()
    val placement = state.placement
    val error = state.error
    val saving = state.saving

    if (page == "profile" && profileId.isNotEmpty()) {
        val profile = placement?.profiles?.firstOrNull { it.id == profileId }
        PlacementProfileContent(
            profile = profile,
            models = placement?.models.orEmpty(),
            loading = placement == null && error == null,
            saving = saving,
            error = error,
            onBack = onBack,
            onReload = { state.reload(context) },
            onSaveProfile = { id, label, entries -> state.save(context, id, label, entries) },
        )
    } else {
        PlacementActionsContent(
            placement = placement,
            saving = saving,
            error = error,
            onBack = onBack,
            onReload = { state.reload(context) },
            onAlertFix = { id -> state.actions(context, alertFix = id) },
            onTextGeneration = { id -> state.actions(context, textGeneration = id ?: "") },
        )
    }
}

/** One profile's detail page: compact full-selection list, drag to reorder, rename in top bar. */
@Composable
internal fun PlacementProfileContent(
    profile: PlacementProfile?,
    models: List<PlacementModel>,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSaveProfile: (id: String, label: String, entries: List<PlacementEntry>) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by remember { mutableStateOf(false) }
    var pickingReasoning by remember { mutableStateOf<Int?>(null) }
    var inspectingEntry by remember { mutableStateOf<PlacementEntry?>(null) }

    val modelsByKey = remember(models) {
        models.associateBy { it.instanceId to it.model }
    }
    val visibleEntries = profile?.entries.orEmpty().withIndex().filter { (_, entry) ->
        modelsByKey[entry.instanceId to entry.model]?.hidden != true
    }


    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle(profile?.displayLabel ?: "Profile") },
                subtitle = {
                    if (profile != null) {
                        val enabledCount = visibleEntries.count { it.value.enabled }
                        Text("$enabledCount of ${visibleEntries.size} enabled · Hold and drag to reorder")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (saving) {
                        LoadingIndicator(Modifier.padding(end = 12.dp).size(24.dp))
                    }
                    if (profile != null) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Rounded.Edit, "Rename profile")
                        }
                    }
                },
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
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item {
                    Panel(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onReload) {
                            Text("Reload", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (loading) {
                item {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Text("Loading profile", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                return@LazyColumn
            }

            if (profile == null) {
                item {
                    Panel {
                        Text("This profile no longer exists.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@LazyColumn
            }

            if (visibleEntries.isEmpty()) {
                item {
                    Panel {
                        Text("No visible models are available.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@LazyColumn
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    ReorderableColumn(
                        items = visibleEntries,
                        key = { "${it.value.instanceId}|${it.value.model}" },
                        enabled = visibleEntries.size > 1,
                        onReorder = { from, to ->
                            val next = profile.entries.toMutableList()
                            val reordered = visibleEntries.map { it.value }.toMutableList()
                            reordered.add(to, reordered.removeAt(from))
                            visibleEntries.forEachIndexed { i, entry -> next[entry.index] = reordered[i] }
                            onSaveProfile(profile.id, profile.label, next)
                        },
                    ) { _, (index, entry), isDragging ->
                        val modelInfo = modelsByKey[entry.instanceId to entry.model]
                        CompactEntryRow(
                            entry = entry,
                            modelInfo = modelInfo,
                            isDragging = isDragging,
                            onRowClick = {
                                if (modelInfo?.reasoning?.levels?.isNotEmpty() == true) {
                                    pickingReasoning = index
                                } else if (entry.reason.isNotEmpty() || entry.state.isNotEmpty()) {
                                    inspectingEntry = entry
                                }
                            },
                            onPickReasoning = { pickingReasoning = index },
                            onToggle = { on ->
                                val next = profile.entries.toMutableList()
                                next[index] = entry.copy(enabled = on)
                                onSaveProfile(profile.id, profile.label, next)
                            },
                        )
                    }
                }
            }
        }
    }

    if (renaming && profile != null) {
        ProfileNameDialog(
            title = "Rename profile",
            initial = profile.label,
            confirmLabel = "Save",
            onConfirm = { label ->
                renaming = false
                onSaveProfile(profile.id, label.trim(), profile.entries)
            },
            onDismiss = { renaming = false },
        )
    }

    pickingReasoning?.let { index ->
        val entry = profile?.entries?.getOrNull(index)
        val reasoning = entry?.let { modelsByKey[it.instanceId to it.model]?.reasoning }
        if (profile != null && entry != null && reasoning != null && reasoning.levels.isNotEmpty()) {
            ReasoningSheet(
                entry = entry,
                levels = reasoning.levels,
                defaultLevel = reasoning.defaultLevel,
                onPick = { level ->
                    pickingReasoning = null
                    val next = profile.entries.toMutableList()
                    next[index] = entry.copy(reasoning = level)
                    onSaveProfile(profile.id, profile.label, next)
                },
                onDismiss = { pickingReasoning = null },
            )
        } else {
            pickingReasoning = null
        }
    }

    inspectingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { inspectingEntry = null },
            title = { Text(entry.displayLabel) },
            text = {
                val lines = buildList {
                    if (entry.displayProvider.isNotEmpty()) add("Provider: ${entry.displayProvider}")
                    val status = entryStatusText(entry)
                    if (status.isNotEmpty()) add(status)
                }
                Text(lines.joinToString("\n"))
            },
            confirmButton = {
                TextButton(onClick = { inspectingEntry = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun CompactEntryRow(
    entry: PlacementEntry,
    modelInfo: PlacementModel?,
    isDragging: Boolean,
    onRowClick: () -> Unit,
    onPickReasoning: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val muted = !entry.enabled || entry.state == "disabled" || entry.state == "unavailable"
    val contentAlpha = if (muted) 0.52f else 1f
    val reasoningLevels = modelInfo?.reasoning?.levels.orEmpty()
    val rowBg = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onRowClick)
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderIcon(
            driver = entry.driver.ifEmpty { modelInfo?.driver.orEmpty() },
            provider = entry.displayProvider.ifEmpty { modelInfo?.displayProvider.orEmpty() },
            size = 20.dp,
            modifier = Modifier.alpha(contentAlpha),
        )
        Column(
            modifier = Modifier.weight(1f).alpha(contentAlpha),
        ) {
            Text(
                text = entry.displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val providerName = entry.displayProvider.ifEmpty { modelInfo?.displayProvider.orEmpty() }
            if (providerName.isNotEmpty()) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (reasoningLevels.isNotEmpty()) {
            val chipLabel = entry.reasoning.ifEmpty { "Default" }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .alpha(contentAlpha)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onPickReasoning),
            ) {
                Text(
                    text = chipLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = onToggle,
        )
    }
}

/** Actions page: Alert fixes, Text generation, and Recent moves. */
@Composable
internal fun PlacementActionsContent(
    placement: Placement?,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onAlertFix: (String) -> Unit,
    onTextGeneration: (String?) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle("Actions") },
                subtitle = { Text("Which profile each kind of work uses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (saving) {
                        LoadingIndicator(Modifier.padding(end = 12.dp).size(24.dp))
                    }
                },
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
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item {
                    Panel(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onReload) {
                            Text("Reload", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (placement == null) {
                if (error == null) {
                    item {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(24.dp))
                            Text("Loading actions", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                return@LazyColumn
            }

            item { SectionHeader("General", Modifier.padding(start = 4.dp)) }
            item {
                Panel {
                    ProfileDropdown(
                        label = "Alert fixes",
                        supporting = "New alert-fix agents start on this profile.",
                        profiles = placement.profiles,
                        selectedId = placement.effectiveAlertFixProfile.ifEmpty { null },
                        noneLabel = null,
                        enabled = placement.profiles.isNotEmpty(),
                        onPick = { id -> if (id != null) onAlertFix(id) },
                    )
                    ProfileDropdown(
                        label = "Text generation",
                        supporting = "Thread titles, commit messages and other generated text.",
                        profiles = placement.profiles,
                        selectedId = placement.effectiveTextGenerationProfile,
                        noneLabel = "None",
                        enabled = placement.profiles.isNotEmpty(),
                        onPick = onTextGeneration,
                    )
                }
            }

            if (placement.moves.isNotEmpty()) {
                item { SectionHeader("Recent moves", Modifier.padding(start = 4.dp, top = 4.dp)) }
                item {
                    Panel {
                        val shown = placement.moves.take(5)
                        shown.forEachIndexed { idx, move ->
                            MoveRow(move)
                            if (idx < shown.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileDropdown(
    label: String,
    supporting: String?,
    profiles: List<PlacementProfile>,
    selectedId: String?,
    noneLabel: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = selectedId?.let { id -> profiles.firstOrNull { it.id == id } }
    val display = when {
        selectedId == null -> noneLabel ?: "None"
        current != null -> current.displayLabel
        else -> selectedId
    }
    ExposedDropdownMenuBox(
        expanded = open && enabled,
        onExpandedChange = { if (enabled) open = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            supportingText = supporting?.let { s -> { Text(s) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && enabled) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = open && enabled,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.large,
        ) {
            if (noneLabel != null) {
                DropdownMenuItem(
                    text = { Text(noneLabel, style = MaterialTheme.typography.bodyLarge) },
                    trailingIcon = { if (selectedId == null) Icon(Icons.Rounded.Check, "Selected") },
                    onClick = {
                        open = false
                        if (selectedId != null) onPick(null)
                    },
                )
            }
            for (p in profiles) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.displayLabel, style = MaterialTheme.typography.bodyLarge)
                            val active = p.entries.count { it.enabled }
                            Text(
                                "$active of ${p.entries.size} models enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = { if (p.id == selectedId) Icon(Icons.Rounded.Check, "Selected") },
                    onClick = {
                        open = false
                        if (p.id != selectedId) onPick(p.id)
                    },
                )
            }
        }
    }
}

private fun entryStatusText(entry: PlacementEntry): String {
    val parts = buildList {
        val reason = entry.reason.trim().removeSuffix(".")
        if (reason.isNotEmpty()) add(reason)
        else if (!entry.enabled) add("Disabled")
        else if (entry.state.isNotEmpty()) add(entry.state.replace('_', ' ').replaceFirstChar { it.uppercase() })
        if (entry.runningThreads > 0) add("${entry.runningThreads} running")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun MoveRow(move: PlacementMove) {
    val from = move.from.substringBefore('/')
    val to = move.to.substringBefore('/')
    val whenText = ago(move.at)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$from → $to",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (whenText.isNotEmpty()) {
                Text(whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val detail = listOfNotNull(
            move.reason.trim().removeSuffix(".").ifEmpty { null },
            move.machine.ifEmpty { null },
        ).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProfileNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val valid = text.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(text) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReasoningSheet(
    entry: PlacementEntry,
    levels: List<String>,
    defaultLevel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = listOf("") + levels
    val status = entryStatusText(entry)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Text(
                    "Reasoning · ${entry.displayLabel}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = if (status.isNotEmpty()) 4.dp else 12.dp),
                )
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            itemsIndexed(values) { _, value ->
                val selected = value == entry.reasoning
                val label = if (value.isEmpty()) {
                    if (defaultLevel.isEmpty()) "Default" else "Default ($defaultLevel)"
                } else {
                    value
                }
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) { onPick(value) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Derives a valid profile ID (`^[a-z0-9][a-z0-9-]*$`, at most 40 chars) not in [existing]. */
internal fun uniqueProfileId(label: String, existing: Set<String>): String {
    val base = label.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .ifEmpty { "profile" }
    if (base !in existing) return base
    var n = 2
    while (true) {
        val suffix = "-$n"
        val candidate = base.take(40 - suffix.length).trimEnd('-') + suffix
        if (candidate !in existing) return candidate
        n++
    }
}
