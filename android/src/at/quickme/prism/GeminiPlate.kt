// Gemini's input card and chips as the Google app draws them on a Pixel with
// Android 13+ (17.76.14): not shapes but shaders.
//
// The card ("SamsyPlate", ccjw): one AGSL shader over the card and 48 dp
// round it. A pill with a near-black (dark) or white (light) elliptical
// gradient lit from the top, a soft glow of the primary colour along its
// inside edge and a thin primary rim at its bottom. It appears as a circle
// that widens into the pill as it fades in (816 ms). While the edge glow
// plays, its top edge is soft and a field of drifting dots sits in and round
// it; as the glow fades it settles sharp and the dots go (583 ms). While
// something loads, a shimmer of the primary colour runs through it.
//
// Chips (ccki): a rounded rectangle in the same black or white with three
// inner shadows: the energy colour softly along the edge and a thin primary
// rim at the bottom. The answer card is the same shader with Gemini's card
// shadows (cckh.b): the energy colour along the edge and a wider, opaque
// shadow of the fill tinted a quarter towards it.
//
// The shaders are Gemini's, unchanged; the colours Gemini derives from the
// wallpaper's palette with its own algorithm are taken from Material's
// dynamic scheme here (primary, surface variant). Below Android 13 both are
// plain rounded shapes.

package at.quickme.prism

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.PathEasing
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The card stays soft, its dots drifting, while the glow plays, and settles
 * as the glow fades, from [PLATE_SETTLE_START_MS] over [PLATE_SETTLE_MS]
 * (Gemini settles both together).
 */
internal const val PLATE_SETTLE_START_MS = GLOW_FADE_START_MS
internal const val PLATE_SETTLE_MS = GLOW_FADE_MS

/**
 * Material 3's emphasized easing as the Google app ships it
 * (gm3_sys_motion_easing_emphasized), which Gemini's card appears on.
 */
internal val EmphasizedEasing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
)

/**
 * How Gemini's card appears (the Google app's Floaty bubble transition on
 * Pixel 2026, 816 ms), each a function of the milliseconds since the overlay
 * opened. The shader draws the pill growing from a circle in the middle to
 * its full width, so the card widens as it fades in; what is in it grows from
 * a seventh of its size and fades in just after.
 */
