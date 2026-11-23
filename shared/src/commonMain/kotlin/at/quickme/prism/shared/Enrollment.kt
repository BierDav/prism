// Enrollment at the Prism server (docs/prism-api.md, "Authentication"): the
// app signs this message with its device key (SSHSIG, namespace
// prism@quickme.at) and gets a bearer token for its device.

package at.quickme.prism.shared

import kotlinx.serialization.json.JsonPrimitive

object Enrollment {
    const val NAMESPACE = "prism@quickme.at"

    /** The enrollment message: compact JSON, keys sorted, a fresh nonce, no trailing newline. */
    fun message(device: String, now: Long = epochSeconds()): ByteArray {
        val nonce = secureRandom(32).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return ("{\"created\":$now,\"device\":${JsonPrimitive(device)},\"namespace\":\"$NAMESPACE\"," +
            "\"nonce\":\"$nonce\",\"v\":1}").encodeToByteArray()
    }
}
