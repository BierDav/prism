package at.quickme.prism

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Delivery of the separately signed T3 Code fork for this phone. */
object T3Chat {
    const val PACKAGE = "at.quickme.t3chat"
    private const val TAG = "PrismT3Chat"
    private const val REPO = "BierDav/t3code"
    private const val SIGNER = "b983f9b05914943381fbd6bfbc6f338960a8e76d03eaae722b1e874988cd27ce"
    private const val PAIRING_URLS = "prism_pairing_urls"
    private val releaseTag = Regex("prism-android-([1-9][0-9]{0,8})")
    private val sha256 = Regex("[a-f0-9]{64}")
    private val pairingHost = Regex("[a-z0-9-]+\\.netbird\\.cloud")
    private var installStarted: Long? = null

    private fun packageInfo(c: Context): PackageInfo? = try {
        c.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun trusted(info: PackageInfo?): Boolean {
        val signer = info?.signingInfo?.apkContentsSigners?.singleOrNull() ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } == SIGNER
    }

    fun trustedInstall(c: Context): Boolean = trusted(packageInfo(c))

    fun configure(c: Context, urls: List<String>) {
        check(Dpc.owner(c)) { "Prism is not Device Owner" }
        check(trustedInstall(c)) { "the signed Prism T3 Code app must be installed first" }
        require(urls.size <= 32 && urls.all(::validPairingUrl)) { "expected fleet T3 pairing URLs" }
        val restrictions = Bundle().apply { putStringArray(PAIRING_URLS, urls.distinct().toTypedArray()) }
        c.getSystemService(DevicePolicyManager::class.java).setApplicationRestrictions(Dpc.admin(c), PACKAGE, restrictions)
    }

    private fun validPairingUrl(url: String): Boolean {
        if (url.length > 2048) return false
        val target = try { URI.create(url) } catch (_: IllegalArgumentException) { return false }
        return target.scheme == "https" && target.host?.matches(pairingHost) == true &&
            target.port == T3Activity.PORT && target.userInfo == null && target.rawQuery == null &&
            (target.path.isNullOrEmpty() || target.path == "/") &&
            target.rawFragment?.let { it.startsWith("token=") && it.length > 6 } == true
    }

    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Prism-Android")
    }

    private fun file(c: Context) = File(c.noBackupFilesDir, "t3chat-update.apk")

    @Synchronized
    fun check(c: Context): Boolean {
        if (!Dpc.owner(c)) return true
        val installed = packageInfo(c) ?: return true
        if (!trusted(installed)) return true
        if (installStarted?.let { SystemClock.elapsedRealtime() - it < 15 * 60_000 } == true) return true
        try {
            val index = connection("https://api.github.com/repos/$REPO/releases/latest")
            val release = try {
                index.setRequestProperty("Accept", "application/vnd.github+json")
                when (val status = index.responseCode) {
                    404 -> return true
                    200 -> index.inputStream.use { JSONObject(String(it.readNBytes(128 shl 10), Charsets.UTF_8)) }
                    else -> throw IOException("GitHub answered HTTP $status")
                }
            } finally {
                index.disconnect()
            }
            val tag = release.getString("tag_name")
            val version = releaseTag.matchEntire(tag)?.groupValues?.get(1)?.toLong() ?: return true
            if (version <= installed.longVersionCode) return true
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map(assets::getJSONObject)
                .singleOrNull { it.optString("name") == "t3chat.apk" } ?: return true
            val digest = asset.getString("digest").removePrefix("sha256:")
            check(sha256.matches(digest)) { "the release has no valid APK digest" }
            val apk = Updater.download(
                connection("https://github.com/$REPO/releases/download/$tag/t3chat.apk"),
                file(c), asset.getLong("size"), digest,
            )
            val candidate = c.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            if (candidate?.packageName != PACKAGE || candidate.longVersionCode != version || !trusted(candidate)) {
                apk.delete()
                throw SecurityException("the APK package, version or signer is incorrect")
            }
            Updater.install(c, apk, "T3 Code $version", PACKAGE)
            installStarted = SystemClock.elapsedRealtime()
            return true
        } catch (e: IOException) {
            Log.w(TAG, "T3 Code update check failed", e)
            return false
        } catch (e: JSONException) {
            Log.w(TAG, "could not read T3 Code release", e)
            return true
        } catch (e: RuntimeException) {
            Log.w(TAG, "T3 Code update refused", e)
            return true
        }
    }

    @Synchronized
    fun installFinished(c: Context, status: Int, message: String?) {
        installStarted = null
        file(c).delete()
        if (status == PackageInstaller.STATUS_SUCCESS) Log.i(TAG, "updated T3 Code")
        else Log.w(TAG, "T3 Code update did not install ($status): $message")
    }
}