internal object PlateAppear {
    const val MS = 816f
    private fun span(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    /** The card's opacity: to half over 222 ms, then whole by 289 ms. */
    fun opacity(t: Float) = if (t < 222f) 0.5f * span(t, 0f, 222f) else 0.5f + 0.5f * span(t, 222f, 289f)

    /** Its scale: from half, linearly, over 300 ms. */
    fun scale(t: Float) = 0.5f + 0.5f * span(t, 0f, 300f)

    /** Circle (0) to full pill (1), from 116 ms to the end. The dots come in alike. */
    fun shape(t: Float) = EmphasizedEasing.transform(span(t, 116f, MS))

    /** What is in the card: its scale, from 0.14 alike. */
    fun contentScale(t: Float) = 0.14f + 0.86f * EmphasizedEasing.transform(span(t, 116f, MS))

    /** And its opacity, from 183 ms to 300 ms. */
    fun contentAlpha(t: Float) = span(t, 183f, 300f)
}

/** The band round the card the shader draws in, for its soft edge and dots. */
private const val PLATE_OVERFLOW_DP = 48f

/** One cycle of the card's clock, as Gemini loops it. */
private const val PLATE_TIMER_LOOP = 13.736f

/**
 * Gemini's "energy" colour for [primary]: the colour its glow, its card's
 * edge glow and dots are drawn in. Gemini derives it from the palette with
 * its own algorithm, and it is never grey: on the owner's near-grey palette
 * its card's rim still reads blue. Here: the primary, its saturation raised
 * to at least 0.3 (Google blue's hue where it has none), its brightness kept.
 */
internal fun energyColor(primary: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
    if (hsv[1] >= ENERGY_MIN_SATURATION) return primary
    if (hsv[1] < 0.04f) hsv[0] = GOOGLE_BLUE_HUE
    hsv[1] = ENERGY_MIN_SATURATION
    return Color(android.graphics.Color.HSVToColor((primary.alpha * 255).toInt(), hsv))
}

private const val ENERGY_MIN_SATURATION = 0.3f
private const val GOOGLE_BLUE_HUE = 217f

/** The card's colours, from the overlay's scheme. */
internal class PlateColors(scheme: ColorScheme, val dark: Boolean) {
    val center = if (dark) Color.Black else Color.White
    val base = scheme.surfaceContainer
    val surfaceMid = scheme.surfaceVariant
    val primary = scheme.primary
    val energy = energyColor(scheme.primary)
    // Gemini's edge in the dark: black towards its surface energy colour
    // (0.4), which is lighter than any Material surface; matched to the
    // owner's screenshot with the outline (tone 60) at 0.35. Light as before.
    val edge = if (dark) lerp(center, scheme.outline, 0.35f) else lerp(center, surfaceMid, 0.6f)
    val midStop = if (dark) 0.3846f else 0.5547f
    val rimAlpha = if (dark) 0.12f else 0.06f
    val outsideDots = if (dark) 0.48f else 0.58f
}

/**
 * Draws Gemini's card behind this element: [ageMs] since it appeared (it
 * settles over [PLATE_SETTLE_MS]), [processing] (0..1) while something loads,
 * at [alpha], with corners of [radius] (a pill when that is half its height).
 */
internal fun Modifier.geminiPlate(
    colors: PlateColors,
    radius: Dp,
    ageMs: () -> Float,
    processing: () -> Float,
    alpha: () -> Float = { 1f },
): Modifier = drawWithCache {
    val plate = if (Build.VERSION.SDK_INT >= 33) runCatching { SamsyPlate(colors, density) }.getOrNull() else null
    val r = radius.toPx()
    onDrawBehind {
        val a = alpha()
        if (a <= 0f) return@onDrawBehind
        var drawn = false
        if (plate != null) drawIntoCanvas { drawn = plate.draw(it.nativeCanvas, this, ageMs(), processing(), a, r) }
        if (!drawn) drawRoundRect(
            colors.center.copy(alpha = a * PlateAppear.opacity(ageMs())),
            cornerRadius = CornerRadius(minOf(r, size.height / 2)),
        )
    }
}

/**
 * A chip in Gemini's chip look (cckh.a), filled [fill], with corners of
 * [radius]: its glow in the energy colour of [primary], its bottom rim in
 * [primary] itself.
 */
internal fun Modifier.geminiChip(fill: Color, primary: Color, dark: Boolean, radius: Dp): Modifier =
    chipShaded(radius, fill) { ChipLook.chip(fill, primary, dark) }

/**
 * A card in Gemini's card look (cckh.b, its answer card): filled [fill],
 * the energy colour of [primary] at 22 % along its inside edge (18 dp) and
 * the fill a quarter of the way to it (24 dp, 3 dp up), no rim.
 */
internal fun Modifier.geminiCard(fill: Color, primary: Color, radius: Dp): Modifier =
    chipShaded(radius, fill) { ChipLook.card(fill, primary) }

private fun Modifier.chipShaded(radius: Dp, fill: Color, look: () -> ChipLook): Modifier = drawWithCache {
    val chip = if (Build.VERSION.SDK_INT >= 33) runCatching { ChipShader() }.getOrNull() else null
    val r = radius.toPx()
    val l = look()
    onDrawBehind {
        var drawn = false
        if (chip != null) drawIntoCanvas { drawn = chip.draw(it.nativeCanvas, size.width, size.height, r, density, l) }
        if (!drawn) drawRoundRect(fill, cornerRadius = CornerRadius(minOf(r, size.height / 2)))
    }
}

/** The chip shader's fill and three inner shadows (colour, blur and y offset in dp), as cckh builds them on Android 14+. */
private class ChipLook(
    val fill: Color,
    val shadows: List<Triple<Color, Float, Float>>,
) {
    companion object {
        fun chip(fill: Color, primary: Color, dark: Boolean) = ChipLook(
            fill,
            listOf(
                // The fill itself on Android 14+: no visible change.
                Triple(fill, 12f, -3f),
                Triple(energyColor(primary).copy(alpha = 0.22f), 18f, 0f),
                Triple(primary.copy(alpha = if (dark) 0.12f else 0.06f), 3f, -3f),
            ),
        )

        fun card(fill: Color, primary: Color): ChipLook {
            val energy = energyColor(primary)
            return ChipLook(
                fill,
                listOf(
                    Triple(energy.copy(alpha = 0.22f), 18f, 0f),
                    Triple(lerp(fill, energy, 0.25f), 24f, -3f),
                    Triple(Color.Transparent, 0f, 0f),
                ),
            )
        }
    }
}

@RequiresApi(33)
private class SamsyPlate(private val colors: PlateColors, private val density: Float) {
    private val shader = RuntimeShader(PLATE_SHADER)
    private val paint = Paint().apply { shader = this@SamsyPlate.shader }

