// The call link's key, MACs, framing, encryption, Bluetooth UUID and ADPCM
// (core/CallLink.kt, docs/calls.md v2) on a plain JVM. The vector (hotspot key 00..1f, nm 16 x a1, np 16 x b2) is
// printed so Prism for macOS can check its own against it.
//
//   java -cp <core classes>:kotlin-stdlib.jar CallLinkTestKt

import at.quickme.prism.core.CallLink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.system.exitProcess

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

fun main() {
    val hotspotKey = ByteArray(32) { it.toByte() }
    val nm = ByteArray(16) { 0xa1.toByte() }
    val np = ByteArray(16) { 0xb2.toByte() }
    val k = CallLink.key(hotspotKey)
    val phone = CallLink.phoneMac(k, nm, np)
    val mac = CallLink.macMac(k, np, nm)
    println("vector: K ${CallLink.hex(k)}")
    println("vector: phone ${CallLink.hex(phone)}")
    println("vector: mac ${CallLink.hex(mac)}")
    check(CallLink.hex(k) == EXPECTED_K, "the shared vector: K")
    check(CallLink.hex(phone) == EXPECTED_PHONE, "the shared vector: the phone's MAC")
    check(CallLink.hex(mac) == EXPECTED_MAC, "the shared vector: the Mac's MAC")
    check(!CallLink.equal(phone, mac), "the two proofs differ")
    check(!CallLink.equal(CallLink.phoneMac(k, np, nm), phone), "nonces in order")

    // The direction keys and the first encrypted frames.
    val (toMac, toPhone) = CallLink.sessionKeys(k, nm, np)
    check(CallLink.hex(toMac) == EXPECTED_TO_MAC, "the shared vector: the phone → Mac key")
    check(CallLink.hex(toPhone) == EXPECTED_TO_PHONE, "the shared vector: the Mac → phone key")
    val answer = """{"type":"answer"}""".toByteArray()
    val macSide = CallLink.Crypt(toPhone)
    val sealed0 = macSide.seal(CallLink.MESSAGE, answer)
    check(CallLink.hex(sealed0) == EXPECTED_FRAME0, "the shared vector: Mac → phone frame 0")
    val phoneSide = CallLink.Crypt(toMac)
    phoneSide.seal(CallLink.MESSAGE, answer)
    check(CallLink.hex(phoneSide.seal(CallLink.MESSAGE, answer)) == EXPECTED_FRAME1, "the shared vector: phone → Mac frame 1")
    val reader = CallLink.Crypt(toPhone)
    check(reader.open(CallLink.MESSAGE, sealed0).contentEquals(answer), "a frame decrypts")
    val tampered = macSide.seal(CallLink.MESSAGE, answer).also { it[0] = (it[0].toInt() xor 1).toByte() }
    check(fails { reader.open(CallLink.MESSAGE, tampered) }, "a changed frame doesn't decrypt")
    val again = CallLink.Crypt(toPhone)
    again.open(CallLink.MESSAGE, sealed0)
    check(fails { again.open(CallLink.MESSAGE, sealed0) }, "a replayed frame doesn't decrypt")
    check(fails { CallLink.Crypt(toPhone).open(CallLink.AUDIO, sealed0) }, "another type doesn't decrypt")

    // The Mac's Bluetooth advertisement: its UUID, and the phone's check.
    val uuid = CallLink.bleUuid(k, 59_999_999L, 0x80)
    println("vector: ble uuid $uuid")
    check(uuid.toString() == EXPECTED_UUID, "the shared vector: the Bluetooth UUID")
    check(CallLink.blePsm(k, uuid, 59_999_999L) == 0x80, "the phone reads the PSM")
    check(CallLink.blePsm(k, uuid, 60_000_001L) == 0x80, "a window behind passes")
    check(CallLink.blePsm(k, uuid, 59_999_998L) == 0x80, "a window ahead passes")
    check(CallLink.blePsm(k, uuid, 60_000_002L) == null, "an old window fails")
    val forged = java.util.UUID(uuid.mostSignificantBits xor 0x10000L, uuid.leastSignificantBits)
    check(CallLink.blePsm(k, forged, 59_999_999L) == null, "another PSM fails")
    check(CallLink.blePsm(hotspotKey, uuid, 59_999_999L) == null, "another key fails")

    // ADPCM: the vector's 440 Hz tone, two frames.
    val tone = ByteArray(1280)
    for (i in 0 until 640) {
        val v = Math.round(8000 * Math.sin(2 * Math.PI * 440 * i / 16000)).toInt()
        tone[2 * i] = v.toByte()
        tone[2 * i + 1] = (v shr 8).toByte()
    }
    val encoder = CallLink.Adpcm.Encoder()
    val f0 = encoder.encode(tone, 0)
    val f1 = encoder.encode(tone, 640)
    check(f0.size == 164 && CallLink.hex(f0).startsWith("0000000070777777378898aa") && sha(f0) == EXPECTED_ADPCM0, "the shared vector: ADPCM frame 0")
    check(CallLink.hex(f1).startsWith("5be132002163433443333212") && sha(f1) == EXPECTED_ADPCM1, "the shared vector: ADPCM frame 1")
    val decoded = CallLink.Adpcm.decode(f1)
    check(sha(decoded) == EXPECTED_DECODED, "the shared vector: ADPCM frame 1 decoded")
    var worst = 0
    for (i in 0 until 320) {
        val a = (decoded[2 * i].toInt() and 0xff) or (decoded[2 * i + 1].toInt() shl 8)
        val b = (tone[640 + 2 * i].toInt() and 0xff) or (tone[640 + 2 * i + 1].toInt() shl 8)
        worst = maxOf(worst, Math.abs(a - b))
    }
    check(worst < 400, "ADPCM follows the tone (worst error $worst)")
    check(fails { CallLink.Adpcm.decode(ByteArray(10)) }, "a short ADPCM frame fails")

    // Frames round-trip, and a stream that ends mid-frame fails.
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
        CallLink.write(it, CallLink.MESSAGE, """{"type":"answer"}""".toByteArray())
        CallLink.write(it, CallLink.AUDIO, ByteArray(CallLink.FRAME_BYTES) { 7 })
    }
    val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    val first = CallLink.read(input)
    check(first.type == CallLink.MESSAGE && String(first.payload) == """{"type":"answer"}""", "a message frame")
    val second = CallLink.read(input)
    check(second.type == CallLink.AUDIO && second.payload.size == 640 && second.payload.all { it == 7.toByte() }, "an audio frame")
    // The message frame is 3 + 17 bytes: cut in the audio frame after it.
    val cut = DataInputStream(ByteArrayInputStream(bytes.toByteArray().copyOf(30)))
    CallLink.read(cut)
    check(
        try {
            CallLink.read(cut); false
        } catch (e: IOException) {
            true
        },
        "a cut frame fails",
    )
    val tooLong = DataInputStream(ByteArrayInputStream(byteArrayOf(3, 0xff.toByte(), 0xff.toByte())))
    check(
        try {
            CallLink.read(tooLong); false
        } catch (e: IOException) {
            true
        },
        "an oversized frame fails",
    )
    check(CallLink.FRAME_BYTES == 640, "20 ms at 16 kHz is 640 bytes")

    if (failed > 0) {
        println("$failed failed")
        exitProcess(1)
    }
}

