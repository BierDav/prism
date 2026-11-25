// The hotspot page (Hotspot.kt, docs/hotspot.md), from the Phone tab and the
// Hotspot tile's long press (QS_TILE_PREFERENCES): the mode ("Always on", the
// same switch as the tile), switching an instant hotspot on or off now, the
// devices on the hotspot, and its name, password, security, band and
// visibility.
//
// The configuration and the devices come from the shell (HotspotTool's
// "info"), every 3 s while the page is in front; without Prism's shell access
// the page says so and has the mode only.

package at.quickme.prism

import android.content.Context
import android.content.Intent
import android.net.wifi.SoftApConfiguration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import at.quickme.prism.shell.Shell
import at.quickme.prism.ui.Health
import at.quickme.prism.ui.PageTitle
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.SettingToggle
import at.quickme.prism.ui.StatusRow
import at.quickme.prism.ui.rememberChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class HotspotActivity : ComponentActivity() {
    companion object {
        fun intent(c: Context): Intent = Intent(c, HotspotActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PrismTheme { HotspotScreen(onBack = ::finish) } }
    }
}

/** What the page shows of the hotspot; the activity and the previews pass it in. */
internal data class HotspotState(
    /** Hotspot.NORMAL or Hotspot.INSTANT. */
    val mode: String,
    /** Wi-Fi tethering runs. */
    val on: Boolean,
    /** The shell's answer, null before the first one or without the shell. */
    val info: Hotspot.Info?,
    /** Why there's no answer. */
    val error: String?,
    val shell: Boolean,
)

@Composable
private fun HotspotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes = rememberChanges(Hotspot::listen to Hotspot::unlisten, Shell::listen to Shell::unlisten)
    var info by remember { mutableStateOf<Hotspot.Info?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val sample = Sample.on(context)
    remember { Hotspot.watch(context) }

    suspend fun load() {
        if (sample) {
            info = Sample.hotspot()
            return
        }
        try {
            info = withContext(Dispatchers.IO) { Hotspot.info(context) }
            error = null
        } catch (e: IOException) {
            error = e.message ?: "no answer"
        }
    }
    LifecycleResumeEffect(reload) {
        val poll = scope.launch {
            while (true) {
                load()
                delay(3_000)
            }
        }
        onPauseOrDispose { poll.cancel() }
    }

    /** Runs a shell action off the main thread, shows its outcome, and loads again. */
    fun act(what: String, block: () -> String?) {
        if (busy != null) return
        busy = what
        notice = null
        scope.launch {
            notice = try {
                if (sample) null else withContext(Dispatchers.IO) { block() }
            } catch (e: IOException) {
                "Couldn't ${what.lowercase()}: ${e.message}"
            }
            busy = null
            reload++
        }
    }

    val state = remember(changes, info, error) {
        HotspotState(Hotspot.mode(context), if (sample) true else Hotspot.on, info, error, sample || Shell.available())
    }
    HotspotContent(
        state, busy, notice,
        onKeepOn = { Hotspot.setMode(context, if (it) Hotspot.NORMAL else Hotspot.INSTANT) },
        onTurnOn = { act("Turn on") { Hotspot.startNow(context); null } },
        onTurnOff = { act("Turn off") { Hotspot.stopNow(context); null } },
        onSave = { config ->
            act("Save") {
                val line = Hotspot.configure(context, config)
                if (line.contains("restarted")) "Saved; the hotspot restarted with it" else "Saved"
            }
        },
        onShell = { Shell.openSetup(context) },
        onBack = onBack,
    )
}

