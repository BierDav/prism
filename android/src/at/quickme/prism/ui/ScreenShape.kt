// The display's corners: the radius of each, as the panel has them
// (WindowInsets.getRoundedCorner), for shapes that grow to fill the screen
// and should meet its edge exactly.

package at.quickme.prism.ui

import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/** Each corner's radius in pixels: top left, top right, bottom right, bottom left. */
class ScreenCorners(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float)

/** The screen's corners; 36 dp for a corner the system doesn't report. */
@Composable
fun rememberScreenCorners(): ScreenCorners {
    val view = LocalView.current
    val fallback = with(LocalDensity.current) { androidx.compose.ui.unit.Dp(36f).toPx() }
    return remember(view, fallback) {
        val insets = view.rootWindowInsets
        fun r(position: Int) = insets?.getRoundedCorner(position)?.radius?.toFloat()?.takeIf { it > 0f } ?: fallback
        ScreenCorners(
            r(RoundedCorner.POSITION_TOP_LEFT), r(RoundedCorner.POSITION_TOP_RIGHT),
            r(RoundedCorner.POSITION_BOTTOM_RIGHT), r(RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }
}
