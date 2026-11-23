// Prism's look on Android: Material 3 Expressive (its motion scheme, shapes
// and emphasized type), the wallpaper's colours where the owner wants them and
// Prism's own night-indigo scheme otherwise, plus the colours that mean the
// same in every app of Prism: one per host (as on the Mac, macos/
// ApprovalPanel.swift HostColor) and one per severity.

package at.quickme.prism.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

/** The owner's choice of colours, kept on the phone. */
object Appearance {
    private const val PREFS = "appearance"

    /** The wallpaper's colours (Material You) rather than Prism's own. */
    var wallpaperColors by mutableStateOf(true)
        private set

    /** Light, dark, or as the system says. */
    enum class Mode(val key: String, val label: String, val night: Int) {
        System("system", "System", UiModeManager.MODE_NIGHT_AUTO),
        Light("light", "Light", UiModeManager.MODE_NIGHT_NO),
        Dark("dark", "Dark", UiModeManager.MODE_NIGHT_YES),
    }

    var mode by mutableStateOf(Mode.System)
        private set

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        wallpaperColors = prefs.getBoolean("wallpaper", true)
        mode = Mode.entries.firstOrNull { it.key == prefs.getString("mode", null) } ?: Mode.System
    }

    /**
     * Android's per-app night mode: it keeps the choice itself, and the
     * windows, the splash screen and Compose all follow it.
     */
    fun setMode(context: Context, m: Mode) {
        mode = m
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m.key).apply()
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(m.night)
    }

    fun setWallpaperColors(context: Context, on: Boolean) {
        wallpaperColors = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("wallpaper", on).apply()
    }
}

// Prism's own scheme: tonal palettes around the icon's indigo (hue ~235),
// a violet tertiary for the prism's far end of the spectrum.
private val BrandLight = lightColorScheme(
    primary = Color(0xFF4553C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF2B39AB),
    inversePrimary = Color(0xFFBCC2FF),
    secondary = Color(0xFF5A5D78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE0FF),
    onSecondaryContainer = Color(0xFF42455F),
    tertiary = Color(0xFF7A4B9C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4DAFF),
    onTertiaryContainer = Color(0xFF603383),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFE2E1EF),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF4553C4),
    inverseSurface = Color(0xFF303037),
    inverseOnSurface = Color(0xFFF2EFF8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFEFEDF6),
    surfaceContainerHigh = Color(0xFFE9E7F1),
    surfaceContainerHighest = Color(0xFFE4E1EB),
    surfaceContainerLow = Color(0xFFF5F2FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDBD9E2),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF0E1D93),
    primaryContainer = Color(0xFF2B39AB),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF4553C4),
    secondary = Color(0xFFC3C4E4),
    onSecondary = Color(0xFF2C2F48),
    secondaryContainer = Color(0xFF42455F),
    onSecondaryContainer = Color(0xFFDFE0FF),
    tertiary = Color(0xFFE5B5FF),
    onTertiary = Color(0xFF481A6A),
    tertiaryContainer = Color(0xFF603383),
    onTertiaryContainer = Color(0xFFF4DAFF),
    background = Color(0xFF121319),
    onBackground = Color(0xFFE4E1EB),
    surface = Color(0xFF121319),
    onSurface = Color(0xFFE4E1EB),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = Color(0xFFBCC2FF),
    inverseSurface = Color(0xFFE4E1EB),
    inverseOnSurface = Color(0xFF303037),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383940),
    surfaceContainer = Color(0xFF1F1F26),
    surfaceContainerHigh = Color(0xFF292A31),
    surfaceContainerHighest = Color(0xFF34343C),
    surfaceContainerLow = Color(0xFF1B1B22),
    surfaceContainerLowest = Color(0xFF0D0E14),
    surfaceDim = Color(0xFF121319),
)

/** A colour role with its container, as M3 has for primary or error. */
@Immutable
data class Tone(val color: Color, val onColor: Color, val container: Color, val onContainer: Color)

/** The colours Material's scheme has no role for. */
@Immutable
data class PrismColors(
    val success: Tone,
    val warning: Tone,
    val info: Tone,
    val critical: Tone,
)

private val LightExtras = PrismColors(
    success = Tone(Color(0xFF1B7A3E), Color.White, Color(0xFFB4F1C1), Color(0xFF00522A)),
    warning = Tone(Color(0xFF8C5000), Color.White, Color(0xFFFFDCBC), Color(0xFF6A3B00)),
    info = Tone(Color(0xFF2C5BC8), Color.White, Color(0xFFDAE2FF), Color(0xFF0D41A6)),
    critical = Tone(Color(0xFFBA1A1A), Color.White, Color(0xFFFFDAD6), Color(0xFF93000A)),
)

private val DarkExtras = PrismColors(
    success = Tone(Color(0xFF8ADBA0), Color(0xFF00391B), Color(0xFF005229), Color(0xFFA7F6BB)),
    warning = Tone(Color(0xFFFFB86E), Color(0xFF4A2800), Color(0xFF6A3B00), Color(0xFFFFDCBC)),
    info = Tone(Color(0xFFB2C5FF), Color(0xFF002B75), Color(0xFF0D41A6), Color(0xFFDAE2FF)),
    critical = Tone(Color(0xFFFFB4AB), Color(0xFF690005), Color(0xFF93000A), Color(0xFFFFDAD6)),
)

val LocalPrismColors = staticCompositionLocalOf { LightExtras }

/** Prism's extra colours, next to MaterialTheme.colorScheme. */
val MaterialTheme.prism: PrismColors
    @Composable get() = LocalPrismColors.current

/** Rounder than the baseline: Expressive leans on large, soft corners. */
private val PrismShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Previews (android/previews) have no wallpaper and no settings: they show Prism's own colours. */
@Composable
fun PrismTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    if (!preview) Appearance.load(context)
    val scheme: ColorScheme = when {
        !preview && Appearance.wallpaperColors && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    CompositionLocalProvider(LocalPrismColors provides if (dark) DarkExtras else LightExtras) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = PrismShapes,
            typography = prismTypography(),
        ) {
            // Text outside a Surface takes the scheme's colour, not black.
            // Focus (a TV's remote, a keyboard) shows as Material's inset
            // ring, which a TV across the room shows; the default, a faint
            // overlay, it doesn't.
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalRippleThemeConfiguration provides RippleDefaults.InsetFocusRingThemeConfiguration,
                content = content,
            )
        }
    }
}

/** The Mac's palette: the same host gets the same colour everywhere (FNV-1a). */
object HostColor {
    private val palette = listOf(
        Color(0.20f, 0.48f, 0.96f), Color(0.55f, 0.33f, 0.90f), Color(0.93f, 0.45f, 0.13f), Color(0.08f, 0.62f, 0.56f),
        Color(0.86f, 0.27f, 0.56f), Color(0.24f, 0.63f, 0.28f), Color(0.36f, 0.36f, 0.86f), Color(0.70f, 0.50f, 0.10f),
        Color(0.10f, 0.55f, 0.80f), Color(0.42f, 0.58f, 0.10f),
    )

    fun index(host: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (b in host.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xff)
            hash *= 16_777_619
        }
        return (hash.toLong() and 0xffffffffL).rem(palette.size).toInt()
    }

    fun of(host: String): Color = palette[index(host)]
}
