// Icons material-icons-core lacks, drawn in its 24-unit grid with round
// 2-unit strokes (the look of Material Symbols Rounded).

package at.quickme.prism.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private class IconScope(val builder: ImageVector.Builder) {
    fun stroke(width: Float = 2f, block: PathBuilder.() -> Unit) {
        builder.path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    fun fill(alpha: Float = 1f, block: PathBuilder.() -> Unit) {
        builder.path(fill = SolidColor(Color.Black), fillAlpha = alpha, pathBuilder = block)
    }
}

private fun icon(name: String, block: IconScope.() -> Unit): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    IconScope(b).block()
    return b.build()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
    close()
}

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    moveTo(l + radius, t)
    lineTo(r - radius, t)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r, y1 = t + radius)
    lineTo(r, b - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r - radius, y1 = b)
    lineTo(l + radius, b)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = l, y1 = b - radius)
    lineTo(l, t + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = l + radius, y1 = t)
    close()
}

object PrismIcons {
    /** Prism's glyph: a beam into a prism and three rays out of it. */
    val Prism = icon("Prism") {
        fill(0.35f) { moveTo(11.2f, 5.2f); lineTo(17.6f, 17.6f); lineTo(4.8f, 17.6f); close() }
        stroke(1.8f) {
            moveTo(11.2f, 5.2f); lineTo(17.6f, 17.6f); lineTo(4.8f, 17.6f); close()
            moveTo(1.6f, 13.6f); lineTo(8.2f, 11.6f)
            moveTo(14.4f, 11.4f); lineTo(22.4f, 8.6f)
            moveTo(14.4f, 11.4f); lineTo(22.4f, 11.6f)
            moveTo(14.4f, 11.4f); lineTo(22.4f, 14.6f)
        }
    }

    val Fingerprint = icon("Fingerprint") {
        stroke(1.7f) {
            moveTo(4.6f, 9.6f); curveTo(6f, 5.8f, 8.7f, 3.9f, 12f, 3.9f); curveTo(15.3f, 3.9f, 18f, 5.8f, 19.4f, 9.6f)
            moveTo(6.9f, 19.2f); curveTo(6.2f, 17.2f, 6.1f, 14.6f, 6.6f, 12.6f); curveTo(7.3f, 9.6f, 9.4f, 7.6f, 12f, 7.6f)
            curveTo(14.6f, 7.6f, 16.7f, 9.6f, 17.4f, 12.6f); curveTo(17.7f, 14f, 17.6f, 15.6f, 17.3f, 17f)
            moveTo(10f, 20.4f); curveTo(9.3f, 18.4f, 9.1f, 15.7f, 9.6f, 13.6f); curveTo(9.9f, 12.2f, 10.8f, 11.3f, 12f, 11.3f)
            curveTo(13.2f, 11.3f, 14.1f, 12.2f, 14.4f, 13.6f); curveTo(14.8f, 15.5f, 14.5f, 17.8f, 13.9f, 19.8f)
            moveTo(12f, 14.6f); curveTo(12.1f, 16.6f, 11.8f, 18.6f, 12.6f, 20.8f)
        }
    }

    val Terminal = icon("Terminal") {
        stroke { roundRect(3f, 4.5f, 21f, 19.5f, 3f) }
        stroke { moveTo(7f, 9.5f); lineTo(10f, 12f); lineTo(7f, 14.5f); moveTo(12.5f, 15f); lineTo(17f, 15f) }
    }

    val Server = icon("Server") {
        stroke { roundRect(3.5f, 4f, 20.5f, 10.5f, 2.5f); roundRect(3.5f, 13.5f, 20.5f, 20f, 2.5f) }
        fill { circle(7.5f, 7.25f, 1.25f); circle(7.5f, 16.75f, 1.25f) }
    }

