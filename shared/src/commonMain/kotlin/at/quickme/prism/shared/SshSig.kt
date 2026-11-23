// SSHSIG, the signature format of `ssh-keygen -Y sign`, for ecdsa-sha2-nistp256
// keys (PROTOCOL.sshsig in OpenSSH). Both apps sign with it: Prism enrollment
// (Enrollment.kt) and approvals (Approvals.kt). The key stays on the device
// (the Mac's Secure Enclave, the phone's StrongBox); the app hands in the
// public key's point and a signer that returns a DER ECDSA signature.

package at.quickme.prism.shared

import kotlin.io.encoding.Base64

object SshSig {
    const val KEY_TYPE = "ecdsa-sha2-nistp256"
    private const val CURVE = "nistp256"
    private const val HASH = "sha512"
    private val MAGIC = "SSHSIG".encodeToByteArray()

    /** The public key blob of an uncompressed P-256 point (04 || X || Y, 65 bytes). */
    fun publicKeyBlob(point: ByteArray): ByteArray {
        require(point.size == 65 && point[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        return Wire().string(KEY_TYPE).string(CURVE).string(point).bytes()
    }

    /** The authorized_keys form: "ecdsa-sha2-nistp256 AAAA…". */
    fun publicKeyLine(point: ByteArray): String = KEY_TYPE + " " + Base64.encode(publicKeyBlob(point))

    /** What the key signs: the preamble, namespace, reserved, hash name and SHA-512 of the message. */
    fun signedData(message: ByteArray, namespace: String): ByteArray =
        MAGIC + Wire().string(namespace).string(ByteArray(0)).string(HASH).string(sha512(message)).bytes()

    /**
     * The armoured signature, exactly as ssh-keygen writes it. [derSignature]
     * is what the device key returned for [signedData] of the message
     * (ECDSA P-256 over SHA-256, DER).
     */
    fun armor(namespace: String, point: ByteArray, derSignature: ByteArray): String {
        val (r, s) = parseDer(derSignature)
        val inner = Wire().mpint(r).mpint(s).bytes()
        val sigBlob = Wire().string(KEY_TYPE).string(inner).bytes()
        val body = Wire().uint32(1).string(publicKeyBlob(point)).string(namespace).string(ByteArray(0))
            .string(HASH).string(sigBlob).bytes()
        val b64 = Base64.encode(MAGIC + body)
        val out = StringBuilder("-----BEGIN SSH SIGNATURE-----\n")
        b64.chunked(70).forEach { out.append(it).append('\n') }
        return out.append("-----END SSH SIGNATURE-----\n").toString()
    }

    /** An SSH wire-format buffer (RFC 4251). */
    internal class Wire {
        private val out = ArrayList<Byte>()

        fun uint32(v: Long): Wire {
            for (shift in intArrayOf(24, 16, 8, 0)) out.add((v ushr shift).toByte())
            return this
        }

        fun string(data: ByteArray): Wire {
            uint32(data.size.toLong())
            data.forEach { out.add(it) }
            return this
        }

        fun string(text: String): Wire = string(text.encodeToByteArray())

        /** A non-negative mpint from big-endian magnitude bytes: minimal, with a 0 byte if the top bit is set. */
        fun mpint(magnitude: ByteArray): Wire {
            var i = 0
            while (i < magnitude.size && magnitude[i] == 0.toByte()) i++
            val trimmed = magnitude.copyOfRange(i, magnitude.size)
            return string(if (trimmed.isNotEmpty() && trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** r and s (big-endian magnitudes) from a DER ECDSA signature: SEQUENCE { INTEGER r, INTEGER s }. */
    internal fun parseDer(der: ByteArray): Pair<ByteArray, ByteArray> {
        var i = 0
        fun next(): Int {
            require(i < der.size) { "truncated DER signature" }
            return der[i++].toInt() and 0xff
        }
        fun length(): Int {
            val first = next()
            if (first < 0x80) return first
            var len = 0
            repeat(first and 0x7f) { len = (len shl 8) or next() }
            return len
        }
        fun integer(): ByteArray {
            require(next() == 0x02) { "not a DER integer" }
            val len = length()
            require(i + len <= der.size) { "truncated DER integer" }
            return der.copyOfRange(i, i + len).also { i += len }
        }
        require(next() == 0x30) { "not a DER sequence" }
        length()
        val r = integer()
        val s = integer()
        require(i == der.size) { "trailing bytes in the signature" }
        return r to s
    }
}

/** SHA-512 (each platform's source set has the actual). */
internal expect fun sha512(data: ByteArray): ByteArray
