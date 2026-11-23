// The type scale in Prism's theme: the text and brand faces (Google Sans
// Flex, the brand one rounded) and the code face (Google Sans Code), to see
// that the renderer has the fonts.

package at.quickme.prism.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.quickme.prism.ui.CodeStyle

@PartPreviews
@Composable
private fun TypePreview() = Part {
    val t = MaterialTheme.typography
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Prism", style = t.displayMediumEmphasized)
        Text("Deploy main to the cluster?", style = t.headlineMediumEmphasized)
        Text("KubeNodeNotReady", style = t.titleLarge)
        Text("Node david-nuc-2 has been NotReady for 12 minutes", style = t.titleMedium)
        Text("The kubelet stopped posting its status. Pods on the node are being evicted.", style = t.bodyMedium)
        Text("machine check · 3 h ago", style = t.labelMedium)
        Text("# systemctl restart kubelet.service", style = CodeStyle)
    }
}
