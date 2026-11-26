// The phone's call link to the Mac (docs/calls.md, v2): framing, the key, the
// handshake's MACs, the frames' encryption, the Bluetooth advertisement's
// UUID and the ADPCM codec audio takes over Bluetooth. Pure Kotlin on the JVM, so the tests
// (tests/CallLinkTest.kt) check it against the vector Prism for macOS checks.

package at.quickme.prism.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CallLink {
    const val PORT = 47470
    const val LABEL = "prism-call/v2"

    /** The Mac's Bluetooth advertisement: its label, and the UUID's prefix (bytes 0..3). */
    const val BLE_LABEL = "prism-call-ble/v1"
    const val BLE_PREFIX = 0x515228f7L

    /** The scan filter: the prefix, and its mask. */
    @JvmField
    val BLE_FILTER_UUID: UUID = UUID(BLE_PREFIX shl 32, 0L)

    @JvmField
    val BLE_FILTER_MASK: UUID = UUID(0xffffffffL shl 32, 0L)

    const val HELLO = 1
    const val AUTH = 2
    const val MESSAGE = 3
    const val AUDIO = 4
    const val ADPCM = 5

    /** AES-GCM's tag, after every encrypted payload. */
    const val TAG_BYTES = 16

    const val NONCE_BYTES = 16
    const val MAC_BYTES = 32

    /** 16 kHz mono 16-bit PCM in 20 ms frames. */
    const val SAMPLE_RATE = 16_000
    const val FRAME_BYTES = SAMPLE_RATE / 50 * 2

    /** A frame's payload is at most this (a JSON message; audio is 640). */
    const val MAX_PAYLOAD = 16 * 1024

    /** K = HMAC-SHA256(hotspot key, "prism-call/v2"). */
    @JvmStatic
    fun key(hotspotKey: ByteArray): ByteArray = hmac(hotspotKey, LABEL.toByteArray(Charsets.US_ASCII))

    /** The phone's proof: HMAC(K, "phone" || nm || np). */
    @JvmStatic
    fun phoneMac(k: ByteArray, macNonce: ByteArray, phoneNonce: ByteArray): ByteArray =
        hmac(k, "phone".toByteArray(Charsets.US_ASCII), macNonce, phoneNonce)

    /** The Mac's proof: HMAC(K, "mac" || np || nm). */
    @JvmStatic
    fun macMac(k: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray): ByteArray =
        hmac(k, "mac".toByteArray(Charsets.US_ASCII), phoneNonce, macNonce)

    /** The direction keys: phone → Mac, Mac → phone. */
    @JvmStatic
    fun sessionKeys(k: ByteArray, macNonce: ByteArray, phoneNonce: ByteArray): Pair<ByteArray, ByteArray> =
        hmac(k, "phone->mac".toByteArray(Charsets.US_ASCII), macNonce, phoneNonce) to
            hmac(k, "mac->phone".toByteArray(Charsets.US_ASCII), macNonce, phoneNonce)

    /**
     * The service UUID the Mac advertises for its L2CAP channel `psm` in
     * `window` (HotspotToken.window): the prefix, the PSM (u16 big-endian),
     * then HMAC(K, label || u64be(window) || u16be(psm)), its first 10 bytes.
     */
    @JvmStatic
    fun bleUuid(k: ByteArray, window: Long, psm: Int): UUID {
        val tag = hmac(
            k, BLE_LABEL.toByteArray(Charsets.US_ASCII), ByteBuffer.allocate(8).putLong(window).array(),
            byteArrayOf((psm shr 8).toByte(), psm.toByte()),
        )
        val b = ByteBuffer.allocate(16).putInt(BLE_PREFIX.toInt()).putShort(psm.toShort()).put(tag, 0, 10)
        b.flip()
        return UUID(b.long, b.long)
    }

    /** The PSM in a UUID the Mac advertised in one of the windows `window-2 .. window+1`, or null. */
    @JvmStatic
    fun blePsm(k: ByteArray, uuid: UUID, window: Long): Int? {
        if (uuid.mostSignificantBits ushr 32 != BLE_PREFIX) return null
        val psm = ((uuid.mostSignificantBits ushr 16) and 0xffff).toInt()
        for (w in window - 2..window + 1) {
            if (equal(HotspotToken.bytes(bleUuid(k, w, psm)), HotspotToken.bytes(uuid))) return psm
        }
        return null
    }

    /**
     * One direction's encryption: AES-256-GCM, the nonce 4 zero bytes and
     * the frame's number (u64 big-endian, from 0), the type byte as
     * additional data. Not thread-safe: one writer, or one reader.
     */
    class Crypt(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var n = 0L

        private fun init(mode: Int, type: Int) {
            val nonce = ByteBuffer.allocate(12).putInt(0).putLong(n++).array()
            cipher.init(mode, spec, GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(byteArrayOf(type.toByte()))
        }

        fun seal(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
            init(Cipher.ENCRYPT_MODE, type)
            return cipher.doFinal(payload, offset, length)
        }

        /** Throws IOException for a frame that doesn't decrypt. */
        fun open(type: Int, payload: ByteArray): ByteArray = try {
            init(Cipher.DECRYPT_MODE, type)
            cipher.doFinal(payload)
        } catch (e: AEADBadTagException) {
            throw IOException("a frame that doesn't decrypt")
        } catch (e: GeneralSecurityException) {
            throw IOException("a frame that doesn't decrypt: $e")
        }
    }

    @JvmStatic
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    class Frame(val type: Int, val payload: ByteArray)

    /** Writes one frame; callers serialize writers. */
    @JvmStatic
    fun write(out: DataOutputStream, type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        require(length <= MAX_PAYLOAD) { "frame too long: $length" }
        out.writeByte(type)
        out.writeShort(length)
        out.write(payload, offset, length)
    }

    /** Reads one frame; throws IOException at the end of the stream or on a bad one. */
    @JvmStatic
    fun read(input: DataInputStream): Frame {
        val type = input.readUnsignedByte()
        val length = input.readUnsignedShort()
        if (length > MAX_PAYLOAD) throw IOException("frame too long: $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, payload)
    }

    @JvmStatic
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * IMA ADPCM, 20 ms (320 samples) a frame: the state before its first
     * sample (predictor i16 little-endian, step index, a zero byte), then a
     * 4-bit code a sample, the even one in the low nibble.
     */
    object Adpcm {
        const val FRAME_BYTES = 4 + SAMPLE_RATE / 50 / 2

        private val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88,
            97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660,
            4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818,
            18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

        /** One direction's encoder; its state carries over from frame to frame. */
        class Encoder {
            private var predictor = 0
            private var index = 0

            /** 640 bytes of 16-bit little-endian PCM to one frame. */
            fun encode(pcm: ByteArray, offset: Int = 0): ByteArray {
                val out = ByteArray(FRAME_BYTES)
                out[0] = predictor.toByte()
                out[1] = (predictor shr 8).toByte()
                out[2] = index.toByte()
                for (i in 0 until FRAME_BYTES * 2 - 8) {
                    val s = (pcm[offset + 2 * i].toInt() and 0xff) or (pcm[offset + 2 * i + 1].toInt() shl 8)
                    var step = STEPS[index]
                    var diff = s - predictor
                    var code = 0
                    if (diff < 0) {
                        code = 8
                        diff = -diff
                    }
                    var delta = step shr 3
                    if (diff >= step) {
                        code = code or 4; diff -= step; delta += step
                    }
                    step = step shr 1
                    if (diff >= step) {
                        code = code or 2; diff -= step; delta += step
                    }
                    step = step shr 1
                    if (diff >= step) {
                        code = code or 1; delta += step
                    }
                    predictor = (if (code and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
                    index = (index + INDEX[code and 7]).coerceIn(0, 88)
                    val b = 4 + i / 2
                    out[b] = if (i % 2 == 0) code.toByte() else (out[b].toInt() or (code shl 4)).toByte()
                }
                return out
            }
        }

        /** One frame to 640 bytes of 16-bit little-endian PCM; throws IOException for a frame of another size. */
        @JvmStatic
        fun decode(frame: ByteArray): ByteArray {
            if (frame.size != FRAME_BYTES) throw IOException("an ADPCM frame of ${frame.size} bytes")
            var predictor = ((frame[0].toInt() and 0xff) or (frame[1].toInt() shl 8)).toShort().toInt()
            var index = (frame[2].toInt() and 0xff).coerceIn(0, 88)
            val out = ByteArray((FRAME_BYTES - 4) * 4)
            for (i in 0 until (FRAME_BYTES - 4) * 2) {
                val b = frame[4 + i / 2].toInt()
                val code = if (i % 2 == 0) b and 0xf else (b shr 4) and 0xf
                val step = STEPS[index]
                var delta = step shr 3
                if (code and 4 != 0) delta += step
                if (code and 2 != 0) delta += step shr 1
                if (code and 1 != 0) delta += step shr 2
                predictor = (if (code and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
                index = (index + INDEX[code and 7]).coerceIn(0, 88)
                out[2 * i] = predictor.toByte()
                out[2 * i + 1] = (predictor shr 8).toByte()
            }
            return out
        }
    }
}
