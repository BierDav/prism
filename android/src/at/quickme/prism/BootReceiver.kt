// Starts the relay connection and NetBird after a boot or an app update, so
// requests reach the phone without opening the app first; as the Device
// Owner, grants a new version's permissions, and schedules the update check;
// applies the hotspot's mode (Hotspot.kt).

package at.quickme.prism

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.quickme.prism.netbird.Netbird

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Dpc.setUp(context)
        PushService.start(context)
        Metrics.schedule(context)
        Netbird.start(context)
        Updater.schedule(context)
        // Normal mode's hotspot comes back after a reboot; instant mode's scan resumes.
        Hotspot.apply(context)
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.installed(context) else Updater.checkSoon(context)
    }
}
