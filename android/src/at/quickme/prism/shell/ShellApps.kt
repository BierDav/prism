// Which apps may use the shell server through Shizuku's API: the owner's
// decisions, per package, kept in Prism's preferences and handed to the
// server whenever it attaches (Shell.attach). An app that asks
// (Shizuku.requestPermission) gets a prompt: a notification that opens
// PermissionActivity (full screen while the phone is in use), with Deny on
// the notification itself; Allow only on the screen, behind the lock screen.
// The Shell access screen lists the apps and takes a decision back.

package at.quickme.prism.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import at.quickme.prism.R

object ShellApps {
    private const val TAG = "PrismShell"
    private const val CHANNEL = "shell-permission"
    private const val ALLOWED = "allowed"
    private const val DENIED = "denied"

    const val EXTRA_UID = "uid"
    const val EXTRA_PID = "pid"
    const val EXTRA_REQUEST_CODE = "requestCode"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_LABEL = "label"

    /** One app on the Shell access screen. */
    data class App(val packageName: String, val label: String, val decision: Int, val attached: Boolean)

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("shell_apps", Context.MODE_PRIVATE)

    /** Every remembered decision: package → Protocol.DECISION_ALLOWED or DECISION_DENIED. */
    fun decisions(c: Context): Map<String, Int> = prefs(c).all.mapNotNull { (k, v) ->
        if (k.startsWith("label:")) return@mapNotNull null
        when (v) {
            ALLOWED -> k to Protocol.DECISION_ALLOWED
            DENIED -> k to Protocol.DECISION_DENIED
            else -> null
        }
    }.toMap()

    fun decision(c: Context, pkg: String): Int = decisions(c)[pkg] ?: Protocol.DECISION_NONE

    /** The app's name, from what the server sent along, then what was kept, then the package. */
    fun label(c: Context, pkg: String, ai: ApplicationInfo? = null): String {
        if (ai != null) {
            try {
                val l = c.packageManager.getApplicationLabel(ai).toString()
                if (l.isNotBlank() && l != pkg) {
                    prefs(c).edit().putString("label:$pkg", l).apply()
                    return l
                }
            } catch (e: RuntimeException) {
                // Its resources aren't readable from here.
            }
        }
        prefs(c).getString("label:$pkg", null)?.let { return it }
        return try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /**
     * Stores the owner's decision (Protocol.DECISION_*; NONE forgets it, so
     * the app asks again) and tells the server, which stops a revoked app.
     */
    fun set(c: Context, pkg: String, state: Int) {
        val e = prefs(c).edit()
        when (state) {
            Protocol.DECISION_ALLOWED -> e.putString(pkg, ALLOWED)
            Protocol.DECISION_DENIED -> e.putString(pkg, DENIED)
            else -> e.remove(pkg)
        }
        e.commit()
        Shell.setDecision(pkg, state)
        Shell.changed()
    }

    /** The owner answered an app's request. */
    fun decide(c: Context, uid: Int, pid: Int, requestCode: Int, pkg: String, allowed: Boolean) {
        set(c, pkg, if (allowed) Protocol.DECISION_ALLOWED else Protocol.DECISION_DENIED)
        Shell.answer(uid, pid, requestCode, allowed)
        c.getSystemService(NotificationManager::class.java).cancel(notificationId(pkg))
        Log.i(TAG, "$pkg " + if (allowed) "allowed" else "denied")
    }

    /** The apps that declare Shizuku's permission (from the server) and those with a decision. Blocks. */
    fun list(c: Context): List<App> {
        val d = decisions(c)
        val fromServer = Shell.apps()
        val apps = LinkedHashMap<String, App>()
        fromServer?.first?.forEach { ai ->
            apps[ai.packageName] = App(ai.packageName, label(c, ai.packageName, ai), d[ai.packageName] ?: Protocol.DECISION_NONE,
                ai.packageName in fromServer.second)
        }
        for ((pkg, state) in d) if (pkg !in apps) apps[pkg] = App(pkg, label(c, pkg), state, false)
        return apps.values.sortedWith(compareBy({ it.decision == Protocol.DECISION_NONE }, { it.label.lowercase() }))
    }

    // --- The prompt ---

    fun createChannel(c: Context) {
        val channel = NotificationChannel(CHANNEL, "Shell access requests", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "An app asks to run commands as the adb shell user through Prism"
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationId(pkg: String): Int = ("shell-permission:$pkg").hashCode()

    /** The server asks for an app (Protocol.MANAGER_ASK). */
    fun ask(c: Context, uid: Int, pid: Int, requestCode: Int, pkg: String, ai: ApplicationInfo?) {
        when (decision(c, pkg)) {
            Protocol.DECISION_ALLOWED -> return decide(c, uid, pid, requestCode, pkg, true)
            Protocol.DECISION_DENIED -> return decide(c, uid, pid, requestCode, pkg, false)
        }
        val label = label(c, pkg, ai)
        val open = Intent(c, PermissionActivity::class.java)
            .putExtra(EXTRA_UID, uid).putExtra(EXTRA_PID, pid).putExtra(EXTRA_REQUEST_CODE, requestCode)
            .putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_LABEL, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = notificationId(pkg)
        val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val deny = PendingIntent.getBroadcast(
            c, id,
            Intent(c, DenyReceiver::class.java).putExtras(open),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_prism)
            .setColor(0xFF4553C4.toInt())
            .setContentTitle("$label asks for shell access")
            .setContentText("Commands as the adb shell user (uid 2000) through Prism · $pkg")
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .addAction(Notification.Action.Builder(null, "Deny", deny).build())
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** Deny from the notification. */
    class DenyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            val r = goAsync()
            Thread {
                try {
                    decide(context, intent.getIntExtra(EXTRA_UID, -1), intent.getIntExtra(EXTRA_PID, -1),
                        intent.getIntExtra(EXTRA_REQUEST_CODE, 0), pkg, false)
                } finally {
                    r.finish()
                }
            }.start()
        }
    }
}
