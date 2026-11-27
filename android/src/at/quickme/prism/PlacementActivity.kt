// Placement detail pages, opened from the Phone tab (docs/placement.md):
// 1. Profile page: the models and profiles one profile holds (drag to reorder,
//    compact rows, entry sheet with reasoning and remove, on/off switch,
//    Add model, rename and delete in the top bar).
// 2. Actions page: alert fixes, text generation, the assistant, recent moves.
// 3. Account limits page: temporary limits on gateway accounts.

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.quickme.prism.shared.Placement
import at.quickme.prism.shared.PlacementAccountLimit
import at.quickme.prism.shared.PlacementEntry
import at.quickme.prism.shared.PlacementModel
import at.quickme.prism.shared.PlacementMove
import at.quickme.prism.shared.PlacementProfile
import at.quickme.prism.ui.PageTitle
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.ProviderIcon
import at.quickme.prism.ui.ReorderableColumn
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.ago
import at.quickme.prism.ui.prism
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PlacementActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val PAGE_PROFILE = "profile"
        private const val PAGE_ACTIONS = "actions"
        private const val PAGE_LIMITS = "limits"

        fun profileIntent(c: Context, profileId: String): Intent =
            Intent(c, PlacementActivity::class.java)
                .putExtra(EXTRA_PAGE, PAGE_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)

        fun actionsIntent(c: Context): Intent =
            Intent(c, PlacementActivity::class.java)
                .putExtra(EXTRA_PAGE, PAGE_ACTIONS)

        fun limitsIntent(c: Context): Intent =
            Intent(c, PlacementActivity::class.java)
                .putExtra(EXTRA_PAGE, PAGE_LIMITS)
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
    var assistant by remember { mutableStateOf(AssistantProfile.get(context)) }
    var agents by remember { mutableStateOf(AgentProfile.get(context)) }

    if (page == "profile" && profileId.isNotEmpty()) {
        val profile = placement?.profiles?.firstOrNull { it.id == profileId }
        PlacementProfileContent(
            profile = profile,
            placement = placement,
            loading = placement == null && error == null,
            saving = saving,
            error = error,
            onBack = onBack,
            onReload = { state.reload(context) },
            onSaveProfile = { id, label, entries -> state.save(context, id, label, entries) },
            onDeleteProfile = { id -> state.delete(context, id) },
            onOpenProfile = { id -> context.startActivity(PlacementActivity.profileIntent(context, id)) },
        )
    } else if (page == "limits") {
        PlacementLimitsContent(
            placement = placement,
            saving = saving,
            error = error,
            onBack = onBack,
            onReload = { state.reload(context) },
            onSetLimit = { account, limit -> state.accountLimit(context, account, limit) },
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
            assistant = assistant,
            onAssistant = { id ->
                AssistantProfile.set(context, id)
                assistant = id
            },
            agents = agents,
            onAgents = { id ->
                AgentProfile.set(context, id)
                agents = id
            },
        )
    }
}

/**
 * One profile's page: the entries it holds (models, and other profiles it
 * uses whole), each with a switch, reasoning, hold-and-drag reorder and
 * remove (hold without moving, or in the entry's sheet), then Add model.
 */
