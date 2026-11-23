// Shizuku's user services: an app's own class (a Binder) run in a process of
// its own as uid 2000, which the app binds with Shizuku.bindUserService.
//
// Derived from Shizuku-API's server-shared UserServiceManager.java,
// UserServiceRecord.java and UserService.java, and Shizuku's
// ShizukuUserServiceManager.java and starter's ServiceStarter.java (v13.6.0;
// MIT and Apache-2.0, android/shizuku/NOTICE). Changed: the process is
// started from Prism's APK (UserServiceStarter below) with an argv, not a
// shell line; it hands its binder over through Prism's provider
// (ShellProvider, METHOD_SEND_USER_SERVICE), which passes it to the server as
// Shizuku's manager does (attachUserService); 64-bit only.

package at.quickme.prism.shell

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.FileObserver
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import moe.shizuku.api.BinderContainer
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** The server's side: one record per app and tag (or class). */
internal class UserServices(private val server: Server) {
    private val executor = Executors.newSingleThreadExecutor()
    private val records = HashMap<String, Record>() // guarded by this

    inner class Record(val key: String, val pkg: String, val versionCode: Int, var daemon: Boolean, apkDir: File) {
        val token = UUID.randomUUID().toString() + "-" + System.currentTimeMillis()
        var service: IBinder? = null
        var starting = false
        var process: java.lang.Process? = null
        private val connections = ArrayList<IBinder>()
        private val deaths = HashMap<IBinder, IBinder.DeathRecipient>()
        private val serviceDied = IBinder.DeathRecipient {
            Log.i(Server.TAG, "user service $key died")
            removeSelf()
        }
        private val startTimeout = Runnable {
            if (starting) {
                Log.w(Server.TAG, "user service $key did not start in 30 s")
                removeSelf()
            }
        }

        // Shizuku's ApkChangedObservers for the app: updated or removed, the service is stale.
        private val apkObserver = object : FileObserver(apkDir, DELETE or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "base.apk") {
                    Log.i(Server.TAG, "$pkg was updated or removed; removing user service $key")
                    removeSelf()
                }
            }
        }.also { it.startWatching() }

        fun register(conn: IBinder) {
            synchronized(connections) {
                if (conn in connections) return
                val death = IBinder.DeathRecipient { connectionDied(conn) }
                try {
                    conn.linkToDeath(death, 0)
                } catch (e: RemoteException) {
                    return
                }
                connections.add(conn)
                deaths[conn] = death
            }
        }

        fun unregister(conn: IBinder?) {
            synchronized(connections) {
                if (conn == null || !connections.remove(conn)) return
                deaths.remove(conn)?.let { conn.unlinkToDeath(it, 0) }
            }
        }

        private fun connectionDied(conn: IBinder) {
            val empty = synchronized(connections) {
                connections.remove(conn)
                deaths.remove(conn)
                connections.isEmpty()
            }
            if (!daemon && empty) {
                Log.i(Server.TAG, "user service $key is no daemon and has no connections left; removing it")
                removeSelf()
            }
        }

        fun setStartingTimeout() {
            starting = true
            server.main.postDelayed(startTimeout, 30_000)
        }

        fun setBinder(binder: IBinder) {
            server.main.removeCallbacks(startTimeout)
            starting = false
            service = binder
            try {
                binder.linkToDeath(serviceDied, 0)
            } catch (e: RemoteException) {
                Log.w(Server.TAG, "user service $key is already gone")
            }
            broadcastConnected()
        }

        fun alive(): Boolean = service?.pingBinder() == true

        fun broadcastConnected() {
            val s = service ?: return
            val list = synchronized(connections) { ArrayList(connections) }
            for (c in list) server.oneway(c, Protocol.CONNECTION_DESCRIPTOR, Protocol.CONNECTION_CONNECTED) { writeStrongBinder(s) }
        }

        fun removeSelf() {
            synchronized(this@UserServices) {
                if (records[key] === this) records.remove(key)
            }
            destroy()
        }

        fun destroy() {
            server.main.removeCallbacks(startTimeout)
            apkObserver.stopWatching()
            val s = service
            if (s != null) {
                s.unlinkToDeath(serviceDied, 0)
                if (s.pingBinder()) {
                    // Shizuku's destroy transaction: the service's own destroy(), if it has one.
                    val data = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(s.interfaceDescriptor ?: "")
                        s.transact(Protocol.USER_SERVICE_DESTROY, data, null, IBinder.FLAG_ONEWAY)
                    } catch (e: Exception) {
                        Log.w(Server.TAG, "destroy user service $key", e)
                    } finally {
                        data.recycle()
                    }
                }
            }
            synchronized(connections) {
                for ((conn, death) in deaths) conn.unlinkToDeath(death, 0)
                connections.clear()
                deaths.clear()
            }
        }
    }

    /** The calling app's package `pkg`, checked to belong to its uid (ensureCallingPackageForUserService). */
    private fun callingPackage(pkg: String, uid: Int): PackageInfo {
        val pi = Hidden.packageInfo(pkg, 0x00002000 /* MATCH_UNINSTALLED_PACKAGES */, uid / 100_000)
        val ai = pi?.applicationInfo ?: throw SecurityException("unable to find package $pkg")
        if (ai.uid % 100_000 != uid % 100_000) throw SecurityException("package $pkg is not owned by $uid")
        return pi
    }

    private fun key(pkg: String, cls: String, options: Bundle): String = pkg + ":" + (options.getString(Protocol.US_TAG) ?: cls)

    /** addUserService: 0, or with US_NO_CREATE the running service's version (-1 if none; pre-13 clients: 0 or 1). */
    fun add(conn: IBinder?, options: Bundle?, uid: Int, apiVersion: Int): Int {
        if (conn == null) throw NullPointerException("connection is null")
        if (options == null) throw NullPointerException("options is null")
        val component = server.component(options) ?: throw NullPointerException("component is null")
        val pkg = component.packageName
        val cls = component.className
        val pi = callingPackage(pkg, uid)
        val sourceDir = pi.applicationInfo?.sourceDir ?: throw NullPointerException("apk path is null")
        val versionCode = options.getInt(Protocol.US_VERSION_CODE, 1)
        val suffix = options.getString(Protocol.US_PROCESS_NAME) ?: "user_service"
        val debug = options.getBoolean(Protocol.US_DEBUGGABLE, false)
        val noCreate = options.getBoolean(Protocol.US_NO_CREATE, false)
        val daemon = options.getBoolean(Protocol.US_DAEMON, true)
        val key = key(pkg, cls, options)

        synchronized(this) {
            var record = records[key]
            if (noCreate) {
                if (record != null) {
                    record.register(conn)
                    if (record.alive()) {
                        record.broadcastConnected()
                        return if (apiVersion >= 13) record.versionCode else 0
                    }
                }
                return if (apiVersion >= 13) -1 else 1
            }
            if (record != null && (record.versionCode != versionCode || (!record.starting && !record.alive()))) {
                Log.i(Server.TAG, "replacing user service $key (version ${record.versionCode} → $versionCode, or dead)")
                records.remove(key)
                record.destroy()
                record = null
            }
            if (record == null) {
                record = Record(key, pkg, versionCode, daemon, File(sourceDir).parentFile ?: File("/"))
                records[key] = record
                Log.i(Server.TAG, "new user service $key: version $versionCode, daemon $daemon")
            } else {
                record.daemon = daemon
            }
            record.register(conn)
            if (record.alive()) {
                record.broadcastConnected()
            } else if (!record.starting) {
                record.setStartingTimeout()
                val r = record
                executor.execute { start(r, pkg, cls, "$pkg:$suffix", uid, debug) }
            }
            return 0
        }
    }

    /** removeUserService: 0, or 1 if there is no such service. */
    fun remove(conn: IBinder?, options: Bundle?, uid: Int): Int {
        if (options == null) throw NullPointerException("options is null")
        val component = server.component(options) ?: throw NullPointerException("component is null")
        callingPackage(component.packageName, uid)
        val key = key(component.packageName, component.className, options)
        // Clients before API 13.1.4 don't say; they mean remove.
        val remove = if (options.containsKey(Protocol.US_REMOVE)) options.getBoolean(Protocol.US_REMOVE) else true
        val record = synchronized(this) { records[key] } ?: return 1
        if (remove) record.removeSelf() else record.unregister(conn)
        return 0
    }

    /** attachUserService: a service's process handed over its binder (through Prism). */
    fun attach(binder: IBinder?, options: Bundle?) {
        if (binder == null) throw NullPointerException("binder is null")
        val token = options?.getString(Protocol.US_TOKEN) ?: throw NullPointerException("token is null")
        val record = synchronized(this) { records.values.firstOrNull { it.token == token } }
            ?: throw IllegalArgumentException("unable to find token $token")
        Log.i(Server.TAG, "user service ${record.key} attached")
        record.setBinder(binder)
    }

    fun removeForPackage(pkg: String) {
        val list = synchronized(this) { records.values.filter { it.pkg == pkg } }
        for (r in list) r.removeSelf()
    }

    private fun start(r: Record, pkg: String, cls: String, processName: String, uid: Int, debug: Boolean) {
        val argv = ArrayList<String>()
        argv.add("/system/bin/app_process")
        argv.add("-Djava.class.path=" + server.apk)
        if (debug) {
            argv.addAll(listOf("-Xcompiler-option", "--debuggable", "-XjdwpProvider:adbconnection", "-XjdwpOptions:suspend=n,server=y"))
        }
        argv.addAll(listOf(
            "/system/bin", "--nice-name=$processName", UserServiceStarter::class.java.name,
            "--token=" + r.token, "--package=$pkg", "--class=$cls", "--uid=$uid",
            "--manager=" + server.prismPackage + Protocol.AUTHORITY_SUFFIX,
        ))
        if (debug) argv.add("--debug-name=$processName")
        Log.i(Server.TAG, "starting user service ${r.key}")
        try {
            val pb = ProcessBuilder(argv).directory(File("/"))
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
            pb.environment()["CLASSPATH"] = server.apk
            r.process = pb.start()
        } catch (e: Exception) {
            Log.w(Server.TAG, "can't start user service ${r.key}", e)
            r.removeSelf()
        }
    }
}

