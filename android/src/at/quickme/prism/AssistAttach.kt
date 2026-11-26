// The assistant overlay's attachments, in Gemini's look (the Google app,
// GSA 17.76: its input's attachment sheet and input chips):
//
// - The + opens the attach menu: a row of tiles, Camera, Photos and Files
//   (Gemini's order, without its screen share: the assistant takes a
//   screenshot itself when it needs one). Each tile is
//   104 dp wide (narrower here when three don't fit), its corners 40 dp, filled
//   in Gemini's surfaceDim with the label in onSurface: a 32 dp icon, 8 dp,
//   then the label at 15/20 sp, 20 dp above and below. The row has 12 dp
//   between tiles and 16 dp at its sides, 12 dp above and 20 dp below, in a
//   card in the chips' look with 28 dp corners.
// - Camera turns the menu into the camera itself, in the same card
//   (AssistCamera.kt, Gemini's inline camera); its photo lands with the
//   attachments like a picked one.
// - What is attached shows at the top of the input card, 12 dp in from its
//   sides and top, 8 dp apart: each a 48 dp pill in onSurfaceLow (white at
//   12 % on dark, black at 8 % on light); a picture as a 40 dp circle 4 dp
//   in, a file as a 48 dp circle with its icon and its name (at most 180 dp,
//   one line); then the remove ×, a 20 dp white circle with a 16 dp black
//   cross, and 12 dp to the pill's end.

package at.quickme.prism

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.quickme.prism.assistant.Attached
import at.quickme.prism.ui.GeminiIcons

/** What the + menu's tiles do. */
internal class AttachActions(
    /** Opens the camera in the menu ([cameraSheet]). */
    val camera: () -> Unit,
    val photos: () -> Unit,
    val files: () -> Unit,
    /** The camera, shown in the menu in place of the tiles while it is open (AssistCamera.kt); null: the tiles. */
    val cameraSheet: (@Composable () -> Unit)? = null,
)

/** Gemini's neutral colours for the attach UI (LumiSysColor), dark or light. */
private class AttachColors(dark: Boolean) {
    val tile = if (dark) Color(0xFF141414) else Color(0xFFF2F0F0)
    val onTile = if (dark) Color(0xFFE0E0E0) else Color(0xFF000000)
    val chip = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val card = if (dark) Color.Black else Color.White
}

@Composable
private fun attachColors(): AttachColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) { AttachColors(dark) }
}

private val TileShape = RoundedCornerShape(40.dp)
private val TileGap = 12.dp
private val MenuSide = 16.dp
private val MenuRadius = 28.dp
private val LabelStyleSize = 15.sp
private val LabelLineHeight = 20.sp

/** The + menu, as it opens above the input card. */
@Composable
internal fun AttachMenu(actions: AttachActions, modifier: Modifier = Modifier) {
    val colors = attachColors()
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    Box(
        modifier
            .geminiChip(colors.card, scheme.primary, dark, MenuRadius)
            .animateContentSize()
            .semantics { contentDescription = "Add to your request" },
    ) {
        val sheet = actions.cameraSheet
        if (sheet != null) sheet() else Row(
            // Three tiles, each exactly a third of what is between the sides.
            Modifier.fillMaxWidth().padding(start = MenuSide, end = MenuSide, top = 12.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalAlignment = Alignment.Top,
        ) {
            AttachTile(GeminiIcons.Camera, "Camera", colors, actions.camera, Modifier.weight(1f))
            AttachTile(GeminiIcons.Photo, "Photos", colors, actions.photos, Modifier.weight(1f))
            AttachTile(GeminiIcons.AttachFile, "Files", colors, actions.files, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AttachTile(icon: ImageVector, label: String, colors: AttachColors, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        shape = TileShape,
        color = colors.tile,
        contentColor = colors.onTile,
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = LabelStyleSize, lineHeight = LabelLineHeight),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** What is attached, at the top of the input card; nothing when empty. */
@Composable
internal fun AttachmentRow(attachments: List<Attached>, onRemove: (Attached) -> Unit, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = attachColors()
    AnimatedVisibility(
        visible = attachments.isNotEmpty(),
        enter = if (animate) expandVertically(tween(250)) + fadeIn(tween(250)) else EnterTransition.None,
        exit = if (animate) shrinkVertically(tween(200)) + fadeOut(tween(150)) else ExitTransition.None,
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attachments.forEach { item ->
                androidx.compose.runtime.key(item) {
                    // Each new one grows in where it lands.
                    val shown = remember { MutableTransitionState(!animate).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = shown,
                        enter = if (animate) scaleIn(initialScale = 0.6f) + fadeIn() else EnterTransition.None,
                        exit = if (animate) scaleOut(targetScale = 0.6f) + fadeOut() else ExitTransition.None,
                    ) {
                        AttachmentChip(item, colors) { onRemove(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: Attached, colors: AttachColors, onRemove: () -> Unit) {
    val a = item.attachment
    val image = item.thumbnail
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(colors.chip)
            .semantics(mergeDescendants = true) {
                contentDescription = when {
                    a.screenshot -> "Screenshot attached"
                    a.isImage -> "Image attached"
                    else -> "${a.name} attached"
                }
            }
            .padding(start = if (image != null) 4.dp else 0.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (image != null) {
            Image(image, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(48.dp).clip(CircleShape).background(colors.chip), contentAlignment = Alignment.Center) {
                Icon(GeminiIcons.AttachFile, null, Modifier.size(24.dp), tint = colors.onTile)
            }
            Text(
                a.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = LabelStyleSize, lineHeight = LabelLineHeight),
                color = colors.onTile,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(role = Role.Button, onClickLabel = "Remove", onClick = onRemove)
                .semantics { contentDescription = if (a.isImage) "Remove image attachment" else "Remove attachment" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(GeminiIcons.Close, null, Modifier.size(16.dp), tint = Color.Black)
        }
    }
}
