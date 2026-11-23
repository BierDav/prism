// The hidden framework APIs the shell server (Server.kt) and the user
// services' processes (UserServices.kt) use, by reflection: they run in
// app_process as uid 2000, where the hidden-API restrictions of apps don't
// apply, but android.jar has none of these classes. Prism's app process never
// calls these.
//
// What they do follows Shizuku's server (rikka.hidden.compat.*Apis as used in
// ShizukuService.java and ServiceStarter.java, v13.6.0, Apache-2.0;
// android/shizuku/NOTICE).

package at.quickme.prism.shell

import android.content.AttributionSource
import android.content.Context
import android.content.pm.ChangedPackages
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal object Hidden {
    private const val TAG = "PrismShell"

    /**
     * Calls the method `name` of `target` (a class for a static one) with
     * `args`: the first overload with as many parameters that takes them,
     * widening an Int to a long parameter (flags became long in API 33).
     * Throws what the method throws.
     */
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val cls = target as? Class<*> ?: target.javaClass
        val receiver = if (target is Class<*>) null else target
        var last: Exception? = null
        for (m in methods(cls, name, args.size)) {
            val converted = convert(m, args) ?: continue
            try {
                return m.invoke(receiver, *converted)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            } catch (e: IllegalArgumentException) {
                last = e
            }
        }
        throw NoSuchMethodException(cls.name + "." + name + "/" + args.size + (last?.let { ": " + it.message } ?: ""))
    }

    private fun methods(cls: Class<*>, name: String, count: Int): List<Method> {
        val all = (cls.methods.toList() + cls.declaredMethods.toList()).filter { it.name == name && it.parameterCount == count }
        for (m in all) m.isAccessible = true
        // long flags before int flags: the newer signature first.
        return all.distinct().sortedByDescending { m -> m.parameterTypes.count { it == java.lang.Long.TYPE } }
    }

    private fun convert(m: Method, args: Array<out Any?>): Array<Any?>? {
        val types = m.parameterTypes
        return Array(args.size) { i ->
            val a = args[i]
            val t = types[i]
            when {
                a == null -> if (t.isPrimitive) return null else null
                t == java.lang.Long.TYPE && a is Int -> a.toLong()
                t == java.lang.Integer.TYPE && a is Long -> if (a in Int.MIN_VALUE..Int.MAX_VALUE) a.toInt() else return null
                t.isPrimitive -> if (box(t).isInstance(a)) a else return null
                t.isInstance(a) -> a
                else -> return null
            }
        }
    }

    private fun box(t: Class<*>): Class<*> = when (t) {
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        else -> t
    }

    // --- Services ---

    fun service(name: String): IBinder? = try {
        call(Class.forName("android.os.ServiceManager"), "getService", name) as IBinder?
    } catch (e: Throwable) {
        Log.w(TAG, "ServiceManager.getService($name)", e)
        null
    }

    fun waitService(name: String) {
        while (service(name) == null) {
            Log.i(TAG, "waiting for system service $name")
            SystemClock.sleep(1000)
        }
    }

    val activityManager: Any by lazy {
        call(Class.forName("android.app.ActivityManager"), "getService") ?: throw IllegalStateException("no IActivityManager")
    }

    val packageManager: Any by lazy {
        call(Class.forName("android.app.ActivityThread"), "getPackageManager") ?: throw IllegalStateException("no IPackageManager")
    }

    // --- Packages ---

    fun packagesForUid(uid: Int): List<String> = try {
        (call(packageManager, "getPackagesForUid", uid) as Array<*>?)?.filterIsInstance<String>() ?: emptyList()
    } catch (e: Throwable) {
        emptyList()
    }

    fun packageInfo(pkg: String, flags: Int, userId: Int): PackageInfo? = try {
        call(packageManager, "getPackageInfo", pkg, flags.toLong(), userId) as PackageInfo?
    } catch (e: Throwable) {
        null
    }

    fun installedPackages(flags: Int, userId: Int): List<PackageInfo> = try {
        val slice = call(packageManager, "getInstalledPackages", flags.toLong(), userId)
        (slice?.let { call(it, "getList") } as List<*>?)?.filterIsInstance<PackageInfo>() ?: emptyList()
    } catch (e: Throwable) {
        Log.w(TAG, "getInstalledPackages", e)
        emptyList()
    }

    fun changedPackages(sequence: Int, userId: Int): ChangedPackages? = try {
        call(packageManager, "getChangedPackages", sequence, userId) as ChangedPackages?
    } catch (e: Throwable) {
        null
    }

    // --- Activity manager ---

    fun checkPermission(permission: String, pid: Int, uid: Int): Int = try {
        call(activityManager, "checkPermission", permission, pid, uid) as Int
    } catch (e: Throwable) {
        -1 // PERMISSION_DENIED
    }

    fun forceStop(pkg: String, userId: Int) {
        try {
            call(activityManager, "forceStopPackage", pkg, userId)
        } catch (e: Throwable) {
            Log.w(TAG, "forceStopPackage $pkg", e)
        }
    }

    /** Registers an IUidObserver implemented by `observer` (a Binder with that descriptor). */
    fun registerUidObserver(observer: IBinder, which: Int) {
        val stub = Class.forName("android.app.IUidObserver\$Stub")
        val proxy = call(stub, "asInterface", observer)
        call(activityManager, "registerUidObserver", proxy, which, -1 /* PROCESS_STATE_UNKNOWN */, "com.android.shell")
    }

    /** A transaction code of IUidObserver, from its Stub (they moved between releases), or `fallback`. */
    fun uidObserverCode(method: String, fallback: Int): Int = try {
        val f = Class.forName("android.app.IUidObserver\$Stub").getDeclaredField("TRANSACTION_$method")
        f.isAccessible = true
        f.getInt(null)
    } catch (e: Throwable) {
        fallback
    }

    /**
     * Calls a content provider of user 0 as the shell, as Shizuku's server
     * does with its clients' providers: getContentProviderExternal (which
     * starts the app's process if needed), IContentProvider.call, then
     * removeContentProviderExternal. `check` sees the provider's info first
     * and may refuse (returns false: no call). Returns the call's result.
     */
    fun callProvider(authority: String, method: String, extras: Bundle, check: (ProviderInfo) -> Boolean = { true }): Bundle? {
        val iam = activityManager
        // A null token, as Shizuku does: the system then counts references
        // and removeContentProviderExternal works.
        val holder = call(iam, "getContentProviderExternal", authority, 0, null, authority) ?: return null
        try {
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo?
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            if (info == null || !check(info)) return null
            val binder = call(provider, "asBinder") as IBinder?
            if (binder != null && !binder.pingBinder()) {
                Log.w(TAG, "the provider $authority is dead")
                return null
            }
            val source = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
            return call(provider, "call", source, authority, method, null, extras) as Bundle?
        } finally {
            try {
                call(iam, "removeContentProviderExternal", authority, null)
            } catch (e: Throwable) {
                Log.w(TAG, "removeContentProviderExternal $authority", e)
            }
        }
    }

    // --- System properties, SELinux ---

    fun getProperty(name: String, def: String?): String? =
        call(Class.forName("android.os.SystemProperties"), "get", name, def ?: "") as String?

    fun setProperty(name: String, value: String?) {
        call(Class.forName("android.os.SystemProperties"), "set", name, value ?: "")
    }

    fun selinuxContext(): String = try {
        call(Class.forName("android.os.SELinux"), "getContext") as String? ?: ""
    } catch (e: Throwable) {
        try {
            File("/proc/self/attr/current").readText(Charsets.UTF_8).trim().replace("\u0000", "")
        } catch (e: IOException) {
            ""
        }
    }

    // --- A user service's process (UserServices.kt) ---

    /** The system context of this process, as Shizuku's UserService gets it. */
    fun systemContext(): Context {
        val thread = call(Class.forName("android.app.ActivityThread"), "systemMain") ?: throw IllegalStateException("no ActivityThread")
        return call(thread, "getSystemContext") as Context
    }

    /** An app's context with its code, in that app's user. */
    fun packageContext(system: Context, pkg: String, uid: Int): Context =
        call(system, "createPackageContextAsUser", pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            UserHandle.getUserHandleForUid(uid)) as Context
}
