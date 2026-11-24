// The assistant's tools on this phone: weather where it is (or anywhere),
// contacts, calls placed directly, texts sent at once and read back, alarms
// and timers in the clock app, apps, links and directions, playback, volume,
// the flashlight, Do Not Disturb, the calendar, and a look at the screen.
// Gemini's overlay does the same through its device utilities; unlike it,
// nothing here asks for confirmation (the model only asks when the target is
// ambiguous: SystemPrompt).
//
// Prism is the Device Owner (Dpc.kt): it grants itself the runtime
// permissions these need (CALL_PHONE, SEND_SMS, READ_SMS, READ_CONTACTS,
// READ_CALENDAR, location) at start, and again here if one is missing. Do Not
// Disturb needs notification policy access, which Prism's shell (uid 2000,
// shell/Shell.kt) grants it.

package at.quickme.prism.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.KeyEvent
import at.quickme.prism.Dpc
import at.quickme.prism.assistant.core.AlarmCard
import at.quickme.prism.assistant.core.Args
import at.quickme.prism.assistant.core.CallCard
import at.quickme.prism.assistant.core.ClockTime
import at.quickme.prism.assistant.core.ContactEntry
import at.quickme.prism.assistant.core.ContactsCard
import at.quickme.prism.assistant.core.EventEntry
import at.quickme.prism.assistant.core.EventsCard
import at.quickme.prism.assistant.core.MessageCard
import at.quickme.prism.assistant.core.PhoneNumbers
import at.quickme.prism.assistant.core.ScreenshotCard
import at.quickme.prism.assistant.core.SettingCard
import at.quickme.prism.assistant.core.ThreadCard
import at.quickme.prism.assistant.core.ThreadListCard
import at.quickme.prism.assistant.core.ThreadMessage
import at.quickme.prism.assistant.core.TimerCard
import at.quickme.prism.assistant.core.ToolArgumentException
import at.quickme.prism.assistant.core.Tools
import at.quickme.prism.assistant.core.Weather
import at.quickme.prism.assistant.core.formatDuration
import at.quickme.prism.shell.Shell
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Runtime permissions as the Device Owner grants them to itself. */
internal object Grants {
    private val RESTRICTED = setOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)

    /** Grants what is missing of [permissions]; throws with what is still missing. */
    fun require(c: Context, vararg permissions: String) {
        val missing = permissions.filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        if (Dpc.owner(c)) {
            // SMS permissions are hard-restricted: granted only once the
            // installer (Prism updates itself) exempts them.
            for (p in missing) if (p in RESTRICTED) runCatching {
                c.packageManager.addWhitelistedRestrictedPermission(c.packageName, p, PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER)
            }
            val dpm = c.getSystemService(DevicePolicyManager::class.java)
            for (p in missing) runCatching {
                dpm.setPermissionGrantState(Dpc.admin(c), c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        val still = permissions.filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (still.isNotEmpty()) {
            throw IllegalStateException("Prism may not ${still.joinToString(" or ") { it.substringAfterLast('.').lowercase().replace('_', ' ') }} on this phone")
        }
    }
}

/** Starts [intent] as its own task, from the overlay (in front, so Android allows it). */
private fun Context.launch(intent: Intent) {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Contacts and the numbers they have. */
internal class Contacts(private val c: Context) {
    data class Found(val name: String, val number: String, val type: Int, val label: String, val contactId: Long = 0)

    fun search(query: String, limit: Int = 20): List<Found> {
        Grants.require(c, Manifest.permission.READ_CONTACTS)
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val out = ArrayList<Found>()
        c.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext() && out.size < limit) {
                val name = cur.getString(0) ?: continue
                val number = cur.getString(1) ?: continue
                val type = cur.getInt(2)
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(c.resources, type, cur.getString(3)).toString()
                val contactId = cur.getLong(4)
                if (out.none { it.contactId == contactId && PhoneNumbers.same(it.number, number) }) out.add(Found(name, number, type, label, contactId))
            }
        }
        return out
    }

    /** The contact's name for [number], or null. */
    fun nameOf(number: String): String? {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    /**
     * Who [to] means: a number as given, or the one contact whose name
     * matches (its mobile number first). Ambiguity goes back to the model,
     * which asks the owner.
     */
    fun resolve(to: String): Pair<String, String> {
        PhoneNumbers.normalize(to)?.let { return (nameOf(it) ?: to) to it }
        val found = search(to)
        if (found.isEmpty()) throw ToolArgumentException("No contact matches \"$to\". Ask the owner for the number.")
        val exact = found.filter { it.name.equals(to.trim(), ignoreCase = true) }
        // Per contact, not per name: two people can share one.
        val people = (exact.ifEmpty { found }).groupBy { it.contactId }
        if (people.size > 1) {
            throw ToolArgumentException(
                "Several contacts match \"$to\": " + people.values.joinToString(", ") { p -> p[0].name + " (" + p.joinToString(", ") { it.number } + ")" } +
                    ". Ask the owner which one.",
            )
        }
        val numbers = people.values.first()
        val name = numbers[0].name
        val mobiles = numbers.filter { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
        val pick = when {
            numbers.size == 1 -> numbers[0]
            mobiles.size == 1 -> mobiles[0]
            else -> throw ToolArgumentException(
                "$name has several numbers: " + numbers.joinToString(", ") { "${it.label} ${it.number}" } + ". Ask the owner which one.",
            )
        }
        return name to pick.number
    }
}

/** Where the phone is, as fast as it can say: a recent fix, else a fresh one. */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(c: Context): Location? {
    Grants.require(c, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val lm = c.getSystemService(LocationManager::class.java)
    val recent = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (recent != null && System.currentTimeMillis() - recent.time < 15 * 60_000) return recent
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return recent
    val fresh = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val cancel = android.os.CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            lm.getCurrentLocation(provider, cancel, c.mainExecutor) { cont.resume(it) }
        }
    }
    return fresh ?: recent
}

@Suppress("DEPRECATION")
private suspend fun placeName(c: Context, l: Location): String = withContext(Dispatchers.IO) {
    runCatching {
        Geocoder(c).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.let { a ->
            a.locality ?: a.subAdminArea ?: a.adminArea
        }
    }.getOrNull() ?: "Here"
}

/** The tools that act on this phone; [capture] takes screenshots. */
internal fun phoneTools(c: Context, capture: ScreenCapture): List<AssistantTool> {
    val contacts = Contacts(c)
    return listOf(
        object : AssistantTool(
            Tools.WEATHER,
            "Current weather and the forecast (next hours, next days) for a place, or for where the phone is when no place is given.",
            optional = listOf(stringParam("place", "A town or city, e.g. \"Graz\" or \"Paris, France\"; leave out for the phone's location")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val card = args.optString("place")?.takeIf { it.isNotBlank() }?.let { WeatherService.forPlace(it) } ?: run {
                    val here = currentLocation(c) ?: throw IllegalStateException("The phone doesn't know where it is (location off?). Ask for a place.")
                    WeatherService.at(here.latitude, here.longitude, placeName(c, here))
                }
                return ToolOutput(Weather.summary(card), card)
            }
        },
        object : AssistantTool(
            Tools.FIND_CONTACTS,
            "Find the owner's contacts by name (or part of it); gives names, numbers and their kind (mobile, work, …).",
            required = listOf(stringParam("query", "A name or part of it")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val q = args.string("query")
                val found = withContext(Dispatchers.IO) { contacts.search(q) }
                if (found.isEmpty()) return ToolOutput("No contact matches \"$q\".")
                val list = found.map { ContactEntry(it.name, it.number, it.label) }
                return ToolOutput(list.joinToString("\n") { "${it.name}: ${it.number} (${it.label})" }, ContactsCard(list))
            }
        },
        object : AssistantTool(
            Tools.CALL,
            "Place a phone call right away (no confirmation). `to` is a phone number or a contact's name.",
            required = listOf(stringParam("to", "A phone number, or a contact's name")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                Grants.require(c, Manifest.permission.CALL_PHONE)
                val (name, number) = withContext(Dispatchers.IO) { contacts.resolve(args.string("to")) }
                c.launch(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
                return ToolOutput("Calling $name ($number).", CallCard(name, number))
            }
        },
        object : AssistantTool(
            Tools.SEND_SMS,
            "Send an SMS right away (no confirmation, no draft). `to` is a phone number or a contact's name. An SMS can't be edited after it is sent.",
            required = listOf(stringParam("to", "A phone number, or a contact's name"), stringParam("text", "The message, as the owner would write it")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                Grants.require(c, Manifest.permission.SEND_SMS)
                val text = args.string("text")
                val (name, number) = withContext(Dispatchers.IO) { contacts.resolve(args.string("to")) }
                val confirmed = sendSms(c, number, text)
                return ToolOutput(
                    if (confirmed) "Sent to $name ($number). It can't be edited; send a correction if needed."
                    else "Handed to the radio for $name ($number), not yet confirmed as sent; it will most likely go out. Don't send it again.",
                    MessageCard(MessageCard.SMS, name, number, text, at = System.currentTimeMillis()),
                )
            }
        },
        object : AssistantTool(
            Tools.READ_SMS,
            "Read text messages: the conversation with a contact or number (newest last), or without `contact` the latest message of each recent conversation.",
            optional = listOf(stringParam("contact", "A contact's name or a number"), intParam("limit", "How many messages (default 10, at most 40)")),
        ) {
            override suspend fun run(args: Args): ToolOutput = withContext(Dispatchers.IO) { readSms(c, contacts, args.optString("contact"), (args.optInt("limit") ?: 10).coerceIn(1, 40)) }
        },
        object : AssistantTool(
            Tools.ALARM,
            "Set an alarm in the clock app (no confirmation).",
            required = listOf(stringParam("time", "The time of day, e.g. \"7:30\", \"19:00\" or \"7 pm\"")),
            optional = listOf(
                stringParam("label", "What it is for"),
                intsParam("days", "Repeat on these days: 1 Sunday, 2 Monday, … 7 Saturday; leave out for once"),
            ),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val time = args.string("time")
                val (hour, minute) = ClockTime.parse(time) ?: throw ToolArgumentException("\"$time\" isn't a time of day like 7:30 or 7 pm")
                val days = args.optInts("days")
                if (days.any { it !in 1..7 }) throw ToolArgumentException("days are 1 (Sunday) to 7 (Saturday)")
                val label = args.optString("label").orEmpty()
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.distinct().sorted()))
                c.launch(intent)
                return ToolOutput("Alarm set for ${ClockTime.format(hour, minute)}" + (if (days.isEmpty()) "" else ", repeating") + ".", AlarmCard(hour, minute, label, days.distinct().sorted()))
            }
        },
        object : AssistantTool(
            Tools.TIMER,
            "Start a timer in the clock app (no confirmation).",
            required = listOf(intParam("seconds", "Its length in seconds (1 to 86400)")),
            optional = listOf(stringParam("label", "What it is for")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val seconds = args.int("seconds")
                if (seconds !in 1..86_400) throw ToolArgumentException("a timer runs 1 second to 24 hours")
                val label = args.optString("label").orEmpty()
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                c.launch(intent)
                return ToolOutput("Timer started for ${formatDuration(seconds)}.", TimerCard(seconds, label, System.currentTimeMillis()))
            }
        },
        object : AssistantTool(
            Tools.OPEN_APP,
            "Open an app on the phone by its name.",
            required = listOf(stringParam("app", "The app's name as on the home screen")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val wanted = args.string("app")
                val apps = withContext(Dispatchers.IO) { launcherApps(c) }
                val q = wanted.trim().lowercase()
                val pick = apps.firstOrNull { it.first.lowercase() == q }
                    ?: apps.filter { q in it.first.lowercase() }.minByOrNull { it.first.length }
                    ?: throw ToolArgumentException("No app called \"$wanted\" is installed.")
                val intent = c.packageManager.getLaunchIntentForPackage(pick.second) ?: throw IllegalStateException("${pick.first} can't be opened")
                c.launch(intent)
                return ToolOutput("Opened ${pick.first}.", SettingCard("app", pick.first, "Opened"))
            }
        },
        object : AssistantTool(
            Tools.OPEN_URL,
            "Open a web page in the browser (or the app that handles it).",
            required = listOf(stringParam("url", "An https:// or http:// address")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val url = args.string("url").trim()
                if (!url.startsWith("https://") && !url.startsWith("http://")) throw ToolArgumentException("only web addresses (https://…) open here")
                c.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return ToolOutput("Opened $url.", SettingCard("link", Uri.parse(url).host ?: url, url))
            }
        },
        object : AssistantTool(
            Tools.NAVIGATE,
            "Start directions to a place in Google Maps.",
            required = listOf(stringParam("destination", "An address or place name")),
            optional = listOf(choiceParam("mode", "How to travel (default driving)", "driving", "walking", "bicycling", "transit")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val to = args.string("destination")
                val mode = args.optChoice("mode", listOf("driving", "walking", "bicycling", "transit")) ?: "driving"
                val url = "https://www.google.com/maps/dir/?api=1&destination=" + URLEncoder.encode(to, "UTF-8") +
                    "&travelmode=$mode&dir_action=navigate"
                c.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return ToolOutput("Directions to $to ($mode) are open in Maps.", SettingCard("navigation", to, "Directions, $mode"))
            }
        },
        object : AssistantTool(
            Tools.MEDIA,
            "Control what is playing (music, podcasts, videos).",
            required = listOf(choiceParam("action", "What to do", "play", "pause", "toggle", "next", "previous", "stop")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val action = args.choice("action", listOf("play", "pause", "toggle", "next", "previous", "stop"))
                val key = when (action) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_STOP
                }
                val audio = c.getSystemService(AudioManager::class.java)
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                val said = mapOf("play" to "Playing", "pause" to "Paused", "toggle" to "Play/pause", "next" to "Next track", "previous" to "Previous track", "stop" to "Stopped")[action]!!
                return ToolOutput("$said.", SettingCard("media", "Media", said, on = action == "play"))
            }
        },
        object : AssistantTool(
            Tools.VOLUME,
            "Set a volume: to a level in percent, or one step up or down, or mute it.",
            required = listOf(choiceParam("stream", "Which volume", "media", "ring", "notification", "alarm", "call")),
            optional = listOf(intParam("percent", "The level, 0 to 100"), choiceParam("change", "Instead of a level", "up", "down", "mute")),
        ) {
            override suspend fun run(args: Args): ToolOutput = setVolume(c, args)
        },
        object : AssistantTool(
            Tools.FLASHLIGHT,
            "Switch the flashlight on or off.",
            required = listOf(boolParam("on", "true for on")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val on = args.boolean("on")
                val cameras = c.getSystemService(CameraManager::class.java)
                val id = cameras.cameraIdList.firstOrNull {
                    val ch = cameras.getCameraCharacteristics(it)
                    ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: throw IllegalStateException("this phone has no flashlight")
                cameras.setTorchMode(id, on)
                return ToolOutput("Flashlight ${if (on) "on" else "off"}.", SettingCard("flashlight", "Flashlight", if (on) "On" else "Off", on))
            }
        },
        object : AssistantTool(
            Tools.DND,
            "Switch Do Not Disturb on or off.",
            required = listOf(boolParam("on", "true for on")),
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val on = args.boolean("on")
                setDnd(c, on)
                return ToolOutput("Do Not Disturb is ${if (on) "on" else "off"}.", SettingCard("dnd", "Do Not Disturb", if (on) "On" else "Off", on))
            }
        },
        object : AssistantTool(
            Tools.CALENDAR,
            "The owner's calendar events from now on: today, or the next few days.",
            optional = listOf(intParam("days", "How many days ahead, from today (default 1, at most 14)")),
        ) {
            override suspend fun run(args: Args): ToolOutput = withContext(Dispatchers.IO) { calendar(c, (args.optInt("days") ?: 1).coerceIn(1, 14)) }
        },
        object : AssistantTool(
            Tools.SCREENSHOT,
            "Take a screenshot of what is on the phone's screen under the overlay, and look at it. No permission needed.",
        ) {
            override suspend fun run(args: Args): ToolOutput {
                val shot = capture.capture() ?: throw IllegalStateException("the screen can't be captured right now")
                return ToolOutput("The screenshot follows as an image.", ScreenshotCard(shot.name), image = shot)
            }
        },
    )
}

