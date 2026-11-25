// The assistant's chat history (assistant/AssistHistory.kt): the list of
// conversations by day, with one from another device and one still waiting
// to sync, and a conversation opened to continue it.

package at.quickme.prism.previews

import androidx.compose.runtime.Composable
import at.quickme.prism.assistant.HistoryChat
import at.quickme.prism.assistant.HistoryList
import at.quickme.prism.assistant.core.DayForecast
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.HourForecast
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.WeatherCard
import at.quickme.prism.assistant.sampleHistory

/** 24 Nov 2026, 18:15 in Vienna, in Unix seconds. */
private const val NOW_S = 1_795_540_500L

/** The chats, newest first and by day. */
@ScreenPreviews
@Composable
private fun HistoryListPreview() = Themed {
    HistoryList(
        items = sampleHistory(NOW_S), error = null, device = "david-pixel", now = NOW_S,
        onOpen = {}, onNew = {}, onDelete = {}, onBack = {},
    )
}

/** No chats yet. */
@ScreenPreviews
@Composable
private fun HistoryEmptyPreview() = Themed {
    HistoryList(items = emptyList(), error = null, device = "david-pixel", now = NOW_S, onOpen = {}, onNew = {}, onDelete = {}, onBack = {})
}

/** A chat opened from the list, to go on with it. */
@ScreenPreviews
@Composable
private fun HistoryChatPreview() = Themed {
    val weather = WeatherCard(
        place = "Vienna", temperature = 4.2, feelsLike = 1.0, code = 2, day = false, high = 6.0, low = -1.0,
        precipitationChance = 10, wind = 12.0,
        hours = listOf(HourForecast("19:00", 3.6, 2, false), HourForecast("20:00", 3.0, 3, false), HourForecast("21:00", 2.4, 61, false)),
        days = listOf(DayForecast("Today", 6.0, -1.0, 2, 10), DayForecast("Wed", 3.0, -2.0, 61, 60)),
    )
    val t = listOf(
        Event.Said("What's the weather in Vienna tonight?"),
        Event.ToolStarted("w", Tools.WEATHER, Tools.label(Tools.WEATHER)),
        Event.ToolFinished("w", Tools.WEATHER, "", weather),
        Event.Answer("Partly cloudy and **4°** now, with light rain from about 21:00."),
        Event.Said("And tomorrow morning?"),
        Event.Answer("Around **-1°** at 7:00 with drizzle later; a warm coat and an umbrella."),
    ).fold(Transcript()) { acc, e -> acc.after(e) }
    HistoryChat(
        transcript = t, running = false, loading = false, error = null, configured = true,
        text = "Remind me at 7 to take the umbrella", onText = {}, onSend = {}, onEdit = { _, _ -> null },
        onNew = {}, onDelete = {}, onBack = {}, now = { NOW_S * 1000 },
    )
}
