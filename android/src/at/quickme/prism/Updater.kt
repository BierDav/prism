// Prism updates itself from the Prism workers (docs/prism-api.md, "The
// Android app"), at prism_url (https://prism.quickme.at), without a token:
//
//  1. GET /v1/android/release?package=<this app's>: the newest version fleet
//     published (a debug build has a package, and a channel, of its own).
//  2. When its versionCode is higher than this app's, GET
//     /v1/android/release.apk?package=… into the app's private no-backup files, check its
//     size and SHA-256 against (1), and that it is this package, that version,
//     signed with the same certificate as this app.
//  3. Install it with PackageInstaller. As the Device Owner (Dpc.kt) that
//     needs no tap; otherwise the owner gets a notification to confirm.
//
// It checks every 6 hours (UpdateJob), after boot, when the relay pushes
// app.release (PrismPush), and on "Check for updates" on the main screen.
// Android refuses an APK signed with another key anyway; the checks here make
// a bad download fail early and say why.

package at.quickme.prism

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

object Updater {
    private const val TAG = "PrismUpdate"
    const val JOB_PERIODIC = 4202
    const val JOB_NOW = 4203
    private const val MAX_APK = 200L shl 20

    // --- Scheduling ---

    /** The 6-hour check; also once soon after a boot or an update. */
    @JvmStatic
    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(
                JobInfo.Builder(JOB_PERIODIC, ComponentName(c, UpdateJob::class.java))
                    .setPeriodic(TimeUnit.HOURS.toMillis(6), TimeUnit.HOURS.toMillis(1))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    /** Asked for again while a check was queued or running: one more after it. */
    @Volatile
    private var again = false

    /**
     * A check as soon as there is a network (a push, a tap, a boot). Never
     * over one already queued or running: scheduling the same job again stops
     * a running one, and the relay pushes several changes in a row after a
     * reconnect. Asked meanwhile, it checks once more after that one
     * ([finished]), which then sees what changed.
     */
    @JvmStatic
    fun checkSoon(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_NOW) != null) {
            again = true
            return
        }
        again = false
        js.schedule(
            JobInfo.Builder(JOB_NOW, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build(),
        )
    }

