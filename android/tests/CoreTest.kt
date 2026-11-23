// The Android app's core on a plain JVM: showing untrusted text, the pairing
// code the owner types for the cluster. (SSHSIG,
// approvals and host formatting are shared/'s, checked there.)
//
//   java -cp <core classes>:kotlin-stdlib.jar CoreTestKt

import at.quickme.prism.core.ShellPairing
import at.quickme.prism.core.Text
import kotlin.system.exitProcess

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

fun main() {
    // --- Untrusted text ---
    check(Text.visible("a\u0007b") == "a\\x07b", "visible escapes a bell")
    check(Text.visible("a\u202eb") == "a\\u{202e}b", "visible escapes a bidi override")
    check(Text.visible("x\ny") == "x\\x0ay" && Text.visible("x\ny", lines = true) == "x\ny", "line breaks only for code")

    // --- Pairing the cluster's adb key (shell/Pairing.kt) ---
    check(ShellPairing.code("123456") == "123456", "a six-digit code")
    check(ShellPairing.code(" 123 456\n") == "123456" && ShellPairing.code("123-456") == "123456", "spaces and dashes dropped")
    check(ShellPairing.code("12345") == null && ShellPairing.code("1234567") == null, "five or seven digits refused")
    check(ShellPairing.code("12345a") == null && ShellPairing.code("") == null && ShellPairing.code(null) == null, "not digits refused")
    check(ShellPairing.code("١٢٣٤٥٦") == null, "only ASCII digits")
    check(ShellPairing.portOk(37123) && !ShellPairing.portOk(1023) && !ShellPairing.portOk(65536) && !ShellPairing.portOk(null), "pairing port range")
    check(ShellPairing.fields(37123, "042137") == ",\"pair_port\":37123,\"pair_code\":\"042137\"", "message fields")
    check(runCatching { ShellPairing.fields(80, "042137") }.isFailure, "fields refuse a low port")
    check(runCatching { ShellPairing.fields(37123, "04213\"") }.isFailure, "fields refuse a bad code")

    println(if (failed == 0) "all passed" else "$failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