/** The page as it shows [s]; [busy] names the action that runs, [notice] how the last one went. */
@Composable
internal fun HotspotContent(
    s: HotspotState,
    busy: String?,
    notice: String?,
    onKeepOn: (Boolean) -> Unit,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onSave: (Hotspot.Config) -> Unit,
    onShell: () -> Unit,
    onBack: () -> Unit,
) {
    val normal = s.mode == Hotspot.NORMAL
    val clients = s.info?.clients
    val subtitle = when {
        !s.on -> if (normal) "Off · starting" else "Off · comes on when your Mac asks"
        clients == null -> if (normal) "On · always" else "On · instant"
        else -> (if (normal) "On · always · " else "On · instant · ") + devices(clients.size)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { PageTitle("Hotspot") },
                subtitle = { Text(subtitle) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            // Below the top bar, not under it: D-pad focus moves down from the bar's buttons
            // only into what lies wholly below them (the list counts as one block).
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingToggle(
                    title = "Always on",
                    detail = if (normal) "On all the time, for every device with the password"
                        else "Instant: comes on when your Mac asks, for one device, and goes off 2 minutes after it left",
                    checked = normal,
                    onCheckedChange = onKeepOn,
                )
            }
            if (!normal && s.shell) {
                item {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (s.on) {
                            FilledTonalButton(onClick = onTurnOff, enabled = busy == null, shapes = ButtonDefaults.shapes()) {
                                Text("Turn off now")
                            }
                        } else {
                            FilledTonalButton(onClick = onTurnOn, enabled = busy == null, shapes = ButtonDefaults.shapes()) {
                                Text("Turn on now")
                            }
                        }
                        if (busy == "Turn on" || busy == "Turn off") LoadingIndicator(Modifier.size(28.dp))
                    }
                }
            }
            if (notice != null) {
                item {
                    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (!s.shell) {
                item {
                    Panel {
                        StatusRow(PrismIcons.Terminal, "Needs shell access",
                            "The devices and the settings come from Prism's shell access, which isn't running", Health.Busy) {
                            TextButton(onClick = onShell, shapes = ButtonDefaults.shapes()) { Text("Open") }
                        }
                    }
                }
                return@LazyColumn
            }
            item { SectionHeader("Connected devices", Modifier.padding(start = 4.dp, top = 8.dp)) }
            item {
                Panel {
                    when {
                        s.info == null && s.error != null -> Text("Couldn't ask the phone: ${s.error}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        s.info == null -> LoadingIndicator(Modifier.size(36.dp).align(Alignment.CenterHorizontally))
                        !s.on -> Text("The hotspot is off.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        clients == null -> Text("Android didn't tell which devices are on it.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        clients.isEmpty() -> Text("No devices yet.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> for (c in clients) ClientRow(c)
                    }
                }
            }
            val info = s.info
            val config = info?.config
            if (info != null && config != null) {
                item { SectionHeader("Network", Modifier.padding(start = 4.dp, top = 8.dp)) }
                item {
                    // A new answer from the phone doesn't overwrite what's being typed.
                    ConfigEditor(config, info.bands, normal, busy, onSave)
                }
            }
        }
    }
}

private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"

@Composable
private fun ClientRow(c: Hotspot.Client) {
    val host = c.host
    val icon = when {
        host == null -> PrismIcons.Laptop
        Regex("(?i)iphone|ipad|pixel|android|galaxy|phone").containsMatchIn(host) -> PrismIcons.Phone
        else -> PrismIcons.Laptop
    }
    StatusRow(icon, host ?: "Unnamed device", listOfNotNull(c.ip, c.mac.ifEmpty { null }).joinToString(" · "), Health.Good)
}

/** The security types Settings offers, with their labels. */
private val SECURITY = linkedMapOf(
    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK to "WPA2",
    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION to "WPA2/WPA3",
    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE to "WPA3",
    SoftApConfiguration.SECURITY_TYPE_OPEN to "None",
)

@Composable
private fun ConfigEditor(saved: Hotspot.Config, bands: List<String>, normal: Boolean, busy: String?, onSave: (Hotspot.Config) -> Unit) {
    var draft by remember { mutableStateOf<Hotspot.Config?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    // Saved: once the phone's answer has the new configuration, it shows again.
    val edited = draft?.takeIf { it != saved }
    val c = edited ?: saved
    val open = c.security == SoftApConfiguration.SECURITY_TYPE_OPEN
    val nameError = when {
        c.name.isEmpty() -> "Needs a name"
        c.name.toByteArray().size > 32 -> "At most 32 bytes"
        else -> null
    }
    val passwordError = if (!open && c.password.length !in 8..63) "8 to 63 characters" else null
    val sixNeedsWpa3 = c.band == "6" && c.security != SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
    Panel {
        OutlinedTextField(
            value = c.name,
            onValueChange = { draft = c.copy(name = it) },
            label = { Text("Name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Choice("Security", SECURITY, c.security) { draft = c.copy(security = it) }
        if (!open) {
            OutlinedTextField(
                value = c.password,
                onValueChange = { draft = c.copy(password = it) },
                label = { Text("Password") },
                singleLine = true,
                isError = passwordError != null,
                supportingText = passwordError?.let { { Text(it) } },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Choice("Band", Hotspot.BANDS.filterKeys { it in bands || it == c.band }, c.band,
            note = when {
                sixNeedsWpa3 -> "6 GHz takes WPA3: saving switches the security to WPA3"
                !normal -> "For Always on: the instant hotspot picks 6 GHz where it can (WPA3), else 5 or 2.4 GHz"
                c.band == "2+5" -> "Both at once: older devices on 2.4 GHz, newer ones on 5 GHz"
                else -> null
            }) { draft = c.copy(band = it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hidden network", style = MaterialTheme.typography.bodyLarge)
                Text("Devices join it by name only", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = c.hidden, onCheckedChange = { draft = c.copy(hidden = it) })
        }
        if (edited != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { onSave(c) },
                    enabled = busy == null && nameError == null && passwordError == null && !(open && c.band == "6"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Save") }
                TextButton(onClick = { draft = null }, enabled = busy == null, shapes = ButtonDefaults.shapes()) { Text("Undo") }
                if (busy == "Save") LoadingIndicator(Modifier.size(28.dp))
            }
            Text(
                if (open && c.band == "6") "6 GHz needs a password."
                else "Devices on the hotspot lose it for a moment while it restarts, and need the new name or password.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A read-only dropdown of [options] (value to label). */
@Composable
private fun <T> Choice(label: String, options: Map<T, String>, value: T, note: String? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = options[value] ?: value.toString(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            supportingText = note?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            for ((v, text) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onPick(v)
                })
            }
        }
    }
}
