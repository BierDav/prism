// Enrollment over USB: fleet's prism-phone-enroll writes the settings
// (Settings.kt) here through adb, from stdin, so no value is ever on a
// command line, in logcat or in a file on the phone:
//
//   adb shell content write --uri content://at.quickme.prism.provision/settings < settings.json
//   adb shell content read  --uri content://at.quickme.prism.provision/status
//   adb shell content write --uri content://at.quickme.prism.provision/t3 < pairing.json
//
// settings.json is {"settings": {"relay_token": "…", "hosts": "a,b", …}}: a
// string sets a key, null removes it (managed configuration or the default
// applies again), keys left out stay as they are. Unknown keys refuse the
// whole write.
//
// The status is JSON: the app's version, whether Prism is the Device Owner,
// each setting's source with the first 16 hex digits of its value's SHA-256
// (never a value), the phone's device public key, NetBird and the updater.
//
// Only adb's shell user (and root) may open either: `content` runs as shell,
// and every other app gets a SecurityException: whoever has adb has the
// phone anyway.

package at.quickme.prism

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import at.quickme.prism.netbird.Netbird
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

class ProvisionProvider : ContentProvider() {
    companion object {
        private const val TAG = "PrismProvision"
        // A Rocky provisioning (rocky_provisioning) alone is about 64 KB.
        private const val MAX_BODY = 512 shl 10

        private fun receive(c: Context, fd: ParcelFileDescriptor, t3: Boolean = false) {
            try {
                val body = ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > MAX_BODY) throw IOException("more than $MAX_BODY bytes")
                    }
                    buf.toString("UTF-8")
                }
                val request = JSONObject(body)
                if (t3) {
                    val urls = request.getJSONArray("pairingUrls")
                    T3Chat.configure(c, (0 until urls.length()).map(urls::getString))
                    remember(c, null, t3 = true)
                    return
                }
                val settings = request.getJSONObject("settings")
                val values = HashMap<String, String?>()
                for (key in settings.keys()) values[key] = if (settings.isNull(key)) null else settings.getString(key)
                val changed = Settings.provision(c, values)
                // Names only, never values.
                Log.i(TAG, "provisioned ${values.size} settings, changed: " + changed.joinToString(", "))
                remember(c, null)
                if (changed.isNotEmpty()) Settings.applied(c)
            } catch (e: Exception) {
                if (e !is IOException && e !is JSONException && e !is RuntimeException) throw e
                // The message names a key at most (Settings.provision), never a value.
                val error = if (t3) "T3 Code provisioning failed; check the signed app and fleet pairing URLs"
                else if (e is JSONException) "not valid JSON of the form {\"settings\": {…}}" else e.message
                Log.w(TAG, "provisioning refused: $error")
                remember(c, error, t3)
            }
        }

        /** The last write's outcome, for the status (`content write` can't report one). */
        private fun remember(c: Context, error: String?, t3: Boolean = false) {
            val prefix = if (t3) "t3-" else ""
            val at = if (t3) System.currentTimeMillis() else System.currentTimeMillis() / 1000
            c.getSharedPreferences("provision", Context.MODE_PRIVATE).edit()
                .putLong(prefix + "at", at)
                .putString(prefix + "error", error ?: "")
                .commit()
        }

        @JvmStatic
        fun status(c: Context): String {
            val s = JSONObject()
            try {
                val pi = c.packageManager.getPackageInfo(c.packageName, 0)
                s.put("versionCode", pi.longVersionCode)
                s.put("versionName", pi.versionName)
                s.put("deviceOwner", Dpc.owner(c))
                val settings = JSONObject()
                for (key in Settings.KEYS) {
                    val source = Settings.source(c, key)
                    if (source.isEmpty()) continue
                    settings.put(key, JSONObject().put("source", source).put("sha256", fingerprint(Settings.value(c, key))))
                }
                s.put("settings", settings)
                val last = c.getSharedPreferences("provision", Context.MODE_PRIVATE)
                s.put("lastWrite", JSONObject().put("at", last.getLong("at", 0)).put("error", last.getString("error", "")))
                s.put("lastT3Write", JSONObject().put("at", last.getLong("t3-at", 0)).put("error", last.getString("t3-error", "")))
                try {
                    s.put("publicKey", DeviceKey.loadOrCreate(c).publicKeyLine())
                    s.put("tv", DeviceKey.tv(c))
                } catch (e: Exception) {
                    s.put("publicKeyError", e.message.toString())
                }
                s.put(
                    "netbird",
                    JSONObject().put("enrolled", Netbird.enrolled(c)).put("status", Netbird.statusText())
                        .put("ip", Netbird.ip()).put("vpnConsent", Netbird.needsConsent(c) == null),
                )
                s.put("update", Updater.statusText(c))
                s.put("replica", PrismApi.status(c))
                val server = c.getSharedPreferences("server-settings", Context.MODE_PRIVATE)
                s.put(
                    "serverSettings",
                    JSONObject().put("checkedAt", server.getLong("at", 0) / 1000).put("updatedAt", server.getLong("updatedAt", 0)),
                )
            } catch (e: JSONException) {
                return "{\"error\":" + JSONObject.quote(e.message.toString()) + "}\n"
            } catch (e: PackageManager.NameNotFoundException) {
                return "{\"error\":" + JSONObject.quote(e.message.toString()) + "}\n"
            }
            return s.toString() + "\n"
        }

        private fun fingerprint(value: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return (0 until 8).joinToString("") { "%02x".format(d[it]) }
        }

        private fun send(fd: ParcelFileDescriptor, text: String) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                Log.w(TAG, "status: " + e.message)
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) throw SecurityException("only adb provisions Prism")
        val c = context!!.applicationContext
        val path = uri.path ?: ""
        try {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            if ((path == "/settings" || path == "/t3") && mode.contains("w")) {
                Thread({ receive(c, pipe[0], t3 = path == "/t3") }, "provision").start()
                return pipe[1]
            }
            if (path == "/status" && mode == "r") {
                Thread({ send(pipe[1], status(c)) }, "provision-status").start()
                return pipe[0]
            }
            pipe[0].close()
            pipe[1].close()
        } catch (e: IOException) {
            throw FileNotFoundException(e.message)
        }
        throw FileNotFoundException("no $path for mode $mode")
    }

    // Not a table.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
}