/**
 * Sends [text] to [number] and waits until the radio took every part; false
 * when it didn't say within a minute (it may still go out: never resend).
 */
private suspend fun sendSms(c: Context, number: String, text: String): Boolean {
    val sms = c.getSystemService(SmsManager::class.java) ?: throw IllegalStateException("this phone can't send SMS")
    val parts = sms.divideMessage(text)
    val action = "at.quickme.prism.SMS_SENT." + UUID.randomUUID()
    val results: List<Int> = withTimeoutOrNull(60_000) {
        suspendCancellableCoroutine<List<Int>> { cont ->
            val codes = ArrayList<Int>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    codes.add(resultCode)
                    if (codes.size == parts.size) {
                        runCatching { c.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(codes.toList())
                    }
                }
            }
            c.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { c.unregisterReceiver(receiver) } }
            val sent = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(
                    c, i, Intent(action).setPackage(c.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            })
            try {
                sms.sendMultipartTextMessage(number, null, parts, sent, null)
            } catch (e: RuntimeException) {
                runCatching { c.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resume(listOf(-1000))
            }
        }
    } ?: return false
    val bad = results.firstOrNull { code -> code != Activity.RESULT_OK } ?: return true
    throw IllegalStateException(
        when (bad) {
            SmsManager.RESULT_ERROR_RADIO_OFF -> "the SMS wasn't sent: the phone's radio is off (airplane mode?)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "the SMS wasn't sent: no mobile service"
            -1000 -> "the SMS couldn't be handed to the radio"
            else -> "the SMS wasn't sent (error $bad)"
        },
    )
}

