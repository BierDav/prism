// Prism's shell server: runs as uid 2000 (adb shell) in its own app_process,
// started by the native starter (android/shizuku/starter/starter.c) from
// Prism's own APK. It speaks Shizuku's binder interface (API v13,
// Protocol.kt), so apps built on rikka.shizuku:api use it as they would use
// Shizuku, without a Shizuku app: Prism is the manager that asks the owner.
//
// Derived from Shizuku's server (github.com/RikkaApps/Shizuku v13.6.0,
// server/.../ShizukuService.java, BinderSender.java, ApkChangedObservers.kt,
// Apache-2.0) and Shizuku-API (server-shared/.../Service.java, ClientManager,
// ClientRecord, RemoteProcessHolder.java, ParcelFileDescriptorUtil.java, MIT);
// see android/shizuku/NOTICE. Kept: Shizuku's way of working (app_process
// with the APK as class path, a main looper, the binder handed to each app
// through its content provider, processes whose pipes die with their owner,
// exiting when the APK is replaced) and its API's semantics, codes and
// parcels. Changed: Prism is the manager (its uid may do everything; it gets
// the binder through its own provider, keeps the owner's decisions per
// package and shows the prompts), no rish, no config file, no hidden-API
// compat library (Hidden.kt, by reflection), user 0 only. The parcels are
// read and written by hand rather than by AIDL-generated stubs.
//
// The starter runs the class at.quickme.prism.shell.Server with its static
// main (the companion's @JvmStatic main; proguard-rules.pro keeps it).