    init {
        shader.setFloatUniform("u_uInsidePadding", PLATE_OVERFLOW_DP)
        shader.setFloatUniform("u_insideParticlesVisibility", 0.5f)
        shader.setFloatUniform("u_outsideParticlesVisibility", colors.outsideDots)
        shader.setFloatUniform("u_dotSpacing", 0.52f)
        shader.setColorUniform("u_baseColor", colors.base.toArgb())
        shader.setColorUniform("u_dodgeColor", colors.surfaceMid.toArgb())
        // As Gemini's factory (cckb) sets them: the dots, the shimmer and the
        // ambient edge glow in the energy colour, the bottom rim in the primary.
        shader.setColorUniform("u_particleColor", colors.energy.toArgb())
        shader.setColorUniform("u_outsideParticleColor", colors.energy.toArgb())
        shader.setColorUniform("u_processingColor1", lerp(colors.center, colors.energy, 0.5f).toArgb())
        shader.setColorUniform("u_processingColor2", colors.energy.toArgb())
        shader.setColorUniform("u_bgCenterColor", colors.center.toArgb())
        shader.setColorUniform("u_bgEdgeColor", colors.edge.toArgb())
        shader.setColorUniform("u_innerShadowAmbientColor", colors.energy.toArgb())
        shader.setColorUniform("u_innerShadowBottomColor", colors.primary.toArgb())
        shader.setFloatUniform("u_bgMidStop", colors.midStop)
        shader.setFloatUniform("u_innerShadowRimAlpha", colors.rimAlpha)
        shader.setFloatUniform("u_DPI", density)
    }

    /** Whether it drew: only on a hardware canvas. */
    fun draw(canvas: android.graphics.Canvas, scope: DrawScope, ageMs: Float, processing: Float, alpha: Float, radius: Float): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        val pad = PLATE_OVERFLOW_DP * density
        val w = scope.size.width + 2 * pad
        val h = scope.size.height + 2 * pad
        // What settles as the card appears: soft top and bottom, the inner
        // shadow's blur and offset, the dots.
        val left = 1f - ((ageMs - PLATE_SETTLE_START_MS) / PLATE_SETTLE_MS).coerceIn(0f, 1f)
        val seconds = ageMs / 1000f
        val drift = 0.32f * seconds
        shader.setFloatUniform("uResolution", w, h)
        shader.setFloatUniform("u_displayWidth", w / density)
        shader.setFloatUniform("u_displayHeight", h / density)
        shader.setFloatUniform("u_timer", seconds % PLATE_TIMER_LOOP)
        shader.setFloatUniform("u_accumulatedNoiseOffset", -drift, drift)
        shader.setFloatUniform("u_uSoftnessTop", 10f * left)
        shader.setFloatUniform("u_uSoftnessBottom", 0.5f * left)
        shader.setFloatUniform("u_shadowBlurTopPixels", 12f * left)
        shader.setFloatUniform("u_shadowBlurBottomPixels", 4f * left)
        shader.setFloatUniform("u_shadowOffsetPixels", -3f * left, -2f * left)
        shader.setFloatUniform("u_particleOpacity", PlateAppear.shape(ageMs) * left)
        shader.setFloatUniform("u_plateScale", PlateAppear.scale(ageMs))
        shader.setFloatUniform("u_shapeProgress", PlateAppear.shape(ageMs))
        shader.setFloatUniform("u_processingAlpha", processing)
        shader.setFloatUniform("u_trueOpacity", alpha * PlateAppear.opacity(ageMs))
        // Gemini's radius as a share of the half height: 1 is a pill.
        val half = minOf(scope.size.width, scope.size.height) / 2
        shader.setFloatUniform("u_uRadius", if (half > 0f) (radius / half).coerceIn(0f, 1f) else 1f)
        canvas.save()
        canvas.translate(-pad, -pad)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
        return true
    }
}

@RequiresApi(33)
private class ChipShader {
    private val shader = RuntimeShader(CHIP_SHADER)
    private val paint = Paint().apply { shader = this@ChipShader.shader }

    fun draw(canvas: android.graphics.Canvas, w: Float, h: Float, radius: Float, density: Float, look: ChipLook): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        if (w <= 0f || h <= 0f) return true
        shader.setFloatUniform("u_resolution", w, h)
        shader.setFloatUniform("u_cornerRadius", radius)
        shader.setColorUniform("u_bgColor", look.fill.toArgb())
        look.shadows.forEachIndexed { i, (color, blur, offsetY) ->
            shader.setColorUniform("u_shadow${i + 1}Color", color.toArgb())
            shader.setFloatUniform("u_shadow${i + 1}Blur", blur * density)
            shader.setFloatUniform("u_shadow${i + 1}OffsetY", offsetY * density)
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        return true
    }
}

