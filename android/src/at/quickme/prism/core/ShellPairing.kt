// Pairing the cluster's adb key from the phone (shell/Pairing.kt): what the
// owner typed into the notification, and the fields Prism adds to its
// `pair` message on the relay (android/shizuku/README.md has the contract).

package at.quickme.prism.core

object ShellPairing {
    /** The six-digit code from what the owner typed (spaces and dashes dropped), or null if it isn't one. */
    fun code(typed: CharSequence?): String? {
        if (typed == null) return null
        val s = typed.filterNot { it.isWhitespace() || it == '-' }.toString()
        return if (s.length == 6 && s.all { it in '0'..'9' }) s else null
    }

    /** A port adbd's pairing service can have. */
    fun portOk(port: Int?): Boolean = port != null && port in 1024..65535

    /** `,"pair_port":…,"pair_code":"…"` for the message; only for a valid port and code. */
    fun fields(port: Int, code: String): String {
        require(portOk(port)) { "pairing port out of range" }
        require(code(code) == code) { "not a six-digit code" }
        return ",\"pair_port\":$port,\"pair_code\":\"$code\""
    }
}
