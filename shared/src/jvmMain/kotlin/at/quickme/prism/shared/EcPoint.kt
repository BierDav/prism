package at.quickme.prism.shared

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/** A P-256 public key as the uncompressed point SshSig takes (04 || X || Y). */
fun ecPoint(key: ECPublicKey): ByteArray {
    fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        val len = minOf(b.size, 32)
        b.copyInto(out, 32 - len, b.size - len, b.size)
        return out
    }
    return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
}
