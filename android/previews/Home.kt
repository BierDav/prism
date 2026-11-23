// The main screen: each tab with its sample data, as a whole screen and, for
// the tabs that scroll, as tall as all of it; the inbox's bar above the
// navigation bar while something waits; the assistant composer; and the
// larger windows: an unfolded foldable, a tablet and a TV, with the rail and
// an alert beside the list.

package at.quickme.prism.previews

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import at.quickme.prism.AlertsState
import at.quickme.prism.AlertsTab
import at.quickme.prism.ApprovalsContent
import at.quickme.prism.ApprovalsStatus
import at.quickme.prism.AssistComposer
import at.quickme.prism.AssistChips
import at.quickme.prism.AssistChoice
import at.quickme.prism.PinnedChoice
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.choice
import at.quickme.prism.machineChoice
import at.quickme.prism.HomeNav
import at.quickme.prism.HomeScaffold
import at.quickme.prism.Inbox
import at.quickme.prism.MachinesState
import at.quickme.prism.MachinesTab
import at.quickme.prism.PhoneContent
import at.quickme.prism.PlacementSection
import at.quickme.prism.QuestionsState
import at.quickme.prism.Sample
import at.quickme.prism.Tab
import at.quickme.prism.shared.Approval
import at.quickme.prism.ui.Appearance

/**
 * The main screen on [tab] with the sample data; [pending] approvals and
 * the sample questions in the inbox when [inbox] says so.
 */
@Composable
internal fun SampleHome(
    tab: Tab,
    alerts: AlertsState = remember { sampleAlerts() },
    /** An alert open beside the list (Sample.alerts' ids). */
    alert: Long? = null,
    pending: List<Approval> = emptyList(),
    questions: QuestionsState = remember { QuestionsState() },
    inbox: Inbox = rememberSampleInbox(pending, questions),
    enrolled: Boolean = true,
    approvals: ApprovalsStatus = remember { sampleApprovals() },
) {
    val machines = remember { sampleMachines() }
    val nav = remember { HomeNav(HomeNav.start(tab)).apply { alert?.let(::openAlert) } }
    HomeScaffold(
        nav, alerts, pending, questions, inbox, t3Linked = true,
        snackbar = remember { SnackbarHostState() },
        tabContent = { t, padding -> SampleTab(t, padding, alerts, machines, approvals, enrolled, nav.alert) },
        alertContent = { id -> SampleAlert(id) },
    )
}

@Composable
private fun SampleTab(
    t: Tab,
    padding: PaddingValues,
    alerts: AlertsState,
    machines: MachinesState,
    approvals: ApprovalsStatus,
    enrolled: Boolean,
    alert: Long?,
) {
    when (t) {
        Tab.Alerts -> AlertsTab(alerts, padding, show = {}, onOpen = {}, selected = alert, enrolled = enrolled)
        Tab.Machines -> MachinesTab(machines, padding, enrolled = enrolled)
        Tab.Approvals -> ApprovalsContent(padding, approvals, onOpen = {}, onRaiseSample = {})
        Tab.Phone -> PhoneContent(
            padding, remember { Sample.phoneStatus("vps-falkenstein") }, "1.0 (1)",
            onNetbird = {}, onCheckUpdates = {}, onRelease = {},
            placementSettings = {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    at.quickme.prism.ui.SectionHeader("Placement", Modifier.padding(start = 4.dp, top = 8.dp))
                    PlacementSection(
                        placement = remember { Sample.placement() },
                        onOpenProfile = {},
                        onOpenActions = {},
                    )
                }
            },
        )
    }
}

/** Taller than the phone: the whole of a tab that scrolls. */
@Preview(name = "whole", device = PHONE, heightDp = 1800)
annotation class WholePreview

@ScreenPreviews
@WholePreview
@Composable
private fun AlertsTabPreview() = Themed { SampleHome(Tab.Alerts) }

@Preview(name = "not-connected", device = PHONE)
@Composable
private fun AlertsTabNotConnectedPreview() = Themed { SampleHome(Tab.Alerts, alerts = remember { AlertsState() }, enrolled = false) }

@ScreenPreviews
@WholePreview
@Composable
private fun MachinesTabPreview() = Themed { SampleHome(Tab.Machines) }

@ScreenPreviews
@WholePreview
@Composable
private fun ApprovalsTabPreview() = Themed { SampleHome(Tab.Approvals) }

/** Nothing waits, nothing decided yet. */
@Preview(name = "empty", device = PHONE)
@Composable
private fun ApprovalsEmptyPreview() = Themed { SampleHome(Tab.Approvals, approvals = remember { sampleApprovals(empty = true) }) }

@ScreenPreviews
@WholePreview
@Composable
private fun PhoneTabPreview() = Themed { SampleHome(Tab.Phone) }

