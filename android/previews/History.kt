// The assistant's chat history (assistant/AssistHistory.kt): the list of
// conversations by day, with one from another device and one still waiting
// to sync, and a conversation opened to continue it.

package at.quickme.prism.previews

import androidx.compose.runtime.Composable
import at.quickme.prism.assistant.HistoryChat
import at.quickme.prism.assistant.HistoryItem
import at.quickme.prism.assistant.HistoryList
import at.quickme.prism.assistant.core.DayForecast
import at.quickme.prism.assistant.core.Event
import at.quickme.prism.assistant.core.HourForecast
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Transcript
import at.quickme.prism.assistant.core.WeatherCard
import at.quickme.prism.shared.AssistantConversation

/** 24 Nov 2026, 18:15 in Vienna, in Unix seconds. */
private const val NOW_S = 1_795_540_500L

/** Chats from today back to October: one begun on the Mac, one refused by the replica. */
private fun sampleHistory(now: Long): List<HistoryItem> {
    fun c(id: String, title: String, ago: Long, device: String = "david-pixel") =
        HistoryItem(AssistantConversation(id, device, title, now - ago - 600, now - ago))
    return listOf(
        c("c-1", "What's the weather in Vienna tonight?", 20 * 60),
        c("c-2", "Text Anna that I'm running ten minutes late", 3 * 3600).copy(waiting = true),
        c("c-3", "Why is the backup on david-mm failing?", 26 * 3600, device = "david-mb"),
        c("c-4", "Set an alarm for 6:30 on weekdays", 3 * 86400),
        c("c-5", "Summarize the Matrix room Team from today", 4 * 86400),
        c("c-6", "Deploy the new Prism worker to the cluster", 40 * 86400)
            .copy(waiting = true, problem = "Changed on another device since; this copy stays on the phone."),
    )
}

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
        transcript = t, running = false, loading = false, error = null, configured = true, canSend = true,
        text = "Remind me at 7 to take the umbrella", onText = {}, onSend = {}, onAction = null,
        onNew = {}, onBack = {}, now = { NOW_S * 1000 },
    )
}
