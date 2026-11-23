package at.quickme.prism.shared

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

// The JVM source set: Android, and the JVM check (shared/default.nix).
internal actual val platformName: String = "jvm"

internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp, config)

internal actual fun sha512(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-512").digest(data)

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

internal actual fun secureRandom(count: Int): ByteArray = ByteArray(count).also { java.security.SecureRandom().nextBytes(it) }
