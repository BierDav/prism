// Which apps may run in the background, for battery life: Android's
// per-app "Restricted" battery setting (app op RUN_ANY_IN_BACKGROUND), set
// through the shell server (uid 2000) since only the shell or Settings may.
//
//  - background_restricted: never in the background (no jobs, alarms,
//    syncs or services while not on screen). They work normally while open.
//  - background_charging_only: in the background only while the phone is
//    plugged in; restricted again when it's unplugged, and their background
//    work stopped then (`am make-uid-idle`).
//  - activity_recognition_denied: may not use activity recognition (app op
//    and runtime permission ACTIVITY_RECOGNITION). Play services' activity
//    detection runs for the apps that ask for it; Digital Wellbeing's
//    requests set off wakeup alarms (NetworkLocationActivityDetection) all
//    day for "walking" features the owner doesn't use.
//
// Package names, separated by commas or new lines; unset, the defaults below
// apply ("" keeps no app restricted). Applied when the phone is plugged in
// or unplugged (PushService), when the shell server attaches, and with every
// metrics push (MetricsJob), which also reports the result
// (phone_app_background_allowed). The app ops stay across reboots, so
// without the shell server the last state holds.

package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import at.quickme.prism.shell.Shell

object BackgroundPolicy {
    private const val TAG = "PrismBackground"

    /**
     * Unset background_restricted: mail is read on the laptop, and Google
     * Weather's SessionWorker job ran for 45 s per hour and a quarter with
     * the screen off; the forecast is fetched when it's opened.
     */
    const val DEFAULT_RESTRICTED = "com.google.android.gm,com.google.android.apps.weather"

    /** Unset background_charging_only: WhatsApp only while open or charging. */
    const val DEFAULT_CHARGING_ONLY = "com.whatsapp"

    /** Unset activity_recognition_denied: Digital Wellbeing (see the header). */
    const val DEFAULT_ACTIVITY_DENIED = "com.google.android.apps.wellbeing"

    /** What was last applied (package -> allowed), so unchanged states cost no command. */
    private val applied = HashMap<String, Boolean>()

    /** Packages whose activity recognition this process has denied. */
    private val activityDenied = HashSet<String>()

    private fun packages(c: Context, key: String, default: String): List<String> {
        val raw = if (Settings.source(c, key).isEmpty()) default else Settings.value(c, key)
        return raw.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    @JvmStatic
    fun plugged(c: Context): Boolean {
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /**
     * Whether each configured, installed app may run in the background now;
     * the app ops are set to match. `force` sets them even when this process
     * applied the same before (someone may have changed them in Settings).
     * Blocks: call it off the main thread.
     */
    @JvmStatic
    @Synchronized
    fun apply(c: Context, plugged: Boolean = plugged(c), force: Boolean = false): Map<String, Boolean> {
        val want = LinkedHashMap<String, Boolean>()
        for (p in packages(c, "background_charging_only", DEFAULT_CHARGING_ONLY)) want[p] = plugged
        for (p in packages(c, "background_restricted", DEFAULT_RESTRICTED)) want[p] = false
        // Without the shell nothing changes: report what was last applied.
        if (!Shell.available()) return HashMap(applied)
        // Which are installed, as the shell sees them: Prism itself may not
        // see other apps (package visibility).
        val installed = try {
            Shell.run(15_000, "pm", "list", "packages").stdout.lineSequence()
                .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "listing packages: " + e.message)
            return HashMap(applied)
        }
        if (installed.isEmpty()) return HashMap(applied)
        want.keys.retainAll { it in installed }
        val commands = ArrayList<String>()
        val deny = packages(c, "activity_recognition_denied", DEFAULT_ACTIVITY_DENIED).filter { it in installed }
        for (p in deny) {
            if (!force && p in activityDenied) continue
            // The app op takes effect at once; revoking the permission keeps
            // Play services' own check from passing too.
            commands.add("cmd appops set $p ACTIVITY_RECOGNITION ignore")
            commands.add("pm revoke $p android.permission.ACTIVITY_RECOGNITION")
        }
        for ((p, allowed) in want) {
            if (!force && applied[p] == allowed) continue
            commands.add("cmd appops set $p RUN_ANY_IN_BACKGROUND " + if (allowed) "allow" else "ignore")
            // Its background work stops now rather than when Android gets to it;
            // an app on screen isn't affected.
            if (!allowed) commands.add("am make-uid-idle --user 0 $p")
        }
        if (commands.isEmpty()) return want
        try {
            val r = Shell.run(30_000, "sh", "-c", commands.joinToString("; "))
            if (r.exitCode < 0) Log.w(TAG, "applying: $r ${r.stderr.take(300)}")
            for ((p, allowed) in want) applied[p] = allowed
            activityDenied.addAll(deny)
            Log.i(TAG, "background: " + want.entries.joinToString { "${it.key}=${if (it.value) "allowed" else "restricted"}" } +
                if (deny.isEmpty()) "" else "; no activity recognition: " + deny.joinToString())
        } catch (e: Exception) {
            Log.w(TAG, "applying: " + e.message)
        }
        return want
    }

    /** apply() on its own thread, for receivers and listeners. */
    @JvmStatic
    fun applyAsync(c: Context, plugged: Boolean? = null) {
        val app = c.applicationContext
        Thread({
            try {
                if (plugged == null) apply(app) else apply(app, plugged)
            } catch (e: RuntimeException) {
                Log.w(TAG, "applying: " + e.message)
            }
        }, "background-policy").start()
    }
}
