// Settings from Prism (docs/prism-api.md, "The Android app"): after
// enrollment over USB, fleet's publisher keeps the phone's settings on the
// workers (PUT /v1/android/settings), which store them in the replicated
// `settings` table (android.settings.<device>). The phone reads its own from
// its replica,
//
//   GET /v1/android/settings   (Replica.call, for this phone's device_name)
//
// into the provisioned settings (Settings.provision): a value sets a key,
// null removes it, keys left out stay. It never sets how the phone reaches
// Prism (prism_url, rocky_provisioning, rocky_peers) or NetBird's setup key
// and management URL; only enrollment does.
//
// It runs with the update check (UpdateJob: every 6 hours, after a boot), and
// soon after the relay pushes app.settings for this phone or the replica's
// settings change (PrismPush). The push carries no values.

package at.quickme.prism

import android.content.Context
import android.util.Log
import at.quickme.prism.shared.PrismError
import kotlinx.coroutines.runBlocking

object ServerSettings {
    private const val TAG = "PrismSettings"
    private val ENROLLMENT_ONLY = setOf(
        "prism_url", "rocky_provisioning", "rocky_peers", "netbird_setup_key", "netbird_management_url",
    )

    /** Applies this phone's settings from its replica; false to try again later. */
    @JvmStatic
    @Synchronized
    fun sync(c: Context): Boolean {
        val app = c.applicationContext
        if (!PrismApi.enrolled(app) || Sample.on(app)) return true // no replica yet
        return try {
            val published = runBlocking { PrismApi.call(app) { androidSettings() } } ?: return true // nothing for this phone
            val values = HashMap<String, String?>()
            for ((key, value) in published.settings) {
                if (key !in Settings.KEYS || key in ENROLLMENT_ONLY) {
                    Log.w(TAG, "ignoring the published $key") // a name, never a value
                    continue
                }
                values[key] = value
            }
            val changed = Settings.provision(app, values)
            app.getSharedPreferences("server-settings", Context.MODE_PRIVATE).edit()
                .putLong("at", System.currentTimeMillis()).putLong("updatedAt", published.updatedAt).apply()
            if (changed.isNotEmpty()) {
                Log.i(TAG, "new settings from Prism: " + changed.joinToString(", "))
                Settings.applied(app)
            }
            true
        } catch (e: PrismError) {
            Log.w(TAG, "reading the settings: " + e.message)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "the published settings were refused: " + e.message)
            true
        }
    }
}