private fun readSms(c: Context, contacts: Contacts, contact: String?, limit: Int): ToolOutput {
    Grants.require(c, Manifest.permission.READ_SMS)
    val numbers: List<String>? = contact?.takeIf { it.isNotBlank() }?.let { who ->
        PhoneNumbers.normalize(who)?.let { listOf(it) }
            ?: contacts.search(who).map { it.number }.ifEmpty { throw ToolArgumentException("No contact matches \"$who\".") }
    }
    data class Row(val address: String, val body: String, val date: Long, val mine: Boolean, val thread: Long)
    val rows = ArrayList<Row>()
    c.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID),
        null, null, Telephony.Sms.DEFAULT_SORT_ORDER,
    )?.use { cur ->
        var scanned = 0
        while (cur.moveToNext() && scanned < 2000) {
            scanned++
            val address = cur.getString(0) ?: continue
            if (numbers != null && numbers.none { PhoneNumbers.same(it, address) }) continue
            rows.add(Row(address, cur.getString(1).orEmpty(), cur.getLong(2), cur.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX, cur.getLong(4)))
            if (numbers != null && rows.size >= limit) break
            if (numbers == null && rows.map { it.thread }.distinct().size > limit) break
        }
    }
    val names = HashMap<String, String>()
    fun name(address: String) = names.getOrPut(address) { contacts.nameOf(address) ?: address }
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return if (numbers != null) {
        if (rows.isEmpty()) return ToolOutput("No texts with $contact.")
        val title = name(rows.first().address)
        val messages = rows.take(limit).reversed().map { ThreadMessage(if (it.mine) "Me" else name(it.address), it.body, it.date, it.mine) }
        ToolOutput(
            "Texts with $title, oldest first:\n" + messages.joinToString("\n") { "[${time.format(Date(it.at))}] ${it.from}: ${it.text}" },
            ThreadCard(MessageCard.SMS, title, messages),
        )
    } else {
        val latest = rows.groupBy { it.thread }.values.map { it.first() }.take(limit)
            .map { ThreadMessage(name(it.address), (if (it.mine) "Me: " else "") + it.body, it.date, it.mine) }
        if (latest.isEmpty()) return ToolOutput("No texts on this phone.")
        ToolOutput(
            "Latest text of each recent conversation, newest first:\n" + latest.joinToString("\n") { "[${time.format(Date(it.at))}] ${it.from}: ${it.text}" },
            ThreadListCard(MessageCard.SMS, latest),
        )
    }
}

