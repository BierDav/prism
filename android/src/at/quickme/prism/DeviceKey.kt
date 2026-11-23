// The phone's signing key: a P-256 key in the phone's StrongBox (the Titan M
// chip on a Pixel), or the TEE where there is none, that signs only right after
// a strong biometric (BiometricPrompt with this key's Signature), also while
// the phone is locked. Created on first use; it can't be exported. It signs
// approvals (Approver.approve), as each Mac's Secure Enclave key does
// (macos/EnclaveKey.swift); its public key is the phone's device key in the
// Prism workers' configuration (devices.david-pixel.key), which checks them.
//
// A TV has no biometrics: its key signs without one. A TV only
// updates Prism (Updater, no token) and never gets a Rocky provisioning
// (rocky_provisioning): every replica holds all of Prism's data, with no
// permissions inside it, so a TV with one would show alerts, agents and
// approvals to anyone holding the remote.

package at.quickme.prism

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import at.quickme.prism.shared.SshSig
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class DeviceKey private constructor(
    private val publicKey: ECPublicKey,
    private val privateKey: PrivateKey,
    @JvmField val strongBox: Boolean,
    /** Whether each signature needs a biometric (not on a TV). */
    @JvmField val needsBiometric: Boolean,
) {
    /** A Signature to hand to BiometricPrompt as its CryptoObject. */
    fun signature(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }

    /** The public key's uncompressed point (04 || X || Y), as SshSig takes it. */
    val point: ByteArray = ByteArray(65).also {
        it[0] = 4
        put32(publicKey.w.affineX, it, 1)
        put32(publicKey.w.affineY, it, 33)
    }

    /** The authorized_keys form: "ecdsa-sha2-nistp256 AAAA…". */
    fun publicKeyLine(): String = SshSig.publicKeyLine(point)

    companion object {
        const val ALIAS = "device"

        /** A TV: no biometrics, and a limited device on the Prism server. */
        @JvmStatic
        fun tv(c: Context): Boolean = c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        private fun put32(v: BigInteger, out: ByteArray, at: Int) {
            val b = v.toByteArray()
            val n = minOf(b.size, 32)
            System.arraycopy(b, b.size - n, out, at + 32 - n, n)
        }

        @JvmStatic
        @Synchronized
        @Throws(Exception::class)
        fun loadOrCreate(c: Context): DeviceKey {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            if (!store.containsAlias(ALIAS)) {
                val biometric = !tv(c)
                try {
                    generate(true, biometric)
                } catch (e: StrongBoxUnavailableException) {
                    generate(false, biometric)
                }
            }
            val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            val info = try {
                KeyFactory.getInstance(entry.privateKey.algorithm, "AndroidKeyStore")
                    .getKeySpec(entry.privateKey, KeyInfo::class.java)
            } catch (e: Exception) {
                null
            }
            val strongBox = info?.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX // only for display
            val needsBiometric = info?.isUserAuthenticationRequired ?: true
            return DeviceKey(entry.certificate.publicKey as ECPublicKey, entry.privateKey, strongBox, needsBiometric)
        }

        private fun generate(strongBox: Boolean, biometric: Boolean) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
            if (biometric) {
                // Every signature needs its own biometric authentication.
                // No setUnlockedDeviceRequired: the approval screen shows on
                // the lock screen, and the fingerprint is what signs.
                spec.setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
            }
            generator.initialize(spec.build())
            generator.generateKeyPair()
        }
    }
}
