// The hotspot request's token (core/HotspotToken.kt, docs/hotspot.md) on a
// plain JVM. The vector (key 00..1f, window 1000) is printed so Prism for
// macOS can check its own against it.
//
//   java -cp <core classes>:kotlin-stdlib.jar HotspotTestKt

import at.quickme.prism.core.HotspotToken
import java.util.UUID
import kotlin.system.exitProcess

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

fun main() {
    val key = ByteArray(32) { it.toByte() }
    val hex = (0 until 32).joinToString("") { "%02x".format(it) }
    check(HotspotToken.parseKey(hex)?.contentEquals(key) == true, "key from 64 hex characters")
    check(HotspotToken.parseKey(hex.uppercase())?.contentEquals(key) == true, "upper-case hex too")
    check(HotspotToken.parseKey(hex.drop(2)) == null && HotspotToken.parseKey(hex.dropLast(1) + "g") == null, "bad keys refused")

    val tag = HotspotToken.tag(key, 1000)
    println("vector: key 00..1f, window 1000 -> tag " + HotspotToken.hex(tag) + ", uuid " + HotspotToken.uuid(key, 1000))
    check(HotspotToken.hex(tag) == EXPECTED_TAG, "the shared vector")
    check(tag.size == 12, "12 bytes")

    val uuid = HotspotToken.uuid(key, 1000)
    check(uuid.toString().startsWith("515228f6-"), "prefix 51 52 28 f6")
    check(HotspotToken.bytes(uuid).copyOfRange(4, 16).contentEquals(tag), "tag in bytes 4..15")
    check(HotspotToken.window(30_000_000L) == 1000L && HotspotToken.window(30_029_999L) == 1000L &&
        HotspotToken.window(30_030_000L) == 1001L, "30 s windows")

    // Windows w-2 .. w+1 around the phone's clock.
    val now = 30_000_000L // window 1000
    check(HotspotToken.accepts(key, HotspotToken.uuid(key, 1000), now), "this window")
    check(HotspotToken.accepts(key, HotspotToken.uuid(key, 998), now), "two windows back")
    check(HotspotToken.accepts(key, HotspotToken.uuid(key, 1001), now), "one window ahead")
    check(!HotspotToken.accepts(key, HotspotToken.uuid(key, 997), now), "three windows back refused")
    check(!HotspotToken.accepts(key, HotspotToken.uuid(key, 1002), now), "two windows ahead refused")
    val other = ByteArray(32) { (it + 1).toByte() }
    check(!HotspotToken.accepts(other, uuid, now), "another key refused")
    val noPrefix = UUID(uuid.mostSignificantBits xor (1L shl 63), uuid.leastSignificantBits)
    check(!HotspotToken.accepts(key, noPrefix, now), "without the prefix refused")
    // The release: its own label, so a request is never a release and the other way round.
    val releaseTag = HotspotToken.tag(key, 1000, HotspotToken.RELEASE)
    println("release vector: key 00..1f, window 1000 -> tag " + HotspotToken.hex(releaseTag))
    check(HotspotToken.hex(releaseTag) == EXPECTED_RELEASE_TAG, "the shared release vector")
    val release = HotspotToken.uuid(key, 1000, HotspotToken.RELEASE)
    check(HotspotToken.accepts(key, release, now, HotspotToken.RELEASE), "a release")
    check(!HotspotToken.accepts(key, release, now), "a release is no request")
    check(!HotspotToken.accepts(key, uuid, now, HotspotToken.RELEASE), "a request is no release")
    check(HotspotToken.hasPrefix(HotspotToken.FILTER_UUID) && HotspotToken.FILTER_MASK.toString() == "ffffffff-0000-0000-0000-000000000000",
        "filter and mask")

    println(if (failed == 0) "all hotspot checks passed" else "$failed hotspot check(s) failed")
    exitProcess(if (failed == 0) 0 else 1)
}

/** HMAC-SHA256(00..1f, "prism-hotspot/v1" || u64be(1000))[0..12], computed with Python's hmac. */
private const val EXPECTED_TAG = "a5afab36970508191b7f7cde"

/** The same with "prism-hotspot-release/v1", computed with Python's hmac. */
private const val EXPECTED_RELEASE_TAG = "d5027623f660c1f921fe0527"
