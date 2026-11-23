// Prism as the phone's Device Owner, set once over USB with
//
//   adb shell dpm set-device-owner at.quickme.prism/.DeviceAdmin
//
// (fleet's prism-phone-enroll does it). Prism uses the role only for itself:
// it grants its own runtime permissions, installs its own updates without
// asking (Updater.kt), and makes its NetBird VPN the always-on VPN once,
// without lockdown (no consent dialog; Android restarts it; other traffic
// still flows while it is down; the owner can change it in Settings > VPN).
// It declares no legacy admin policies
// (res/xml/device_admin.xml). Android also exempts a Device Owner from
// background limits (starting its services, App Standby).
//
// A Device Owner can't be removed in Settings. "Release device owner" on the
// main screen gives the role up (clearDeviceOwnerApp), after which Prism is an
// ordinary app that can be uninstalled.

package at.quickme.prism

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.provider.Settings
import android.util.Log

object Dpc {
    private const val TAG = "PrismDpc"

    fun admin(c: Context) = ComponentName(c, DeviceAdmin::class.java)

    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)

    /** Whether Prism is this phone's Device Owner. */
    @JvmStatic
    fun owner(c: Context): Boolean = dpm(c).isDeviceOwnerApp(c.packageName)

    /**
     * Grants Prism's own runtime permissions. Safe
     * to call at any time; does nothing unless Prism is the Device Owner. A new
     * version that asks for a new permission gets it at its first start.
     */
    @JvmStatic
    fun setUp(c: Context) {
        if (!owner(c)) return
        alwaysOnVpn(c)
        migrateAssistant(c)
        val pm = c.packageManager
        val pkg = c.packageName
        try {
            val requested = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions ?: return
            for (permission in requested) {
                val p = try {
                    pm.getPermissionInfo(permission, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    continue // a permission this Android doesn't have
                }
                if (p.protection != PermissionInfo.PROTECTION_DANGEROUS) continue
                if (c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) continue
                val ok = dpm(c).setPermissionGrantState(admin(c), pkg, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                Log.i(TAG, (if (ok) "granted " else "could not grant ") + permission)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "granting permissions", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "granting permissions", e)
        }
    }

    /** Upgrade the assistant component without changing which app the user selected. */
    private fun migrateAssistant(c: Context) {
        if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val resolver = c.contentResolver
            val old = ComponentName(c, AssistActivity::class.java)
            val assistantKey = "assistant"
            val selected = Settings.Secure.getString(resolver, assistantKey) ?: return
            if (ComponentName.unflattenFromString(selected) != old) return
            val role = c.getSystemService(RoleManager::class.java)
            if (role?.isRoleHeld(RoleManager.ROLE_ASSISTANT) != true) return

            val service = ComponentName(c, PrismAssistantService::class.java)
            val interactorKey = "voice_interaction_service"
            val interactor = Settings.Secure.getString(resolver, interactorKey)
            if (!interactor.isNullOrEmpty() && ComponentName.unflattenFromString(interactor) != service) return
            if (interactor.isNullOrEmpty() &&
                !Settings.Secure.putString(resolver, interactorKey, service.flattenToShortString())) return
            if (Settings.Secure.getString(resolver, assistantKey) == selected) {
                Settings.Secure.putString(resolver, assistantKey, service.flattenToShortString())
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "migrate assistant selection", e)
        }
    }

    /**
     * Makes Prism's NetBird VPN the always-on VPN, without lockdown, the first
     * time Prism is the Device Owner; never again, so a change the owner makes
     * in Settings stays.
     */
    private fun alwaysOnVpn(c: Context) {
        val prefs = c.getSharedPreferences("dpc", Context.MODE_PRIVATE)
        if (prefs.getBoolean("always-on-vpn", false)) return
        try {
            dpm(c).setAlwaysOnVpnPackage(admin(c), c.packageName, false)
            prefs.edit().putBoolean("always-on-vpn", true).apply()
            Log.i(TAG, "NetBird is the always-on VPN")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "always-on VPN", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "always-on VPN", e)
        }
    }

    /** Gives up the Device Owner role for good (only a new enrollment brings it back). */
    @Suppress("DEPRECATION")
    @JvmStatic
    fun release(c: Context) {
        dpm(c).clearDeviceOwnerApp(c.packageName)
    }
}
