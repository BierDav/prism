package at.quickme.prism.shared

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/** "jvm" or "macos" (each platform's source set has the actual). */
internal expect val platformName: String

/** A Ktor client on the platform's engine. */
internal expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient
