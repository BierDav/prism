// The phone's side of a call on the Mac (docs/calls.md), run as the shell
// user (uid 2000) for as long as one call lasts. Calls.kt starts it through
// the shell server when a call starts ringing or is placed:
//
//   PRISM_CALL_KEY=<K, 64 hex> app_process -Djava.class.path=<Prism's APK> \
//     /system/bin at.quickme.prism.shell.CallBridge HOST [PORT]
//
// It connects to the Mac (core/CallLink.kt), tells it the call's state,
// number and contact name, answers, declines and ends the call when the Mac
// says so, and while the call is on the Mac carries its audio both ways with
// Android's call audio interception (AudioManager's
// getCallDownlinkExtractionAudioRecord and getCallUplinkInjectionAudioTrack,
// the system API behind Google's call streaming). It exits when the call
// ends.
//
// Why the shell: those are system APIs that take CALL_AUDIO_INTERCEPTION,
// which a normal app's process may not call; the shell holds it, and
// READ_CALL_LOG, READ_CONTACTS, ANSWER_PHONE_CALLS and MODIFY_PHONE_STATE.
//
// While the call is on the Mac, the bridge asks for audio mode
// MODE_CALL_REDIRECT ("a telephony call is established and its audio is
// being redirected to another device"), so the phone's earpiece and
// microphone leave the call; the most recent privileged request owns the
// mode, so it asks again when Telecom sets MODE_IN_CALL over it. Moving the
// call back, a lost connection or the bridge's exit hands the mode back to
// Telecom.

