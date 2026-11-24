// The screens beside the main one: an alert (AlertScreen), the approval
// screen (ApproveActivity), NetBird (NetbirdActivity),
// shell access (ShellActivity) and an app's request for it (PermissionActivity).

package at.quickme.prism.previews

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import at.quickme.prism.AlertContent
import at.quickme.prism.ApproveScreen
import at.quickme.prism.HotspotContent
import at.quickme.prism.HotspotState
import at.quickme.prism.NetbirdContent
import at.quickme.prism.PlacementActionsContent
import at.quickme.prism.PlacementProfileContent
import at.quickme.prism.Sample
import at.quickme.prism.shell.AdbState
import at.quickme.prism.shell.Pairing
import at.quickme.prism.shell.PermissionContent
import at.quickme.prism.shell.ShellContent

@Composable
internal fun SampleAlert(id: Long, busy: String? = null, error: String? = null) {
    val alert = remember { Sample.alerts().first { it.id == id } }
    AlertContent(id, alert, error, busy, remember { SnackbarHostState() }, onBack = {}, onAction = {})
}

/** A critical cluster alert with an agent that asks something. */
@ScreenPreviews
@Composable
private fun AlertPreview() = Themed { SampleAlert(412) }

/** A failed machine check: its command and output. */
@ScreenPreviews
@Preview(name = "whole", device = PHONE, heightDp = 1500)
@Composable
private fun AlertMachineCheckPreview() = Themed { SampleAlert(409) }

/** Starting a fix, after the last load failed. */
@Preview(name = "busy", device = PHONE)
@Composable
private fun AlertBusyPreview() = Themed { SampleAlert(405, busy = "fix", error = "Prism did not answer: timeout after 10 s.") }

/** Still loading. */
@Preview(name = "loading", device = PHONE)
@Composable
private fun AlertLoadingPreview() = Themed {
    AlertContent(412, null, null, null, remember { SnackbarHostState() }, onBack = {}, onAction = {})
}

/** The sample approval, the first of two. */
@ScreenPreviews
@Composable
private fun ApprovePreview() = Themed {
    ApproveScreen(
        remember { Sample.approval() }, position = 1, count = 2, notice = null,
        onDeny = {}, onApprove = {}, onOpenThread = {}, canOpenThread = true,
    )
}

/** A long command, and what went wrong with the last try. */
@ScreenPreviews
@Composable
private fun ApproveLongPreview() = Themed {
    ApproveScreen(
        remember { Sample.approval(long = true) }, position = 1, count = 1,
        notice = "No fingerprint is available (11).",
        onDeny = {}, onApprove = {}, onOpenThread = {}, canOpenThread = true,
    )
}

/** NetBird: on, connected, with an exit node picked. */
@ScreenPreviews
@Composable
private fun NetbirdPreview() = Themed {
    val s = remember { Sample.phoneStatus("vps-falkenstein") }
    NetbirdContent(
        s.netbird, s.netbirdOn, s.netbirdConnected, s.exitNodes, s.exitNode, Sample.resources, Sample.enabledResources,
        onToggle = {}, onExitNode = {}, onResourceToggle = { _, _ -> }, onBack = {},
    )
}

/** The hotspot: Always on, two devices, and its network. */
@ScreenPreviews
@Composable
private fun HotspotPreview() = Themed {
    HotspotContent(
        HotspotState("normal", on = true, info = remember { Sample.hotspot() }, error = null, shell = true),
        busy = null, notice = null, onKeepOn = {}, onTurnOn = {}, onTurnOff = {}, onSave = {}, onShell = {}, onBack = {},
    )
}

/** One placement profile: full-selection model list with provider icons, reasoning chips and switches. */
@ScreenPreviews
@Composable
private fun PlacementProfilePreview() = Themed {
    val placement = remember { Sample.placement() }
    PlacementProfileContent(
        profile = placement.profiles.first(),
        models = placement.models,
        loading = false,
        saving = false,
        error = null,
        onBack = {},
        onReload = {},
        onSaveProfile = { _, _, _ -> },
    )
}

/** Placement actions: alert fixes, text generation, delegated agents roles, and recent moves. */
@ScreenPreviews
@WholePreview
@Composable
private fun PlacementActionsPreview() = Themed {
    PlacementActionsContent(
        placement = remember { Sample.placement() },
        saving = false,
        error = null,
        onBack = {},
        onReload = {},
        onAlertFix = {},
        onTextGeneration = {},
    )
}

/** Shell access with the server running, adb on TCP 5555 and three apps on Shizuku's API. */
@ScreenPreviews
@Composable
private fun ShellPreview() = Themed {
    ShellContent(running = true, status = Sample.SHELL_STATUS, adb = AdbState(true, true), apps = Sample.shellApps(), onBack = {})
}

/** After a reboot, before the cluster started the server. */
@Preview(name = "not-running", device = PHONE)
@Composable
private fun ShellNotRunningPreview() = Themed {
    ShellContent(running = false, status = "not running (boot) since 08:10: the cluster starts it over adb. Wireless debugging must stay on.",
        adb = AdbState(true, null), apps = emptyList(), onBack = {})
}

/** Pairing the cluster's adb key: the code went out, the cluster pairs and starts the server. */
@Preview(name = "pairing", device = PHONE)
@Composable
private fun ShellPairingPreview() = Themed {
    ShellContent(running = false, status = "not running (no-server) since 08:10: the cluster starts it over adb. Wireless debugging must stay on.",
        adb = AdbState(true, null), apps = emptyList(), onBack = {},
        pairing = Pairing.State(Pairing.Phase.SENT, "Code sent at 08:12; the cluster pairs and starts the shell server.", 37123))
}

/** Pairing didn't finish: the reason and Try again. */
@Preview(name = "pairing-failed", device = PHONE)
@Composable
private fun ShellPairingFailedPreview() = Themed {
    ShellContent(running = false, status = "not running (no-server) since 08:10: the cluster starts it over adb. Wireless debugging must stay on.",
        adb = AdbState(true, null), apps = emptyList(), onBack = {},
        pairing = Pairing.State(Pairing.Phase.FAILED, "The cluster didn't start the shell server within 90 s. The code may have been " +
            "mistyped or the dialog closed; Wi-Fi and NetBird must be on."))
}

/** An app on Shizuku's API asks for the shell. */
@ScreenPreviews
@Composable
private fun ShellPermissionPreview() = Themed { PermissionContent("App Manager", "io.github.muntashirakon.AppManager", onDecide = {}) }
