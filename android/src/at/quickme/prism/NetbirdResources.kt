package at.quickme.prism

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.quickme.prism.netbird.Netbird
import at.quickme.prism.ui.Panel
import at.quickme.prism.ui.SectionHeader
import at.quickme.prism.ui.SettingGroup
import at.quickme.prism.ui.SettingToggle
import at.quickme.prism.ui.dpadStop

/** Routes can be changed in place; saved routes remain editable when no longer offered. */
internal fun LazyListScope.netbirdResourceItems(
    resources: List<Netbird.Resource>,
    enabled: Set<String>,
    connected: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    val missing = (enabled - resources.map { it.id }.toSet()).sorted()
    item {
        SectionHeader("Resources", Modifier.padding(start = 4.dp, top = 8.dp))
        Text(
            "Switch on a resource to route it through NetBird.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (resources.isEmpty()) {
        item {
            Panel(Modifier.dpadStop()) {
                Text(
                    if (connected) "The NetBird network offers this phone no resources."
                    else "Resources appear once NetBird is connected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    if (resources.isNotEmpty()) {
        item {
            SettingGroup(resources.size) { index, shape ->
                val r = resources[index]
                val peer = r.peer.substringBefore('.')
                val detail = listOfNotNull(
                    r.network.ifEmpty { null },
                    if (peer.isEmpty()) null else "via $peer",
                    if (r.online) null else "offline",
                ).joinToString(" · ")
                SettingToggle(r.id, detail, r.id in enabled, { onToggle(r.id, it) }, shape = shape)
            }
        }
    }
    if (missing.isNotEmpty()) {
        item { SectionHeader("Not offered now", Modifier.padding(start = 4.dp)) }
        item {
            SettingGroup(missing.size) { index, shape ->
                val id = missing[index]
                SettingToggle(id, "Routed again if the network offers it", true, { onToggle(id, it) }, shape = shape)
            }
        }
    }
}