package at.quickme.prism.shell

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import at.quickme.prism.core.CallLink
import at.quickme.prism.core.HotspotToken
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object CallBridge {
    private const val TAG = "PrismCall"

    /** Playback falls at most this far behind (200 ms of 20 ms frames). */
    private const val QUEUE_FRAMES = 10
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val RETRY_MS = 2_000L

    /** The longest call it serves (the wake lock's limit). */
    private const val MAX_CALL_MS = 6 * 3600_000L

    private lateinit var main: Handler
    private lateinit var context: Context
    private lateinit var telephony: TelephonyManager
    private lateinit var telecom: TelecomManager
    private lateinit var audio: AudioManager
    private lateinit var key: ByteArray
    private lateinit var host: String
    private var port = CallLink.PORT

    // The call, on the main thread.
    private var state = TelephonyManager.CALL_STATE_IDLE
    private var number: String? = null
    private var name: String? = null
    private var since: Long? = null
    private var error: String? = null

    /** The Mac wants the call's audio (it answered, or moved it there). */
    private var wantMac = false

    @Volatile private var ended = false
    private val link = AtomicReference<Link?>()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "call-send") }
    private val modes = Executors.newSingleThreadExecutor { r -> Thread(r, "call-mode") }

    // The audio, while the call is on the Mac.
    @Volatile private var pipe: Pipe? = null

    @Suppress("DEPRECATION") // prepareMainLooper: a process of our own
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        main = Handler(Looper.getMainLooper())
        host = args.getOrNull(0) ?: usage()
        port = args.getOrNull(1)?.toIntOrNull() ?: CallLink.PORT
        key = System.getenv("PRISM_CALL_KEY")?.let { HotspotToken.parseKey(it) } ?: usage()
        Hidden.initMainlineModules()
        context = Hidden.packageContext(Hidden.systemContext(), "com.android.shell", Process.SHELL_UID)
        // As com.android.shell: on the system's context, telephony calls as
        // "android", which TelephonyRegistry refuses for uid 2000.
        val shell = Hidden.shellContext(context)
        telephony = Hidden.manager(TelephonyManager::class.java, shell)
        telecom = Hidden.manager(TelecomManager::class.java, shell)
        audio = Hidden.manager(AudioManager::class.java, shell)

        // One listener per SIM: each reports only its own calls. Made here,
        // after the main looper exists (a PhoneStateListener takes it).
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList?.map { it.subscriptionId }.orEmpty()
        for (id in subscriptions.ifEmpty { listOf(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) }) {
            val tm = telephony.createForSubscriptionId(id)
            states[id] = tm.callStateForSubscription
            listeners += tm to SimListener(id)
        }
        state = aggregate()
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            println("no call")
            System.exit(0)
        }
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) since = System.currentTimeMillis()
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prism:call")
        wake.acquire(MAX_CALL_MS)

        for ((tm, listener) in listeners) tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        audio.addOnModeChangedListener(modes) { mode -> main.post { modeChanged(mode) } }
        Thread(::connectLoop, "call-link").start()
        Log.i(TAG, "bridging the call to $host:$port")
        Looper.loop()
    }

    private fun usage(): Nothing {
        System.err.println("usage: PRISM_CALL_KEY=<64 hex> CallBridge HOST [PORT]")
        System.exit(2)
        throw IllegalStateException()
    }

    // --- The call ---

    /** Each SIM's call state, on the main thread. */
    private val states = HashMap<Int, Int>()
    @Suppress("DEPRECATION")
    private val listeners = ArrayList<Pair<TelephonyManager, PhoneStateListener>>()

    @Suppress("DEPRECATION") // PhoneStateListener: TelephonyCallback has no number
    private class SimListener(private val id: Int) : PhoneStateListener() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCallStateChanged(s: Int, phoneNumber: String?) = callChanged(id, s, phoneNumber)
    }

    /** The phone's state over all SIMs: a call in progress, else one ringing, else idle. */
    private fun aggregate(): Int = when {
        TelephonyManager.CALL_STATE_OFFHOOK in states.values -> TelephonyManager.CALL_STATE_OFFHOOK
        TelephonyManager.CALL_STATE_RINGING in states.values -> TelephonyManager.CALL_STATE_RINGING
        else -> TelephonyManager.CALL_STATE_IDLE
    }

    private fun callChanged(id: Int, simState: Int, phoneNumber: String?) {
        if (!phoneNumber.isNullOrBlank() && phoneNumber != number) {
            number = phoneNumber
            name = lookup(phoneNumber)
        }
        states[id] = simState
        val s = aggregate()
        val was = state
        state = s
        when (s) {
            TelephonyManager.CALL_STATE_IDLE -> return finish()
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (since == null) since = System.currentTimeMillis()
                if (wantMac && pipe == null) startAudio(attempt = 0)
            }
        }
        if (s != was || !phoneNumber.isNullOrBlank()) sendState()
    }

    /** The contact's name for a number, or null. */
    private fun lookup(number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "contact lookup: $e")
        null
    }

    private fun command(message: JSONObject) {
        error = null
        try {
            obey(message)
        } catch (e: RuntimeException) {
            Log.w(TAG, message.optString("type"), e)
            error = "${message.optString("type")} failed: ${e.message ?: e}"
        }
        if (error != null) Log.w(TAG, error!!)
        sendState()
    }

    @Suppress("DEPRECATION") // acceptRingingCall: the way for a holder of ANSWER_PHONE_CALLS outside an InCallService
    private fun obey(message: JSONObject) {
        when (message.optString("type")) {
            "answer" -> if (state == TelephonyManager.CALL_STATE_RINGING) {
                wantMac = true
                telecom.acceptRingingCall()
            } else {
                error = "no ringing call to answer"
            }
            "decline", "hangup" -> endCall()
            "audio" -> when (message.optString("to")) {
                "mac" -> if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    wantMac = true
                    startAudio(attempt = 0)
                } else {
                    error = "the call isn't active"
                }
                "phone" -> {
                    wantMac = false
                    stopAudio()
                }
                else -> error = "audio to mac or phone"
            }
            else -> error = "unknown message ${message.optString("type")}"
        }
    }

    @Suppress("DEPRECATION") // endCall: still the way for a holder of ANSWER_PHONE_CALLS
    private fun endCall() {
        if (state == TelephonyManager.CALL_STATE_IDLE) return
        val ok = try {
            telecom.endCall()
        } catch (e: SecurityException) {
            Log.w(TAG, "endCall: $e")
            false
        }
        if (!ok) {
            // The call key, as a headset's button would end it.
            Log.i(TAG, "endCall refused; sending KEYCODE_ENDCALL")
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/input", "keyevent", "KEYCODE_ENDCALL")).waitFor()
            } catch (e: IOException) {
                error = "could not end the call: ${e.message}"
            }
        }
    }

    private fun finish() {
        if (ended) return
        ended = true
        Log.i(TAG, "the call ended")
        stopAudio()
        sendState()
        sender.execute { link.getAndSet(null)?.close() }
        main.postDelayed({
            println("call ended")
            System.out.flush()
            System.exit(0)
        }, 500)
    }

    private fun sendState() {
        val message = JSONObject()
            .put("type", "call")
            .put("state", stateName())
            .put("audio", if (pipe != null) "mac" else "phone")
        number?.let { message.put("number", it) }
        name?.let { message.put("name", it) }
        since?.let { message.put("since", it) }
        error?.let { message.put("error", it) }
        val bytes = message.toString().toByteArray()
        sender.execute { link.get()?.send(CallLink.MESSAGE, bytes) }
    }

    private fun stateName(): String = when {
        ended -> "ended"
        state == TelephonyManager.CALL_STATE_RINGING -> "ringing"
        else -> "active"
    }

    // --- The audio ---

    private fun startAudio(attempt: Int) {
        if (pipe != null || ended) return
        try {
            audio.mode = AudioManager.MODE_CALL_REDIRECT
            val record = Hidden.call(
                audio, "getCallDownlinkExtractionAudioRecord",
                format(AudioFormat.CHANNEL_IN_MONO),
            ) as AudioRecord
            val track = try {
                Hidden.call(audio, "getCallUplinkInjectionAudioTrack", format(AudioFormat.CHANNEL_OUT_MONO)) as AudioTrack
            } catch (e: Throwable) {
                record.release()
                throw e
            }
            pipe = Pipe(record, track).also { it.start() }
            Log.i(TAG, "the call's audio is on the Mac")
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            // Just answered: Telecom sets the call's audio mode a moment later.
            if (cause is IllegalStateException && attempt < 10) {
                main.postDelayed({ startAudio(attempt + 1) }, 300)
                return
            }
            Log.w(TAG, "moving the call's audio to the Mac", cause)
            error = "the phone can't hand over the call's audio: ${cause.message ?: cause}"
            wantMac = false
            audio.mode = AudioManager.MODE_NORMAL
        }
        sendState()
    }

    private fun stopAudio() {
        val p = pipe ?: return
        pipe = null
        p.stop()
        // Hands the mode back to Telecom's request (MODE_IN_CALL).
        audio.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "the call's audio is on the phone")
    }

    /** Telecom set the mode over ours: ask again while the call is on the Mac. */
    private fun modeChanged(mode: Int) {
        if (pipe != null && mode == AudioManager.MODE_IN_CALL) {
            Log.i(TAG, "Telecom took the audio mode; redirecting again")
            audio.mode = AudioManager.MODE_CALL_REDIRECT
        }
    }

    private fun format(channels: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(CallLink.SAMPLE_RATE)
        .setChannelMask(channels)
        .build()

    /** The call's downlink to the Mac and the Mac's microphone into its uplink. */
    private class Pipe(private val record: AudioRecord, private val track: AudioTrack) {
        @Volatile private var running = true
        val incoming = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
        private val threads = ArrayList<Thread>()

        fun start() {
            record.startRecording()
            track.play()
            threads += Thread({
                val buf = ByteArray(CallLink.FRAME_BYTES)
                while (running) {
                    val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        // Released: the call ended or its mode left the call.
                        if (running) main.post { if (pipe === this@Pipe) lost("the call's audio stopped ($n)") }
                        return@Thread
                    }
                    if (n > 0) link.get()?.send(CallLink.AUDIO, buf, 0, n)
                }
            }, "call-downlink")
            threads += Thread({
                while (running) {
                    val frame = try {
                        incoming.poll(100, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    } ?: continue
                    if (track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING) < 0) return@Thread
                }
            }, "call-uplink")
            threads.forEach { it.start() }
        }

        /** A frame from the Mac; the oldest goes when it falls behind. */
        fun offer(frame: ByteArray) {
            while (!incoming.offer(frame)) incoming.poll()
        }

        fun stop() {
            running = false
            threads.forEach { it.interrupt() }
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // released already
            }
            try {
                track.stop()
            } catch (e: IllegalStateException) {
                // released already
            }
            threads.forEach { it.join(500) }
            record.release()
            track.release()
        }
    }

    /** The connection broke or the audio stopped: the call goes back to the phone. */
    private fun lost(why: String) {
        Log.i(TAG, why)
        if (pipe != null) {
            wantMac = false
            stopAudio()
            sendState()
        }
    }

    // --- The connection ---

    private class Link(val socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        fun send(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
            try {
                synchronized(out) {
                    CallLink.write(out, type, payload, offset, length)
                    out.flush()
                }
            } catch (e: IOException) {
                close() // the reader sees it and reconnects
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (e: IOException) {
                // closed
            }
        }
    }

    private fun connectLoop() {
        while (!ended) {
            var l: Link? = null
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                l = Link(socket)
                socket.soTimeout = CONNECT_TIMEOUT_MS
                handshake(l)
                socket.soTimeout = 0
                link.set(l)
                Log.i(TAG, "connected to the Mac")
                main.post { sendState() }
                read(l)
            } catch (e: IOException) {
                if (!ended) Log.i(TAG, "the Mac: ${e.message}")
            } finally {
                l?.close()
                if (l != null && link.compareAndSet(l, null)) main.post { lost("the connection to the Mac broke") }
            }
            if (!ended) Thread.sleep(RETRY_MS)
        }
    }

    /** The Mac's hello, our proof, the Mac's proof (docs/calls.md). */
    private fun handshake(l: Link) {
        val k = key
        val hello = CallLink.read(l.input)
        if (hello.type != CallLink.HELLO || hello.payload.size != CallLink.NONCE_BYTES) throw IOException("no hello")
        val np = ByteArray(CallLink.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        l.send(CallLink.AUTH, np + CallLink.phoneMac(k, hello.payload, np))
        val proof = CallLink.read(l.input)
        if (proof.type != CallLink.AUTH || !CallLink.equal(proof.payload, CallLink.macMac(k, np, hello.payload))) {
            throw IOException("the Mac's proof is wrong")
        }
    }

    private fun read(l: Link) {
        while (!ended) {
            val frame = CallLink.read(l.input)
            when (frame.type) {
                CallLink.AUDIO -> pipe?.offer(frame.payload)
                CallLink.MESSAGE -> {
                    val message = try {
                        JSONObject(String(frame.payload, Charsets.UTF_8))
                    } catch (e: JSONException) {
                        Log.w(TAG, "a message that isn't JSON")
                        continue
                    }
                    main.post { command(message) }
                }
                else -> Log.w(TAG, "frame type ${frame.type} after the handshake")
            }
        }
    }
}
