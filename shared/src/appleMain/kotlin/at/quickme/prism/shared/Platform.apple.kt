package at.quickme.prism.shared

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

// The Apple source set: the Mac app's framework (macos_arm64).
internal actual val platformName: String = "macos"

internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin, config)

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha512(data: ByteArray): ByteArray {
    val out = UByteArray(CC_SHA512_DIGEST_LENGTH)
    data.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA512(if (data.isEmpty()) null else input.addressOf(0), data.size.toUInt(), digest.addressOf(0))
        }
    }
    return out.asByteArray()
}

actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandom(count: Int): ByteArray {
    val out = ByteArray(count)
    out.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) == 0) { "SecRandomCopyBytes failed" } }
    return out
}
