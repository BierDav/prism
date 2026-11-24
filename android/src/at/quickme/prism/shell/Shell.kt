// Commands as the adb shell user (uid 2000) for the rest of Prism, through
// Prism's embedded shell server (Server.kt, derived from Shizuku).
//
// The server can't start itself: after every reboot (and after each Prism
// update) the cluster starts it over adb when Prism reports it down on the
// relay (Signal.kt; the contract is android/shizuku/README.md). Once it runs,
// it hands its binder to Prism (ShellProvider). The binder is Shizuku's
// (Protocol.kt): Prism uses it as Shizuku's manager would, and other apps
// get it through their ShizukuProvider once the owner allowed them
// (ShellApps.kt).
//
//   if (Shell.available()) {
//       val r = Shell.run(30_000, "dumpsys", "batterystats", "--charged")
//       if (r.ok()) parse(r.stdout)
//   }
//
// run() blocks: call it off the main thread.

package at.quickme.prism.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import moe.shizuku.api.BinderContainer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

object Shell {
    private const val TAG = "PrismShell"

    /** Default timeout of run(vararg argv). */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /** Longest timeout run() takes. */
    const val MAX_TIMEOUT_MS = 30 * 60_000L

    /** Each of stdout and stderr is cut after this many bytes (Result.truncated). */
    const val MAX_OUTPUT_BYTES = 32 shl 20

