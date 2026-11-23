// The call link's key, MACs and framing (core/CallLink.kt, docs/calls.md) on
// a plain JVM. The vector (hotspot key 00..1f, nm 16 x a1, np 16 x b2) is
// printed so Prism for macOS can check its own against it.
//
//   java -cp <core classes>:kotlin-stdlib.jar CallLinkTestKt

import at.quickme.prism.core.CallLink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
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

private const val EXPECTED_K = "770eb5e989c18d4fbd66609b94934b6e218999c822a3477313a8e726c356b864"
private const val EXPECTED_PHONE = "16ceaf00645894bb0710f4daccb787f733b3b4c7b58433e4d46ec61bd67362d7"
private const val EXPECTED_MAC = "967350dc16f4d14828d8320272b3a6e9446f932f9265667dbfa2750445d08321"
