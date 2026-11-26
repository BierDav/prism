// The assistant's chat history, full screen: every conversation stored in
// Rocky (ConversationStore; those still waiting on the phone too), newest
// first and grouped by day as Gemini's chat list is; one opens with its
// transcript (TranscriptView's entries) and goes on with what is typed here,
// through the same harness (AssistantSession). New chat starts another;
// Delete removes one everywhere. AssistHistory.open opens the list; the
// overlay shows its own conversation full screen with HistoryChat, in its
// window, in Gemini's app look: a hamburger opens the chats in a drawer
// (ChatsDrawer), a handle at the top drags it down into the overlay's card,
// and Back (predictive) shrinks it into the card.

package at.quickme.prism.assistant

import at.quickme.prism.assistant.a2ui.SurfaceHost
import at.quickme.prism.assistant.a2ui.rememberSurfaceHost
import at.quickme.prism.assistant.core.UiAction
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.quickme.prism.PrismApi
import at.quickme.prism.Sample
import at.quickme.prism.Settings
import at.quickme.prism.assistant.core.Entry
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.shared.AssistantConversation
import at.quickme.prism.shared.PrismError
import at.quickme.prism.ui.EmptyState
import at.quickme.prism.ui.PageTitle
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.SectionHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The history screen's entry point. */
object AssistHistory {
    fun open(c: Context) {
        c.startActivity(Intent(c, AssistHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class AssistHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PrismTheme { HistoryScreen(onClose = ::finish) } }
    }
}

/**
 * A conversation in the list; [waiting]: kept on the phone, not in the
 * replica yet; [problem]: why the replica refused it (it stays on the phone).
 */
data class HistoryItem(val conversation: AssistantConversation, val waiting: Boolean = false, val problem: String = "")

@Composable
private fun HistoryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = remember { Settings.deviceName(context) }
    var items by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val storeProblem by ConversationStore.problem.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    // The chat shown (null: the list). Its session is the one holding the
    // conversation in this process (the overlay's too), so a turn goes on
    // when the chat is left, and reopening shows it; no session while it loads.
    var inChat by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<AssistantSession?>(null) }
    var opening by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<Job?>(null) }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        val (loaded, problem) = loadHistory(context, device)
        items = loaded
        error = problem
    }
    // Synced or written here: the list follows; so it does when the outbox
    // sent a chat, or the replica refused one.
    LaunchedEffect(Unit) {
        PrismApi.changes(listOf("assistant_conversations")).collect { if (!inChat) reload++ }
    }
    LaunchedEffect(Unit) {
        ConversationStore.changes.drop(1).collect { if (!inChat) reload++ }
    }
    DisposableEffect(Unit) { onDispose { session?.detach() } }

    fun show(s: AssistantSession) {
        s.attach(context)
        s.onHandoff = { _, _ -> }
        s.followed = { null }
        session = s
    }

    fun leave() {
        loading?.cancel()
        loading = null
        session?.detach()
        session = null
        opening = false
        openError = null
        inChat = false
        reload++
    }

    fun openConversation(id: String?) {
        loading?.cancel()
        session?.detach()
        session = null
        openError = null
        text = ""
        inChat = true
        if (id == null) {
            show(AssistantSession(context, ScreenCapture.None, resume = false))
            return
        }
        AssistantSession.live(id)?.let { return show(it) }
        opening = true
        loading = scope.launch {
            try {
                val loaded = ConversationStore.load(context, id)
                // Opened meanwhile by the overlay? Then that session.
                show(AssistantSession.live(id) ?: AssistantSession(context, ScreenCapture.None, resume = false).also { it.open(loaded) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                openError = e.message ?: "The conversation did not open."
            } finally {
                opening = false
            }
        }
    }

    fun delete(id: String) {
        ConversationStore.delete(context, id)
        items = items?.filterNot { it.conversation.id == id }
    }

    if (!inChat) {
        HistoryList(
            items = items, error = error ?: storeProblem, device = device, now = System.currentTimeMillis() / 1000,
            onOpen = { openConversation(it) }, onNew = { openConversation(null) }, onDelete = ::delete, onBack = onClose,
        )
    } else {
        BackHandler { leave() }
        val shown = session
        HistoryChat(
            transcript = shown?.transcript ?: Transcript(),
            running = shown?.running == true,
            loading = opening,
            error = openError,
            // Only the chat asked for, once it is there, takes what is typed.
            configured = shown?.configured ?: true,
            canSend = shown != null,
            text = text,
            onText = { text = it },
            onSend = {
                val request = text.trim()
                if (shown != null && request.isNotEmpty() && !shown.running) {
                    text = ""
                    scope.launch { shown.send(request) }
                }
            },
            onAction = { entry, action -> shown?.act(entry, action) },
            host = shown?.surfaces,
            onNew = { openConversation(null) },
            onBack = ::leave,
        )
    }
}

/**
 * Every conversation, newest first: those in Rocky and those still waiting on
 * the phone (marked so), without those deleted here; and why Rocky's list
 * couldn't be read, if it couldn't.
 */
internal suspend fun loadHistory(context: Context, device: String): Pair<List<HistoryItem>, String?> {
    if (Sample.on(context)) return emptyList<HistoryItem>() to null
    ConversationStore.flush(context)
    val (kept, deleted) = ConversationStore.pending(context)
    var error: String? = null
    val stored = try {
        PrismApi.call(context) { assistantConversations() }
    } catch (e: PrismError) {
        error = e.message
        emptyList()
    }
    val byId = LinkedHashMap<String, HistoryItem>()
    stored.forEach { byId[it.id] = HistoryItem(it) }
    for (s in kept) {
        val known = byId[s.id]?.conversation
        if (known == null || known.updatedAt <= s.updatedAt || s.problem.isNotEmpty()) {
            byId[s.id] = HistoryItem(s.summary.copy(device = known?.device ?: device), waiting = true, problem = s.problem)
        }
    }
    return byId.values.filter { it.conversation.id !in deleted }.sortedByDescending { it.conversation.updatedAt } to error
}

/**
 * The chats in the full-screen chat's drawer, as Gemini's app has them: New
 * chat at the top, then every conversation by day, newest first, [current]
 * marked. Loaded when shown, and again when the conversations change.
 */
@Composable
internal fun ChatsDrawer(current: String, onOpen: (String) -> Unit, onNew: () -> Unit, newEnabled: Boolean = true) {
    val context = LocalContext.current
    val device = remember { Settings.deviceName(context) }
    var items by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        val (loaded, problem) = loadHistory(context, device)
        items = loaded
        error = problem
    }
    LaunchedEffect(Unit) { PrismApi.changes(listOf("assistant_conversations")).collect { reload++ } }
    LaunchedEffect(Unit) { ConversationStore.changes.drop(1).collect { reload++ } }
    val now = System.currentTimeMillis() / 1000
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "title") {
                Text(
                    "Chats", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
                )
            }
            item(key = "new") {
                NavigationDrawerItem(
                    label = { Text("New chat") },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    selected = false,
                    onClick = { if (newEnabled) onNew() },
                )
            }
            val list = items
            when {
                list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                list.isEmpty() -> item(key = "empty") {
                    Text(
                        error ?: "No chats yet.", style = MaterialTheme.typography.bodyMedium,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {
                    if (error != null) item(key = "error") {
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                    for ((day, group) in list.groupBy { dayLabel(it.conversation.updatedAt, now) }) {
                        item(key = "day:$day") { SectionHeader(day, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                        items(group, key = { it.conversation.id }) { item ->
                            NavigationDrawerItem(
                                label = {
                                    Text(item.conversation.title.ifEmpty { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                selected = item.conversation.id == current,
                                onClick = { onOpen(item.conversation.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The list: conversations by day, newest first, and New chat. */
@Composable
internal fun HistoryList(
    items: List<HistoryItem>?,
    error: String?,
    /** This phone's name: other devices' conversations say where they began. */
    device: String,
    /** Unix seconds; fixed in previews. */
    now: Long,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirm by remember { mutableStateOf<HistoryItem?>(null) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle("Chats") },
                subtitle = { Text(items?.let { if (it.size == 1) "1 conversation with Prism" else "${it.size} conversations with Prism" } ?: "Loading…") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New chat") },
            )
        },
    ) { padding ->
        when {
            items == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            items.isEmpty() -> EmptyState(
                PrismIcons.Chat, "No chats yet",
                error ?: "What you ask Prism appears here, on every device.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (error != null) {
                    item(key = "error") {
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                for ((day, group) in items.groupBy { dayLabel(it.conversation.updatedAt, now) }) {
                    item(key = "day:$day") { SectionHeader(day, Modifier.padding(start = 4.dp, top = 12.dp)) }
                    items(group, key = { it.conversation.id }) { item ->
                        ConversationRow(item, device, now, onOpen = { onOpen(item.conversation.id) }, onDelete = { confirm = item })
                    }
                }
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${item.conversation.title.ifEmpty { "Untitled" }}” is deleted on every device.") },
            confirmButton = { TextButton(onClick = { onDelete(item.conversation.id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConversationRow(item: HistoryItem, device: String, now: Long, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val c = item.conversation
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(colors.surfaceContainerLow, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title.ifEmpty { "Untitled" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                timeLabel(c.updatedAt, now),
                c.device.takeIf { it.isNotEmpty() && it != device },
                item.problem.ifEmpty { "Not synced yet" }.takeIf { item.waiting },
            ).joinToString(" · ")
            Text(
                detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (item.problem.isNotEmpty()) colors.error else colors.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${c.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/**
 * One conversation, full screen: its transcript and a composer that continues
 * it, in Gemini's app look (a flat field, no glow). The Chats list opens it,
 * and so does the overlay (AssistActivity), whose card grows into it: there
 * a hamburger opens the chats ([chats], a drawer), and the handle at the top
 * (dragged down) or Back ([onCollapse], predictive) take it back into the
 * card; [composer] is the overlay's own card. Opened from the list, Back
 * ([onBack]) returns to the list.
 */
@Composable
internal fun HistoryChat(
    transcript: Transcript,
    running: Boolean,
    loading: Boolean,
    error: String?,
    configured: Boolean,
    /** The chat asked for is there (not loading, not failed): what is typed goes to it. */
    canSend: Boolean,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    /** What the owner did on a surface (entry, A2UI action): AssistantSession.act; null where the cards only show. */
    onAction: (suspend (String, UiAction) -> String?)?,
    onNew: () -> Unit,
    onBack: () -> Unit,
    /** The clock timers count from; fixed in previews. */
    now: (() -> Long)? = null,
    /** The drawer's chats ([close] closes the drawer); with them a hamburger in place of Back. */
    chats: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** Back to the overlay's card: the handle at the top and (predictive) Back; null opened from the list. */
    onCollapse: (() -> Unit)? = null,
    /** Whether Back collapses the chat now (not while the overlay's + menu is open, which Back closes first). */
    collapseOnBack: Boolean = true,
    /** The agent the overlay follows, shown live in the card of hand-off [handoff]. */
    agent: FollowedAgent? = null,
    handoff: String? = null,
    /** In place of the plain field: the overlay's card. */
    composer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** The conversation's renderer (AssistantSession.surfaces), shared with its other screens; one of its own without. */
    host: SurfaceHost? = null,
) {
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val entries = transcript.entries
    val surfaces = rememberSurfaceHost(entries, onAction, host)
    LaunchedEffect(entries.size, (entries.lastOrNull() as? Entry.Answer)?.text?.length, running) {
        if (entries.isNotEmpty()) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // How far the chat has gone back towards the card: by predictive Back
    // (0..1) or the handle dragged down (px).
    val back = remember { Animatable(0f) }
    var pulled by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    if (onCollapse != null) {
        // The drawer handles Back itself (predictively) while it is open.
        PredictiveBackHandler(enabled = collapseOnBack && drawer.isClosed && drawer.targetValue == DrawerValue.Closed) { progress ->
            try {
                progress.collect { back.snapTo(CollapseEasing.transform(it.progress)) }
                onCollapse()
            } catch (e: CancellationException) {
                scope.launch { back.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) }
                throw e
            }
        }
    }
    // The overlay's card lies over the chat at its bottom (as in the overlay),
    // and the list keeps clear of it.
    var cardHeight by remember { mutableIntStateOf(0) }
    val cardSpace = with(density) { cardHeight.toDp() }
    val reach = with(density) { CollapseReach.toPx() }
    val drag = rememberDraggableState { dy ->
        val threshold = with(density) { CollapseDrag.toPx() }
        val before = pulled
        val next = (before + dy).coerceAtLeast(0f)
        pulled = next
        if (before < threshold && next >= threshold) {
            view.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else android.view.HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
    }
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = colors.surface,
                topBar = {
                    Column(
                        Modifier.background(colors.surface)
                            .then(
                                if (onCollapse == null) Modifier else Modifier.draggable(
                                    drag, Orientation.Vertical,
                                    onDragStopped = { velocity ->
                                        val (threshold, fling) = with(density) { CollapseDrag.toPx() to CollapseFlingDpPerS.dp.toPx() }
                                        if (pulled >= threshold || (velocity >= fling && pulled > 0f)) {
                                            onCollapse()
                                        } else {
                                            androidx.compose.animation.core.animate(
                                                pulled, 0f, animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
                                            ) { v, _ -> pulled = v }
                                        }
                                    },
                                ),
                            ),
                    ) {
                        if (onCollapse != null) {
                            // The overlay card's handle, now at the top: drag down for the card.
                            Box(
                                Modifier.fillMaxWidth().statusBarsPadding().height(16.dp)
                                    .semantics { contentDescription = "Drag handle" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(32.dp, 4.dp).background(colors.outlineVariant, RoundedCornerShape(2.dp)))
                            }
                        }
                        TopAppBar(
                            title = {
                                Text(
                                    at.quickme.prism.assistant.core.Conversations.title(transcript) ?: "New chat",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                if (chats != null) {
                                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Chats") }
                                } else {
                                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Chats") }
                                }
                            },
                            actions = {
                                IconButton(onClick = onNew, enabled = !running) { Icon(Icons.Rounded.Add, "New chat") }
                            },
                            windowInsets = if (onCollapse != null) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                        )
                    }
                },
            ) { padding ->
                when {
                    loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    error != null -> EmptyState(PrismIcons.Chat, "This chat did not open", error, Modifier.padding(padding))
                    entries.isEmpty() && !running -> EmptyState(
                        PrismIcons.Sparkle, "New chat",
                        if (configured) "Ask Prism anything; it can act on this phone and hand work to T3."
                        else "The assistant isn't set up on this phone (assistant_api_key).",
                        Modifier.padding(padding).padding(bottom = cardSpace),
                    )
                    else -> LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + cardSpace + 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().fadeUnderBar(list, padding.calculateTopPadding()),
                    ) {
                        items(entries, key = { it.id }) { e -> TranscriptEntry(e, surfaces, agent?.takeIf { e.id == handoff }, now) }
                        if (thinking(transcript, running)) item(key = "thinking") { Thinking() }
                    }
                }
            }
            Box(
                // Gemini's app keeps its field 16 dp from the sides and 12 dp above the bottom.
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                    .onSizeChanged { cardHeight = it.height },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (composer != null) composer() else at.quickme.prism.AssistComposer(
                    text = text,
                    onText = onText,
                    canSend = configured && canSend && !running,
                    sending = running,
                    onSend = onSend,
                    animate = true,
                    flat = true,
                    selectors = {},
                    modifier = Modifier.fillMaxWidth().widthIn(max = 696.dp),
                )
            }
        }
    }
    Box(
        modifier.graphicsLayer {
            // Going back to the card: smaller towards the bottom, where the
            // card is, and down with the finger, the corners rounding.
            val p = maxOf(back.value, (pulled / reach).coerceIn(0f, 1f))
            val s = 1f - 0.1f * p
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 1f)
            translationY = pulled * 0.5f
            shape = RoundedCornerShape(CollapseRadius * p)
            clip = p > 0f
        },
    ) {
        if (chats == null) content() else ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = { chats { scope.launch { drawer.close() } } },
            content = content,
        )
    }
}

/**
 * The chat fades out under the top bar ([top] its bottom) over [length], as
 * Gemini's app's does, once it has scrolled under it.
 */
private fun Modifier.fadeUnderBar(list: androidx.compose.foundation.lazy.LazyListState, top: androidx.compose.ui.unit.Dp, length: androidx.compose.ui.unit.Dp = 40.dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        if (!list.canScrollBackward) return@drawWithContent
        val t = top.toPx()
        val l = length.toPx()
        drawRect(
            Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = t, endY = t + l),
            topLeft = Offset(0f, t), size = Size(size.width, l), blendMode = BlendMode.DstOut,
        )
    }

/** The handle dragged this far down takes the chat back into the card. */
private val CollapseDrag = 96.dp

/** Or flung down this fast (dp/s). */
private const val CollapseFlingDpPerS = 1200f

/** How far the drag goes for the whole shrink. */
private val CollapseReach = 320.dp

/** The corners the chat rounds to on its way back. */
private val CollapseRadius = 32.dp

/** Material's predictive back: the gesture's progress, decelerated. */
private val CollapseEasing = androidx.compose.animation.core.CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** "Today", "Yesterday", "Previous 7 days", or the month. */
internal fun dayLabel(unix: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochSecond(unix).atZone(zone).toLocalDate()
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "Previous 7 days"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
        else -> day.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    }
}

/** The time on the day it was today, else the date. */
private fun timeLabel(unix: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochSecond(unix).atZone(zone)
    val today: LocalDate = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    return when {
        at.toLocalDate() == today -> at.format(DateTimeFormatter.ofPattern("HH:mm"))
        at.year == today.year -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        else -> at.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }
}
