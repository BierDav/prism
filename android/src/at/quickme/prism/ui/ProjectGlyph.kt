// A T3 project's icon as T3's own app draws it without the repository's
// favicon: the emoji or monogram chosen in T3, a Lucide icon as the name's
// monogram in its colour, and otherwise the name's monogram in a colour of
// the name (apps/mobile/src/lib/projectIcon.ts).

package at.quickme.prism.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.quickme.prism.shared.ProjectIcon
import java.text.Normalizer
import java.util.Locale

@Composable
fun ProjectGlyph(name: String, icon: ProjectIcon?, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val sp = with(LocalDensity.current) { size.toSp() }
    if (icon?.kind == "emoji" && icon.emoji.isNotEmpty()) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(icon.emoji, style = TextStyle(fontSize = sp * 0.8f, lineHeight = sp))
        }
        return
    }
    val text = icon?.text?.takeIf { icon.kind == "monogram" && it.isNotEmpty() } ?: monogram(name)
    val color = ProjectColors[icon?.color?.takeIf { icon.kind != "emoji" }] ?: ProjectColors.getValue(monogramColor(name))
    Box(
        modifier.size(size).background(color.copy(alpha = 0.18f), RoundedCornerShape(size * 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(
                fontSize = sp * (if (text.codePointCount(0, text.length) <= 2) 0.5f else 0.36f),
                lineHeight = sp,
                fontWeight = FontWeight.Bold,
                color = color,
            ),
            maxLines = 1,
        )
    }
}

/** Tailwind's 500 shades, by the names T3 stores. */
private val ProjectColors = mapOf(
    "gray" to Color(0xFF6B7280), "red" to Color(0xFFEF4444), "orange" to Color(0xFFF97316),
    "amber" to Color(0xFFF59E0B), "yellow" to Color(0xFFEAB308), "lime" to Color(0xFF84CC16),
    "green" to Color(0xFF22C55E), "emerald" to Color(0xFF10B981), "teal" to Color(0xFF14B8A6),
    "cyan" to Color(0xFF06B6D4), "sky" to Color(0xFF0EA5E9), "blue" to Color(0xFF3B82F6),
    "indigo" to Color(0xFF6366F1), "violet" to Color(0xFF8B5CF6), "purple" to Color(0xFFA855F7),
    "fuchsia" to Color(0xFFD946EF), "pink" to Color(0xFFEC4899), "rose" to Color(0xFFF43F5E),
)

private val Word = Regex("[\\p{L}\\p{N}]+")
private val Number = Regex("\\p{N}")

private fun glyphs(s: String): List<String> = s.codePoints().toArray().map { String(Character.toChars(it)) }

/** Two letters of the name: its first, then a digit after it, the last word's first, or its last. */
internal fun monogram(name: String): String {
    val words = Word.findAll(Normalizer.normalize(name, Normalizer.Form.NFKC).trim()).map { it.value }.toList()
    val first = glyphs(words.firstOrNull() ?: return "PR")
    val second = first.drop(1).firstOrNull { Number.matches(it) }
        ?: (if (words.size > 1) glyphs(words.last()).firstOrNull() else first.last())
        ?: first[0]
    return glyphs((first[0] + second).uppercase(Locale.ROOT)).take(2).joinToString("")
}

/** The colour T3 gives a name without one chosen, in T3's order of names. */
internal fun monogramColor(name: String): String {
    val seed = Normalizer.normalize(name, Normalizer.Form.NFKC).trim().lowercase(Locale.US).ifEmpty { "project" }
    val names = ProjectColors.keys.toList()
    var index = 0
    seed.codePoints().forEach { index = (index * 31 + it) % names.size }
    return names[index]
}
