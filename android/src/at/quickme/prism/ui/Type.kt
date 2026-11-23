// Type: the phone's own Google Sans, as Gemini and Pixel's apps set it (a
// Pixel keeps the variable GoogleSans-Regular.ttf in /product/fonts; apps may
// read it): optical size 17 for text (the system's google-sans-text), 18 for
// headlines (google-sans). Without it, Google Sans Flex (rounded for
// headlines), else the platform's sans. Commands and output are in Google Sans
// Code (res/font, from default.nix).
//
// The previews' renderer (android/previews) draws variable fonts at their
// default instance only, so it names a directory of static instances of both
// in the system property at.quickme.prism.previewFonts, which a phone
// never has.

package at.quickme.prism.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import at.quickme.prism.R
import java.io.File

private val WEIGHTS = listOf(300, 400, 500, 600, 700, 800)

/** The previews' static fonts (default.nix, `previewFonts`); null on a phone. */
private val previewFonts: File? = System.getProperty("at.quickme.prism.previewFonts")?.let(::File)

private fun flex(rounded: Boolean): FontFamily? {
    previewFonts?.let { dir ->
        return FontFamily(WEIGHTS.map { w -> Font(File(dir, "GoogleSansFlex-$w" + (if (rounded) "-Rounded" else "") + ".ttf"), FontWeight(w)) })
    }
    val file = listOf("/product/fonts/GoogleSansFlex-Regular.ttf", "/system/fonts/GoogleSansFlex-Regular.ttf")
        .map(::File).firstOrNull { it.canRead() } ?: return null
    return try {
        FontFamily(WEIGHTS.map { w ->
            Font(
                file,
                FontWeight(w),
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(w),
                    FontVariation.Setting("ROND", if (rounded) 100f else 0f),
                ),
            )
        })
    } catch (e: RuntimeException) {
        null
    }
}

/** The phone's Google Sans at optical size [opsz], or null without it (and in the previews). */
private fun googleSans(opsz: Float): FontFamily? {
    if (previewFonts != null) return null
    val file = listOf("/product/fonts/GoogleSans-Regular.ttf", "/system/fonts/GoogleSans-Regular.ttf")
        .map(::File).firstOrNull { it.canRead() } ?: return null
    return try {
        FontFamily(WEIGHTS.map { w ->
            Font(
                file,
                FontWeight(w),
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(w),
                    FontVariation.Setting("opsz", opsz),
                    FontVariation.Setting("GRAD", 0f),
                ),
            )
        })
    } catch (e: RuntimeException) {
        null
    }
}

/** Text: Google Sans Text, else Google Sans Flex, else the platform sans. */
val TextFamily: FontFamily by lazy { googleSans(17f) ?: flex(rounded = false) ?: FontFamily.SansSerif }

/** Headlines and numbers: Google Sans, else Google Sans Flex rounded, else the platform sans. */
val BrandFamily: FontFamily by lazy { googleSans(18f) ?: flex(rounded = true) ?: FontFamily.SansSerif }

/** Commands, output, keys: Google Sans Code. */
val CodeFamily: FontFamily by lazy {
    previewFonts?.let { dir -> return@lazy FontFamily(listOf(400, 500, 700).map { w -> Font(File(dir, "GoogleSansCode-$w.ttf"), FontWeight(w)) }) }
    FontFamily(listOf(400, 500, 700).map { w ->
        Font(R.font.google_sans_code, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    })
}

@Composable
fun prismTypography(): Typography = remember {
    val base = Typography()
    fun TextStyle.text() = copy(fontFamily = TextFamily)
    fun TextStyle.brand() = copy(fontFamily = BrandFamily)
    fun TextStyle.emphasized() = copy(fontWeight = FontWeight(maxOf(600, (fontWeight ?: FontWeight.Normal).weight + 200)))
    Typography(
        displayLarge = base.displayLarge.brand(),
        displayMedium = base.displayMedium.brand(),
        displaySmall = base.displaySmall.brand(),
        headlineLarge = base.headlineLarge.brand(),
        headlineMedium = base.headlineMedium.brand(),
        headlineSmall = base.headlineSmall.brand(),
        titleLarge = base.titleLarge.brand(),
        titleMedium = base.titleMedium.text().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.text().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.text(),
        bodyMedium = base.bodyMedium.text(),
        bodySmall = base.bodySmall.text(),
        labelLarge = base.labelLarge.text().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.text().copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.text().copy(fontWeight = FontWeight.SemiBold),
        displayLargeEmphasized = base.displayLarge.brand().emphasized(),
        displayMediumEmphasized = base.displayMedium.brand().emphasized(),
        displaySmallEmphasized = base.displaySmall.brand().emphasized(),
        headlineLargeEmphasized = base.headlineLarge.brand().emphasized(),
        headlineMediumEmphasized = base.headlineMedium.brand().emphasized(),
        headlineSmallEmphasized = base.headlineSmall.brand().emphasized(),
        titleLargeEmphasized = base.titleLarge.brand().emphasized(),
        titleMediumEmphasized = base.titleMedium.text().copy(fontWeight = FontWeight.Bold),
        titleSmallEmphasized = base.titleSmall.text().copy(fontWeight = FontWeight.Bold),
        bodyLargeEmphasized = base.bodyLarge.text().copy(fontWeight = FontWeight.Medium),
        bodyMediumEmphasized = base.bodyMedium.text().copy(fontWeight = FontWeight.Medium),
        bodySmallEmphasized = base.bodySmall.text().copy(fontWeight = FontWeight.Medium),
        labelLargeEmphasized = base.labelLarge.text().copy(fontWeight = FontWeight.Bold),
        labelMediumEmphasized = base.labelMedium.text().copy(fontWeight = FontWeight.Bold),
        labelSmallEmphasized = base.labelSmall.text().copy(fontWeight = FontWeight.Bold),
    )
}

/** Code at body size. */
val CodeStyle = TextStyle(fontFamily = CodeFamily, fontSize = 15.sp, lineHeight = 22.sp)
