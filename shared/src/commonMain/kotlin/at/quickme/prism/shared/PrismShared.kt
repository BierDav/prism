@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package at.quickme.prism.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.native.HiddenFromObjC

/** The module's entry point for the apps (Swift: `PrismShared.shared`). */
object PrismShared {
    const val VERSION = "1"

    /** The JSON settings both apps use for the server's API ([PrismClient.json]). */
    @HiddenFromObjC
    val json: Json get() = PrismClient.json

    fun version(): String = "prism-shared $VERSION ($platformName)"

    /** JSON out and back in on a background dispatcher: proves coroutines and
     *  the serialization plugin on each platform. */
    suspend fun roundTrip(probe: Probe): Probe =
        withContext(Dispatchers.Default) {
            json.decodeFromString(Probe.serializer(), json.encodeToString(Probe.serializer(), probe))
        }

    /** GET [url] and decode its JSON body as a [Probe], with the platform's
     *  Ktor engine (Darwin: NSURLSession; JVM and Android: OkHttp). */
    suspend fun fetchProbe(url: String): Probe =
        httpClient().use { it.get(url).body<Probe>() }

    /** A Ktor client with the platform's engine and the module's JSON; Kotlin
     *  only (Swift would get all of Ktor's types in the framework's header). */
    @HiddenFromObjC
    fun httpClient(): HttpClient =
        platformHttpClient {
            install(ContentNegotiation) { json(json) }
            expectSuccess = true
        }
}

/** A small message for the self-tests. */
@Serializable
data class Probe(
    val name: String,
    val count: Int,
    val tags: List<String> = emptyList(),
    val note: String? = null,
)