private fun fails(block: () -> Unit): Boolean = try {
    block(); false
} catch (e: IOException) {
    true
}

private fun sha(b: ByteArray): String = CallLink.hex(MessageDigest.getInstance("SHA-256").digest(b))

private const val EXPECTED_K = "851c91dae3b20db650e63a523064dbf9e605f57b3c7f0e1125b239ed9c0e47f7"
private const val EXPECTED_PHONE = "6d2840f6c2b6cb1bb98a9d9eea1e3580a827eadceea38372c3463c54a60ea3ef"
private const val EXPECTED_MAC = "0b26ccd7c0ed51269b5108d93ec61edf7ff01d2a29849ea842944800e3b263a7"
private const val EXPECTED_TO_MAC = "3c7eca885cb1f3c6ab0dcadbd210d4b07f7bb61e4469ca63319807b53023a43c"
private const val EXPECTED_TO_PHONE = "e217c291c386519a354b1495f052bfa3ee077d449a732034e729b15ed389e0a1"
private const val EXPECTED_FRAME0 = "1460883d130b6181c25fdaf6e9590c9ab3944501a65af93501ef0fa7e62e2e8b85"
private const val EXPECTED_FRAME1 = "82c2303e6af04e6dbef747805e1be4ecdecd0470cdb1f26aac9213a56f64603c11"
private const val EXPECTED_UUID = "515228f7-0080-8c3b-e48f-5aa7d078035d"
private const val EXPECTED_ADPCM0 = "e17b4bcf8c19563fdf44bfc80c0d852b8051669c7df1d8fa9b57eb7510953e96"
private const val EXPECTED_ADPCM1 = "48e4a988cd7299c7a31c0549af4343cad970723df63ee885bb4a3a3fa3b84010"
private const val EXPECTED_DECODED = "a1a915d51880d90d3115543ac3943552faf742a4a52d73a4f528513dbbb9cee6"
