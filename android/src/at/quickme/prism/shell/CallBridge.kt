// The phone's side of a call on the Mac (docs/calls.md), run as the shell
// user (uid 2000) for as long as one call lasts. Calls.kt starts it through
// the shell server when a call starts ringing or is placed:
//
//   PRISM_CALL_KEY=<K, 64 hex> app_process -Djava.class.path=<Prism's APK> \
//     /system/bin at.quickme.prism.shell.CallBridge HOST [PORT]
//
//   app_process ... at.quickme.prism.shell.CallBridge --data-during-calls
//
// turns on "Mobile data during calls" for every SIM but the one with mobile
// data, and exits (dataDuringCalls()); Calls.kt runs it when it starts
// following calls, and the bridge again at each call.
//
// It links to the Mac (core/CallLink.kt, docs/calls.md v2) over Bluetooth LE
// first: the Mac advertises an L2CAP channel, and the bridge scans for it and
// connects, so neither needs a network, and a call on a SIM without data (or
// a data switch to one out of quota) doesn't matter. At the same time, while
// there is no Bluetooth link, it connects over TCP: at HOST (its NetBird
// name), and directly at every device on the phone's own networks (its
// hotspot from the tethering module's clients, its hotspot and Wi-Fi from the
// kernel's ARP table). The handshake tells the Mac from anything else
// listening there; the first that passes wins and the others close before
// theirs. A Bluetooth link replaces a TCP one; every frame after the
// handshake is encrypted, and audio goes as ADPCM over Bluetooth. It tells the Mac the call's state,
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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.TetheringManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
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
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object CallBridge {
    private const val TAG = "PrismCall"

    /** Playback falls at most this far behind (200 ms of 20 ms frames). */
    private const val QUEUE_FRAMES = 10

    /**
     * Frames the queue for the call keeps at least, over a second: more than
     * that is delay a burst left behind, which the queue sheds (Pipe).
     */
    private const val WINDOW_FRAMES = 50
    private const val MIN_TARGET = 2
    private const val MAX_TARGET = 6
    private const val CALM_FRAMES = 500
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val RETRY_MS = 2_000L

    /** A call on the Mac waits this long for another link before its audio goes back to the phone. */
    private const val LINK_GRACE_MS = 5_000L

    /** After the call ends, how long the bridge waits for the Mac to have "ended" and close the link. */
    private const val END_WAIT_MS = 3_000L

    /** AudioManager.CALL_REDIRECT_PSTN (hidden). */
    private const val CALL_REDIRECT_PSTN = 1

    /** AudioAttributes.USAGE_CALL_ASSISTANT (system). */
    private const val USAGE_CALL_ASSISTANT = 17

    /** The longest call it serves (the wake lock's limit). */
    private const val MAX_CALL_MS = 6 * 3600_000L

    private lateinit var main: Handler
    private lateinit var context: Context
    private lateinit var telephony: TelephonyManager
    private lateinit var telecom: TelecomManager
    private lateinit var audio: AudioManager
    private lateinit var shell: Context
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

    /** The hotspot's clients' IPv4 addresses (their DHCP leases), as the tethering module last said. */
    @Volatile private var hotspotClients: List<String> = emptyList()

    // The audio, while the call is on the Mac.
    @Volatile private var pipe: Pipe? = null

    @Suppress("DEPRECATION") // prepareMainLooper: a process of our own
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        main = Handler(Looper.getMainLooper())
        if (args.getOrNull(0) == "--data-during-calls") {
            Hidden.initMainlineModules()
            context = Hidden.packageContext(Hidden.systemContext(), "com.android.shell", Process.SHELL_UID)
            telephony = Hidden.manager(TelephonyManager::class.java, Hidden.shellContext(context))
            println(dataDuringCalls())
            System.exit(0)
        }
        host = args.getOrNull(0) ?: usage()
        port = args.getOrNull(1)?.toIntOrNull() ?: CallLink.PORT
        key = System.getenv("PRISM_CALL_KEY")?.let { HotspotToken.parseKey(it) } ?: usage()
        Hidden.initMainlineModules()
        context = Hidden.packageContext(Hidden.systemContext(), "com.android.shell", Process.SHELL_UID)
        // As com.android.shell: on the system's context, telephony calls as
        // "android", which TelephonyRegistry refuses for uid 2000.
        shell = Hidden.shellContext(context)
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
        Log.i(TAG, dataDuringCalls())
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
        followHotspot()
        Thread(::bluetoothLoop, "call-bluetooth").start()
        Thread(::connectLoop, "call-link").start()
        Log.i(TAG, "bridging the call to $host:$port")
        Looper.loop()
    }

    /** TelephonyManager.MOBILE_DATA_POLICY_DATA_ON_NON_DEFAULT_DURING_VOICE_CALL (system API). */
    private const val DATA_DURING_CALLS = 1

    /**
     * Turns on "Mobile data during calls" (Settings, a SIM's page) for every
     * active SIM but the one with mobile data: with two SIMs, a call on the
     * other stops mobile data otherwise, and with it the connection to the
     * Mac over NetBird; with it, data moves to the calling SIM for the call.
     * The shell holds MODIFY_PHONE_STATE, which setMobileDataPolicyEnabled
     * (a system API) takes. Returns what it did, for the log.
     */
    private fun dataDuringCalls(): String {
        val data = SubscriptionManager.getDefaultDataSubscriptionId()
        val subs = try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
                ?.map { it.subscriptionId }.orEmpty()
        } catch (e: RuntimeException) {
            return "data during calls: no SIMs: $e"
        }
        val done = ArrayList<String>()
        for (id in subs) {
            if (id == data) continue
            val tm = telephony.createForSubscriptionId(id)
            try {
                if (Hidden.call(tm, "isMobileDataPolicyEnabled", DATA_DURING_CALLS) == true) {
                    done += "SIM $id already"
                } else {
                    Hidden.call(tm, "setMobileDataPolicyEnabled", DATA_DURING_CALLS, true)
                    done += "SIM $id on"
                }
            } catch (e: Throwable) {
                done += "SIM $id: $e"
            }
        }
        return "data during calls: " + done.ifEmpty { listOf("one SIM") }.joinToString(", ")
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

    /**
     * The contact's name for a number, or null. Through the shell's own
     * `content` command, which queries as com.android.shell: this process's
     * ContentResolver names the system's package, which the contacts
     * provider refuses for uid 2000.
     */
    private fun lookup(number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val p = ProcessBuilder("/system/bin/content", "query", "--uri", uri.toString(),
            "--projection", ContactsContract.PhoneLookup.DISPLAY_NAME).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        out.lineSequence().firstOrNull { it.startsWith("Row: ") }
            ?.substringAfter("${ContactsContract.PhoneLookup.DISPLAY_NAME}=", "")?.trim()?.ifEmpty { null }
    } catch (e: IOException) {
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
        stopScan()
        sendState()
        // "ended" may wait behind audio the link still holds, and closing
        // drops what's unsent: the Mac closes the link once it has it
        // (linkLost), or this gives up after a while.
        main.postDelayed({ exit() }, END_WAIT_MS)
    }

    private fun exit() {
        sender.execute {
            link.getAndSet(null)?.close()
            println("call ended")
            System.out.flush()
            System.exit(0)
        }
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
            // What AudioManager's getCallDownlinkExtractionAudioRecord and
            // getCallUplinkInjectionAudioTrack build, but on the shell's
            // context: without one the record names the system's package
            // ("android"), which AudioFlinger refuses for uid 2000. Their
            // attributes are what route them to the call (telephony-rx and
            // telephony-tx): without, the record takes the phone's
            // microphone and the track plays on its speaker.
            val downlink = AudioAttributes.Builder()
            Hidden.call(downlink, "setInternalCapturePreset", MediaRecorder.AudioSource.VOICE_DOWNLINK)
            val recordBuilder = AudioRecord.Builder().setContext(shell).setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            Hidden.call(recordBuilder, "setAudioAttributes", downlink.build()) // a system API for a record
            Hidden.call(recordBuilder, "setCallRedirectionMode", CALL_REDIRECT_PSTN)
            val record = recordBuilder.build()
            val track = try {
                val uplink = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                Hidden.call(uplink, "setSystemUsage", USAGE_CALL_ASSISTANT)
                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(uplink.build())
                    .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                try {
                    Hidden.call(trackBuilder, "setContext", shell) // API 34
                } catch (e: NoSuchMethodException) {
                    // Older: the track takes the process's identity.
                }
                Hidden.call(trackBuilder, "setCallRedirectionMode", CALL_REDIRECT_PSTN)
                trackBuilder.build()
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

        // Since the last stats(): frames recorded from the call and played
        // into it, and their loudest sample.
        private val recorded = AtomicInteger()
        private val recordedPeak = AtomicInteger()
        private val played = AtomicInteger()
        private val playedPeak = AtomicInteger()
        private val trimmed = AtomicInteger()
        private val gaps = AtomicInteger()
        @Volatile var target = MIN_TARGET

        /** `pcm` (16-bit little-endian) fading from 0.6 to silence. */
        private fun fadeOut(pcm: ByteArray): ByteArray {
            val out = ByteArray(pcm.size)
            val n = pcm.size / 2
            for (i in 0 until n) {
                val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
                val v = (s * 0.6f * (1f - i.toFloat() / n)).toInt()
                out[2 * i] = v.toByte()
                out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }

        /** Recorded, its peak, played, its peak (0..32768), frames queued for the call, frames shed; and starts over. */
        fun stats(): IntArray = intArrayOf(
            recorded.getAndSet(0), recordedPeak.getAndSet(0),
            played.getAndSet(0), playedPeak.getAndSet(0), incoming.size, trimmed.getAndSet(0), gaps.getAndSet(0), target,
        )

        private fun peak(pcm: ByteArray, length: Int): Int {
            var p = 0
            var i = 0
            while (i + 1 < length) {
                val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                p = maxOf(p, kotlin.math.abs(s))
                i += 2
            }
            return p
        }

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
                    if (n > 0) {
                        recorded.incrementAndGet()
                        recordedPeak.accumulateAndGet(peak(buf, n)) { a, b -> maxOf(a, b) }
                        link.get()?.sendAudio(buf, n)
                    }
                }
            }, "call-downlink")
            threads += Thread({
                // An adaptive jitter buffer: after a gap it waits for
                // `target` frames before playing again, keeping the call's
                // track fed meanwhile (the last frame fading out, then
                // silence) so it doesn't stop; a gap raises the target, 10 s
                // without one lowers it. Over a second it sheds frames that
                // stay above the target, the rest of a burst.
                var least = Int.MAX_VALUE
                var seen = 0
                var calm = 0
                var buffering = true
                var last: ByteArray? = null
                val silence = ByteArray(CallLink.FRAME_BYTES)
                while (running) {
                    if (buffering) {
                        if (incoming.size < target) {
                            val fill = last?.let { fadeOut(it) } ?: silence
                            last = null
                            // Blocks for about a frame once the track is full: the pace.
                            if (track.write(fill, 0, fill.size, AudioTrack.WRITE_BLOCKING) < 0) return@Thread
                            continue
                        }
                        buffering = false
                    }
                    val frame = try {
                        incoming.poll(30, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    if (frame == null) {
                        gaps.incrementAndGet()
                        buffering = true
                        target = minOf(MAX_TARGET, target + 1)
                        calm = 0
                        continue
                    }
                    least = minOf(least, incoming.size)
                    if (++seen == WINDOW_FRAMES) {
                        repeat(least - (target - 1)) { if (incoming.poll() != null) trimmed.incrementAndGet() }
                        least = Int.MAX_VALUE
                        seen = 0
                    }
                    if (++calm >= CALM_FRAMES) {
                        calm = 0
                        target = maxOf(MIN_TARGET, target - 1)
                    }
                    if (track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING) < 0) return@Thread
                    last = frame
                    played.incrementAndGet()
                    playedPeak.accumulateAndGet(peak(frame, frame.size)) { a, b -> maxOf(a, b) }
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

    /**
     * A connection to the Mac, over TCP or Bluetooth: frames, encrypted once
     * the handshake set the keys (CallLink.Crypt).
     */
    private class Link(input: InputStream, output: OutputStream, private val closer: Closeable, val bluetooth: Boolean, val peer: String) {
        val input = DataInputStream(BufferedInputStream(input))
        private val out = DataOutputStream(BufferedOutputStream(output))
        @Volatile var authenticated = false
        private var sealer: CallLink.Crypt? = null
        private var opener: CallLink.Crypt? = null
        private val encoder = CallLink.Adpcm.Encoder()
        val pace = Pace()
        /** Audio frames from the Mac on this link (the reader's). */
        var audioReceived = 0

        /** After the handshake: every frame from here on is encrypted. */
        fun keys(toMac: ByteArray, toPhone: ByteArray) {
            synchronized(out) { sealer = CallLink.Crypt(toMac) }
            opener = CallLink.Crypt(toPhone)
            authenticated = true
        }

        fun send(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
            try {
                synchronized(out) {
                    val s = sealer
                    if (s == null) {
                        CallLink.write(out, type, payload, offset, length)
                    } else {
                        CallLink.write(out, type, s.seal(type, payload, offset, length))
                    }
                    out.flush()
                }
            } catch (e: IOException) {
                close() // the reader sees it and reconnects
            }
        }

        /** 20 ms of the downlink: ADPCM over Bluetooth, PCM over TCP. */
        fun sendAudio(pcm: ByteArray, length: Int) {
            if (bluetooth && length == CallLink.FRAME_BYTES) {
                // One writer, the downlink thread; encoded even when paced
                // out, so the encoder follows the signal.
                val adpcm = encoder.encode(pcm)
                if (pace.shouldSend()) send(CallLink.ADPCM, adpcm)
            } else if (pace.shouldSend()) {
                send(CallLink.AUDIO, pcm, 0, length)
            }
        }

        /** The next frame, decrypted. Throws IOException at the end or on a bad frame. */
        fun read(): CallLink.Frame {
            val f = CallLink.read(input)
            val o = opener ?: return f
            return CallLink.Frame(f.type, o.open(f.type, f.payload))
        }

        fun close() {
            try {
                closer.close()
            } catch (e: IOException) {
                // closed
            }
        }

        companion object {
            fun tcp(socket: Socket) = Link(socket.getInputStream(), socket.getOutputStream(), socket, false, socket.inetAddress.hostAddress ?: "?")
        }
    }

    /** The link `l` is gone: unless another comes up soon, the call's audio goes back to the phone. */
    private fun linkLost(why: String) {
        Log.i(TAG, why)
        if (ended) {
            exit()
            return
        }
        main.postDelayed({ if (link.get() == null) lost("no link to the Mac") }, LINK_GRACE_MS)
    }

    /** TCP, while there is no Bluetooth link. */
    private fun connectLoop() {
        while (!ended) {
            if (link.get()?.bluetooth == true) {
                Thread.sleep(RETRY_MS)
                continue
            }
            var l: Link? = null
            try {
                l = connect()
                // A Bluetooth link came up meanwhile: it stays.
                if (!link.compareAndSet(null, l)) {
                    l.close()
                    l = null
                    continue
                }
                Log.i(TAG, "connected to the Mac (${l.peer})")
                main.post { sendState() }
                read(l)
            } catch (e: IOException) {
                if (!ended) Log.i(TAG, "the Mac: ${e.message}")
            } finally {
                l?.close()
                if (l != null && link.compareAndSet(l, null)) main.post { linkLost("the connection to the Mac broke") }
            }
            if (!ended) Thread.sleep(RETRY_MS)
        }
    }

    // --- Bluetooth ---

    @Volatile private var scanner: BluetoothLeScanner? = null
    private var scanning: ScanCallback? = null

    /** Macs found by the scan: a device and its channel's PSM. */
    private val found = LinkedBlockingQueue<Pair<BluetoothDevice, Int>>()

    /**
     * Scans for the Mac's advertisement (docs/calls.md) while there's no
     * Bluetooth link and connects to its L2CAP channel; a Mac that passes the
     * handshake becomes the link, replacing a TCP one.
     */
    private fun bluetoothLoop() {
        val adapter: BluetoothAdapter? = try {
            Hidden.manager(BluetoothManager::class.java, shell).adapter
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bluetooth: $e")
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "Bluetooth is off: no Bluetooth link")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "Bluetooth: no LE scanner")
            return
        }
        while (!ended) {
            main.post { startScan() }
            val (device, psm) = found.poll(1, TimeUnit.SECONDS) ?: continue
            main.post { stopScan() }
            Log.i(TAG, "Bluetooth: found the Mac (PSM $psm)")
            val socket = try {
                device.createInsecureL2capChannel(psm)
            } catch (e: IOException) {
                Log.i(TAG, "Bluetooth: $e")
                Thread.sleep(RETRY_MS)
                continue
            }
            val l = Link(socket.inputStream, socket.outputStream, socket, true, "Bluetooth")
            // BluetoothSocket has no timeouts: close it if connecting or the handshake stalls.
            main.postDelayed({ if (!l.authenticated) l.close() }, 3L * CONNECT_TIMEOUT_MS)
            try {
                socket.connect()
                handshake(l)
                link.getAndSet(l)?.close()
                Log.i(TAG, "connected to the Mac over Bluetooth")
                main.post { sendState() }
                read(l)
            } catch (e: IOException) {
                if (!ended) Log.i(TAG, "Bluetooth link: ${e.message}")
            } finally {
                l.close()
                if (link.compareAndSet(l, null)) main.post { linkLost("the Bluetooth link to the Mac broke") }
            }
            found.clear()
            if (!ended) Thread.sleep(RETRY_MS)
        }
    }

    /** Scans for the Mac's prefix (in the controller), unless it already does. On the main thread. */
    private fun startScan() {
        val sc = scanner ?: return
        if (scanning != null || ended) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val window = HotspotToken.window(System.currentTimeMillis())
                for (uuid in result.scanRecord?.serviceUuids.orEmpty()) {
                    val psm = CallLink.blePsm(key, uuid.uuid, window) ?: continue
                    found.offer(result.device to psm)
                    return
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Bluetooth: scan failed ($errorCode)")
                if (scanning === this) scanning = null
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CallLink.BLE_FILTER_UUID), ParcelUuid(CallLink.BLE_FILTER_MASK))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            sc.startScan(listOf(filter), settings, callback)
            scanning = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bluetooth: scan: $e")
        }
    }

    private fun stopScan() {
        val callback = scanning ?: return
        scanning = null
        try {
            scanner?.stopScan(callback)
        } catch (e: RuntimeException) {
            // Bluetooth went off
        }
    }

    /**
     * Follows the hotspot's clients for the whole call. The tethering module
     * tells them (TetheredClient, a system API, through onClientsChanged)
     * only to a caller with NETWORK_SETTINGS, which the shell holds, so the
     * callback is a proxy that also takes that method.
     */
    private fun followHotspot() {
        val tm = try {
            HotspotTool.tetheringManager(context)
        } catch (e: RuntimeException) {
            null
        } ?: return
        val type = TetheringManager.TetheringEventCallback::class.java
        val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "onClientsChanged" -> {
                    try {
                        hotspotClients = (args?.getOrNull(0) as? Collection<*>).orEmpty().filterNotNull().flatMap { client ->
                            if ((Hidden.call(client, "getTetheringType") as Number?)?.toInt() != HotspotTool.TETHERING_WIFI) {
                                emptyList()
                            } else {
                                (Hidden.call(client, "getAddresses") as List<*>?).orEmpty().filterNotNull().mapNotNull { a ->
                                    ((Hidden.call(a, "getAddress") as android.net.LinkAddress?)?.address as? Inet4Address)?.hostAddress
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "hotspot clients: $e")
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "CallBridge.hotspot"
                else -> null
            }
        } as TetheringManager.TetheringEventCallback
        try {
            tm.registerTetheringEventCallback(modes, callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "hotspot clients: $e")
        }
    }

    /** At most this many devices on the phone's networks are tried. */
    private const val MAX_NEIGHBOURS = 16

    /**
     * Where the Mac may be: its NetBird name first, then the hotspot's
     * clients, then the IPv4 neighbours on the phone's hotspot and Wi-Fi
     * (complete ARP entries; the shell may read /proc/net/arp).
     */
    private fun targets(): List<InetSocketAddress> {
        val neighbours = try {
            File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
                val f = line.trim().split(Regex("\\s+"))
                val flags = f.getOrNull(2)?.removePrefix("0x")?.toIntOrNull(16) ?: 0
                val device = f.getOrNull(5) ?: ""
                val local = device.startsWith("ap_") || device.startsWith("swlan") || device.startsWith("wlan")
                if (flags and 0x2 == 0 || !local) null else f[0]
            }.distinct().take(MAX_NEIGHBOURS)
        } catch (e: IOException) {
            emptyList()
        }
        return listOf(InetSocketAddress(host, port)) + (hotspotClients + neighbours).distinct().take(MAX_NEIGHBOURS).mapNotNull { ip ->
            (InetAddress.getByName(ip) as? Inet4Address)?.let { InetSocketAddress(it, port) }
        }
    }

    /**
     * Connects to every target at once and returns the first connection
     * whose handshake passes; the others close, unauthenticated. Throws
     * IOException with the NetBird target's error when none does.
     */
    private fun connect(): Link {
        val targets = targets()
        val results = LinkedBlockingQueue<Pair<InetSocketAddress, Any>>() // a Socket, or an IOException
        val won = java.util.concurrent.atomic.AtomicBoolean(false)
        for (t in targets) {
            Thread({
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(t, CONNECT_TIMEOUT_MS)
                    if (won.get()) socket.close() else results.put(t to socket)
                } catch (e: IOException) {
                    socket.close()
                    results.put(t to e)
                }
            }, "call-connect").start()
        }
        var error: IOException? = null
        repeat(targets.size) {
            val (target, result) = results.poll(CONNECT_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS)
                ?: return@repeat
            if (result is IOException) {
                if (target == targets[0] || error == null) error = result
                return@repeat
            }
            val socket = result as Socket
            if (won.get()) {
                socket.close()
                return@repeat
            }
            val l = Link.tcp(socket)
            try {
                socket.soTimeout = CONNECT_TIMEOUT_MS
                handshake(l)
                socket.soTimeout = 0
            } catch (e: IOException) {
                l.close()
                if (target == targets[0] || error == null) error = e
                return@repeat
            }
            won.set(true)
            // Connections that come later close as they come.
            Thread({
                while (true) {
                    val (_, late) = results.poll(CONNECT_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS) ?: break
                    (late as? Socket)?.close()
                }
            }, "call-connect-rest").start()
            return l
        }
        throw error ?: IOException("no answer")
    }

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
        val (toMac, toPhone) = CallLink.sessionKeys(k, hello.payload, np)
        l.keys(toMac, toPhone)
    }

    /** The Mac's ping, echoed with the audio's counters since the last (docs/calls.md). */
    private fun pong(l: Link, ping: JSONObject) {
        val reply = JSONObject().put("type", "pong").put("t", ping.opt("t")).put("skipped", l.pace.takeSkipped())
        pipe?.stats()?.let { s ->
            reply.put("recorded", s[0]).put("recordedPeak", s[1]).put("played", s[2]).put("playedPeak", s[3]).put("queued", s[4]).put("trimmed", s[5]).put("gaps", s[6]).put("target", s[7])
                .put("mode", audio.mode)
        }
        val bytes = reply.toString().toByteArray(Charsets.UTF_8)
        sender.execute { l.send(CallLink.MESSAGE, bytes) }
    }

    /** An audio frame from the Mac; every 5th gets an ack (Pace). */
    private fun heard(l: Link, pcm: ByteArray) {
        val n = ++l.audioReceived
        if (n % Pace.ACK_EVERY == 0) {
            val ack = JSONObject().put("type", "ack").put("n", n).toString().toByteArray(Charsets.UTF_8)
            sender.execute { l.send(CallLink.MESSAGE, ack) }
        }
        pipe?.offer(pcm)
    }

    /** Until the link closes: after the call ends too, until the Mac has "ended" and closes it (finish). */
    private fun read(l: Link) {
        while (true) {
            val frame = l.read()
            when (frame.type) {
                CallLink.AUDIO -> heard(l, frame.payload)
                CallLink.ADPCM -> heard(l, CallLink.Adpcm.decode(frame.payload))
                CallLink.MESSAGE -> {
                    val message = try {
                        JSONObject(String(frame.payload, Charsets.UTF_8))
                    } catch (e: JSONException) {
                        Log.w(TAG, "a message that isn't JSON")
                        continue
                    }
                    // Answered here, not on the main thread: the Mac times the round trip.
                    when (message.optString("type")) {
                        "ping" -> pong(l, message)
                        "ack" -> l.pace.acked(message.optInt("n"))
                        else -> main.post { command(message) }
                    }
                }
                else -> Log.w(TAG, "frame type ${frame.type} after the handshake")
            }
        }
    }

    /**
     * Paces a link's audio by its delay (docs/calls.md, "Acks"): the
     * receiver acks every 5th audio frame with the count it has, and the
     * sender times each ack against when that frame went out. The least of
     * those times over the last 5–10 s is the path's own delay; more than
     * BEHIND_MS over it for SUSTAINED acks means frames wait in buffers on
     * the way, and the sender then leaves out one frame in four until it
     * drains.
     */
    class Pace {
        private val sentAt = LongArray(256)
        private var sent = 0
        private var least = Long.MAX_VALUE
        private var leastBefore = Long.MAX_VALUE
        private var since = 0L
        private var late = 0
        private var turn = 0
        private var skipped = 0

        @Synchronized fun shouldSend(): Boolean {
            // Backed up: one frame in four stays home until it drains.
            if (late >= SUSTAINED) {
                if (++turn % 4 == 0) {
                    skipped++
                    return false
                }
            }
            sentAt[sent and 255] = SystemClock.uptimeMillis()
            sent++
            return true
        }

        @Synchronized fun acked(n: Int) {
            if (n <= 0 || n > sent || sent - n >= 256) return
            val now = SystemClock.uptimeMillis()
            val age = now - sentAt[(n - 1) and 255]
            if (now - since > 5_000) {
                leastBefore = least
                least = Long.MAX_VALUE
                since = now
            }
            least = minOf(least, age)
            late = if (age - minOf(least, leastBefore) > BEHIND_MS) late + 1 else 0
        }

        @Synchronized fun takeSkipped(): Int = skipped.also { skipped = 0 }

        companion object {
            const val ACK_EVERY = 5
            /** Late by this much, for SUSTAINED acks in a row (300 ms), is a backlog, not the link's unevenness. */
            const val BEHIND_MS = 250L
            const val SUSTAINED = 3
        }
    }
}
