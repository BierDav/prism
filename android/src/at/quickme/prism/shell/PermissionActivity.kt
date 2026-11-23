// An app asks to use the shell server through Shizuku's API
// (ShellApps.ask): Allow or Deny, remembered for the package until the owner
// takes it back on the Shell access screen. Not shown over the lock screen:
// allowing needs the phone unlocked.

package at.quickme.prism.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.quickme.prism.ui.CodeStyle
import at.quickme.prism.ui.PrismIcons
import at.quickme.prism.ui.PrismTheme
import at.quickme.prism.ui.ShapedIcon
import at.quickme.prism.ui.prism

class PermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(ShellApps.EXTRA_PACKAGE)
        if (pkg == null) {
            finish()
            return
        }
        val label = intent.getStringExtra(ShellApps.EXTRA_LABEL) ?: pkg
        val uid = intent.getIntExtra(ShellApps.EXTRA_UID, -1)
        val pid = intent.getIntExtra(ShellApps.EXTRA_PID, -1)
        val requestCode = intent.getIntExtra(ShellApps.EXTRA_REQUEST_CODE, 0)
        setContent {
            PrismTheme {
                PermissionContent(label, pkg) { allowed ->
                    ShellApps.decide(this, uid, pid, requestCode, pkg, allowed)
                    finish()
                }
            }
        }
    }

    /** Another app asks while this screen is open: show that one (the first one's notification stays). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

/** The prompt for [label] ([pkg]); the activity and the previews pass them in. */
@Composable
internal fun PermissionContent(label: String, pkg: String, onDecide: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val tone = MaterialTheme.prism.warning
                ShapedIcon(PrismIcons.Terminal, MaterialShapes.Cookie9Sided, tone.container, tone.onContainer, size = 88.dp)
                Text("Shell access for $label?", style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
                Text(pkg, style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text(
                    "It asks through the Shizuku API. Allowed, it can run any command as the adb shell user (uid 2000) and " +
                        "call system services as the shell, as an app with Shizuku can: install and remove apps, grant " +
                        "permissions, read logs. Prism remembers the answer; take it back on the Shell access screen.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onDecide(false) }, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
                ) { Text("Deny") }
                Button(
                    onClick = { onDecide(true) }, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
                ) { Text("Allow") }
            }
        }
    }
}
