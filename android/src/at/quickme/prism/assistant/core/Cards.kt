// What a tool did, as the overlay's transcript shows it: one card per tool
// result, as Gemini renders its action cards (the Google app's robin action
// cards: a header with the app or kind and a subtitle "x • y", the content,
// and its buttons). Plain data, serializable, so a conversation reopened
// within the resume window shows the same cards; no Android here (tested on a
// plain JVM: android/tests/AssistantTest.kt).

package at.quickme.prism.assistant.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Card

/** Current weather and the forecast for [place] (Open-Meteo), in °C. */
@Serializable
@SerialName("weather")
data class WeatherCard(
    val place: String,
    val temperature: Double,
    val feelsLike: Double? = null,
    /** WMO weather code (Weather.condition). */
    val code: Int,
    val day: Boolean = true,
    val high: Double? = null,
    val low: Double? = null,
    val precipitationChance: Int? = null,
    val wind: Double? = null,
    val hours: List<HourForecast> = emptyList(),
    val days: List<DayForecast> = emptyList(),
) : Card

/** An hour of the forecast: "14:00", its temperature and weather code; its chance of rain and wind (km/h) where known. */
@Serializable
data class HourForecast(
    val time: String,
    val temperature: Double,
    val code: Int,
    val day: Boolean = true,
    val precipitationChance: Int? = null,
    val wind: Double? = null,
)

/** A day of the forecast: its short name ("Tue"), high, low, weather code. */
@Serializable
data class DayForecast(val day: String, val high: Double, val low: Double, val code: Int, val precipitationChance: Int? = null)

/** A phone call placed to [name] at [number]. */
@Serializable
@SerialName("call")
data class CallCard(val name: String, val number: String, val label: String = "") : Card

/**
 * A message sent: by SMS (to a number) or Matrix (to a room). A Matrix
 * message stays editable ([eventId], m.replace); an SMS can't be changed once
 * sent, only followed by a correction.
 */
@Serializable
@SerialName("message")
data class MessageCard(
    val channel: String,
    /** Who it went to, as shown: a contact's or room's name. */
    val to: String,
    /** The number (SMS) or room ID (Matrix). */
    val address: String,
    val text: String,
    /** The Matrix event of the original message, which edits replace. */
    val eventId: String = "",
    val edited: Boolean = false,
    val at: Long = 0,
    /** The radio hadn't confirmed it as sent (an SMS that may still go out). */
    val unconfirmed: Boolean = false,
) : Card {
    val editable: Boolean get() = channel == MATRIX && eventId.isNotEmpty()

    companion object {
        const val SMS = "sms"
        const val MATRIX = "matrix"
    }
}

/** Messages read from a conversation (SMS thread or Matrix room). */
@Serializable
@SerialName("thread")
data class ThreadCard(
    val channel: String,
    val title: String,
    val messages: List<ThreadMessage>,
    /** Where a reply goes: the number (SMS) or the room ID (Matrix). */
    val address: String = "",
) : Card

@Serializable
data class ThreadMessage(val from: String, val text: String, val at: Long, val mine: Boolean = false)

/** Several conversations at a glance: the latest line of each. */
@Serializable
@SerialName("threads")
data class ThreadListCard(val channel: String, val threads: List<ThreadMessage>) : Card

/** An alarm set in the clock app; [days] are 1 (Sunday) … 7 (Saturday), empty for once. */
@Serializable
@SerialName("alarm")
data class AlarmCard(
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val days: List<Int> = emptyList(),
    /** What the owner did to it on its card: [SNOOZED], [DISMISSED], or nothing. */
    val state: String = "",
) : Card {
    companion object {
        const val SNOOZED = "snoozed"
        const val DISMISSED = "dismissed"
    }
}

/** A timer of [seconds] started at [startedAt] (epoch ms). */
@Serializable
@SerialName("timer")
data class TimerCard(
    val seconds: Int,
    val label: String = "",
    val startedAt: Long = 0,
    /** Stopped on its card (epoch ms), 0 while it runs. */
    val stoppedAt: Long = 0,
) : Card

/**
 * A device setting or action, as Gemini's "Settings • Flashlight" card: what
 * ([kind]: flashlight, dnd, volume, media, app, link, navigation), a title, a
 * subtitle, and its state where it has one.
 */
@Serializable
@SerialName("setting")
data class SettingCard(val kind: String, val title: String, val subtitle: String = "", val on: Boolean? = null) : Card

/** Contacts found. */
@Serializable
@SerialName("contacts")
data class ContactsCard(val contacts: List<ContactEntry>) : Card

@Serializable
data class ContactEntry(val name: String, val number: String, val label: String = "")

/** Calendar events. */
@Serializable
@SerialName("events")
data class EventsCard(val events: List<EventEntry>) : Card

@Serializable
data class EventEntry(
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    val location: String = "",
    /** The calendar's event ID, which opens it (0: unknown). */
    val id: Long = 0,
)

/**
 * A request handed to a T3 Code agent: its [agentId] once the leading worker
 * started it (empty until then: the overlay asks again with [requestId]).
 */
@Serializable
@SerialName("handoff")
data class HandoffCard(
    val request: String,
    val requestId: String,
    val agentId: String = "",
    val project: String = "auto",
    val title: String = "",
    /** A follow-up into an existing thread rather than a new agent. */
    val followUp: Boolean = false,
    /** Why the start failed; empty while it starts or once it runs. */
    val failed: String = "",
) : Card

/** T3 agents listed. */
@Serializable
@SerialName("agents")
data class AgentsCard(val agents: List<AgentEntry>) : Card

@Serializable
data class AgentEntry(val id: String, val title: String, val state: String, val target: String = "")

/** A screenshot the assistant took of the phone's screen. */
@Serializable
@SerialName("screenshot")
data class ScreenshotCard(val name: String = "Screenshot") : Card