@Composable
internal fun PlacementProfileContent(
    profile: PlacementProfile?,
    placement: Placement?,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSaveProfile: (id: String, label: String, entries: List<PlacementEntry>) -> Unit,
    /** Deletes the profile (after the page asked); null: no delete action. */
    onDeleteProfile: ((String) -> Unit)? = null,
    onOpenProfile: (String) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<PlacementEntry?>(null) }
    var removing by remember { mutableStateOf<PlacementEntry?>(null) }

    val models = placement?.models.orEmpty()
    val modelsByKey = remember(models) {
        models.associateBy { it.instanceId to it.model }
    }
    val configured = profile?.configuredEntries.orEmpty()
    // Hidden models stay in the profile (and are saved with it), but aren't shown.
    val visibleEntries = configured.filter { entry ->
        entry.isProfileReference || modelsByKey[entry.instanceId to entry.model]?.hidden != true
    }
    val addableModels = models.filter { model ->
        !model.hidden && model.instanceId != PlacementEntry.PROFILE_INSTANCE &&
            configured.none { it.instanceId == model.instanceId && it.model == model.model }
    }
    val addableProfiles = if (profile != null && placement != null) placement.referenceCandidates(profile.id, configured) else emptyList()
    fun labelOf(entry: PlacementEntry): String =
        if (entry.isProfileReference) placement?.profiles?.firstOrNull { it.id == entry.model }?.displayLabel ?: entry.displayLabel
        else entry.displayLabel

    fun saveWith(change: (MutableList<PlacementEntry>) -> Unit) {
        val p = profile ?: return
        val next = p.configuredEntries.toMutableList()
        change(next)
        onSaveProfile(p.id, p.label, next)
    }
    fun update(target: PlacementEntry, change: (PlacementEntry) -> PlacementEntry) = saveWith { list ->
        val i = list.indexOfFirst { it.sameTarget(target) }
        if (i >= 0) list[i] = change(list[i])
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle(profile?.displayLabel ?: "Profile") },
                subtitle = {
                    if (profile != null) {
                        val enabledCount = visibleEntries.count { it.enabled }
                        Text(
                            if (visibleEntries.size > 1) "$enabledCount of ${visibleEntries.size} enabled · Hold and drag to reorder"
                            else "$enabledCount of ${visibleEntries.size} enabled",
                        )
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
                        if (onDeleteProfile != null) {
                            IconButton(onClick = { deleting = true }) {
                                Icon(Icons.Rounded.Delete, "Delete profile")
                            }
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

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (visibleEntries.isEmpty()) {
                            Text(
                                "No models yet. Add the models this profile may use, in the order it should try them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        } else {
                            ReorderableColumn(
                                items = visibleEntries,
                                key = { "${it.instanceId}|${it.model}" },
                                enabled = true,
                                onReorder = { from, to ->
                                    // Move it among the shown entries; hidden ones keep their places.
                                    val reordered = visibleEntries.toMutableList()
                                    reordered.add(to, reordered.removeAt(from))
                                    val shown = reordered.iterator()
                                    saveWith { list ->
                                        for (i in list.indices) {
                                            if (visibleEntries.any { it.sameTarget(list[i]) }) list[i] = shown.next()
                                        }
                                    }
                                },
                                onLongPressRelease = { i -> removing = visibleEntries.getOrNull(i) },
                            ) { _, entry, isDragging ->
                                CompactEntryRow(
                                    entry = entry,
                                    label = labelOf(entry),
                                    modelInfo = modelsByKey[entry.instanceId to entry.model],
                                    isDragging = isDragging,
                                    onRowClick = { opened = entry },
                                    onPickReasoning = { opened = entry },
                                    onToggle = { on -> update(entry) { it.copy(enabled = on) } },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        AddRow(
                            label = "Add model",
                            enabled = addableModels.isNotEmpty() || addableProfiles.isNotEmpty(),
                            onClick = { adding = true },
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
                onSaveProfile(profile.id, label.trim(), profile.configuredEntries)
            },
            onDismiss = { renaming = false },
        )
    }

    if (deleting && profile != null && placement != null && onDeleteProfile != null) {
        DeleteProfileDialog(
            placement = placement,
            profile = profile,
            onDelete = {
                deleting = false
                onDeleteProfile(profile.id)
                onBack()
            },
            onDismiss = { deleting = false },
        )
    }

    if (adding && profile != null) {
        AddEntrySheet(
            profileLabel = profile.displayLabel,
            models = addableModels,
            profiles = addableProfiles,
            onPickModel = { model ->
                adding = false
                saveWith { it.add(PlacementEntry.of(model)) }
            },
            onPickProfile = { picked ->
                adding = false
                saveWith { it.add(PlacementEntry.reference(picked)) }
            },
            onDismiss = { adding = false },
        )
    }

    opened?.let { target ->
        // The entry as it is now (a save may have changed it while the sheet is open).
        val entry = configured.firstOrNull { it.sameTarget(target) }
        if (profile != null && entry != null) {
            val reasoning = if (entry.isProfileReference) null else modelsByKey[entry.instanceId to entry.model]?.reasoning
            EntrySheet(
                entry = entry,
                label = labelOf(entry),
                levels = reasoning?.levels.orEmpty(),
                defaultLevel = reasoning?.defaultLevel.orEmpty(),
                onPick = { level ->
                    opened = null
                    update(entry) { it.copy(reasoning = level) }
                },
                onOpenProfile = if (entry.isProfileReference && placement?.profiles?.any { it.id == entry.model } == true) {
                    {
                        opened = null
                        onOpenProfile(entry.model)
                    }
                } else null,
                onRemove = {
                    opened = null
                    removing = entry
                },
                onDismiss = { opened = null },
            )
        } else {
            opened = null
        }
    }

    removing?.let { entry ->
        if (profile == null) {
            removing = null
            return@let
        }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${labelOf(entry)}?") },
            text = {
                Text(
                    if (entry.isProfileReference) "${profile.displayLabel} stops using this profile's models. You can add it again with Add model."
                    else "${profile.displayLabel} stops using this model. You can add it again with Add model.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    saveWith { list -> list.removeAll { it.sameTarget(entry) } }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** A list's last row that adds to it: a plus and [label] in the primary color (as Add profile on the Phone tab). */
@Composable
internal fun AddRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CompactEntryRow(
    entry: PlacementEntry,
    label: String,
    modelInfo: PlacementModel?,
    isDragging: Boolean,
    onRowClick: () -> Unit,
    onPickReasoning: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val muted = !entry.enabled || entry.state == "disabled" || entry.state == "unavailable"
    val contentAlpha = if (muted) 0.52f else 1f
    val reasoningLevels = if (entry.isProfileReference) emptyList() else modelInfo?.reasoning?.levels.orEmpty()
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
        if (entry.isProfileReference) {
            Icon(
                PrismIcons.Layers, "Profile",
                Modifier.size(20.dp).alpha(contentAlpha),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            ProviderIcon(
                driver = entry.driver.ifEmpty { modelInfo?.driver.orEmpty() },
                provider = entry.displayProvider.ifEmpty { modelInfo?.displayProvider.orEmpty() },
                size = 20.dp,
                modifier = Modifier.alpha(contentAlpha),
            )
        }
        Column(
            modifier = Modifier.weight(1f).alpha(contentAlpha),
        ) {
            Text(
                text = label,
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

/** What Add model offers: the models the profile doesn't hold yet, and the profiles it may use. */
@Composable
private fun AddEntrySheet(
    profileLabel: String,
    models: List<PlacementModel>,
    profiles: List<PlacementProfile>,
    onPickModel: (PlacementModel) -> Unit,
    onPickProfile: (PlacementProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Text(
                    "Add to $profileLabel",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (models.isNotEmpty()) {
                item { SectionHeader("Models", Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                itemsIndexed(models, key = { _, m -> "m|${m.instanceId}|${m.model}" }) { _, model ->
                    PickRow(
                        title = model.displayLabel,
                        detail = model.displayProvider,
                        icon = { ProviderIcon(driver = model.driver, provider = model.displayProvider, size = 24.dp) },
                        onClick = { onPickModel(model) },
                    )
                }
            }
            if (profiles.isNotEmpty()) {
                item { SectionHeader("Profiles", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                itemsIndexed(profiles, key = { _, p -> "p|${p.id}" }) { _, p ->
                    val on = p.configuredEntries.count { it.enabled }
                    PickRow(
                        title = "Profile · ${p.displayLabel}",
                        detail = "Its enabled models, in its order ($on on)",
                        icon = { Icon(PrismIcons.Layers, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onPickProfile(p) },
                    )
                }
            }
            if (models.isEmpty() && profiles.isEmpty()) {
                item {
                    Text(
                        "Every model and profile is in it already.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, detail: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Actions page: Alert fixes, Text generation, the assistant and its agents, and Recent moves. */
@Composable
internal fun PlacementActionsContent(
    placement: Placement?,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onAlertFix: (String) -> Unit,
    onTextGeneration: (String?) -> Unit,
    /** The profile the assistant overlay runs on, on this phone (AssistantProfile); null: none picked yet. */
    assistant: String? = null,
    onAssistant: (String?) -> Unit = {},
    /** The profile the T3 agents the assistant starts run on by default (AgentProfile); null: none picked yet. */
    agents: String? = null,
    onAgents: (String?) -> Unit = {},
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
                    ProfileDropdown(
                        label = "Assistant",
                        supporting = "The assistant overlay on this phone answers with this profile's best model.",
                        profiles = placement.profiles,
                        selectedId = assistant,
                        noneLabel = null,
                        enabled = placement.profiles.isNotEmpty(),
                        onPick = { id -> if (id != null) onAssistant(id) },
                    )
                    ProfileDropdown(
                        label = "Assistant's agents",
                        supporting = "T3 agents the assistant starts, unless you name another profile; Prism picks the machine and project.",
                        profiles = placement.profiles,
                        selectedId = agents,
                        noneLabel = null,
                        enabled = placement.profiles.isNotEmpty(),
                        onPick = { id -> if (id != null) onAgents(id) },
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
                            val active = p.configuredEntries.count { it.enabled }
                            Text(
                                "$active of ${p.configuredEntries.size} enabled",
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

/** An entry's sheet: its status, its reasoning level (models with levels), opening a used profile, and Remove. */
@Composable
private fun EntrySheet(
    entry: PlacementEntry,
    label: String,
    levels: List<String>,
    defaultLevel: String,
    onPick: (String) -> Unit,
    onOpenProfile: (() -> Unit)?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val values = if (levels.isEmpty()) emptyList() else listOf("") + levels
    val status = entryStatusText(entry)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Text(
                    if (entry.isProfileReference) "Profile · $label" else label,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                val detail = listOf(if (entry.isProfileReference) "" else entry.displayProvider, status)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.isProfileReference) {
                    Text(
                        "This profile tries that profile's enabled models here, in that profile's order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (values.isNotEmpty()) {
                    SectionHeader("Reasoning", Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
            }
            itemsIndexed(values) { _, value ->
                val selected = value == entry.reasoning
                val text = if (value.isEmpty()) {
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
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenProfile != null) {
                        TextButton(onClick = onOpenProfile) { Text("Open $label") }
                    }
                    TextButton(onClick = onRemove) {
                        Text("Remove from profile", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Account limits page: hold gateway accounts back for a while (docs/placement.md, "Temporary account limits"). */
@Composable
internal fun PlacementLimitsContent(
    placement: Placement?,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    /** Sets an account's limit; null removes it. */
    onSetLimit: (account: String, limit: PlacementAccountLimit?) -> Unit,
    now: Long = System.currentTimeMillis() / 1000,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // The account being edited: "" adds one.
    var editing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle("Account limits") },
                subtitle = { Text("Leave part of an account's windows unused for a while") },
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
                            Text("Loading limits", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                return@LazyColumn
            }
            val limits = placement.policy.accountLimits.toSortedMap()
            val free = placement.accounts.filter { it !in limits }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (limits.isEmpty()) {
                            Text(
                                "No account is held back. Placement uses each account's windows up to the switch point.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                        limits.entries.forEachIndexed { i, (account, limit) ->
                            AccountLimitRow(
                                account = account,
                                limit = limit,
                                now = now,
                                onClick = { editing = account },
                                onRemove = { onSetLimit(account, null) },
                            )
                            if (i < limits.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        AddRow(label = "Add limit", enabled = free.isNotEmpty(), onClick = { editing = "" })
                    }
                }
            }
            item {
                Text(
                    "Placement counts a held-back window as full early: keeping 30% of the 5-hour window counts it " +
                        "as full at 70% used, so its threads move before then. Models without an account (pooled ones) aren't limited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    editing?.let { account ->
        val current = placement?.policy?.accountLimits?.get(account)
        val choices = if (account.isEmpty()) placement?.accounts.orEmpty().filter { it !in placement?.policy?.accountLimits.orEmpty() } else listOf(account)
        AccountLimitDialog(
            accounts = choices,
            current = current,
            now = now,
            onSave = { picked, limit ->
                editing = null
                onSetLimit(picked, limit)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AccountLimitRow(
    account: String,
    limit: PlacementAccountLimit,
    now: Long,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val ended = limit.ended(now)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (ended) 0.52f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(limit.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                limitEnd(limit, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Delete, "Remove the limit on $account")
        }
    }
}

/** "Until Fri 28 Nov, 09:00", "Ended …" or "No end". */
internal fun limitEnd(limit: PlacementAccountLimit, now: Long): String {
    if (limit.until.isBlank()) return "No end"
    val at = limit.untilEpochSeconds ?: return "Until ${limit.until}"
    return (if (at <= now) "Ended " else "Until ") + formatLocal(at)
}

private fun formatLocal(epoch: Long): String =
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))

/** Adds a limit (an account from [accounts]) or edits [current] (one account). */
@Composable
private fun AccountLimitDialog(
    accounts: List<String>,
    current: PlacementAccountLimit?,
    now: Long,
    onSave: (String, PlacementAccountLimit) -> Unit,
    onDismiss: () -> Unit,
) {
    var account by remember { mutableStateOf(accounts.firstOrNull().orEmpty()) }
    var session by remember { mutableStateOf(current?.sessionKept?.toString().orEmpty()) }
    var weekly by remember { mutableStateOf(current?.weeklyKept?.toString().orEmpty()) }
    val ends = remember(now, current) { limitEndChoices(now, current) }
    var end by remember { mutableIntStateOf(0) }
    val sessionKept = keptValue(session)
    val weeklyKept = keptValue(weekly)
    val valid = account.isNotEmpty() && (sessionKept != null || weeklyKept != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Hold an account back" else "Limit $account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current == null) {
                    AccountDropdown(accounts, account) { account = it }
                }
                KeptField("Keep of the 5-hour window", session) { session = it }
                KeptField("Keep of the week", weekly) { weekly = it }
                Text("Ends", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                ends.forEachIndexed { i, choice ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = end == i, role = Role.RadioButton) { end = i }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = end == i, onClick = null)
                        Text(choice.first, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(account, PlacementAccountLimit.keeping(sessionKept, weeklyKept, ends[end].second))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A kept share typed in (at most two digits: 0–99), or null when empty. */
private fun keptValue(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..99 }

@Composable
private fun KeptField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(2)) },
        label = { Text(label) },
        placeholder = { Text("No limit") },
        suffix = { Text("%") },
        supportingText = {
            val kept = keptValue(value)
            Text(if (kept == null) "Empty: no limit on this window" else "Counts as full at ${100 - kept}% used")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccountDropdown(accounts: List<String>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in accounts) {
                DropdownMenuItem(
                    text = { Text(a) },
                    trailingIcon = { if (a == selected) Icon(Icons.Rounded.Check, "Selected") },
                    onClick = {
                        open = false
                        onPick(a)
                    },
                )
            }
        }
    }
}

/** The ends offered: the current one first when it hasn't passed, no end, and a few spans from [now]. */
private fun limitEndChoices(now: Long, current: PlacementAccountLimit?): List<Pair<String, String>> = buildList {
    val currentEnd = current?.untilEpochSeconds
    if (currentEnd != null && currentEnd > now) add("As set: until ${formatLocal(currentEnd)}" to current.until)
    add("No end" to "")
    fun after(seconds: Long) = PlacementAccountLimit.formatRfc3339(now + seconds)
    add("For 5 hours" to after(5 * 3600))
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochSecond(now).atZone(zone)
    val eight = local.toLocalDate().plusDays(if (local.hour < 8) 0 else 1).atTime(8, 0).atZone(zone)
    val today = eight.toLocalDate() == local.toLocalDate()
    add("Until 08:00 ${if (today) "today" else "tomorrow"}" to PlacementAccountLimit.formatRfc3339(eight.toEpochSecond()))
    add("For 3 days" to after(3 * 86400))
    add("For a week" to after(7 * 86400))
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