/**
 * The process of one user service (ServiceStarter): loads the app's class
 * from its APK, hands the binder to the server through Prism's provider,
 * and exits when the server does.
 */
object UserServiceStarter {
    private const val TAG = "PrismShellService"

    // Must stay referenced, or its death recipient goes with it.
    private var server: IBinder? = null

    @Suppress("DEPRECATION") // prepareMainLooper: a process of our own
    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        var token: String? = null
        var pkg: String? = null
        var cls: String? = null
        var uid = -1
        var manager: String? = null
        for (a in args) {
            when {
                a.startsWith("--token=") -> token = a.substring(8)
                a.startsWith("--package=") -> pkg = a.substring(10)
                a.startsWith("--class=") -> cls = a.substring(8)
                a.startsWith("--uid=") -> uid = a.substring(6).toIntOrNull() ?: -1
                a.startsWith("--manager=") -> manager = a.substring(10)
            }
        }
        if (token == null || pkg == null || cls == null || uid < 0 || manager == null) {
            Log.e(TAG, "bad arguments")
            System.exit(1)
            return
        }
        Log.i(TAG, "starting $pkg/$cls")
        val service: IBinder = try {
            val context = Hidden.packageContext(Hidden.systemContext(), pkg, uid)
            val c = context.classLoader.loadClass(cls)
            try {
                c.getConstructor(Context::class.java).newInstance(context) as IBinder
            } catch (e: ReflectiveOperationException) {
                c.getConstructor().newInstance() as IBinder
            }
        } catch (e: Throwable) {
            Log.e(TAG, "unable to start $pkg/$cls", e)
            System.exit(1)
            return
        }
        if (!send(service, token, manager)) System.exit(1)
        Looper.loop()
        System.exit(0)
    }

    private fun send(service: IBinder, token: String, manager: String): Boolean {
        val extras = Bundle()
        extras.putParcelable(Protocol.CLIENT_EXTRA_BINDER, BinderContainer(service))
        extras.putString(Protocol.US_TOKEN, token)
        for (attempt in 0 until 5) {
            try {
                val reply = Hidden.callProvider(manager, Protocol.METHOD_SEND_USER_SERVICE, extras)
                if (reply != null) {
                    reply.classLoader = BinderContainer::class.java.classLoader
                    @Suppress("DEPRECATION") // the typed getParcelable needs API 33
                    val s = reply.getParcelable<BinderContainer>(Protocol.CLIENT_EXTRA_BINDER)?.binder
                    if (s != null && s.pingBinder()) {
                        s.linkToDeath({
                            Log.i(TAG, "the shell server is gone; exiting")
                            System.exit(0)
                        }, 0)
                        server = s
                        return true
                    }
                }
                Log.w(TAG, "Prism did not take the user service (attempt ${attempt + 1})")
            } catch (e: Throwable) {
                Log.w(TAG, "sendUserService", e)
            }
            Thread.sleep(2000)
        }
        return false
    }
}
