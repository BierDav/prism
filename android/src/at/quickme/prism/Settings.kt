// The app's settings, so the phone needs no setup by hand: the relay that
// carries Prism's push and the token for it, where T3 Code's web app is, and
// so on (the keys in KEYS).
//
// They come from two places, the first that has a key wins:
//
//  1. Provisioned: written over USB adb (ProvisionProvider, fleet's
//     prism-phone-enroll, also with --settings-only to rotate one) into the
//     app's private preferences ("provisioned"), which never leave the phone
//     (no backup, no device transfer: res/xml/data_extraction_rules.xml).
//     The last write wins.
//  2. Managed configuration (app restrictions, res/xml/app_restrictions.xml)
//     from an MDM, if one sets it (the phone has none since Prism is its Device Owner).

package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import at.quickme.prism.netbird.Netbird

object Settings {
    /** Every setting, as in res/xml/app_restrictions.xml. */
    @JvmField
    val KEYS: List<String> = listOf(
        "relay_url", "relay_token", "t3_url", "prism_url", "rocky_provisioning", "rocky_peers", "device_name",
        "telemetry_url", "telemetry_user", "telemetry_password", "netbird_setup_key", "netbird_management_url",
        "background_restricted", "background_charging_only", "activity_recognition_denied", "hotspot_key",
        "assistant_url", "assistant_api_key", "assistant_model", "matrix_homeserver", "matrix_access_token",
        "call_mac",
    )

    /** Sent inside the app after provisioned settings changed. */
    const val ACTION_CHANGED = "at.quickme.prism.SETTINGS_CHANGED"

