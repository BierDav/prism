// The main screen: four tabs. Alerts (the Prism server's alerts and agents,
// an alert beside the list where there is room), Machines (the hosts' CPU,
// memory, disk and network), Approvals (what waits, the last decisions, the
// device key) and Phone (NetBird, push, metrics; "TV" on a TV). Above the
// navigation bar, the inbox (Inbox.kt): approvals and every T3 thread's
// questions, opened full screen to swipe through. HomeNav.kt lays it out
// for the window: a phone, a foldable, a tablet or a TV. Everything reloads
// by itself: on resume, on every push, and every 30 s while on screen.

package at.quickme.prism

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import at.quickme.prism.netbird.Netbird
import at.quickme.prism.shell.Shell
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"

        /** Opens the inbox at this item (InboxItem.key), or at the first one for "". */
        const val EXTRA_INBOX = "inbox"

        /** Opens this alert (its id, a Long) on the Alerts tab. */
        const val EXTRA_ALERT = "alertId"
        const val TAB_ALERTS = "alerts"
        const val TAB_MACHINES = "machines"
        const val TAB_APPROVALS = "approvals"
        const val TAB_PHONE = "phone"

        /** Opens the main screen on a tab (from notifications). */
        @JvmStatic
        fun intent(c: Context, tab: String): Intent = Intent(c, MainActivity::class.java)
            .putExtra(EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Opens the inbox full screen at one item (from notifications). */
        @JvmStatic
        fun inboxIntent(c: Context, key: String): Intent = Intent(c, MainActivity::class.java)
            .putExtra(EXTRA_INBOX, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Opens one alert (from notifications). */
        @JvmStatic
        fun alertIntent(c: Context, id: Long): Intent = Intent(c, MainActivity::class.java)
            .putExtra(EXTRA_ALERT, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /** Where an intent asks to go, until Home has gone there. */
    private var request by mutableStateOf<Route?>(null)
    private var openInbox by mutableStateOf<String?>(null)
    private var askedVpnConsent = false
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) Netbird.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Recreated, the back stack comes back on its own (HomeNav.Saver).
        if (savedInstanceState == null) {
            request = route(intent)
            openInbox = intent.getStringExtra(EXTRA_INBOX)
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Dpc.setUp(this)
        PushService.start(this)
        Hotspot.apply(this)
        Metrics.schedule(this)
        Netbird.start(this)
        Updater.schedule(this)
        setContent {
            PrismTheme {
                Home(request, onRequestDone = { request = null }, openInbox, onInboxOpened = { openInbox = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)?.let { request = it }
        intent.getStringExtra(EXTRA_INBOX)?.let { openInbox = it }
    }

    private fun route(intent: Intent): Route? = when {
        intent.hasExtra(EXTRA_ALERT) -> Route.AlertDetail(intent.getLongExtra(EXTRA_ALERT, 0))
        intent.hasExtra(EXTRA_TAB) -> Route.Top(Tab.of(intent.getStringExtra(EXTRA_TAB)))
        else -> null
    }

    // The inbox shows approvals here: their notifications go quiet meanwhile.
    override fun onResume() {
        super.onResume()
        Approver.setInFront(this, true)
        Shell.front()
        // NetBird connects by itself; only the system's VPN consent needs the
        // owner, once (a Device Owner's always-on VPN never asks).
        val consent = if (Netbird.on(this)) Netbird.needsConsent(this) else null
        if (consent != null && !askedVpnConsent) {
            askedVpnConsent = true
            vpnConsent.launch(consent)
        } else {
            Netbird.start(this)
        }
    }

    override fun onPause() {
        Approver.setInFront(this, false)
        super.onPause()
    }
}

enum class Tab(val key: String, val label: String, private val glyph: ImageVector) {
    Alerts(MainActivity.TAB_ALERTS, "Alerts", PrismIcons.Prism),
    Machines(MainActivity.TAB_MACHINES, "Machines", PrismIcons.Server),
    Approvals(MainActivity.TAB_APPROVALS, "Approvals", PrismIcons.Shield),
    Phone(MainActivity.TAB_PHONE, "Phone", PrismIcons.Phone);

    /** The tab's name; the Phone tab is "TV" on a TV. */
    @Composable
    fun title(): String = if (this == Phone && DeviceKey.tv(LocalContext.current)) "TV" else label

    @Composable
    fun icon(): ImageVector = if (this == Phone && DeviceKey.tv(LocalContext.current)) PrismIcons.Tv else glyph

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Alerts
    }
}

@Composable
private fun Home(
    request: Route?,
    onRequestDone: () -> Unit,
    openInbox: String?,
    onInboxOpened: () -> Unit,
) {
    val context = LocalContext.current
    val nav = rememberSaveable(saver = HomeNav.Saver) { HomeNav() }
    val alerts = remember { AlertsState() }
    val machines = remember { MachinesState() }
    val questions = remember { QuestionsState() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val inbox = remember { Inbox() }

    LaunchedEffect(request) {
        when (request) {
            is Route.Top -> nav.select(request.tab)
            is Route.AlertDetail -> nav.openAlert(request.id)
            null -> return@LaunchedEffect
        }
        onRequestDone()
    }

    // Alerts and questions follow the server's push while the screen is up,
    // and a slow poll repairs a push that got lost.
    LifecycleResumeEffect(Unit) {
        val reload = Runnable {
            scope.launch { alerts.load(context) }
            scope.launch { questions.load(context) }
            scope.launch { Approver.load(context) }
        }
        PrismPush.listen(reload)
        Netbird.listen(reload)
        reload.run()
        val poll = scope.launch {
            while (true) {
                delay(30_000)
                reload.run()
            }
        }
        onPauseOrDispose {
            PrismPush.unlisten(reload)
            Netbird.unlisten(reload)
            poll.cancel()
        }
    }
    val pending by remember { Approver.queue(context) }.pending.collectAsState()
    val items = remember(pending, questions.questions) {
        pending.map { InboxItem.Pending(it) } + questions.questions.map { InboxItem.Question(it) }
    }
    SideEffect { inbox.update(items) }
    LaunchedEffect(openInbox) {
        if (openInbox != null) {
            inbox.openAt = openInbox
            onInboxOpened()
        }
    }
    InboxEffects(inbox, questions.loaded || !PrismApi.enrolled(context))

    HomeScaffold(
        nav, alerts, pending, questions, inbox,
        t3Linked = Settings.t3Url(context).isNotEmpty(),
        snackbar = snackbar,
        tabContent = { t, padding ->
            val show: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
            when (t) {
                Tab.Alerts -> AlertsTab(alerts, padding, show, onOpen = nav::openAlert, selected = nav.alert)
                Tab.Machines -> {
                    MachinesPoll(machines)
                    MachinesTab(machines, padding)
                }
                Tab.Approvals -> ApprovalsTab(padding)
                Tab.Phone -> PhoneTab(padding)
            }
        },
        alertContent = { id -> AlertScreen(id, onBack = nav::back) },
    )
}
