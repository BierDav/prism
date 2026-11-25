package at.quickme.prism

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import at.quickme.prism.shared.Placement
import at.quickme.prism.shared.PlacementAccountLimit
import at.quickme.prism.shared.PlacementEntry
import at.quickme.prism.shared.PlacementProfile
import at.quickme.prism.shared.PrismClient
import at.quickme.prism.shared.PrismError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One queue for both activities. Leaving a page cannot cancel its writes. */
internal object PlacementSettingsState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private var confirmed by mutableStateOf<Placement?>(null)
    private val pending = mutableStateListOf<(Placement) -> Placement>()
    var error by mutableStateOf<String?>(null)
        private set

    val placement: Placement?
        get() = confirmed?.let { base -> pending.fold(base) { value, edit -> edit(value) } }
    val saving: Boolean get() = pending.isNotEmpty()

    suspend fun refresh(context: Context) {
        lock.withLock {
            try {
                confirmed = PrismApi.call(context) { placement() }
            } catch (e: PrismError) {
                error = e.message
            }
        }
    }

    fun reload(context: Context) {
        error = null
        scope.launch { refresh(context.applicationContext) }
    }

    fun save(context: Context, id: String, label: String, entries: List<PlacementEntry>) {
        edit(context, { placement ->
            val profile = PlacementProfile(id, label, entries, configuredEntryCount = entries.size)
            placement.copy(profiles = if (placement.profiles.any { it.id == id }) {
                placement.profiles.map { if (it.id == id) profile else it }
            } else placement.profiles + profile)
        }) { savePlacementProfile(id, label, entries) }
    }

    fun order(context: Context, ids: List<String>) {
        val index = ids.withIndex().associate { it.value to it.index }
        edit(context, { it.copy(profiles = it.profiles.sortedBy { profile -> index[profile.id] ?: Int.MAX_VALUE }) }) {
            setPlacementOrder(ids)
        }
    }

    fun delete(context: Context, id: String) {
        edit(context, { it.copy(profiles = it.profiles.filterNot { profile -> profile.id == id }) }) {
            deletePlacementProfile(id)
        }
    }

    fun actions(context: Context, alertFix: String? = null, textGeneration: String? = null) {
        edit(context, {
            it.copy(actions = it.actions.copy(
                alertFix = alertFix ?: it.actions.alertFix,
                textGeneration = textGeneration ?: it.actions.textGeneration,
            ))
        }) { setPlacementActions(alertFix, textGeneration) }
    }

    /** Sets [account]'s temporary limit, or removes it with null. */
    fun accountLimit(context: Context, account: String, limit: PlacementAccountLimit?) {
        edit(context, {
            val limits = if (limit == null) it.policy.accountLimits - account else it.policy.accountLimits + (account to limit)
            it.copy(policy = it.policy.copy(accountLimits = limits))
        }) { if (limit == null) clearAccountLimit(account) else setAccountLimit(account, limit) }
    }

    private fun edit(context: Context, apply: (Placement) -> Placement, write: suspend PrismClient.() -> Placement) {
        if (confirmed == null) return
        val app = context.applicationContext
        pending.add(apply)
        error = null
        scope.launch {
            lock.withLock {
                try {
                    confirmed = PrismApi.call(app, write)
                } catch (e: PrismError) {
                    error = e.message
                } finally {
                    pending.remove(apply)
                }
            }
        }
    }
}

@Composable
internal fun rememberPlacementSettings(): PlacementSettingsState {
    val context = LocalContext.current.applicationContext
    val state = PlacementSettingsState
    LaunchedEffect(context) {
        state.refresh(context)
        PrismApi.changes(listOf(
            "profiles", "profile_entries", "actions", "policy", "hosts", "threads", "instructions",
            "move_acks", "samples", "settings",
        )).conflate().collect { state.refresh(context) }
    }
    return state
}