    /**
     * What a receiver registered at run time (RECEIVER_NOT_EXPORTED) listens
     * to for new settings: provisioned ones, or managed configuration.
     */
    @JvmStatic
    fun changedFilter(): IntentFilter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED).apply {
        addAction(ACTION_CHANGED)
    }

    @JvmStatic
    fun announce(c: Context) {
        c.sendBroadcast(Intent(ACTION_CHANGED).setPackage(c.packageName))
    }

    /**
     * New provisioned settings take effect now: push, NetBird and
     * the jobs pick them up (after a write over adb or from the server).
     */
    @JvmStatic
    fun applied(c: Context) {
        announce(c)
        try {
            PushService.start(c)
            Metrics.schedule(c)
            Updater.schedule(c)
            Netbird.start(c)
        } catch (e: RuntimeException) {
            // Not the Device Owner, so no service starts from the background:
            // they happen when the app opens.
            Log.w("PrismSettings", "starting the services: " + e.message)
        }
    }

    private fun provisioned(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("provisioned", Context.MODE_PRIVATE)

    private fun restrictions(c: Context): Bundle =
        c.getSystemService(RestrictionsManager::class.java).applicationRestrictions ?: Bundle()

    private fun get(c: Context, key: String): String =
        (provisioned(c).getString(key, null) ?: restrictions(c).getString(key))?.trim() ?: ""

    /** A key's value, "" when unset (the defaults below don't apply). */
    @JvmStatic
    fun value(c: Context, key: String): String = get(c, key)

    /** Where a key's value comes from: "provisioned", "managed" or "" (unset). */
    @JvmStatic
    fun source(c: Context, key: String): String {
        if (provisioned(c).contains(key)) return "provisioned"
        val managed = restrictions(c).getString(key)
        return if (managed != null && managed.isNotBlank()) "managed" else ""
    }

    /**
     * Stores provisioned values: a value replaces the key's, null removes it
     * (managed configuration or the default applies again). Unknown keys are
     * refused before anything is written. Returns the keys that changed.
     */
    @JvmStatic
    @Synchronized
    fun provision(c: Context, values: Map<String, String?>): List<String> {
        for (key in values.keys) require(key in KEYS) { "unknown setting $key" }
        val prefs = provisioned(c)
        val edit = prefs.edit()
        val changed = ArrayList<String>()
        for ((key, value) in values) {
            val old = prefs.getString(key, null)
            val now = value?.trim()
            if (now == old) continue
            if (now == null) edit.remove(key) else edit.putString(key, now)
            changed.add(key)
        }
        // Synchronously: enrollment reads the status right after.
        check(edit.commit()) { "could not store the settings" }
        return changed
    }

    /**
     * Whether a relay URL may be used: https, or plain http to this phone's
     * loopback, a test relay behind `adb reverse` for a debug build (only a
     * debug build allows cleartext traffic at all: android/default.nix).
     */
    @JvmStatic
    fun relayAllowed(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")

    /** The relay (ntfy) base URL, e.g. https://ntfy.quickme.at. */
    @JvmStatic
    fun relayUrl(c: Context): String = get(c, "relay_url")

    /** This phone's access token on the relay. */
    @JvmStatic
    fun relayToken(c: Context): String = get(c, "relay_token")

    /** T3 Code's web app, for "Open in T3": <url>/<environment>/<thread>. */
    @JvmStatic
    fun t3Url(c: Context): String = get(c, "t3_url")

    /** The workers' public address, where the app updates from (GET /v1/android/release). */
    @JvmStatic
    fun prismUrl(c: Context): String = get(c, "prism_url").ifEmpty { "https://prism.quickme.at" }

    /** This phone's Rocky provisioning (`prism-device-v1:…`), which enrolls its replica (PrismApi). */
    @JvmStatic
    fun rockyProvisioning(c: Context): String = get(c, "rocky_provisioning")

    /** The workers' Rocky contacts (`prism-worker peer`), one per line. */
    @JvmStatic
    fun rockyPeers(c: Context): String = get(c, "rocky_peers")

    /** This phone's name: the Prism device and the metrics' instance label. */
    @JvmStatic
    fun deviceName(c: Context): String = get(c, "device_name").ifEmpty { "david-pixel" }

    /**
     * Prometheus remote write for the phone metrics (android/METRICS.md): the
     * workers, which pass it on to the cluster's telemetry. A telemetry_url
     * set explicitly still wins, to point a test build elsewhere.
     */
    @JvmStatic
    fun telemetryUrl(c: Context): String =
        get(c, "telemetry_url").ifEmpty { prismUrl(c).trimEnd('/') + "/v1/telemetry/write" }

    @JvmStatic
    fun telemetryUser(c: Context): String = get(c, "telemetry_user").ifEmpty { "prism_phone" }

    @JvmStatic
    fun telemetryPassword(c: Context): String = get(c, "telemetry_password")

    /** A NetBird setup key, used once to enroll this phone (netbird/Netbird.kt). */
    @JvmStatic
    fun netbirdSetupKey(c: Context): String = get(c, "netbird_setup_key")

    /** NetBird's management service; NetBird's hosted one unless configured otherwise. */
    @JvmStatic
    fun netbirdManagementUrl(c: Context): String = get(c, "netbird_management_url").ifEmpty { "https://api.netbird.io:443" }

    /**
     * The assistant's model gateway (assistant/Harness.kt): an OpenAI-compatible
     * chat completions API, by default the owner's (CLIProxyAPI).
     */
    @JvmStatic
    fun assistantUrl(c: Context): String = get(c, "assistant_url").ifEmpty { "https://ai.quickme.at" }

    /** The gateway's API key; unset, the overlay starts T3 agents directly, without the assistant. */
    @JvmStatic
    fun assistantApiKey(c: Context): String = get(c, "assistant_api_key")

    /** The assistant's model on the gateway. */
    @JvmStatic
    fun assistantModel(c: Context): String = get(c, "assistant_model").ifEmpty { "agy-pro/gemini-4-neon" }

    /** The owner's Matrix homeserver (client-server API base URL), for the assistant's Matrix tools. */
    @JvmStatic
    fun matrixHomeserver(c: Context): String = get(c, "matrix_homeserver")

    /** The owner's Matrix access token; the assistant reads and sends Matrix messages as the owner. */
    @JvmStatic
    fun matrixAccessToken(c: Context): String = get(c, "matrix_access_token")
}