    /** What a command did. */
    class Result internal constructor(
        /** The exit status; -1 when it timed out and was killed. */
        @JvmField val exitCode: Int,
        @JvmField val stdout: String,
        @JvmField val stderr: String,
        /** It ran past its timeout and was killed. */
        @JvmField val timedOut: Boolean,
        /** stdout or stderr was longer than MAX_OUTPUT_BYTES and is cut. */
        @JvmField val truncated: Boolean,
        /** stdout as it came, for commands that write binary (`screencap`). */
        @JvmField val stdoutBytes: ByteArray = ByteArray(0),
    ) {
        /** Exit status 0 within the timeout. */
        fun ok(): Boolean = exitCode == 0 && !timedOut

        override fun toString(): String =
            "exit $exitCode" + (if (timedOut) " (timed out)" else "") + (if (truncated) " (output cut)" else "")
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>()

    private var app: Context? = null
    @Volatile private var server: IBinder? = null
    @Volatile private var serverPid = 0
    @Volatile private var serverStarted = 0L
    @Volatile private var serverContext = ""

    /** When the server attached or went away (System.currentTimeMillis), 0 if never this process. */
    @Volatile private var changedAt = 0L
    @Volatile private var downReason = ""

    /**
     * Handed to the server as Prism's token: it kills Prism's commands when
     * this process dies, hands its binder again, and asks here when an app
     * wants permission (Protocol.MANAGER_ASK). Only the shell user may call it.
     */
    private val TOKEN = object : Binder() {
        init {
            attachInterface(null, Protocol.MANAGER_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != Protocol.MANAGER_ASK) return super.onTransact(code, data, reply, flags)
            if (getCallingUid() != Process.SHELL_UID) return false
            data.enforceInterface(Protocol.MANAGER_DESCRIPTOR)
            val uid = data.readInt()
            val pid = data.readInt()
            val requestCode = data.readInt()
            val pkg = data.readString() ?: return true
            val ai = if (data.readInt() != 0) ApplicationInfo.CREATOR.createFromParcel(data) else null
            val c = app ?: return true
            main.post { ShellApps.ask(c, uid, pid, requestCode, pkg, ai) }
            return true
        }
    }

    // --- For the screens ---

    /** Whether the shell server runs and Prism can use it now. */
    @JvmStatic
    fun available(): Boolean = server?.isBinderAlive == true

    /** One line for the screens: running, or why not and what happens next. */
    @JvmStatic
    fun statusText(): String {
        if (available()) return "running as uid 2000 (pid $serverPid, $serverContext) since " + time(serverStarted)
        val what = if (Signal.hasWifi()) {
            "the cluster starts it over adb"
        } else {
            "the cluster starts it once the phone is on Wi-Fi"
        }
        if (changedAt == 0L) return "looking for the shell server…"
        return "not running" + (if (downReason.isEmpty()) "" else " ($downReason)") + " since " + time(changedAt) +
            ": " + what + ". Wireless debugging must stay on."
    }

    /** `r` runs on the main thread whenever the state changes. */
    @JvmStatic
    fun listen(r: Runnable) {
        synchronized(listeners) { listeners.add(r) }
    }

    @JvmStatic
    fun unlisten(r: Runnable) {
        synchronized(listeners) { listeners.remove(r) }
    }

    /** The screen that shows the state and what the owner can do (ShellActivity). */
    @JvmStatic
    fun openSetup(c: Context) {
        c.startActivity(Intent(c, ShellActivity::class.java).addFlags(if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Prism came to the front: while the server is down, the cluster is asked again (Signal, at most every 2 min). */
    @JvmStatic
    fun front() = Signal.front()

    /** The owner asks: while the server is down, tell the cluster now (reason `request`). */
    @JvmStatic
    fun askCluster() = Signal.request()

    /**
     * "Pair with the cluster" (Pairing.kt): opens Wireless debugging and asks
     * for the pairing code in a notification. From an Activity.
     */
    @JvmStatic
    fun pairCluster(activity: Activity) = Pairing.start(activity)

    // --- Running commands ---

    /** run(DEFAULT_TIMEOUT_MS, argv). */
    @JvmStatic
    fun run(vararg argv: String): Result = run(DEFAULT_TIMEOUT_MS, *argv)

    /**
     * Runs argv (no shell; use "sh", "-c", "..." for one) as uid 2000 in "/",
     * stdin empty, and waits for it at most timeoutMs. On timeout it is killed
     * and the result has timedOut. Throws IOException when the server isn't
     * running, dies meanwhile, or can't start the command.
     */
    @JvmStatic
    fun run(timeoutMs: Long, vararg argv: String): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Shell.run blocks; call it off the main thread" }
        require(argv.isNotEmpty()) { "no command" }
        require(timeoutMs > 0 && timeoutMs <= MAX_TIMEOUT_MS) { "timeout $timeoutMs" }
        val s = server
        if (s == null || !s.isBinderAlive) throw IOException("the shell server is not running: " + statusText())

        val process: IBinder
        val out: Collector
        val err: Collector
        try {
            process = transact(s, Protocol.DESCRIPTOR, Protocol.NEW_PROCESS, {
                writeStringArray(argv)
                writeStringArray(null)
                writeString("/")
            }) { readStrongBinder() } ?: throw IOException("the shell server sent no process")
            // Empty stdin: close it at once.
            processPfd(process, Protocol.PROCESS_GET_OUTPUT_STREAM)?.close()
            out = Collector(processPfd(process, Protocol.PROCESS_GET_INPUT_STREAM) ?: throw IOException("no stdout"))
            err = Collector(processPfd(process, Protocol.PROCESS_GET_ERROR_STREAM) ?: throw IOException("no stderr"))
        } catch (e: RemoteException) {
            throw IOException("the shell server died", e)
        } catch (e: RuntimeException) {
            throw IOException(e.message, e)
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var exited = false
        var code = -1
        try {
            while (!exited) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                exited = transact(process, Protocol.PROCESS_DESCRIPTOR, Protocol.PROCESS_WAIT_FOR_TIMEOUT, {
                    writeLong(minOf(left, 5_000))
                    writeString(TimeUnit.MILLISECONDS.name)
                }) { readInt() != 0 }
            }
            if (exited) {
                code = transact(process, Protocol.PROCESS_DESCRIPTOR, Protocol.PROCESS_EXIT_VALUE, {}) { readInt() }
            } else {
                transact(process, Protocol.PROCESS_DESCRIPTOR, Protocol.PROCESS_DESTROY, {}) {}
            }
        } catch (e: RemoteException) {
            out.close()
            err.close()
            throw IOException("the shell server died while running " + argv[0], e)
        } catch (e: RuntimeException) {
            out.close()
            err.close()
            throw IOException(e.message, e)
        }
        // The output ends when the process exits, unless something it started
        // keeps the pipe open: then take what came.
        out.finish(if (exited) 2_000 else 500)
        err.finish(if (exited) 2_000 else 500)
        val stdout = out.bytes()
        return Result(if (exited) code else -1, String(stdout, Charsets.UTF_8), err.text(), !exited, out.truncated || err.truncated, stdout)
    }

    /** A system property as uid 2000 reads it, or null without the server. */
    @JvmStatic
    fun property(name: String): String? {
        val s = server ?: return null
        return try {
            transact(s, Protocol.DESCRIPTOR, Protocol.GET_SYSTEM_PROPERTY, {
                writeString(name)
                writeString("")
            }) { readString() }
        } catch (e: Exception) {
            null
        }
    }

    private fun processPfd(process: IBinder, code: Int): ParcelFileDescriptor? =
        transact(process, Protocol.PROCESS_DESCRIPTOR, code, {}) {
            if (readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(this) else null
        }

    private fun <T> transact(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, reply, 0)
            reply.readException()
            return reply.read()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Reads one pipe on its own thread, keeping at most MAX_OUTPUT_BYTES. */
    private class Collector(private val pfd: ParcelFileDescriptor) {
        private val bytes = ByteArrayOutputStream()
        private val thread = Thread(::read, "prism-shell-output").apply {
            isDaemon = true
            start()
        }

        @Volatile var truncated = false

        private fun read() {
            val buf = ByteArray(65536)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        synchronized(bytes) {
                            val room = MAX_OUTPUT_BYTES - bytes.size()
                            if (n > room) truncated = true
                            if (room > 0) bytes.write(buf, 0, minOf(n, room))
                        }
                    }
                }
            } catch (e: IOException) {
                // Closed by finish() or close().
            }
        }

        fun finish(ms: Long) {
            try {
                thread.join(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) close()
        }

        fun close() {
            try {
                pfd.close()
            } catch (e: IOException) {
                // Already closed.
            }
        }

        fun bytes(): ByteArray = synchronized(bytes) { bytes.toByteArray() }

        fun text(): String = String(bytes(), Charsets.UTF_8)
    }

    // --- Prism as the manager (ShellApps.kt) ---

    /** Tells the server the owner's decision for an app (Protocol.DECISION_*); false without the server. */
    internal fun setDecision(pkg: String, state: Int): Boolean {
        val s = server ?: return false
        return try {
            transact(s, Protocol.DESCRIPTOR, Protocol.PRISM_SET_DECISION, {
                writeString(pkg)
                writeInt(state)
            }) {}
            true
        } catch (e: Exception) {
            Log.w(TAG, "setDecision $pkg", e)
            false
        }
    }

    /** Answers an app's permission request (dispatchPermissionConfirmationResult). */
    internal fun answer(uid: Int, pid: Int, requestCode: Int, allowed: Boolean) {
        val s = server ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(Protocol.DESCRIPTOR)
            data.writeInt(uid)
            data.writeInt(pid)
            data.writeInt(requestCode)
            data.writeInt(1)
            Bundle().apply {
                putBoolean(Protocol.REPLY_ALLOWED, allowed)
                putBoolean(Protocol.REPLY_IS_ONETIME, false)
            }.writeToParcel(data, 0)
            s.transact(Protocol.DISPATCH_PERMISSION_RESULT, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: RemoteException) {
            Log.w(TAG, "dispatchPermissionConfirmationResult", e)
        } finally {
            data.recycle()
        }
    }

    /** The apps that declare Shizuku's permission, and the packages attached now; null without the server. Blocks. */
    internal fun apps(): Pair<List<ApplicationInfo>, Set<String>>? {
        val s = server ?: return null
        return try {
            transact(s, Protocol.DESCRIPTOR, Protocol.PRISM_LIST_APPS, {}) {
                val list = createTypedArrayList(ApplicationInfo.CREATOR) ?: arrayListOf()
                list to (createStringArray()?.toSet() ?: emptySet())
            }
        } catch (e: Exception) {
            Log.w(TAG, "listApps", e)
            null
        }
    }

    // --- The server's binder (ShellProvider) ---

    /** Called once per process from ShellProvider.onCreate. */
    @JvmStatic
    @Synchronized
    fun init(c: Context) {
        if (app != null) return
        val context = c.applicationContext
        app = context
        ShellApps.createChannel(context)
        Signal.init(context)
    }

    /**
     * The server hands over its binder. Returns the result for the server
     * (our token and the owner's decisions), or null to refuse it.
     */
    @JvmStatic
    @Synchronized
    fun attach(extras: Bundle): Bundle? {
        val binder = extras.getBinder(Protocol.EXTRA_BINDER) ?: return null
        try {
            if (binder.interfaceDescriptor != Protocol.DESCRIPTOR) {
                Log.w(TAG, "refused a binder that is not a shell server")
                return null
            }
        } catch (e: RemoteException) {
            return null
        }
        val context = app ?: return null
        val ourApk = context.applicationInfo?.sourceDir ?: return null
        if (ourApk != extras.getString(Protocol.EXTRA_APK)) {
            // A server from an older APK: let it go; the cluster starts a new one.
            Log.i(TAG, "the shell server runs from " + extras.getString(Protocol.EXTRA_APK) + ", not " + ourApk + "; stopping it")
            try {
                transact(binder, Protocol.DESCRIPTOR, Protocol.EXIT, {}) {}
            } catch (e: Exception) {
                // Already gone.
            }
            detached(binder, "updated")
            return null
        }
        val old = server
        try {
            binder.linkToDeath({ detached(binder, "died") }, 0)
        } catch (e: RemoteException) {
            return null
        }
        serverPid = extras.getInt(Protocol.EXTRA_PID)
        serverStarted = extras.getLong(Protocol.EXTRA_STARTED)
        serverContext = extras.getString(Protocol.EXTRA_CONTEXT, "")
        server = binder
        changedAt = System.currentTimeMillis()
        downReason = ""
        Log.i(TAG, "shell server attached: pid $serverPid uid " + extras.getInt(Protocol.EXTRA_UID))
        if (old == null || !old.isBinderAlive) {
            Signal.up()
            Pairing.serverUp()
        }
        changed()
        val d = ShellApps.decisions(context)
        return Bundle().apply {
            putBinder(Protocol.RESULT_TOKEN, TOKEN)
            putStringArray(Protocol.RESULT_ALLOWED, d.filterValues { it == Protocol.DECISION_ALLOWED }.keys.toTypedArray())
            putStringArray(Protocol.RESULT_DENIED, d.filterValues { it == Protocol.DECISION_DENIED }.keys.toTypedArray())
        }
    }

    /**
     * A user service's process hands over its binder (UserServiceStarter):
     * passed to the server, as Shizuku's manager does. Returns the server's
     * binder for the service to watch, or null.
     */
    @JvmStatic
    fun attachUserService(extras: Bundle): Bundle? {
        val s = server ?: return null
        extras.classLoader = BinderContainer::class.java.classLoader
        @Suppress("DEPRECATION") // the typed getParcelable needs API 33
        val binder = extras.getParcelable<BinderContainer>(Protocol.CLIENT_EXTRA_BINDER)?.binder ?: return null
        val token = extras.getString(Protocol.US_TOKEN) ?: return null
        return try {
            transact(s, Protocol.DESCRIPTOR, Protocol.ATTACH_USER_SERVICE, {
                writeStrongBinder(binder)
                writeInt(1)
                Bundle().apply { putString(Protocol.US_TOKEN, token) }.writeToParcel(this, 0)
            }) {}
            Bundle().apply { putParcelable(Protocol.CLIENT_EXTRA_BINDER, BinderContainer(s)) }
        } catch (e: Exception) {
            Log.w(TAG, "attachUserService", e)
            null
        }
    }

    @Synchronized
    private fun detached(binder: IBinder, reason: String) {
        val s = server
        if (s != null && s !== binder && s.isBinderAlive) return // an older server; the new one runs
        server = null
        changedAt = System.currentTimeMillis()
        downReason = reason
        Log.i(TAG, "shell server gone: $reason")
        Signal.down(reason)
        changed()
    }

    /** No server reached this process in its first seconds (Signal). */
    @JvmStatic
    @Synchronized
    fun noServer(reason: String) {
        if (available()) return
        changedAt = System.currentTimeMillis()
        downReason = reason
        changed()
    }

    @JvmStatic
    fun changed() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (r in copy) main.post(r)
    }

    private fun time(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}
