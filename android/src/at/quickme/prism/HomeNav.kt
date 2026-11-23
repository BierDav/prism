// The main screen's frame and navigation (Navigation 3). Its back stack
// (HomeNav) starts on the Alerts tab, then holds the tab in view and an
// alert opened from the Alerts list; NavDisplay shows its entries. Where the
// window is wide enough (an unfolded foldable, a tablet, a TV) the Alerts
// list and the open alert sit side by side (ListDetailSceneStrategy, which
// keeps both off a foldable's hinge); on a phone the alert covers the list.
//
// The navigation: a bar at the bottom of a phone held upright, a rail at the
// leading edge everywhere else (Material's navigation for medium and wider
// windows, and a phone on its side, which is short). With a D-pad (a TV's
// remote, a keyboard) focus moves from the rail or the bar into the tab, onto
// every card and row (ui/Components.kt, dpadStop), and the theme's inset
// focus ring (PrismTheme) shows it.

package at.quickme.prism

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import at.quickme.prism.shared.Approval
import at.quickme.prism.ui.EmptyState
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.READABLE_WIDTH
import at.quickme.prism.ui.SpectrumGlyph
import at.quickme.prism.ui.readableGutter
import at.quickme.prism.ui.shortWindow

/** A place on the main screen: an entry of its back stack. */
sealed interface Route {
    /** A tab's own screen. */
    data class Top(val tab: Tab) : Route

    /** One alert (AlertScreen), beside the Alerts list where there is room. */
    data class AlertDetail(val id: Long) : Route
}

/**
 * The main screen's back stack: the Alerts tab first, then the tab in view
 * (Back from another tab returns to Alerts, Back from Alerts leaves), then
 * the alert open on the Alerts tab.
 */
@Stable
class HomeNav(initial: List<Route> = start(Tab.Alerts)) {
    val stack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { start(Tab.Alerts) }) }

    /** The tab in view. */
    val tab: Tab get() = stack.filterIsInstance<Route.Top>().lastOrNull()?.tab ?: Tab.Alerts

    /** The alert open, if any. */
    val alert: Long? get() = (stack.lastOrNull() as? Route.AlertDetail)?.id

    /** Shows [t]'s own screen; on the tab in view, back to its top. */
    fun select(t: Tab) = replace(start(t))

    fun openAlert(id: Long) = replace(start(Tab.Alerts) + Route.AlertDetail(id))

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // Keeps the entries both stacks start with, and so their state (the scroll).
    private fun replace(routes: List<Route>) {
        val keep = stack.zip(routes).takeWhile { (a, b) -> a == b }.size
        while (stack.size > keep) stack.removeAt(stack.lastIndex)
        stack.addAll(routes.drop(keep))
    }

    companion object {
        fun start(t: Tab): List<Route> =
            if (t == Tab.Alerts) listOf(Route.Top(Tab.Alerts)) else listOf(Route.Top(Tab.Alerts), Route.Top(t))

        /** In the saved state as "tab:<key>" and "alert:<id>". */
        val Saver: Saver<HomeNav, Any> = listSaver(
            save = { nav ->
                nav.stack.map {
                    when (it) {
                        is Route.Top -> "tab:" + it.tab.key
                        is Route.AlertDetail -> "alert:" + it.id
                    }
                }
            },
            restore = { saved ->
                HomeNav(saved.mapNotNull { s ->
                    when {
                        s.startsWith("tab:") -> Route.Top(Tab.of(s.removePrefix("tab:")))
                        s.startsWith("alert:") -> s.removePrefix("alert:").toLongOrNull()?.let(Route::AlertDetail)
                        else -> null
                    }
                })
            },
        )
    }
}

/** Where the navigation goes: a bar on a phone held upright, a rail on anything wider or short. */
internal fun usesRail(width: Dp, height: Dp): Boolean = width >= 600.dp || height < 480.dp

/**
 * The main screen as it looks: the navigation, [nav]'s entries in
 * NavDisplay, the inbox's bar and its full-screen view. Home feeds it live
 * state; the previews feed it sample data. [tabContent] is a tab's content
 * inside the padding of its top bar and the window's edges; [alertContent]
 * is an open alert.
 */
