// Phone calls on the Mac (docs/calls.md): follows the call state and, when a
// call starts ringing or is placed, starts the call's bridge to the Mac
// (shell/CallBridge.kt) through the shell server, as the shell user. The
// bridge lives as long as the call; this waits for it and starts it again if
// it dies while the call goes on.
//
// Off unless the settings name a Mac (call_mac) and hold the key it shares
// with it (hotspot_key). The call state takes READ_PHONE_STATE, which the
// Device Owner grants itself (Dpc.setUp).

package at.quickme.prism

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import at.quickme.prism.core.CallLink
import at.quickme.prism.core.HotspotToken
import at.quickme.prism.shell.CallBridge
import at.quickme.prism.shell.Shell
import java.io.IOException

object Calls {
    private const val TAG = "PrismCalls"

    /** A bridge that dies while the call goes on is started again, this often at most. */
    private const val MAX_STARTS = 5

    private var watching = false // guarded by this

    @Volatile private var bridging = false

    /** The call state the listener last saw. */
    @Volatile private var callState = TelephonyManager.CALL_STATE_IDLE

    /** Starts following the call state; once per process. */
    @JvmStatic
    @Synchronized
    fun watch(c: Context) {
        if (watching) return
        val app = c.applicationContext
        if (app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no READ_PHONE_STATE yet; not following calls")
            return
        }
        val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
        telephony.registerTelephonyCallback(app.mainExecutor, Listener(app))
        watching = true
    }

    private class Listener(private val app: Context) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            callState = state
            if (state != TelephonyManager.CALL_STATE_IDLE) bridge(app)
        }
    }

    /** The Mac's host and port from call_mac ("host" or "host:port"), or null. */
    @JvmStatic
    fun mac(c: Context): Pair<String, Int>? {
        val value = Settings.value(c, "call_mac").trim()
        if (value.isEmpty()) return null
        val colon = value.lastIndexOf(':')
        if (colon > 0 && !value.contains(']') && value.indexOf(':') == colon) {
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            return value.substring(0, colon) to port
        }
        return value to CallLink.PORT
    }

    private fun bridge(c: Context) {
        if (bridging) return
        val (host, port) = mac(c) ?: return
        val key = HotspotToken.parseKey(Settings.value(c, "hotspot_key"))
        if (key == null) {
            Log.w(TAG, "call_mac is set, but hotspot_key isn't: no call on the Mac")
            return
        }
        bridging = true
        Thread({
            try {
                run(c, host, port, CallLink.hex(CallLink.key(key)))
            } finally {
                bridging = false
            }
        }, "calls").start()
    }

    private fun run(c: Context, host: String, port: Int, k: String) {
        val apk = c.applicationInfo.sourceDir
        for (start in 1..MAX_STARTS) {
            if (!Shell.available()) {
                Log.i(TAG, "no shell server; no call on the Mac")
                return
            }
            try {
                val r = Shell.run(
                    Shell.MAX_TIMEOUT_MS, "/system/bin/env", "CLASSPATH=$apk", "PRISM_CALL_KEY=$k",
                    "/system/bin/app_process", "-Djava.class.path=$apk", "/system/bin", "--nice-name=prism_call",
                    CallBridge::class.java.name, host, port.toString(),
                )
                val line = r.stdout.trim().lineSequence().lastOrNull()?.trim() ?: ""
                if (r.ok()) {
                    Log.i(TAG, "bridge: $line")
                } else {
                    Log.w(TAG, "bridge: $r $line ${r.stderr.takeLast(500)}")
                }
            } catch (e: IOException) {
                Log.w(TAG, "bridge: ${e.message}")
            }
            if (callState == TelephonyManager.CALL_STATE_IDLE) return
            Thread.sleep(1_000)
        }
        Log.w(TAG, "the bridge kept dying; giving up on this call")
    }
}
