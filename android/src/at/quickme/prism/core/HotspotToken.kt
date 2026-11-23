// The Mac's request for the hotspot over Bluetooth LE (docs/hotspot.md, v1),
// and its release when it leaves for a proven Wi-Fi: one 128-bit service
// UUID, a fixed 4-byte prefix the scan filter matches in hardware, then 12
// bytes of HMAC-SHA256 over a label and the current 30 s window with the key
// both sides share (setting hotspot_key). Pure Kotlin on the JVM, so
// the tests (tests/HotspotTest.kt) check it against the Mac's vector.

package at.quickme.prism.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HotspotToken {
    /** Bytes 0..3 of the UUID: what the scan filter's mask covers. */
    @JvmField
    val PREFIX = byteArrayOf(0x51, 0x52, 0x28, 0xf6.toByte())

    const val WINDOW_SECONDS = 30L
    const val TAG_BYTES = 12
    /** The request's label: switch the hotspot on. */
    const val REQUEST = "prism-hotspot/v1"

    /** The release's label: the Mac left for a proven Wi-Fi, switch it off. */
    const val RELEASE = "prism-hotspot-release/v1"

    /** The filter's UUID: the prefix, the rest zero. */
    @JvmField
    val FILTER_UUID: UUID = UUID(0x515228f6L shl 32, 0L)

    /** The filter's mask: the prefix only, so every window's UUID passes in hardware. */
    @JvmField
    val FILTER_MASK: UUID = UUID(0xffffffffL shl 32, 0L)

    /** The 30 s window of a time in Unix milliseconds. */
    @JvmStatic
    fun window(unixMs: Long): Long = Math.floorDiv(Math.floorDiv(unixMs, 1000L), WINDOW_SECONDS)

    /** A key from its 64 hex characters, or null if it isn't one. */
    @JvmStatic
    fun parseKey(hex: String): ByteArray? {
        val s = hex.trim()
        if (s.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** HMAC-SHA256(key, label || u64be(window)), its first 12 bytes. */
    @JvmStatic
    @JvmOverloads
    fun tag(key: ByteArray, window: Long, label: String = REQUEST): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        mac.update(ByteBuffer.allocate(8).putLong(window).array())
        return mac.doFinal().copyOf(TAG_BYTES)
    }

    /** The UUID the Mac advertises in `window`. */
    @JvmStatic
    @JvmOverloads
    fun uuid(key: ByteArray, window: Long, label: String = REQUEST): UUID {
        val b = ByteBuffer.wrap(PREFIX + tag(key, window, label))
        return UUID(b.long, b.long)
    }

    /** The UUID's 16 bytes in the advertisement's order (most significant first). */
    @JvmStatic
    fun bytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    /** Whether the UUID carries the prefix at all (anything else is not ours). */
    @JvmStatic
    fun hasPrefix(uuid: UUID): Boolean = (uuid.mostSignificantBits ushr 32) == 0x515228f6L

    /**
     * Whether `uuid` is a request (or what `label` says) made with `key` in one of the windows
     * w-2 .. w+1 around `nowUnixMs`: the clocks may differ by a minute, and
     * the Mac may have changed windows just before the phone saw it.
     */
    @JvmStatic
    @JvmOverloads
    fun accepts(key: ByteArray, uuid: UUID, nowUnixMs: Long, label: String = REQUEST): Boolean {
        if (!hasPrefix(uuid)) return false
        val got = bytes(uuid).copyOfRange(4, 16)
        val w = window(nowUnixMs)
        var ok = false
        // Every window, compared in constant time: the answer takes no hint along.
        for (d in -2L..1L) ok = MessageDigest.isEqual(got, tag(key, w + d, label)) or ok
        return ok
    }

    @JvmStatic
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