private fun launcherApps(c: Context): List<Pair<String, String>> {
    val pm = c.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }
}

private fun setVolume(c: Context, args: Args): ToolOutput {
    val stream = args.choice("stream", listOf("media", "ring", "notification", "alarm", "call"))
    val type = when (stream) {
        "media" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        else -> AudioManager.STREAM_VOICE_CALL
    }
    val percent = args.optInt("percent")
    val change = args.optChoice("change", listOf("up", "down", "mute"))
    if (percent == null && change == null) throw ToolArgumentException("give \"percent\" or \"change\"")
    val audio = c.getSystemService(AudioManager::class.java)
    val max = audio.getStreamMaxVolume(type)
    val min = audio.getStreamMinVolume(type)
    try {
        when {
            percent != null -> {
                if (percent !in 0..100) throw ToolArgumentException("percent is 0 to 100")
                audio.setStreamVolume(type, (min + (max - min) * percent / 100.0).toInt().coerceIn(min, max), AudioManager.FLAG_SHOW_UI)
            }
            change == "mute" -> audio.adjustStreamVolume(type, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> audio.adjustStreamVolume(type, if (change == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    } catch (e: SecurityException) {
        throw IllegalStateException("Android doesn't let Prism change the $stream volume now (Do Not Disturb?)")
    }
    val now = audio.getStreamVolume(type)
    val level = if (max > min) (now - min) * 100 / (max - min) else 0
    return ToolOutput("The $stream volume is at $level%.", SettingCard("volume", "${stream.replaceFirstChar { it.uppercase() }} volume", "$level%", on = now > min))
}

private suspend fun setDnd(c: Context, on: Boolean) {
    val nm = c.getSystemService(NotificationManager::class.java)
    // The phone's own Do Not Disturb, through the shell: since Android 15
    // an app's interruption filter only switches its own rule.
    if (Shell.available()) {
        val r = withContext(Dispatchers.IO) { Shell.run(10_000, "cmd", "notification", "set_dnd", if (on) "priority" else "off") }
        if (r.ok()) return
    }
    if (nm.isNotificationPolicyAccessGranted) {
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return
    }
    throw IllegalStateException("Prism may not switch Do Not Disturb: it has no notification policy access and the shell isn't running")
}

private fun calendar(c: Context, days: Int): ToolOutput {
    Grants.require(c, Manifest.permission.READ_CALENDAR)
    val now = System.currentTimeMillis()
    val end = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now - 12 * 3600_000L)
        ContentUris.appendId(it, end)
    }.build()
    val events = ArrayList<EventEntry>()
    c.contentResolver.query(
        uri,
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
        null, null, CalendarContract.Instances.BEGIN + " ASC",
    )?.use { cur ->
        while (cur.moveToNext() && events.size < 30) {
            val e = EventEntry(cur.getString(0).orEmpty().ifBlank { "(no title)" }, cur.getLong(1), cur.getLong(2), cur.getInt(3) != 0, cur.getString(4).orEmpty())
            if (e.end > now || e.allDay && e.end > now - 24 * 3600_000L) events.add(e)
        }
    }
    if (events.isEmpty()) return ToolOutput(if (days == 1) "Nothing more in the calendar today." else "Nothing in the calendar for the next $days days.")
    val f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val d = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return ToolOutput(
        events.joinToString("\n") { e ->
            (if (e.allDay) "${d.format(Date(e.start))} all day" else "${f.format(Date(e.start))}–${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.end))}") +
                ": ${e.title}" + (if (e.location.isNotBlank()) " (${e.location})" else "")
        },
        EventsCard(events),
    )
}

/** The battery for the system prompt: "80%, charging". */
internal fun batteryText(c: Context): String {
    val bm = c.getSystemService(BatteryManager::class.java) ?: return ""
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (level !in 0..100) return ""
    return "$level%" + if (bm.isCharging) ", charging" else ""
}
