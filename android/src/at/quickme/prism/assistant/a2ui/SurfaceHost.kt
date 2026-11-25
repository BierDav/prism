// One A2UI renderer per conversation (AssistantSession.surfaces), shared by
// the screens that show it (the overlay's panel and its full-screen chat, a
// chat from the history), so what the owner filled in but hasn't sent stays
// when the chat expands or collapses: Google's message processor
// with Prism's catalog, kept in step with the transcript's surfaces (each
// drawn once, then sent only what changed: core/A2ui.kt's Renderer), and
// their actions handed to the session (AssistantSession.act), which keeps
// what each surface shows while it handles one (SurfaceStatus).
//
// The processor runs unconfined: a message is processed on the thread that
// sends it, at once. The renderer keeps its components and data model in
// Compose state, and a surface drawn the first time is sent from the
// composition that shows it: processed there, its state is part of that
// composition's snapshot (processed on another thread, it would be state the
// composition can't read yet).

package at.quickme.prism.assistant.a2ui

import android.content.Context
import android.util.Log
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.runtime.LocalA2uiReadinessEvaluator
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.asReadinessEvaluator
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Renderer
import at.quickme.prism.assistant.core.Surface
import at.quickme.prism.assistant.core.UiAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The renderer of one conversation. The handler of the screen shown last
 * ([register]) gets what the owner did on a surface (the entry showing it,
 * the action) and says why it couldn't, or null; without one the surfaces
 * only show.
 */
class SurfaceHost(context: Context) {
    /** The screens' handlers, the latest shown last; each screen removes its own when it goes. */
    private val handlers = java.util.concurrent.CopyOnWriteArrayList<suspend (String, UiAction) -> String?>()
    internal fun register(h: suspend (String, UiAction) -> String?) = handlers.add(h)
    internal fun unregister(h: suspend (String, UiAction) -> String?) = handlers.remove(h)

    private val catalog = prismCatalog(context.applicationContext)
    private val processor = A2uiMessageProcessor(listOf(catalog))
    private val parser = A2uiMessageParser()
    internal val readiness = catalog.asReadinessEvaluator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Each surface as the renderer has it, by surfaceId, and the entry that shows it. */
    private val drawn = HashMap<String, Surface>()
    private val entryOf = HashMap<String, String>()

    /** What each surface's last action is doing, by surfaceId. */
    internal val status = mutableStateMapOf<String, SurfaceStatus>()

    internal val surfaces get() = processor.activeSurfaces

    init {
        scope.launch { processor.collectMessages() }
        scope.launch {
            processor.outboundEvents.collect { m ->
                when (m) {
                    is A2uiClientEventMessage -> handle(m)
                    is A2uiClientErrorMessage -> Log.w(TAG, "a surface failed: ${m.code} ${m.message.take(200)}")
                }
            }
        }
    }

    /** The renderer draws [entries]' surfaces as they are now; those gone are deleted. */
    @Synchronized
    fun sync(entries: List<Entry>) {
        val now = entries.filterIsInstance<Entry.Ui>()
        for (e in now) {
            val s = e.surface
            Renderer.sync(drawn[s.surfaceId], entryOf[s.surfaceId], s, e.id).forEach { processor.processInput(parser, it) }
            // Another entry's card under this ID starts with no action status.
            if (entryOf[s.surfaceId] != e.id) status.remove(s.surfaceId)
            entryOf[s.surfaceId] = e.id
            drawn[s.surfaceId] = s
        }
        val ids = now.map { it.surface.surfaceId }.toSet()
        for (gone in drawn.keys - ids) {
            processor.processInput(parser, Renderer.delete(gone))
            drawn.remove(gone)
            entryOf.remove(gone)
            status.remove(gone)
        }
    }

    private suspend fun handle(m: A2uiClientEventMessage) {
        val act = handlers.lastOrNull() ?: return
        val entry = synchronized(this) { entryOf[m.surfaceId] } ?: return
        val data = m.clientDataModel?.surfaces?.get(m.surfaceId)
        val action = UiAction.fromRenderer(m.type, m.surfaceId, m.componentId, m.timestamp, m.context, data)
        withContext(Dispatchers.Main) {
            val before = status[m.surfaceId] ?: SurfaceStatus()
            status[m.surfaceId] = before.copy(busy = action.name, error = null)
            val why = try {
                act(entry, action)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.message ?: "That didn't work."
            }
            // Not onto another entry's card that took the ID meanwhile (sync cleared its status).
            if (synchronized(this@SurfaceHost) { entryOf[m.surfaceId] } == entry) {
                status[m.surfaceId] = SurfaceStatus(busy = null, error = why, done = before.done + if (why == null) 1 else 0)
            }
        }
    }

    fun close() = scope.cancel()

    private companion object {
        const val TAG = "PrismA2ui"
    }
}

/**
 * The renderer for this screen, in step with [entries]: [shared] (the
 * conversation's, which outlives the screen), or one kept while it shows.
 */
@Composable
fun rememberSurfaceHost(entries: List<Entry>, onAction: (suspend (String, UiAction) -> String?)?, shared: SurfaceHost? = null): SurfaceHost {
    val context = LocalContext.current
    val handler = rememberUpdatedState(onAction)
    val host = shared ?: remember { SurfaceHost(context) }.also { own -> DisposableEffect(own) { onDispose { own.close() } } }
    remember(host) { host.sync(entries) }
    // Only while this screen shows: the handler holds its Activity, and a shared host outlives it.
    DisposableEffect(host) {
        val mine: suspend (String, UiAction) -> String? = { id, a ->
            // null from the handler is success; only a missing handler is "can't act".
            val h = handler.value
            if (h == null) "This chat can't act from here." else h(id, a)
        }
        host.register(mine)
        onDispose { host.unregister(mine) }
    }
    LaunchedEffect(host, entries) { host.sync(entries) }
    return host
}

/** Surface [surfaceId] as the renderer draws it; nothing while it isn't ready. */
@Composable
fun A2uiSurface(host: SurfaceHost, surfaceId: String, modifier: Modifier = Modifier) {
    val active by host.surfaces.collectAsState()
    val surface = active.firstOrNull { it.id == surfaceId } ?: return
    CompositionLocalProvider(
        LocalA2uiReadinessEvaluator provides host.readiness,
        LocalSurfaceStatus provides (host.status[surfaceId] ?: SurfaceStatus()),
    ) {
        when (val root = observeA2uiComponentState(surface)) {
            is A2uiComponentState.Success -> A2uiComponent(root.component, modifier.fillMaxWidth())
            is A2uiComponentState.Error -> Text(
                "This part of the answer can't be shown: ${root.exception.message.orEmpty().take(160)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = modifier,
            )
            A2uiComponentState.Loading -> Unit
        }
    }
}
