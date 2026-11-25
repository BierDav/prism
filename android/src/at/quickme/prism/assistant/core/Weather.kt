// Weather from Open-Meteo (open-meteo.com: free, no key): a place's
// coordinates from its geocoding API, then the current weather, the next
// hours and the next days from its forecast API, decoded into a WeatherCard
// and a line for the model.

package at.quickme.prism.assistant.core

import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

object Weather {
    /** A place found by name: what to call it and where it is. */
    data class Place(val name: String, val latitude: Double, val longitude: Double)

    fun geocodeUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" +
            URLEncoder.encode(name.trim(), "UTF-8")

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,precipitation_probability" +
            "&hourly=temperature_2m,weather_code,is_day,precipitation_probability,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=5"

    /** The first place of a geocoding answer: "Vienna, Austria"; null when nothing matched. */
    fun decodePlace(body: String): Place? {
        val first = (Json.parseToJsonElement(body).jsonObject["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val name = first.string("name") ?: return null
        val lat = first.double("latitude") ?: return null
        val lon = first.double("longitude") ?: return null
        val country = first.string("country")
        return Place(listOfNotNull(name, country?.takeIf { it != name }).joinToString(", "), lat, lon)
    }

    /** A forecast answer as the card for [place]: now, the next [hours] hours, the days ahead. */
    fun decodeForecast(body: String, place: String, hours: Int = 6): WeatherCard {
        val root = Json.parseToJsonElement(body).jsonObject
        val current = root["current"] as? JsonObject ?: throw IllegalStateException("no current weather in the answer")
        val now = current.string("time").orEmpty()
        val hourly = root["hourly"] as? JsonObject
        val times = hourly.strings("time")
        // The hour now: the last one at or before the current time.
        val start = times.indexOfLast { it <= now }.coerceAtLeast(0)
        val temps = hourly.doubles("temperature_2m")
        val codes = hourly.ints("weather_code")
        val isDay = hourly.ints("is_day")
        val hourRain = hourly.ints("precipitation_probability")
        val hourWind = hourly.doubles("wind_speed_10m")
        val next = (start + 1 until minOf(times.size, start + 1 + hours)).mapNotNull { i ->
            val t = temps.getOrNull(i) ?: return@mapNotNull null
            HourForecast(times[i].substringAfter('T'), t, codes.getOrNull(i) ?: 0, isDay.getOrNull(i) != 0, hourRain.getOrNull(i), hourWind.getOrNull(i))
        }
        val daily = root["daily"] as? JsonObject
        val dayTimes = daily.strings("time")
        val highs = daily.doubles("temperature_2m_max")
        val lows = daily.doubles("temperature_2m_min")
        val dayCodes = daily.ints("weather_code")
        val rain = daily.ints("precipitation_probability_max")
        val days = dayTimes.indices.mapNotNull { i ->
            val high = highs.getOrNull(i) ?: return@mapNotNull null
            val low = lows.getOrNull(i) ?: return@mapNotNull null
            DayForecast(dayName(dayTimes[i], i), high, low, dayCodes.getOrNull(i) ?: 0, rain.getOrNull(i))
        }
        return WeatherCard(
            place = place,
            temperature = current.double("temperature_2m") ?: throw IllegalStateException("no temperature in the answer"),
            feelsLike = current.double("apparent_temperature"),
            code = current.int("weather_code") ?: 0,
            day = current.int("is_day") != 0,
            high = days.firstOrNull()?.high,
            low = days.firstOrNull()?.low,
            precipitationChance = current.int("precipitation_probability") ?: days.firstOrNull()?.precipitationChance,
            wind = current.double("wind_speed_10m"),
            hours = next,
            days = days,
        )
    }

    /** The card in words, for the model. */
    fun summary(card: WeatherCard): String = buildString {
        append("${card.place}: ${deg(card.temperature)}, ${condition(card.code).lowercase()}")
        card.feelsLike?.let { append(", feels like ${deg(it)}") }
        if (card.high != null && card.low != null) append("; today ${deg(card.low)} to ${deg(card.high)}")
        card.precipitationChance?.let { append(", $it% chance of precipitation") }
        card.wind?.let { append(", wind ${it.roundToInt()} km/h") }
        append(".")
        if (card.hours.isNotEmpty()) {
            append(" Next hours: ")
            append(card.hours.joinToString(", ") { "${it.time} ${deg(it.temperature)} ${condition(it.code).lowercase()}" })
            append(".")
        }
        if (card.days.size > 1) {
            append(" Coming days: ")
            append(card.days.drop(1).joinToString(", ") { d ->
                "${d.day} ${deg(d.low)}–${deg(d.high)} ${condition(d.code).lowercase()}" +
                    (d.precipitationChance?.takeIf { it > 0 }?.let { " ($it% chance of precipitation)" } ?: "")
            })
            append(".")
        }
    }

    fun deg(t: Double): String = "${t.roundToInt()}°"

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    fun condition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    /** The kind of sky for an icon: clear, partly, cloudy, fog, rain, snow, storm. */
    fun sky(code: Int): String = when (code) {
        0, 1 -> "clear"
        2 -> "partly"
        3 -> "cloudy"
        45, 48 -> "fog"
        in 51..67, 80, 81, 82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "cloudy"
    }

    private fun dayName(date: String, index: Int): String = when (index) {
        0 -> "Today"
        else -> runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }.getOrDefault(date)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }
    private fun JsonObject?.array(key: String) = (this?.get(key) as? JsonArray).orEmpty()
    private fun JsonObject?.strings(key: String) = array(key).map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    private fun JsonObject?.doubles(key: String) = array(key).map { (it as? JsonPrimitive)?.doubleOrNull }
    private fun JsonObject?.ints(key: String) = array(key).map { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.doubleOrNull?.roundToInt() } }
}
