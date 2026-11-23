// The phone's call link to the Mac (docs/calls.md, v1): framing, the key and
// the handshake's MACs. Pure Kotlin on the JVM, so the tests
// (tests/CallLinkTest.kt) check it against the vector Prism for macOS checks.

package at.quickme.prism.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CallLink {
    const val PORT = 47470
    const val LABEL = "prism-call/v1"

    const val HELLO = 1
    const val AUTH = 2
    const val MESSAGE = 3
    const val AUDIO = 4

    const val NONCE_BYTES = 16
    const val MAC_BYTES = 32

    /** 16 kHz mono 16-bit PCM in 20 ms frames. */
    const val SAMPLE_RATE = 16_000
    const val FRAME_BYTES = SAMPLE_RATE / 50 * 2

    /** A frame's payload is at most this (a JSON message; audio is 640). */
    const val MAX_PAYLOAD = 16 * 1024

    /** K = HMAC-SHA256(hotspot key, "prism-call/v1"). */
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
}