private const val PLATE_SHADER = """

uniform float2 uResolution;


const half PI = 3.14159265359;

// --- Geometry & Signed Distance Function (SDF) Variables ---
uniform half u_uRadius;                     // Corner/cap radius defining visual roundness
uniform float u_displayWidth;                // Logical display width in CSS layout space
uniform float u_displayHeight;               // Logical display height in CSS layout space
uniform float u_uInsidePadding;              // Inside padding to subtract from dynamic dimensions
uniform half u_shapeProgress;               // Shape progress [0=Circle, 1=Capsule]
uniform half u_plateScale;                  // Plate scale [0=Invisible, 1=Full Size]

// --- Softness/Blur Uniforms ---
uniform half u_uSoftnessTop;                // Blur top mapping
uniform half u_uSoftnessBottom;             // Blur bottom mapping
const half GRAD_START = -0.7;

// --- Colors & Opacity ---
layout(color) uniform half4 u_baseColor;
layout(color) uniform half4 u_dodgeColor;
layout(color) uniform half4 u_processingColor1;
layout(color) uniform half4 u_processingColor2;
const half INNER_SHADOW_BACKGROUND_OPACITY = 1.0;
uniform half u_bgMidStop;
const half OPACITY_GRADIENT_START = 0.99;
const half OPACITY_GRADIENT_END = 0.97;
layout(color) uniform half4 u_bgCenterColor;
layout(color) uniform half4 u_bgEdgeColor;
layout(color) uniform half4 u_innerShadowAmbientColor;
layout(color) uniform half4 u_innerShadowBottomColor;
uniform half2 u_shadowOffsetPixels;
uniform half u_shadowBlurTopPixels;
uniform half u_shadowBlurBottomPixels;
uniform half u_innerShadowRimAlpha;

uniform half u_trueOpacity;
uniform half u_processingAlpha;             // Opacity of a Processing state wave

// --- Time, Clock & Noise Systems ---
uniform half u_timer;                        // Global elapsed time counter (continuous animation seconds)

const half2 NOISE_DIRECTION = half2(1.0, 0.0); // Directional vector mapping progression axis of local noise waves
uniform half2 u_accumulatedNoiseOffset;       // Accumulated temporal noise offset vector

// --- Procedural Particle Grid Uniforms ---
uniform half u_dotSpacing;                  // Spatial spacing between grid points
const half DOT_SIZE = 86.0;                 // Base diameter scaling factor
const half UV_SPACE = 0.06;                 // Noise space frequency density modifier
const half DISP_FORCE = 0.035;              // Curl noise displacement amplitude
const half SHAPE_MIX = 1.0;                  // Multi-shape transition sweep [0=Pill, 0.5=Circle, 1=Diamond]
uniform float u_DPI;                         // Device Pixel Ratio scaling factor
const half VISIBLE_WAVE_FREQUENCY = 0.35;   // Spatial repetition scaling factor of base ripples
const half3 VISIBLE_WAVE_DIRECTION = half3(1.0, 0.0, 1.0); // Dimensional progression heading vector of ripples
const half VISIBLE_WAVE_AFFECT = 0.8;       // Influence amplitude scaling of base ripples
const half DOT_DRIVER_STRETCH_AFFECT = 0.1; // Stretch multiplier under motion

// Visibility & falloff
uniform half u_insideParticlesVisibility;
uniform half u_outsideParticlesVisibility;
const half PARTICLE_FALLOFF = 0.6;
const half VERTICAL_FACTOR_PARTICLE_TOP_MIN = 0.6;
const half VERTICAL_FACTOR_PARTICLE_BOTTOM_MIN = 0.4;
uniform half u_particleOpacity;

layout(color) uniform half4 u_particleColor;
layout(color) uniform half4 u_outsideParticleColor;

// ==========================================
// NOISE & HASH FUNCTIONS (2D)
// ==========================================

half3 noiseAndCurl2D(half2 p) {
    half2 i = floor(p), f = fract(p), u = smoothstep(0.0, 1.0, f);
    float4 d = dot(i, float2(0.00991, 0.0113)) + float4(0.0, 0.00991, 0.0113, 0.02121);
    half4 h = half4(fract(769.9829189 * fract(d)) * 2.0 - 1.0);
    half2 k12 = h.yz - h.xx;
    half k3 = h.w - h.z - k12.x;
    half2 term = k12 + k3 * u.yx;
    return half3(h.x + term.x * u.x + k12.y * u.y, 6.0 * f.yx * (1.0 - f.yx) * (term.yx * half2(1.0, -1.0)));
}

// ==========================================
// SHAPING & DISTANCE FUNCTIONS
// ==========================================

half sdCapsuleRaw(float2 p, float2 halfDims, float r) {
    float2 q = abs(p) - halfDims + r;
    return half(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r);
}

// ==========================================
// PROCEDURAL DOT GRID GENERATOR
// ==========================================

half2 sampleDisp(half2 p) {
    half dispScale = (DISP_FORCE * 5.0) / UV_SPACE;
    half maxDisp = u_dotSpacing * 1.95;
    half2 d = noiseAndCurl2D(p * UV_SPACE - u_accumulatedNoiseOffset).yz * dispScale;
    return d * min(1.0, maxDisp / max(0.0001, length(d)));
}

half getProceduralDotsLayer(float2 fragCoord) {
    half2 p_pixel = half2(((fragCoord / u_DPI) - float2(u_displayWidth, u_displayHeight)) * 0.1);

    half dp = DISP_FORCE;
    half uvSp = UV_SPACE;
    half dispScale = (dp * 5.0) / uvSp;
    half maxDisp = u_dotSpacing * 1.95;

    // 1. Predictor: approximate noise displacement at pixel location
    half2 disp0 = sampleDisp(p_pixel);

    // 2. Fixed-point (Picard) refinement step to resolve high-divergence vortex regions
    half2 p_warped = p_pixel - sampleDisp(p_pixel - disp0);

    const half rotConst = 0.70710678;
    half2 p_unrotated = half2(
        (p_warped.x + p_warped.y) * rotConst,
        (-p_warped.x + p_warped.y) * rotConst
    );

    half2 g = p_unrotated / u_dotSpacing;
    half row0 = floor(g.y);
    half offX = step(mod(row0, 2.0), 0.5) * 0.5;
    half2 offset = half2(offX, 0.5 - offX);
    half2 cx = floor(g.x - offset + 0.5) + offset;

    half gyFract = fract(g.y);
    half2 dx = g.x - cx;
    half2 dDistSq = dx * dx + half2(gyFract * gyFract, (gyFract - 1.0) * (gyFract - 1.0));
    half2 p_center = ((dDistSq.x < dDistSq.y) ? half2(cx.x, row0) : half2(cx.y, row0 + 1.0)) * u_dotSpacing;

    // 2. Map grid node back to rotated world coordinates
    half2 p_base_rotated = half2(
        (p_center.x - p_center.y) * rotConst,
        (p_center.x + p_center.y) * rotConst
    );

    // 3. Corrector: evaluate exact displacement and gradient at the candidate dot's base center
    half2 p_temp_center = p_base_rotated * uvSp - u_accumulatedNoiseOffset;
    half2 curlCenter = noiseAndCurl2D(p_temp_center).yz;
    half2 simGrad = half2(-curlCenter.y, curlCenter.x);
    half2 dispVec = curlCenter * dispScale;
    half dispLen = length(dispVec);
    half2 dispCenter = dispVec * min(1.0, maxDisp / max(0.0001, dispLen));
    half2 p_displaced = p_base_rotated + dispCenter;

    // 4. Euclidean distance to true displaced center (preserves exact circular/diamond shape)
    half2 diffDisplaced = abs(p_pixel - p_displaced);
    half distToCenter = length(diffDisplaced);

    half maxPossibleRadius = DOT_SIZE * 0.002;
    if (distToCenter > maxPossibleRadius) return 0.0;

    half2 waveDirOffset = VISIBLE_WAVE_DIRECTION.xy * 10.0 + half2(VISIBLE_WAVE_DIRECTION.z * 17.13, VISIBLE_WAVE_DIRECTION.z * 31.79);
    half2 noiseCoord = p_base_rotated * VISIBLE_WAVE_FREQUENCY + waveDirOffset;
    half waveNoiseVal = (noiseAndCurl2D(noiseCoord).x + 1.0) * 0.5;
    half waveOffset = waveNoiseVal * VISIBLE_WAVE_AFFECT;
    half sizeMod = 1.0 - waveOffset;

    half maxQuadRadius = maxPossibleRadius * sizeMod;
    if (distToCenter > maxQuadRadius) return 0.0;

    half speedFade = 1.0;
    if (DOT_DRIVER_STRETCH_AFFECT > 0.001) {
        float displayRatio = u_displayWidth / u_displayHeight;
        half2 velocityFactor = half2(0.008 / displayRatio, 0.008);
        half2 velocityVector = simGrad * dot(simGrad, NOISE_DIRECTION) * velocityFactor;
        half minS = 0.75;
        speedFade = max(minS, min(1.0, length(velocityVector) * 200.0));
    }
    half quadRadius = maxQuadRadius * speedFade;
    if (quadRadius < 0.0001) return 0.0;

    half diamondDistUnprojected = diffDisplaced.x + diffDisplaced.y;
    half finalDistUnprojected = mix(distToCenter, diamondDistUnprojected, SHAPE_MIX) - (quadRadius * 2.0);
    half aaRange = half(7.5 / max(0.0001, u_displayHeight * u_DPI));
    half shapeAlpha = smoothstep(aaRange, 0.0, finalDistUnprojected);

    half motionFactor = smoothstep(0.0, 1.0, dispLen);
    shapeAlpha *= motionFactor;

    half basePointSize = half(DOT_SIZE * 0.0008 * u_displayHeight * u_DPI);
    half unscaledPointSize = basePointSize * sizeMod;
    half opacityCompensator = clamp(unscaledPointSize, 0.0, 1.0);

    return shapeAlpha * opacityCompensator;
}

// ==========================================
// LOADING PROCESSING WAVE HELPER FUNCTIONS
// ==========================================

const half PROCESSING_HEIGHT = 1.35;
const half PROCESSING_SPEED = 0.437;
const half PROCESSING_TIME_FREQ = 0.49;
const half PROCESSING_SPATIAL_FREQ = 2.5;
const half PROCESSING_VALLEY = 0.5;
const half PROCESSING_VERTICAL_EXPONENT = 4.0;
const half PROCESSING_SPREAD = 0.73;

half4 computeProcessingWave(half2 uv) {
    half pos = mod((uv.x - u_timer * PROCESSING_SPEED) * PROCESSING_SPREAD, 1.0);
    half baseCurve = sin(uv.x * PI);

    half2 v = half2(uv.x * PROCESSING_SPATIAL_FREQ, u_timer * PROCESSING_TIME_FREQ);
    half4 angles = half4(
        v.x * 3.0,
        (v.x - v.y) * 4.0,
        v.y * 2.5 + PI * 0.5,
        (v.x + v.y) * 5.0 + PI * 0.5
    );
    half wiggle = dot(sin(angles), half4(0.25));
    half noise = (wiggle + 1.0) * (1.0 - PROCESSING_VALLEY) * 0.5 + PROCESSING_VALLEY;

    half waveHeight = max(0.0001, noise * baseCurve * PROCESSING_HEIGHT);
    half yMask = pow(smoothstep(0.0, 1.0, 1.0 - (1.0 - uv.y) / waveHeight), PROCESSING_VERTICAL_EXPONENT);

    half waveT = 1.0 - abs(pos * 2.0 - 1.0);
    half3 gradColor = mix(u_processingColor1.rgb, u_processingColor2.rgb, smoothstep(0.0, 1.0, waveT));

    return half4(gradColor, baseCurve * yMask);
}

half4 main(float2 coord) {
    half4 FragColor = half4(0.0);
    half2 uv = half2(coord.x / uResolution.x, 1.0 - coord.y / uResolution.y);

    // ==========================================
    // 1. ASPECT RATIO & POSITION NORMALIZATION
    // ==========================================
    float safeRealPixelWidth = max(1.0, u_displayWidth * 2.0);
    half invSafeWidth = half(1.0 / safeRealPixelWidth);
    float safeRealPixelHeight = max(1.0, u_displayHeight * 2.0);
    half aspect = half(safeRealPixelHeight) * invSafeWidth;

    // ==========================================
    // 2. BASE GEOMETRY & SDF COMPUTATION
    // ==========================================
    half scale = clamp(u_plateScale, 0.0, 1.0);
    float baseWidth = max(1.0, u_displayWidth - 2.0 * u_uInsidePadding);
    float baseHeight = max(1.0, u_displayHeight - 2.0 * u_uInsidePadding);
    half fullNormWidth = half(baseWidth * 0.5) * invSafeWidth * scale;
    half dynamicHeight = half(baseHeight) * scale;
    half normHeight = (dynamicHeight * 0.5) * invSafeWidth;
    half normDefaultHeight = half(baseHeight * 0.5) * invSafeWidth;
    half normWidth = mix(normHeight, fullNormWidth, clamp(u_shapeProgress, 0.0, 1.0));

    half2 halfDims = half2(normWidth * 0.5, normHeight * 0.5);
    half defaultHalfDimsY = half(baseHeight * 0.25) * invSafeWidth;
    half roundness = clamp(u_uRadius, 0.0, 1.0);
    half r = roundness * min(halfDims.x, min(halfDims.y, defaultHalfDimsY));

    half2 capsulePos = (uv - 0.5) * half2(0.25, aspect * 0.25);
    capsulePos.y -= (normHeight - normDefaultHeight) * 0.5;

    half dist = sdCapsuleRaw(capsulePos, halfDims, r);

    // ==========================================
    // 3. DEMO-STYLE GRADIENT BLUR COMPOSITION
    // ==========================================
    half yNorm = capsulePos.y / max(half(0.0001), normHeight);
    half final_t = smoothstep(GRAD_START, 1.0, yNorm);

    half2 blurRaw = mix(max(half2(0.0), half2(u_uSoftnessBottom, u_shadowBlurBottomPixels)),
                        max(half2(0.0), half2(u_uSoftnessTop, u_shadowBlurTopPixels)), final_t) * (scale * invSafeWidth);

    half b1 = max(blurRaw.x, 0.0001);

    // Smoothstep mask (weighted to be more blur outside than inside)
    half alpha = 1.0 - smoothstep(-b1 * 0.7, b1 * 1.3, dist);

    // ==========================================
    // 4. INNER SHADOW / SECONDARY SPHERE LAYER COMPOSITION
    // ==========================================
    half2 innerShadowPos = capsulePos - u_shadowOffsetPixels * (invSafeWidth * half2(1.0, -1.0));
    half innerShadowSDF = sdCapsuleRaw(innerShadowPos, halfDims, r);
    half shadowIntensity = (blurRaw.y > 0.0)
        ? smoothstep(-blurRaw.y, blurRaw.y, innerShadowSDF)
        : step(0.0, innerShadowSDF);

    FragColor = half4(mix(u_baseColor.rgb, u_dodgeColor.rgb, shadowIntensity), alpha);

    // ==========================================
    // 4.1. FIGMA PILL BACKGROUND & INSET SHADOWS
    // ==========================================
    half ambientShadowMask = 0.0;
    if (INNER_SHADOW_BACKGROUND_OPACITY > 0.001) {
        // Pill-local UV space [0..1, 0..1], top-center = (0.5, 0.0)
        half halfW = normWidth * 0.5;
        half halfH = normHeight * 0.5;
        half2 pillUv = half2(
            clamp((capsulePos.x + halfW) / max(half(0.0001), normWidth), 0.0, 1.0),
            clamp((halfH - capsulePos.y) / max(half(0.0001), normHeight), 0.0, 1.0)
        );

        // Radial gradient (elliptical, offset toward top)
        half2 delta = pillUv - half2(0.50, 0.1437);
        half t = clamp(length(delta / half2(0.7031, 0.8563)), 0.0, 1.0);

        half3 color0 = u_bgCenterColor.rgb;
        half3 color2 = u_bgEdgeColor.rgb;

        half3 figmaColor = mix(color0, color2, clamp((t - u_bgMidStop) / max(half(0.0001), 1.0 - u_bgMidStop), 0.0, 1.0));
        half gradAlpha = mix(OPACITY_GRADIENT_START, OPACITY_GRADIENT_END, clamp((t - 0.1106) / max(half(0.0001), u_bgMidStop - 0.1106), 0.0, 1.0));

        // Inset shadow 1: ambient edge glow (no offset, blur = 18.6 dp)
        half blur1 = half(18.6 * 0.5) * invSafeWidth;
        half dInside1 = max(0.0, -dist);
        half normDist1 = clamp(dInside1 / max(half(0.0001), blur1), 0.0, 1.0);
        half w1 = 1.0 - normDist1;
        half glowFactor = 0.35 * w1 * w1;
        ambientShadowMask = glowFactor;
        half3 s1Color = u_innerShadowAmbientColor.rgb;
        figmaColor = mix(figmaColor, s1Color, glowFactor * 0.22);

        // Inset shadow 2: bottom rim bevel (offset Y = -3 dp, blur = 3 dp)
        half offset3 = half(3.0 * 0.5) * invSafeWidth;
        half sigma2 = half(3.0 * 0.5) * invSafeWidth;
        half distShifted = sdCapsuleRaw(capsulePos - half2(0.0, offset3), halfDims, r);
        half rimFactor = smoothstep(-sigma2, sigma2, distShifted);

        half3 s2Color = u_innerShadowBottomColor.rgb;
        figmaColor = mix(figmaColor, s2Color, rimFactor * u_innerShadowRimAlpha);

        FragColor.rgb = mix(FragColor.rgb, figmaColor, INNER_SHADOW_BACKGROUND_OPACITY * gradAlpha);
        FragColor.a *= mix(1.0, gradAlpha, INNER_SHADOW_BACKGROUND_OPACITY);
    }

    // ==========================================
    // 4.5. LOADING PROCESSING WAVE (SHIMMER ON PILL)
    // ==========================================
    if (u_processingAlpha > 0.001) {
        half2 pillUv = half2(
            clamp((capsulePos.x + normWidth * 0.5) / max(half(0.0001), normWidth), 0.0, 1.0),
            clamp((normHeight * 0.5 - capsulePos.y) / max(half(0.0001), normHeight), 0.0, 1.0)
        );
        half4 procColor = computeProcessingWave(pillUv);
        half waveBlendAlpha = alpha * u_processingAlpha * procColor.a;
        FragColor.rgb = mix(FragColor.rgb, procColor.rgb, waveBlendAlpha);
    }

    // ==========================================
    // 5. DOT MATRIX LAYERING
    // ==========================================
    half verticalFactor = smoothstep(-normHeight, normHeight, capsulePos.y);

    half topVisibility = smoothstep(VERTICAL_FACTOR_PARTICLE_TOP_MIN, 1.0, verticalFactor);
    half bottomVisibility = smoothstep(VERTICAL_FACTOR_PARTICLE_BOTTOM_MIN, 0.0, verticalFactor);
    half verticalFactorParticleVisibility = max(topVisibility, bottomVisibility);

    half dots = 0.0;
    if (u_particleOpacity > 0.001 && (u_insideParticlesVisibility > 0.001 || u_outsideParticlesVisibility > 0.001)) {
        dots = getProceduralDotsLayer(coord);
    }

    if (u_particleOpacity > 0.001) {
        half2 halfDimsFull = half2(fullNormWidth * 0.5, normHeight * 0.5);
        half rFull = roundness * min(halfDimsFull.x, min(halfDimsFull.y, defaultHalfDimsY));
        half particleDist = sdCapsuleRaw(capsulePos, halfDimsFull, rFull);
        half particleAlpha = 1.0 - smoothstep(-b1 * 0.7, b1 * 1.3, particleDist);

        half3 currentParticleColor = mix(u_outsideParticleColor.rgb, u_particleColor.rgb, particleAlpha);

        // Inside shape particles (based on static full capsule boundary)
        half dotsOpacity = dots * u_particleOpacity;
        half insideMask = (verticalFactorParticleVisibility + ambientShadowMask) * u_insideParticlesVisibility;
        half dotOverlayOpacity = clamp(dotsOpacity * particleAlpha * insideMask, 0.0, 1.0);
        FragColor.rgb = mix(FragColor.rgb, currentParticleColor, dotOverlayOpacity);

        // Background particles (outside full capsule shape)
        half distanceToPill = max(0.0, particleDist);
        half overflowNorm = half(u_uInsidePadding) * invSafeWidth;
        half fadeEndDistance = overflowNorm * max(half(0.0001), PARTICLE_FALLOFF);
        half particleFade = 1.0 - smoothstep(0.0, fadeEndDistance, distanceToPill);

        half bgDotOpacity = clamp(dotsOpacity * (1.0 - particleAlpha) * u_outsideParticlesVisibility * particleFade, 0.0, 1.0);

        // Composite bg particles behind capsule (porter-duff "dst-over" in premultiplied space)
        half invDstAlpha = (1.0 - FragColor.a) * bgDotOpacity;
        half3 premulRGB = FragColor.rgb * FragColor.a + currentParticleColor * invDstAlpha;
        FragColor.a = (FragColor.a + invDstAlpha) * u_trueOpacity;

        return half4(premulRGB * u_trueOpacity, FragColor.a);
    }

    FragColor.a *= u_trueOpacity;
    return half4(FragColor.rgb * FragColor.a, FragColor.a);
}
"""