@Composable
internal fun HomeScaffold(
    nav: HomeNav,
    alerts: AlertsState,
    pending: List<Approval>,
    questions: QuestionsState,
    inbox: Inbox,
    t3Linked: Boolean,
    snackbar: SnackbarHostState,
    tabContent: @Composable (Tab, PaddingValues) -> Unit,
    alertContent: @Composable (Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val screenHeight = constraints.maxHeight.toFloat()
        val screenWidth = constraints.maxWidth.toFloat()
        val badge: (Tab) -> Int = { t ->
            when (t) {
                Tab.Alerts -> alerts.criticalCount()
                Tab.Approvals -> pending.size
                Tab.Machines, Tab.Phone -> 0
            }
        }
        val subtitle = summary(pending.size, questions.questions.size, alerts)
        val display: @Composable (Modifier) -> Unit = { modifier ->
            HomeDisplay(nav, subtitle, snackbar, tabContent, alertContent, modifier)
        }
        // On a short window (a TV, a phone on its side) the bar only shows
        // while something waits: saying so would take a fifth of the height.
        val showInboxBar = maxHeight >= 600.dp || inbox.items.isNotEmpty()
        val inboxBar: @Composable () -> Unit = {
            // A mini player, no wider than it reads well.
            if (showInboxBar) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = READABLE_WIDTH)) { InboxBar(inbox, screenHeight) }
                }
            }
        }
        if (usesRail(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize()) {
                HomeRail(nav, badge)
                Column(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) {
                    display(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) { inboxBar() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                display(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                inboxBar()
                ShortNavigationBar {
                    Tab.entries.forEach { t ->
                        ShortNavigationBarItem(
                            selected = nav.tab == t,
                            onClick = { nav.select(t) },
                            icon = { TabIcon(t, badge(t)) },
                            label = { Text(t.title()) },
                        )
                    }
                }
            }
        }
        InboxPlayer(inbox, questions, screenWidth, screenHeight, t3Linked)
    }
}

/** The rail: the tabs at the leading edge; on a TV, focus starts on the tab in view. */
@Composable
private fun HomeRail(nav: HomeNav, badge: (Tab) -> Int) {
    val focus = remember { FocusRequester() }
    val tv = DeviceKey.tv(LocalContext.current)
    WideNavigationRail(
        arrangement = if (tv) Arrangement.Center else Arrangement.Top,
    ) {
        Tab.entries.forEach { t ->
            val selected = nav.tab == t
            WideNavigationRailItem(
                selected = selected,
                onClick = { nav.select(t) },
                icon = { TabIcon(t, badge(t)) },
                label = { Text(t.title()) },
                railExpanded = false,
                modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    if (tv) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun TabIcon(t: Tab, badge: Int) {
    BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(t.icon(), null) }
}

/** [nav]'s entries: a tab, or the Alerts list and an alert (side by side where there is room). */
@Composable
private fun HomeDisplay(
    nav: HomeNav,
    subtitle: String,
    snackbar: SnackbarHostState,
    tabContent: @Composable (Tab, PaddingValues) -> Unit,
    alertContent: @Composable (Long) -> Unit,
    modifier: Modifier,
) {
    // Two panes from a medium width on: an unfolded foldable shows both.
    val listDetail = rememberListDetailSceneStrategy<Route>(
        directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()),
    )
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    NavDisplay(
        backStack = nav.stack,
        modifier = modifier,
        onBack = { nav.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        sceneStrategies = listOf(listDetail),
        // Tabs fade through each other; the panes animate on their own.
        transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
        popTransitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
        entryProvider = entryProvider {
            entry<Route.Top>(
                metadata = { route ->
                    if (route.tab == Tab.Alerts) {
                        ListDetailSceneStrategy.listPane(detailPlaceholder = { NoAlertOpen() })
                    } else {
                        emptyMap()
                    }
                },
            ) { route -> TabScreen(route.tab, subtitle, snackbar, tabContent) }
            entry<Route.AlertDetail>(metadata = ListDetailSceneStrategy.detailPane()) { route -> alertContent(route.id) }
        },
    )
}

/** The detail pane while no alert is open. */
@Composable
private fun NoAlertOpen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(PrismIcons.Prism, "No alert open", "Pick an alert to see what fires, its agents and what to do.", focusable = false)
    }
}

/**
 * A tab with Prism's top bar: large and collapsing on a tall window, small
 * on a short one. [content] gets the bar's padding and, on a wide window,
 * gutters that keep it readable (the Machines tab takes the whole width,
 * for its columns).
 */
@Composable
private fun TabScreen(tab: Tab, subtitle: String, snackbar: SnackbarHostState, content: @Composable (Tab, PaddingValues) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val short = shortWindow()
    val title: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!short) SpectrumGlyph(Modifier.size(width = 40.dp, height = 30.dp), glass = MaterialTheme.colorScheme.onSurface)
            Text("Prism", style = if (short) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLargeEmphasized)
        }
    }
    val sub: @Composable () -> Unit = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    Scaffold(
        modifier = if (short) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (short) {
                TopAppBar(title = title, subtitle = sub, colors = colors)
            } else {
                LargeFlexibleTopAppBar(title = title, subtitle = sub, scrollBehavior = scrollBehavior, colors = colors)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gutter = if (tab == Tab.Machines) 0.dp else readableGutter(maxWidth)
            val direction = LocalLayoutDirection.current
            content(
                tab,
                PaddingValues(
                    start = padding.calculateStartPadding(direction) + gutter,
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction) + gutter,
                    bottom = padding.calculateBottomPadding(),
                ),
            )
        }
    }
}

private fun summary(pending: Int, questions: Int, alerts: AlertsState): String {
    val critical = alerts.criticalCount()
    val open = alerts.alerts?.size ?: 0
    return when {
        pending == 1 -> "An approval is waiting"
        pending > 1 -> "$pending approvals are waiting"
        questions == 1 -> "An agent asks you something"
        questions > 1 -> "$questions agent questions wait for you"
        critical == 1 -> "1 critical alert"
        critical > 1 -> "$critical critical alerts"
        open == 1 -> "1 open alert"
        open > 1 -> "$open open alerts"
        alerts.alerts != null -> "All quiet"
        else -> "Your cluster, your machines, your agents"
    }
}