    val Shield = icon("Shield") {
        stroke {
            moveTo(12f, 3f); lineTo(19f, 5.8f); lineTo(19f, 11.2f)
            curveTo(19f, 15.6f, 16.1f, 19.3f, 12f, 21f); curveTo(7.9f, 19.3f, 5f, 15.6f, 5f, 11.2f)
            lineTo(5f, 5.8f); close()
        }
        stroke { moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 10f) }
    }

    val ShieldLock = icon("ShieldLock") {
        stroke {
            moveTo(12f, 3f); lineTo(19f, 5.8f); lineTo(19f, 11.2f)
            curveTo(19f, 15.6f, 16.1f, 19.3f, 12f, 21f); curveTo(7.9f, 19.3f, 5f, 15.6f, 5f, 11.2f)
            lineTo(5f, 5.8f); close()
        }
        fill { roundRect(9f, 11f, 15f, 15.6f, 1.2f) }
        stroke(1.6f) { moveTo(10.3f, 11f); lineTo(10.3f, 9.6f); curveTo(10.3f, 8.6f, 11.1f, 7.9f, 12f, 7.9f); curveTo(12.9f, 7.9f, 13.7f, 8.6f, 13.7f, 9.6f); lineTo(13.7f, 11f) }
    }

    val Copy = icon("Copy") {
        stroke { roundRect(8.5f, 8.5f, 20f, 20f, 2.5f) }
        stroke { moveTo(4.5f, 15.5f); lineTo(4.5f, 6.5f); curveTo(4.5f, 5.4f, 5.4f, 4.5f, 6.5f, 4.5f); lineTo(15.5f, 4.5f) }
    }

    val OpenInNew = icon("OpenInNew") {
        stroke {
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
            moveTo(20f, 4f); lineTo(11.5f, 12.5f)
            moveTo(18f, 14f); lineTo(18f, 18f); curveTo(18f, 19.1f, 17.1f, 20f, 16f, 20f); lineTo(6f, 20f)
            curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f); lineTo(4f, 8f); curveTo(4f, 6.9f, 4.9f, 6f, 6f, 6f); lineTo(10f, 6f)
        }
    }

    val Bolt = icon("Bolt") {
        fill { moveTo(13.4f, 2.4f); lineTo(5.2f, 13.4f); lineTo(11.2f, 13.4f); lineTo(10.4f, 21.6f); lineTo(18.8f, 10.4f); lineTo(12.8f, 10.4f); close() }
    }

    /** An agent at work. */
    val Sparkle = icon("Sparkle") {
        fill {
            moveTo(10f, 3f); curveTo(10.6f, 7.6f, 12.4f, 9.4f, 17f, 10f); curveTo(12.4f, 10.6f, 10.6f, 12.4f, 10f, 17f)
            curveTo(9.4f, 12.4f, 7.6f, 10.6f, 3f, 10f); curveTo(7.6f, 9.4f, 9.4f, 7.6f, 10f, 3f); close()
            moveTo(17.5f, 14f); curveTo(17.8f, 16.1f, 18.4f, 16.7f, 20.5f, 17f); curveTo(18.4f, 17.3f, 17.8f, 17.9f, 17.5f, 20f)
            curveTo(17.2f, 17.9f, 16.6f, 17.3f, 14.5f, 17f); curveTo(16.6f, 16.7f, 17.2f, 16.1f, 17.5f, 14f); close()
        }
    }

    /** NetBird: peers in a mesh. */
    val Mesh = icon("Mesh") {
        stroke { circle(12f, 5.5f, 2.3f); circle(5.5f, 17.5f, 2.3f); circle(18.5f, 17.5f, 2.3f) }
        stroke(1.6f) { moveTo(10.9f, 7.5f); lineTo(6.7f, 15.4f); moveTo(13.1f, 7.5f); lineTo(17.3f, 15.4f); moveTo(7.8f, 17.5f); lineTo(16.2f, 17.5f) }
    }

    val Phone = icon("Phone") {
        stroke { roundRect(6.5f, 2.5f, 17.5f, 21.5f, 2.8f) }
        stroke { moveTo(10.5f, 18.4f); lineTo(13.5f, 18.4f) }
    }

    val Tv = icon("Tv") {
        stroke { roundRect(2.5f, 4f, 21.5f, 17f, 2.2f) }
        stroke { moveTo(8f, 20.5f); lineTo(16f, 20.5f) }
    }

    val Laptop = icon("Laptop") {
        stroke { roundRect(5f, 5f, 19f, 15.5f, 1.8f) }
        stroke { moveTo(2.8f, 19f); lineTo(21.2f, 19f) }
    }

    /** Critical: an octagon with an exclamation mark. */
    val Octagon = icon("Octagon") {
        stroke { moveTo(8.3f, 3f); lineTo(15.7f, 3f); lineTo(21f, 8.3f); lineTo(21f, 15.7f); lineTo(15.7f, 21f); lineTo(8.3f, 21f); lineTo(3f, 15.7f); lineTo(3f, 8.3f); close() }
        stroke { moveTo(12f, 7.6f); lineTo(12f, 12.8f) }
        fill { circle(12f, 16.4f, 1.25f) }
    }

    val Triangle = icon("TriangleAlert") {
        stroke { moveTo(12f, 3.8f); lineTo(21.2f, 19.6f); lineTo(2.8f, 19.6f); close() }
        stroke { moveTo(12f, 9.4f); lineTo(12f, 13.8f) }
        fill { circle(12f, 16.8f, 1.2f) }
    }

    val InfoCircle = icon("InfoCircle") {
        stroke { circle(12f, 12f, 9f) }
        stroke { moveTo(12f, 11f); lineTo(12f, 16.5f) }
        fill { circle(12f, 7.7f, 1.25f) }
    }

    val Key = icon("Key") {
        stroke { circle(8f, 15.5f, 4.5f) }
        stroke { moveTo(11.3f, 12.2f); lineTo(20f, 3.5f); moveTo(16.8f, 6.7f); lineTo(19f, 8.9f); moveTo(14.6f, 8.9f); lineTo(16.3f, 10.6f) }
    }

    val Battery = icon("Battery") {
        stroke { roundRect(7f, 4.5f, 17f, 21f, 2.2f) }
        stroke { moveTo(10.2f, 2.5f); lineTo(13.8f, 2.5f) }
        fill(0.85f) { roundRect(9.2f, 11f, 14.8f, 18.8f, 0.8f) }
    }

    val Clock = icon("Clock") {
        stroke { circle(12f, 12f, 9f) }
        stroke { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.2f, 14f) }
    }

    /** Voice input. */
    val Mic = icon("Mic") {
        stroke { roundRect(9f, 3f, 15f, 14f, 3f) }
        stroke { moveTo(5.5f, 11f); curveTo(5.5f, 14.6f, 8.4f, 17.5f, 12f, 17.5f); curveTo(15.6f, 17.5f, 18.5f, 14.6f, 18.5f, 11f); moveTo(12f, 17.5f); lineTo(12f, 21f) }
    }

    /** Stop: a rounded square. */
    val Stop = icon("Stop") {
        stroke { roundRect(6f, 6f, 18f, 18f, 3f) }
    }

    /** Send, as an arrow up. */
    val Up = icon("Up") {
        stroke { moveTo(12f, 19f); lineTo(12f, 5.5f); moveTo(6f, 11.5f); lineTo(12f, 5.5f); lineTo(18f, 11.5f) }
    }

    val Chat = icon("Chat") {
        stroke {
            moveTo(4f, 6.5f); curveTo(4f, 5.1f, 5.1f, 4f, 6.5f, 4f); lineTo(17.5f, 4f); curveTo(18.9f, 4f, 20f, 5.1f, 20f, 6.5f)
            lineTo(20f, 14.5f); curveTo(20f, 15.9f, 18.9f, 17f, 17.5f, 17f); lineTo(9f, 17f); lineTo(4.5f, 20.5f); lineTo(4.5f, 17f)
            curveTo(4.2f, 16.6f, 4f, 16.1f, 4f, 15.5f); close()
        }
        stroke(1.6f) { moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 12.5f); lineTo(13f, 12.5f) }
    }

    val Palette = icon("Palette") {
        stroke {
            moveTo(12f, 3f); curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f)
            curveTo(13.1f, 21f, 13.8f, 20.2f, 13.8f, 19.2f); curveTo(13.8f, 18.1f, 13f, 17.6f, 13f, 16.6f)
            curveTo(13f, 15.5f, 13.9f, 14.6f, 15f, 14.6f); lineTo(17f, 14.6f); curveTo(19.2f, 14.6f, 21f, 12.8f, 21f, 10.6f)
            curveTo(21f, 6.4f, 17f, 3f, 12f, 3f); close()
        }
        fill { circle(7.6f, 11.6f, 1.3f); circle(10f, 7.6f, 1.3f); circle(14.6f, 7.6f, 1.3f) }
    }

    val Bug = icon("Bug") {
        stroke { roundRect(7.5f, 8f, 16.5f, 20f, 4.5f) }
        stroke(1.7f) {
            moveTo(9.5f, 8f); curveTo(9.5f, 6.3f, 10.6f, 5f, 12f, 5f); curveTo(13.4f, 5f, 14.5f, 6.3f, 14.5f, 8f)
            moveTo(7.5f, 12f); lineTo(4f, 11f); moveTo(16.5f, 12f); lineTo(20f, 11f)
            moveTo(7.5f, 16f); lineTo(4f, 17.5f); moveTo(16.5f, 16f); lineTo(20f, 17.5f)
            moveTo(12f, 11f); lineTo(12f, 19.5f)
        }
    }
}