private const val CHIP_SHADER = """

uniform float2 u_resolution;
uniform float u_cornerRadius;
layout(color) uniform half4 u_bgColor;

layout(color) uniform half4 u_shadow1Color;
uniform float u_shadow1Blur;
uniform float u_shadow1OffsetY;

layout(color) uniform half4 u_shadow2Color;
uniform float u_shadow2Blur;
uniform float u_shadow2OffsetY;

layout(color) uniform half4 u_shadow3Color;
uniform float u_shadow3Blur;
uniform float u_shadow3OffsetY;

float erf_approx(float x) {
    float x2 = x * x;
    float sign_x = sign(x);
    float a = 0.147;
    float inner = exp(-x2 * (1.27323954 + a * x2) / (1.0 + a * x2));
    return sign_x * sqrt(max(0.0, 1.0 - inner));
}

float sdRoundRect(float2 p, float2 halfDims, float radius) {
    float r = min(radius, min(halfDims.x, halfDims.y));
    float2 d = abs(p - halfDims) - halfDims + float2(r);
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
}

float computeInnerShadow(float2 p, float2 halfDims, float radius, float blur, float offsetY) {
    if (blur <= 0.001) return 0.0;
    float2 shiftedP = p - float2(0.0, offsetY);
    float d = sdRoundRect(shiftedP, halfDims, radius);
    float sigma = blur * 0.57735 + 0.5;
    float z = (d + 0.12 * blur) / (1.41421356 * sigma);
    return clamp(0.5 + 0.5 * erf_approx(z), 0.0, 1.0);
}

half4 blendPremul(half4 dst, half3 srcRgb, float srcA) {
    half3 srcPremul = srcRgb * srcA;
    return half4(srcPremul + dst.rgb * (1.0 - srcA), srcA + dst.a * (1.0 - srcA));
}

half4 main(float2 fragCoord) {
    float2 halfDims = u_resolution * 0.5;
    float r = min(u_cornerRadius, min(halfDims.x, halfDims.y));
    float dist = sdRoundRect(fragCoord, halfDims, r);

    float shapeAlpha = clamp(0.5 - dist, 0.0, 1.0);
    if (shapeAlpha <= 0.0) {
        return half4(0.0);
    }

    half4 color = half4(u_bgColor.rgb * u_bgColor.a, u_bgColor.a);

    if (u_shadow1Color.a > 0.001 && u_shadow1Blur > 0.0) {
        float s1 = computeInnerShadow(fragCoord, halfDims, r, u_shadow1Blur, u_shadow1OffsetY);
        color = blendPremul(color, u_shadow1Color.rgb, s1 * u_shadow1Color.a);
    }

    if (u_shadow2Color.a > 0.001 && u_shadow2Blur > 0.0) {
        float s2 = computeInnerShadow(fragCoord, halfDims, r, u_shadow2Blur, u_shadow2OffsetY);
        color = blendPremul(color, u_shadow2Color.rgb, s2 * u_shadow2Color.a);
    }

    if (u_shadow3Color.a > 0.001 && u_shadow3Blur > 0.0) {
        float s3 = computeInnerShadow(fragCoord, halfDims, r, u_shadow3Blur, u_shadow3OffsetY);
        color = blendPremul(color, u_shadow3Color.rgb, s3 * u_shadow3Color.a);
    }

    return color * shapeAlpha;
}
"""