package at.quickme.prism.shell

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.util.Log
import moe.shizuku.api.BinderContainer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class Server private constructor() : Binder() {
    companion object {
        internal const val TAG = "PrismShell"

        /** Exit codes of the server process. */
        private const val EXIT_APK_GONE = 10
        private const val EXIT_NOT_PRISM = 11
        private const val EXIT_REQUESTED = 0

        /** How often the package manager is asked for installed or updated apps. */
        private const val PACKAGES_POLL_MS = 30_000L

        // ActivityManager.UID_OBSERVER_*.
        private const val UID_OBSERVER_GONE = 1 shl 1
        private const val UID_OBSERVER_IDLE = 1 shl 2
        private const val UID_OBSERVER_ACTIVE = 1 shl 3
        private const val UID_OBSERVER_CACHED = 1 shl 4

        // Shizuku's ConfigManager flags (getFlagsForUid, updateFlagsForUid).
        private const val FLAG_ALLOWED = 1 shl 1
        private const val FLAG_DENIED = 1 shl 2

        @Suppress("DEPRECATION") // prepareMainLooper: right for a process of our own, as in Shizuku
        @JvmStatic
        fun main(args: Array<String>) {
            Looper.prepareMainLooper()
            Server()
            Looper.loop()
        }

        /**
         * The package an APK at /data/app/…/<package>-<suffix>/base.apk belongs
         * to: up to the first '-', which no package name has (the suffix is
         * URL-safe base64 and may).
         */
        internal fun packageOfApk(apk: String): String {
            val dir = File(apk).parentFile?.name ?: return Protocol.PACKAGE
            val i = dir.indexOf('-')
            return if (i > 0) dir.substring(0, i) else Protocol.PACKAGE
        }

        /** A pipe whose read end gets everything `input` gives (ParcelFileDescriptorUtil.pipeFrom). */
        internal fun pipeFrom(input: InputStream): ParcelFileDescriptor {
            val pipe = ParcelFileDescriptor.createPipe()
            transfer(input, ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            return pipe[0]
        }

        /** A pipe whose write end feeds `output` (ParcelFileDescriptorUtil.pipeTo). */
        internal fun pipeTo(output: OutputStream): ParcelFileDescriptor {
            val pipe = ParcelFileDescriptor.createPipe()
            transfer(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), output)
            return pipe[1]
        }

        private fun transfer(input: InputStream, output: OutputStream) {
            Thread({
                val buf = ByteArray(65536)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                } catch (e: IOException) {
                    // The other side went away.
                } finally {
                    try {
                        input.close()
                    } catch (e: IOException) {
                        // Closed already.
                    }
                    try {
                        output.close()
                    } catch (e: IOException) {
                        // Closed already.
                    }
                }
            }, "prism-shell-pipe").apply {
                isDaemon = true
                start()
            }
        }

        private fun appId(uid: Int): Int = uid % 100_000

        private fun userId(uid: Int): Int = uid / 100_000

        @Suppress("DEPRECATION") // the typed getParcelable needs API 33
        private fun <T> Bundle.parcelable(key: String): T? = getParcelable(key)
    }

    /** An app that attached (attachApplication), as Shizuku's ClientRecord. */
    private inner class Client(
        val uid: Int,
        val pid: Int,
        val app: IBinder,
        val packageName: String,
        val apiVersion: Int,
    ) {
        @Volatile var allowed = false

        fun bind(reply: Bundle) = oneway(app, Protocol.APPLICATION_DESCRIPTOR, Protocol.APP_BIND_APPLICATION) { writeTypedBundle(reply) }

        fun permissionResult(requestCode: Int, allowed: Boolean) =
            oneway(app, Protocol.APPLICATION_DESCRIPTOR, Protocol.APP_DISPATCH_PERMISSION_RESULT) {
                writeInt(requestCode)
                writeTypedBundle(Bundle().apply { putBoolean(Protocol.REPLY_ALLOWED, allowed) })
            }
    }

    internal val main = Handler(Looper.myLooper()!!)

    /** Package-manager calls and the hand-overs, which may block (they can start an app's process). */
    private val worker: Handler = HandlerThread("prism-shell-clients").let {
        it.start()
        Handler(it.looper)
    }

    internal val apk: String = System.getProperty("java.class.path") ?: ""
    internal val prismPackage = packageOfApk(apk)
    private val started = System.currentTimeMillis()

    // Must stay referenced, or it stops watching.
    private val apkObserver: FileObserver

    /** Prism's uid, from the package manager's record of its provider; -1 until known. */
    @Volatile internal var prismUid = -1
    @Volatile private var prismToken: IBinder? = null
    private var retryDelay = 1_000L
    private var deathDelay = 1_000L
    private var lastDeath = -60_000L
    private var clientsStarted = false

    private val clients = ArrayList<Client>() // guarded by itself
    /** The owner's decisions, per package (Protocol.DECISION_*), as Prism keeps them. */
    private val decisions = HashMap<String, Int>() // guarded by clients
    /** Uids whose apps had the binder offered since their processes started (BinderSender's UID_LIST). */
    private val offered = HashSet<Int>() // worker thread only
    private var packagesSequence = 0 // worker thread only

    internal val userServices = UserServices(this)

    init {
        attachInterface(null, Protocol.DESCRIPTOR)
        Log.i(TAG, "starting as uid " + Process.myUid() + " from " + apk + " for " + prismPackage)
        Hidden.waitService("package")
        Hidden.waitService("activity")
        Hidden.waitService("user")
        // Shizuku's ApkChangedObservers: watch the folder, not the file (a
        // deleted file stays alive while processes have it open). When Prism
        // is updated or removed, this server's code is stale: exit, and Prism
        // asks the cluster for a new one.
        val dir = File(apk).parentFile ?: File("/")
        apkObserver = object : FileObserver(dir, DELETE or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "base.apk" || !File(apk).exists()) exit(EXIT_APK_GONE, "the APK was replaced or removed")
            }
        }
        apkObserver.startWatching()
        worker.post(::deliver)
    }

    private fun exit(code: Int, why: String) {
        Log.i(TAG, "exiting ($code): $why")
        System.exit(code)
    }

    // --- Handing the binder to Prism (Shizuku's sendBinderToManager) ---

    private fun deliver() {
        if (!File(apk).exists()) exit(EXIT_APK_GONE, "the APK is gone")
        val authority = prismPackage + Protocol.AUTHORITY_SUFFIX
        var ok = false
        try {
            val extras = Bundle()
            extras.putBinder(Protocol.EXTRA_BINDER, this)
            extras.putString(Protocol.EXTRA_APK, apk)
            extras.putInt(Protocol.EXTRA_PID, Process.myPid())
            extras.putInt(Protocol.EXTRA_UID, Process.myUid())
            extras.putString(Protocol.EXTRA_CONTEXT, Hidden.selinuxContext())
            extras.putLong(Protocol.EXTRA_STARTED, started)
            val reply = Hidden.callProvider(authority, Protocol.METHOD_SEND_BINDER, extras) { info ->
                val ai = info.applicationInfo
                when {
                    ai == null || info.packageName != prismPackage -> exit(EXIT_NOT_PRISM, "the provider $authority is not Prism's")
                    ai.sourceDir != apk -> exit(EXIT_APK_GONE, "Prism now runs from " + ai.sourceDir)
                    else -> prismUid = ai.uid
                }
                true
            }
            if (reply == null) {
                Log.w(TAG, "no provider $authority, or Prism refused the binder")
                return
            }
            val token = reply.getBinder(Protocol.RESULT_TOKEN) ?: run {
                Log.w(TAG, "Prism refused the binder")
                return
            }
            token.linkToDeath({ worker.post(::prismDied) }, 0)
            prismToken = token
            setDecisions(reply.getStringArray(Protocol.RESULT_ALLOWED), reply.getStringArray(Protocol.RESULT_DENIED))
            ok = true
            retryDelay = 1_000
            Log.i(TAG, "handed the binder to Prism (uid $prismUid)")
            if (!clientsStarted) {
                clientsStarted = true
                startClients()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendBinder to Prism failed", e)
        } finally {
            if (!ok) {
                worker.postDelayed(::deliver, retryDelay)
                retryDelay = minOf(retryDelay * 3, 5 * 60_000L)
            }
        }
    }

    /** Prism's process ended: give the binder to the next one (this starts it). */
    private fun prismDied() {
        prismToken = null
        val now = SystemClock.elapsedRealtime()
        // Repeated deaths (a crash loop) back off; a single one is answered at once.
        deathDelay = if (now - lastDeath < 60_000) minOf(deathDelay * 3, 5 * 60_000L) else 1_000
        lastDeath = now
        Log.i(TAG, "Prism's process died; handing the binder again in $deathDelay ms")
        worker.postDelayed(::deliver, deathDelay)
    }

    private fun setDecisions(allowed: Array<String>?, denied: Array<String>?) {
        synchronized(clients) {
            decisions.clear()
            allowed?.forEach { decisions[it] = Protocol.DECISION_ALLOWED }
            denied?.forEach { decisions[it] = Protocol.DECISION_DENIED }
            for (c in clients) c.allowed = decisions[c.packageName] == Protocol.DECISION_ALLOWED
        }
    }

    // --- Handing the binder to apps (sendBinderToClient, BinderSender) ---

    /** Every app that declares Shizuku's permission now, then each one whose process starts, is installed or updated. */
    private fun startClients() {
        for (pi in Hidden.installedPackages(PackageManager.GET_PERMISSIONS, 0)) {
            if (requestsApi(pi)) {
                pi.applicationInfo?.uid?.let { offered.add(it) }
                sendToApp(pi.packageName)
            }
        }
        val gone = Hidden.uidObserverCode("onUidGone", IBinder.FIRST_CALL_TRANSACTION)
        val observer = object : Binder() {
            init {
                attachInterface(null, "android.app.IUidObserver")
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code < IBinder.FIRST_CALL_TRANSACTION || code > IBinder.LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                // Every IUidObserver call starts with the uid.
                data.enforceInterface("android.app.IUidObserver")
                val uid = data.readInt()
                if (appId(uid) < Process.FIRST_APPLICATION_UID || uid == prismUid) return true
                worker.post {
                    if (code == gone) {
                        offered.remove(uid)
                    } else if (offered.add(uid)) {
                        sendToUid(uid)
                    }
                }
                return true
            }
        }
        try {
            Hidden.registerUidObserver(observer, UID_OBSERVER_GONE or UID_OBSERVER_IDLE or UID_OBSERVER_ACTIVE or UID_OBSERVER_CACHED)
        } catch (e: Throwable) {
            Log.w(TAG, "registerUidObserver: apps get the binder only at the start and on install", e)
        }
        packagesSequence = Hidden.changedPackages(0, 0)?.sequenceNumber ?: 0
        worker.postDelayed(::pollPackages, PACKAGES_POLL_MS)
    }

    /** Installed or updated apps since the last look (PackageManager.getChangedPackages); runs on uptime, so not while the phone sleeps. */
    private fun pollPackages() {
        try {
            val changed = Hidden.changedPackages(packagesSequence, 0)
            if (changed != null) {
                packagesSequence = changed.sequenceNumber
                for (name in changed.packageNames) {
                    val pi = Hidden.packageInfo(name, PackageManager.GET_PERMISSIONS, 0) ?: continue
                    if (!requestsApi(pi)) continue
                    pi.applicationInfo?.uid?.let { offered.add(it) }
                    sendToApp(name)
                }
            }
        } finally {
            worker.postDelayed(::pollPackages, PACKAGES_POLL_MS)
        }
    }

    private fun requestsApi(pi: PackageInfo?): Boolean =
        pi != null && pi.packageName != prismPackage && pi.requestedPermissions?.contains(Protocol.API_PERMISSION) == true

    private fun sendToUid(uid: Int) {
        for (name in Hidden.packagesForUid(uid)) {
            if (requestsApi(Hidden.packageInfo(name, PackageManager.GET_PERMISSIONS, userId(uid)))) {
                sendToApp(name)
                return
            }
        }
    }

    /** sendBinderToUserApp: the app's ShizukuProvider (`<package>.shizuku`) gets the binder in a BinderContainer. */
    private fun sendToApp(pkg: String) {
        val extras = Bundle()
        extras.putParcelable(Protocol.CLIENT_EXTRA_BINDER, BinderContainer(this))
        try {
            val reply = Hidden.callProvider(pkg + Protocol.CLIENT_AUTHORITY_SUFFIX, "sendBinder", extras) { it.packageName == pkg }
            if (reply != null) Log.i(TAG, "sent the binder to $pkg") else Log.w(TAG, "$pkg has no ShizukuProvider or did not take the binder")
        } catch (e: Throwable) {
            Log.w(TAG, "sendBinder to $pkg failed", e)
        }
    }

    // --- Who may call ---

    private fun findClient(uid: Int, pid: Int): Client? = synchronized(clients) { clients.firstOrNull { it.uid == uid && it.pid == pid } }

    private fun isPrism(uid: Int): Boolean = prismUid != -1 && appId(uid) == appId(prismUid)

    /** Service.enforceCallingPermission: this uid, Prism, or an attached client the owner allowed. */
    private fun enforce(func: String) {
        val uid = getCallingUid()
        if (uid == Process.myUid() || isPrism(uid)) return
        val c = findClient(uid, getCallingPid())
            ?: throw SecurityException("Permission Denial: $func from pid=" + getCallingPid() + " is not an attached client")
        if (!c.allowed) throw SecurityException("Permission Denial: $func from pid=" + getCallingPid() + " requires permission")
    }

    private fun enforcePrism(func: String) {
        val uid = getCallingUid()
        if (getCallingPid() == Os.getpid() || isPrism(uid)) return
        throw SecurityException("Permission Denial: $func from pid=" + getCallingPid() + " is not Prism")
    }

    // --- Transactions ---

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code < IBinder.FIRST_CALL_TRANSACTION || code > IBinder.LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(Protocol.DESCRIPTOR)
        when (code) {
            Protocol.TRANSACT_REMOTE -> {
                transactRemote(data, reply, flags)
                return true
            }
            Protocol.ATTACH_APPLICATION_V11 -> {
                val app = data.readStrongBinder()
                val args = Bundle()
                args.putString(Protocol.ATTACH_PACKAGE_NAME, data.readString())
                args.putInt(Protocol.ATTACH_API_VERSION, -1)
                attachApplication(app, args)
            }
            Protocol.GET_VERSION -> {
                enforce("getVersion")
                reply?.writeNoException()
                reply?.writeInt(Protocol.SERVER_VERSION)
                return true
            }
            Protocol.GET_UID -> {
                enforce("getUid")
                reply?.writeNoException()
                reply?.writeInt(Os.getuid())
                return true
            }
            Protocol.CHECK_PERMISSION -> {
                enforce("checkPermission")
                val permission = data.readString() ?: ""
                reply?.writeNoException()
                reply?.writeInt(Hidden.checkPermission(permission, Os.getpid(), Os.getuid()))
                return true
            }
            Protocol.NEW_PROCESS -> {
                enforce("newProcess")
                val cmd = data.createStringArray()
                val env = data.createStringArray()
                val dir = data.readString()
                val p = newProcess(cmd, env, dir)
                reply?.writeNoException()
                reply?.writeStrongBinder(p)
                return true
            }
            Protocol.GET_SELINUX_CONTEXT -> {
                enforce("getSELinuxContext")
                reply?.writeNoException()
                reply?.writeString(Hidden.selinuxContext())
                return true
            }
            Protocol.GET_SYSTEM_PROPERTY -> {
                enforce("getSystemProperty")
                val name = data.readString()
                val def = data.readString()
                val value = try {
                    Hidden.getProperty(name ?: "", def)
                } catch (e: Throwable) {
                    throw IllegalStateException(e.message)
                }
                reply?.writeNoException()
                reply?.writeString(value)
                return true
            }
            Protocol.SET_SYSTEM_PROPERTY -> {
                enforce("setSystemProperty")
                val name = data.readString()
                val value = data.readString()
                try {
                    Hidden.setProperty(name ?: "", value)
                } catch (e: Throwable) {
                    throw IllegalStateException(e.message)
                }
            }
            Protocol.ADD_USER_SERVICE -> {
                enforce("addUserService")
                val conn = data.readStrongBinder()
                val options = data.readTypedBundle()
                val uid = getCallingUid()
                val api = findClient(uid, getCallingPid())?.apiVersion ?: Protocol.SERVER_VERSION
                val r = userServices.add(conn, options, uid, api)
                reply?.writeNoException()
                reply?.writeInt(r)
                return true
            }
            Protocol.REMOVE_USER_SERVICE -> {
                enforce("removeUserService")
                val conn = data.readStrongBinder()
                val options = data.readTypedBundle()
                val r = userServices.remove(conn, options, getCallingUid())
                reply?.writeNoException()
                reply?.writeInt(r)
                return true
            }
            Protocol.REQUEST_PERMISSION -> requestPermission(data.readInt())
            Protocol.CHECK_SELF_PERMISSION -> {
                val r = selfAllowed()
                reply?.writeNoException()
                reply?.writeInt(if (r) 1 else 0)
                return true
            }
            Protocol.SHOULD_SHOW_RATIONALE -> {
                val r = shouldShowRationale()
                reply?.writeNoException()
                reply?.writeInt(if (r) 1 else 0)
                return true
            }
            Protocol.ATTACH_APPLICATION -> {
                val app = data.readStrongBinder()
                attachApplication(app, data.readTypedBundle())
            }
            Protocol.EXIT -> {
                enforcePrism("exit")
                reply?.writeNoException()
                main.post { exit(EXIT_REQUESTED, "Prism asked") }
                return true
            }
            Protocol.ATTACH_USER_SERVICE -> {
                enforcePrism("attachUserService")
                val binder = data.readStrongBinder()
                userServices.attach(binder, data.readTypedBundle())
            }
            Protocol.DISPATCH_PACKAGE_CHANGED -> {
                // Sui's; Prism may use it to offer an app the binder now.
                enforcePrism("dispatchPackageChanged")
                val intent = data.readTyped(Intent.CREATOR)
                intent?.data?.schemeSpecificPart?.let { pkg ->
                    worker.post { if (requestsApi(Hidden.packageInfo(pkg, PackageManager.GET_PERMISSIONS, 0))) sendToApp(pkg) }
                }
                return true
            }
            Protocol.IS_HIDDEN -> {
                data.readInt()
                reply?.writeNoException()
                reply?.writeInt(0)
                return true
            }
            Protocol.DISPATCH_PERMISSION_RESULT -> {
                enforcePrism("dispatchPermissionConfirmationResult")
                permissionResult(data.readInt(), data.readInt(), data.readInt(), data.readTypedBundle())
                return true
            }
            Protocol.GET_FLAGS_FOR_UID -> {
                enforcePrism("getFlagsForUid")
                val uid = data.readInt()
                val mask = data.readInt()
                val d = synchronized(clients) { Hidden.packagesForUid(uid).map { decisions[it] }.firstOrNull { it != null } }
                reply?.writeNoException()
                reply?.writeInt(mask and when (d) {
                    Protocol.DECISION_ALLOWED -> FLAG_ALLOWED
                    Protocol.DECISION_DENIED -> FLAG_DENIED
                    else -> 0
                })
                return true
            }
            Protocol.UPDATE_FLAGS_FOR_UID -> {
                enforcePrism("updateFlagsForUid")
                val uid = data.readInt()
                val mask = data.readInt()
                val value = data.readInt() and mask
                val state = when {
                    value and FLAG_ALLOWED != 0 -> Protocol.DECISION_ALLOWED
                    value and FLAG_DENIED != 0 -> Protocol.DECISION_DENIED
                    else -> Protocol.DECISION_NONE
                }
                for (pkg in Hidden.packagesForUid(uid)) setDecision(pkg, state)
            }
            Protocol.PRISM_SET_DECISION -> {
                enforcePrism("setDecision")
                setDecision(data.readString() ?: "", data.readInt())
            }
            Protocol.PRISM_LIST_APPS -> {
                enforcePrism("listApps")
                val apps = Hidden.installedPackages(PackageManager.GET_PERMISSIONS, 0).filter(::requestsApi).mapNotNull { it.applicationInfo }
                val attached = synchronized(clients) { clients.map { it.packageName }.distinct() }
                reply?.writeNoException()
                reply?.writeTypedList(apps)
                reply?.writeStringArray(attached.toTypedArray())
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        reply?.writeNoException()
        return true
    }

    /** Service.transactRemote: a call to any binder (a system service), made as uid 2000. */
    private fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        enforce("transactRemote")
        val target = data.readStrongBinder() ?: throw IllegalArgumentException("no target binder")
        val targetCode = data.readInt()
        val client = findClient(getCallingUid(), getCallingPid())
        val targetFlags = if (client != null && client.apiVersion >= 13) data.readInt() else flags
        val forward = Parcel.obtain()
        try {
            forward.appendFrom(data, data.dataPosition(), data.dataAvail())
            val id = clearCallingIdentity()
            try {
                target.transact(targetCode, forward, reply, targetFlags)
            } finally {
                restoreCallingIdentity(id)
            }
        } finally {
            forward.recycle()
        }
    }

    private fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): RemoteProcess {
        if (cmd == null || cmd.isEmpty()) throw IllegalArgumentException("no command")
        val process = try {
            Runtime.getRuntime().exec(cmd, env, dir?.let(::File))
        } catch (e: IOException) {
            throw IllegalStateException(e.message)
        }
        val uid = getCallingUid()
        val owner = if (isPrism(uid)) prismToken else findClient(uid, getCallingPid())?.app
        return RemoteProcess(process, owner)
    }

    // --- Clients and permissions (ShizukuService.attachApplication, Service.requestPermission) ---

    private fun attachApplication(app: IBinder?, args: Bundle?) {
        if (app == null || args == null) return
        val pkg = args.getString(Protocol.ATTACH_PACKAGE_NAME) ?: return
        val api = args.getInt(Protocol.ATTACH_API_VERSION, -1)
        val uid = getCallingUid()
        val pid = getCallingPid()
        if (pkg !in Hidden.packagesForUid(uid)) throw SecurityException("Request package $pkg does not belong to uid $uid")
        var client = findClient(uid, pid)
        if (client == null) {
            val c = Client(uid, pid, app, pkg, api)
            try {
                app.linkToDeath({ synchronized(clients) { clients.remove(c) } }, 0)
            } catch (e: RemoteException) {
                return
            }
            synchronized(clients) {
                c.allowed = decisions[pkg] == Protocol.DECISION_ALLOWED
                clients.add(c)
            }
            client = c
        }
        Log.i(TAG, "attachApplication: $pkg uid $uid pid $pid api $api")
        val reply = Bundle()
        reply.putInt(Protocol.BIND_SERVER_UID, Os.getuid())
        // Clients of rikka.shizuku:api 12.2.0 attach the old way and must see a v12 server (as in Shizuku).
        reply.putInt(Protocol.BIND_SERVER_VERSION, if (api == -1) 12 else Protocol.SERVER_VERSION)
        reply.putString(Protocol.BIND_SERVER_SECONTEXT, Hidden.selinuxContext())
        reply.putInt(Protocol.BIND_SERVER_PATCH_VERSION, Protocol.SERVER_PATCH_VERSION)
        reply.putBoolean(Protocol.BIND_PERMISSION_GRANTED, client.allowed)
        reply.putBoolean(Protocol.BIND_SHOULD_SHOW_RATIONALE, false)
        client.bind(reply)
    }

    private fun requireClient(): Client = findClient(getCallingUid(), getCallingPid())
        ?: throw IllegalStateException("Not an attached client")

    private fun isSelf(): Boolean = getCallingUid() == Os.getuid() || getCallingPid() == Os.getpid()

    private fun selfAllowed(): Boolean = isSelf() || isPrism(getCallingUid()) || requireClient().allowed

    private fun shouldShowRationale(): Boolean {
        if (isSelf() || isPrism(getCallingUid())) return true
        val c = requireClient()
        return synchronized(clients) { decisions[c.packageName] == Protocol.DECISION_DENIED }
    }

    private fun requestPermission(requestCode: Int) {
        if (isSelf() || isPrism(getCallingUid())) return
        val c = requireClient()
        if (c.allowed) {
            c.permissionResult(requestCode, true)
            return
        }
        if (synchronized(clients) { decisions[c.packageName] } == Protocol.DECISION_DENIED) {
            c.permissionResult(requestCode, false)
            return
        }
        val token = prismToken
        if (token == null || !token.isBinderAlive) {
            Log.w(TAG, "${c.packageName} asks for permission, but Prism is not attached: denied")
            c.permissionResult(requestCode, false)
            return
        }
        val ai: ApplicationInfo? = Hidden.packageInfo(c.packageName, 0, userId(c.uid))?.applicationInfo
        oneway(token, Protocol.MANAGER_DESCRIPTOR, Protocol.MANAGER_ASK) {
            writeInt(c.uid)
            writeInt(c.pid)
            writeInt(requestCode)
            writeString(c.packageName)
            writeTyped(ai)
        }
    }

    /** The owner answered a prompt (dispatchPermissionConfirmationResult); the decision itself comes as PRISM_SET_DECISION. */
    private fun permissionResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) {
        if (data == null) return
        val allowed = data.getBoolean(Protocol.REPLY_ALLOWED)
        val records = synchronized(clients) { clients.filter { it.uid == requestUid } }
        for (r in records) {
            r.allowed = allowed
            if (r.pid == requestPid) r.permissionResult(requestCode, allowed)
        }
    }

    /** The owner decided, or changed their mind, for an app; a revoked app is stopped and loses its user services, as in Shizuku. */
    private fun setDecision(pkg: String, state: Int) {
        if (pkg.isEmpty()) return
        val revoked = ArrayList<Client>()
        synchronized(clients) {
            if (state == Protocol.DECISION_NONE) decisions.remove(pkg) else decisions[pkg] = state
            for (c in clients) {
                if (c.packageName != pkg) continue
                val allowed = state == Protocol.DECISION_ALLOWED
                if (c.allowed && !allowed) revoked.add(c)
                c.allowed = allowed
            }
        }
        Log.i(TAG, "decision for $pkg: $state")
        if (revoked.isNotEmpty() || state != Protocol.DECISION_ALLOWED) userServices.removeForPackage(pkg)
        for (c in revoked.distinctBy { it.uid }) {
            worker.post { Hidden.forceStop(c.packageName, userId(c.uid)) }
        }
    }

    // --- Parcels ---

    private fun Parcel.writeTypedBundle(b: Bundle?) = writeTyped(b)

    private fun Parcel.writeTyped(p: Parcelable?) {
        if (p == null) {
            writeInt(0)
        } else {
            writeInt(1)
            p.writeToParcel(this, 0)
        }
    }

    private fun Parcel.readTypedBundle(): Bundle? = readTyped(Bundle.CREATOR)

    private fun <T> Parcel.readTyped(creator: Parcelable.Creator<T>): T? = if (readInt() != 0) creator.createFromParcel(this) else null

    /** A oneway call to another process's binder; failures are logged (the other side is gone). */
    internal fun oneway(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: RemoteException) {
            Log.w(TAG, "oneway $descriptor/$code failed", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "oneway $descriptor/$code failed", e)
        } finally {
            data.recycle()
        }
    }

    /** User services' options name their component; Bundle.getParcelable for UserServices. */
    internal fun component(options: Bundle): ComponentName? = options.parcelable(Protocol.US_COMPONENT)

    /** One running command (Shizuku-API's RemoteProcessHolder): IRemoteProcess. */
    private class RemoteProcess(private val process: java.lang.Process, owner: IBinder?) : Binder() {
        private var input: ParcelFileDescriptor? = null
        private var output: ParcelFileDescriptor? = null

        init {
            attachInterface(null, Protocol.PROCESS_DESCRIPTOR)
            if (owner != null) {
                try {
                    owner.linkToDeath({
                        if (process.isAlive) {
                            process.destroy()
                            Log.i(TAG, "destroyed a process: its owner is gone")
                        }
                    }, 0)
                } catch (e: RemoteException) {
                    process.destroy() // the caller is already gone
                }
            }
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < IBinder.FIRST_CALL_TRANSACTION || code > IBinder.LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(Protocol.PROCESS_DESCRIPTOR)
            when (code) {
                Protocol.PROCESS_GET_OUTPUT_STREAM -> {
                    val pfd = synchronized(this) { output ?: pipeTo(process.outputStream).also { output = it } }
                    reply?.writeNoException()
                    reply?.writePfd(pfd)
                }
                Protocol.PROCESS_GET_INPUT_STREAM -> {
                    val pfd = synchronized(this) { input ?: pipeFrom(process.inputStream).also { input = it } }
                    reply?.writeNoException()
                    reply?.writePfd(pfd)
                }
                Protocol.PROCESS_GET_ERROR_STREAM -> {
                    val pfd = pipeFrom(process.errorStream)
                    reply?.writeNoException()
                    reply?.writePfd(pfd)
                }
                Protocol.PROCESS_WAIT_FOR -> {
                    val r = try {
                        process.waitFor()
                    } catch (e: InterruptedException) {
                        throw IllegalStateException(e)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(r)
                }
                Protocol.PROCESS_EXIT_VALUE -> {
                    val r = process.exitValue()
                    reply?.writeNoException()
                    reply?.writeInt(r)
                }
                Protocol.PROCESS_DESTROY -> {
                    process.destroy()
                    reply?.writeNoException()
                }
                Protocol.PROCESS_ALIVE -> {
                    reply?.writeNoException()
                    reply?.writeInt(if (process.isAlive) 1 else 0)
                }
                Protocol.PROCESS_WAIT_FOR_TIMEOUT -> {
                    val timeout = data.readLong()
                    val unit = TimeUnit.valueOf(data.readString() ?: "MILLISECONDS")
                    val exited = try {
                        process.waitFor(timeout, unit)
                    } catch (e: InterruptedException) {
                        throw IllegalStateException(e)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (exited) 1 else 0)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }

        private fun Parcel.writePfd(pfd: ParcelFileDescriptor) {
            writeInt(1)
            pfd.writeToParcel(this, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
        }
    }
}
