// Plain HTTPS for the assistant's tools (Open-Meteo, the Matrix homeserver):
// java.net, off the main thread, with short timeouts and errors that say what
// the server answered. No Android here (it runs on a plain JVM too).

package at.quickme.prism.assistant

import at.quickme.prism.assistant.core.Weather
import at.quickme.prism.assistant.core.WeatherCard
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An HTTP answer that wasn't 2xx: its [status] and what the server said. */
class HttpException(val status: Int, message: String) : IOException(message)

object Http {
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000

    /** [method] on [url] with a JSON [body] and [headers]; the answer's text. */
    suspend fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Prism-Assistant")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (status !in 200..299) throw HttpException(status, "HTTP $status: ${text.take(300)}")
            text
        } finally {
            c.disconnect()
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()) = request("GET", url, null, headers)
}

/** Open-Meteo's weather (no key) for a named place or coordinates. */
object WeatherService {
    suspend fun forPlace(name: String): WeatherCard {
        val place = Weather.decodePlace(Http.get(Weather.geocodeUrl(name)))
            ?: throw IllegalArgumentException("No place called \"$name\" was found. Try the town's name, or add the country.")
        return at(place.latitude, place.longitude, place.name)
    }

    suspend fun at(latitude: Double, longitude: Double, name: String): WeatherCard =
        Weather.decodeForecast(Http.get(Weather.forecastUrl(latitude, longitude)), name)
}
