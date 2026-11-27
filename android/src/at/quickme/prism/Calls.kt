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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import at.quickme.prism.core.CallLink
import at.quickme.prism.core.HotspotToken
import at.quickme.prism.shell.CallBridge
import at.quickme.prism.shell.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

object Calls {
    private const val TAG = "PrismCalls"

    /** A bridge that dies while the call goes on is started again, this often at most. */
    private const val MAX_STARTS = 5

    private var watching = false // guarded by this
    private val dialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var bridging = false

    /** Picks up pending outgoing call requests written by a Mac and dials them. */
    @JvmStatic
    fun handleDialRequests(c: Context) {
        val app = c.applicationContext
        dialScope.launch {
            try {
                val pending = PrismApi.run(app) { it.pendingCallRequests() }
                for (req in pending) {
                    var err: String? = null
                    try {
                        val number = req.number.trim()
                        if (number.isEmpty()) {
                            err = "empty number"
                        } else if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                            err = "missing CALL_PHONE permission"
                        } else {
                            Log.i(TAG, "dialing requested number from ${req.requestedBy}")
                            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            app.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        err = e.message ?: e.javaClass.simpleName
                        Log.w(TAG, "dial request ${req.requestId} failed: $err")
                    }
                    try {
                        PrismApi.run(app) { it.finishCallRequest(req.requestId, err) }
                    } catch (e: Exception) {
                        Log.w(TAG, "finishCallRequest ${req.requestId}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "handleDialRequests: ${e.message}")
            }
        }
    }

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
        // The broadcast covers every SIM; a TelephonyCallback sees only one.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK) bridge(app)
            }
        }, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        watching = true
        // A call that is already going on.
        if (inCall(app)) bridge(app) else if (mac(app) != null) Thread({ dataDuringCalls(app) }, "calls-data").start()
    }

    /**
     * Turns on "Mobile data during calls" for the SIMs without mobile data
     * (CallBridge --data-during-calls), so a call on one of them doesn't cut
     * the way to the Mac.
     */
    private fun dataDuringCalls(c: Context) {
        if (!Shell.available()) return
        val apk = c.applicationInfo.sourceDir
        try {
            val r = Shell.run(
                30_000, "/system/bin/env", "CLASSPATH=$apk",
                "/system/bin/app_process", "-Djava.class.path=$apk", "/system/bin", "--nice-name=prism_call",
                CallBridge::class.java.name, "--data-during-calls",
            )
            Log.i(TAG, r.stdout.trim().lineSequence().lastOrNull() ?: "data during calls: $r")
        } catch (e: IOException) {
            Log.w(TAG, "data during calls: ${e.message}")
        }
    }

    /** A call rings or goes on, on any SIM. */
    private fun inCall(c: Context): Boolean = try {
        c.getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (e: SecurityException) {
        false
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
            if (!inCall(c)) return
            Thread.sleep(1_000)
        }
        Log.w(TAG, "the bridge kept dying; giving up on this call")
    }
}