/** The inbox's bar over the Alerts tab: the sample approval first, then two questions. */
@ScreenPreviews
@Composable
private fun InboxBarPreview() = Themed {
    SampleHome(Tab.Alerts, pending = remember { listOf(Sample.approval()) }, questions = remember { sampleQuestions() })
}

/** The bar alone on a question, the only thing waiting. */
@PartPreviews
@Composable
private fun InboxBarQuestionPreview() = Part {
    val questions = remember { QuestionsState().apply { questions = sampleQuestions().questions.take(1) } }
    at.quickme.prism.InboxBar(rememberSampleInbox(emptyList(), questions), screenHeight = 2856f)
}

/** The assistant composer over the live app, with the launch choices and a draft. */
@ScreenPreviews
@Composable
private fun AssistantPreview() = Themed {
    Box(Modifier.fillMaxSize()) {
        SampleHome(Tab.Alerts)
        Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor))
        val options = remember { Sample.agentOptions() }
        at.quickme.prism.GeminiColors {
        AssistComposer(
            text = "Fix the stale Paperless backup", onText = {}, canSend = true, sending = false,
            onSend = {}, animate = false,
            selectors = {
                AssistChips("Profile", options.profiles.map { AssistChoice(it.id, it.label) },
                    selected = "coding", enabled = true, onPick = {})
                AssistChips("Machine", listOf(options.machine, "david-mb", "david-nuc-2").map { machineChoice(it, it) },
                    selected = "auto", enabled = true, searchable = true, onPick = {},
                    pinned = listOf(PinnedChoice("auto", "Pick the machine", PrismIcons.Sparkle, "david-mb")))
                AssistChips("Project", options.projects.map { it.choice() }.filter { it.key.isNotEmpty() },
                    selected = options.defaultProject, enabled = true, searchable = true, onPick = {},
                    pinned = listOf(
                        PinnedChoice("", "No project", PrismIcons.Chat, "No project"),
                        PinnedChoice("auto", "Pick the project", PrismIcons.Sparkle, "Auto"),
                    ))
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
        )
        }
    }
}

/** The overlay after a send: the agent at work above the composer, which now takes a follow-up. */
@ScreenPreviews
@Composable
private fun AssistantConversationPreview() = Themed {
    Box(Modifier.fillMaxSize()) {
        SampleHome(Tab.Alerts)
        Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor))
        val agent = at.quickme.prism.shared.Agent(id = "a3", title = "Clean up old images on david-server", target = "david-server")
        fun item(n: Int, type: String, text: String, status: String = "completed", title: String = "") =
            at.quickme.prism.shared.ActivityItem("i$n", n.toLong(), "r1", type, status, title, text)
        val activity = at.quickme.prism.shared.AgentActivity(agent, "running", listOf(
            item(1, "user_message", "Free up disk space on david-server"),
            item(2, "assistant_message", "Looking at what fills the disk first."),
            item(3, "command_execution", "$ df -h /var/lib/containerd"),
            item(4, "command_execution", "$ crictl rmi --prune", status = "running"),
        ))
        at.quickme.prism.GeminiColors {
            AssistComposer(
                text = "", onText = {}, canSend = true, sending = false, onSend = {}, animate = false,
                placeholder = "Reply to the agent", onMic = {}, onNew = {},
                selectors = {
                    at.quickme.prism.AgentConversation(
                        title = "Free up disk space", activity = activity, pending = emptyList(), error = null,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        onOpen = {}, onStop = {}, modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
            )
        }
    }
}

// --- Larger windows ---

/** A foldable, open: Pixel 9 Pro Fold's inner screen. */
@Preview(name = "foldable", widthDp = 851, heightDp = 882)
annotation class FoldablePreview

/** A tablet on its side. */
@Preview(name = "tablet", widthDp = 1280, heightDp = 800)
annotation class TabletPreview

/** A TV (1080p at 320 dpi). */
@Preview(name = "tv", widthDp = 960, heightDp = 540, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_TELEVISION)
annotation class TvPreview

/** A phone on its side: short, so the rail and a small top bar. */
@Preview(name = "phone-landscape", widthDp = 892, heightDp = 412)
annotation class LandscapePreview

/** The Alerts list and an alert side by side. */
@FoldablePreview
@TabletPreview
@TvPreview
@Composable
private fun AlertsListDetailPreview() = Themed { SampleHome(Tab.Alerts, alert = 412) }

/** The Alerts list with no alert open: the detail pane waits. */
@TabletPreview
@Composable
private fun AlertsNoDetailPreview() = Themed { SampleHome(Tab.Alerts) }

/** The machines in columns. */
@TabletPreview
@TvPreview
@Composable
private fun MachinesWidePreview() = Themed { SampleHome(Tab.Machines) }

/** The device's tab in readable width. */
@TvPreview
@LandscapePreview
@Composable
private fun PhoneWidePreview() = Themed { SampleHome(Tab.Phone) }