    /** A check (UpdateJob) ended: the one more it was asked for meanwhile, if any. */
    @JvmStatic
    fun finished(c: Context) {
        if (!again) return
        again = false
        c.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB_NOW, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build(),
        )
    }

    // --- State for the screens ---

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    private fun note(c: Context, status: String) {
        Log.i(TAG, status)
        prefs(c).edit().putString("status", status).putLong("at", System.currentTimeMillis()).apply()
        changed()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>()

    @JvmStatic
    @Synchronized
    fun listen(r: Runnable) {
        listeners.add(r)
    }

    @JvmStatic
    @Synchronized
    fun unlisten(r: Runnable) {
        listeners.remove(r)
    }

    private fun changed() {
        val copy = synchronized(this) { ArrayList(listeners) }
        for (r in copy) main.post(r)
    }

    /** One line: the last check's outcome and when. */
    @JvmStatic
    fun statusText(c: Context): String {
        val p = prefs(c)
        val at = p.getLong("at", 0)
        if (at == 0L) return "not checked yet"
        return p.getString("status", "") + " (" +
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) + ")"
    }

    // --- The check ---

    /** Checks, downloads and starts the install; false to try again later. */
    @JvmStatic
    @Synchronized
    fun check(c: Context): Boolean {
        val app = c.applicationContext
        try {
            val own = ownVersion(app)
            val release = getJson(app, "/v1/android/release?package=" + app.packageName)
            if (release == null) {
                note(app, "no version published on the Prism server")
                return true
            }
            val code = release.getLong("versionCode")
            val name = release.optString("versionName", code.toString())
            if (code <= own) {
                note(app, "up to date")
                return true
            }
            note(app, "downloading $name")
            val apk = download(app, release.getLong("size"), release.getString("sha256"))
            verify(app, apk, code)
            install(app, apk, name)
            note(app, "installing $name")
            return true
        } catch (e: IOException) {
            note(app, "check failed: " + e.message)
            return false
        } catch (e: JSONException) {
            note(app, "update refused: " + e.message)
            return true
        } catch (e: SecurityException) {
            note(app, "update refused: " + e.message)
            return true
        } catch (e: IllegalStateException) {
            note(app, "update refused: " + e.message)
            return true
        }
    }

    private fun ownVersion(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        throw IllegalStateException(e)
    }

    private fun open(c: Context, path: String): HttpURLConnection {
        val http = URL(Settings.prismUrl(c).trimEnd('/') + path).openConnection() as HttpURLConnection
        http.connectTimeout = 15_000
        http.readTimeout = 60_000
        return http
    }

    /** The release's metadata, or null when none is published. */
    private fun getJson(c: Context, path: String): JSONObject? {
        val http = open(c, path)
        http.setRequestProperty("Accept", "application/json")
        try {
            val status = http.responseCode
            if (status == 404) return null
            if (status != 200) throw IOException("the Prism server answered HTTP $status")
            return http.inputStream.use { input ->
                // InputStream.readNBytes(Int) requires API 33; Android TV can run API 31.
                val bytes = ByteArray(64 shl 10)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count == -1) break
                    size += count
                }
                if (size == bytes.size && input.read() != -1) {
                    throw IOException("the release metadata exceeds 64 KiB")
                }
                JSONObject(String(bytes, 0, size, Charsets.UTF_8))
            }
        } finally {
            http.disconnect()
        }
    }

    @JvmStatic
    fun apkFile(c: Context) = File(c.noBackupFilesDir, "update.apk")

    private fun download(c: Context, size: Long, sha256: String): File {
        return download(open(c, "/v1/android/release.apk?package=" + c.packageName), apkFile(c), size, sha256)
    }

    internal fun download(http: HttpURLConnection, out: File, size: Long, sha256: String): File {
        try {
            check(size in 1..MAX_APK) { "the published APK has an implausible size $size" }
            val status = http.responseCode
            if (status != 200) throw IOException("downloading the APK: HTTP $status")
            val digest = try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException(e)
            }
            var total = 0L
            http.inputStream.use { input ->
                FileOutputStream(out).use { file ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > size) throw IOException("the APK is larger than published")
                        digest.update(buf, 0, n)
                        file.write(buf, 0, n)
                    }
                }
            }
            if (total != size) throw IOException("the APK download ended early ($total of $size bytes)")
            if (hex(digest.digest()) != sha256) throw IOException("the APK's SHA-256 does not match")
            return out
        } catch (e: IOException) {
            out.delete()
            throw e
        } catch (e: RuntimeException) {
            out.delete()
            throw e
        } finally {
            http.disconnect()
        }
    }

    /** This package, the published version, and signed by the same key as this app. */
    private fun verify(c: Context, apk: File, code: Long) {
        val pm = c.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val archiveSigning = archive?.signingInfo
        if (archive == null || archiveSigning == null) {
            apk.delete()
            throw IllegalStateException("the download is not a readable APK")
        }
        val self = try {
            pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException(e)
        }
        val problem = when {
            c.packageName != archive.packageName -> "the APK is " + archive.packageName
            archive.longVersionCode != code -> "the APK is version ${archive.longVersionCode}, not $code"
            !sameSigners(archiveSigning.apkContentsSigners, self.signingInfo?.apkContentsSigners) ->
                "the APK is signed with another key"
            else -> null
        }
        if (problem != null) {
            apk.delete()
            throw SecurityException(problem)
        }
    }

    private fun sameSigners(a: Array<Signature>?, b: Array<Signature>?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        return a.map { it.toCharsString() }.sorted() == b.map { it.toCharsString() }.sorted()
    }

    internal fun install(c: Context, apk: File, name: String, targetPackage: String = c.packageName) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(targetPackage)
        params.setSize(apk.length())
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("prism.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val done = Intent(c, Done::class.java).putExtra("name", name).putExtra("package", targetPackage)
                // Mutable: the installer adds the outcome to it.
                val pi = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pi.intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw e
        } catch (e: RuntimeException) {
            installer.abandonSession(id)
            throw e
        }
        // Installing replaces this process; MY_PACKAGE_REPLACED (BootReceiver) carries on.
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** After an update: the downloaded APK is no longer needed. */
    @JvmStatic
    fun installed(c: Context) {
        apkFile(c).delete()
        val name = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
        note(c, "updated to $name")
    }

    /** The installer's outcome. Rarely seen on success: the update replaces this process first. */
    class Done : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val name = intent.getStringExtra("name")
            if (intent.getStringExtra("package") == T3Chat.PACKAGE) {
                T3Chat.installFinished(c, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                return
            }
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Not the Device Owner: the owner confirms the update.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PrismPush.channels(c)
                c.getSystemService(NotificationManager::class.java).notify(
                    "update", 1,
                    Notification.Builder(c, PrismPush.CHANNEL_QUIET)
                        .setSmallIcon(R.drawable.ic_prism)
                        .setContentTitle("Prism $name is ready")
                        .setContentText("Tap to install the update.")
                        .setContentIntent(
                            PendingIntent.getActivity(c, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                        )
                        .setAutoCancel(true)
                        .build(),
                )
                note(c, "$name waits for your confirmation")
                return
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                installed(c)
                return
            }
            apkFile(c).delete()
            note(c, "installing $name failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
