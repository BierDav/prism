// The assistant's edge glow, as Gemini draws it on a Pixel (the Google app's
// LrScreenEdgeGlowDrawable, 17.76.14): one AGSL shader over the whole screen.
// Waves of Google's four colours rise from the left, right and bottom edges,
// a field of sparks crosses them early on, and a pulsing rim in the theme's
// primary colour runs along the display's outline. The four colours blend
// into the primary as the waves settle, and the whole effect plays once over
// four seconds and is gone (it fades itself out from 95 %).
//
// The shader below is Gemini's, unchanged; EdgeGlow sets its uniforms the way
// the drawable's draw() and its animation callback do. RuntimeShader needs
// Android 13; below that there is no glow.

package at.quickme.prism

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.sin

/** The glow's run, start to finish, in milliseconds. */
internal const val GLOW_MS = 4000f

/**
 * When the glow starts to fade, as Gemini times it (the Google app's
 * SingleLineFloatyGlow): two thirds of its run less the fade, so it is gone
 * at two thirds. The fade is linear and lasts [GLOW_FADE_MS]; the input card
 * settles over the same span.
 */
internal const val GLOW_FADE_START_MS = GLOW_MS * 2f / 3f - 583f
internal const val GLOW_FADE_MS = 583f

/** How far the glow has faded out by [t] ms after it started: 1 shown, 0 gone. */
internal fun glowFade(t: Float) = 1f - ((t - GLOW_FADE_START_MS) / GLOW_FADE_MS).coerceIn(0f, 1f)

// Gemini's colours: Google blue, green, yellow and red.
private val GlowBlue = Color(0xFF1A73E8)
private val GlowGreen = Color(0xFF0EBC5F)
private val GlowYellow = Color(0xFFFFCC00)
private val GlowRed = Color(0xFFFF4641)

/** The glow, or null before Android 13. */
internal fun edgeGlow(): EdgeGlow? = if (Build.VERSION.SDK_INT >= 33) EdgeGlow() else null

@RequiresApi(33)
internal class EdgeGlow {
    private val shader = RuntimeShader(GLOW_SHADER)
    private val paint = Paint().apply { shader = this@EdgeGlow.shader }
    /** Where round the colour wheel the waves start: a different spot each time. */
    private val randomOffset = (System.currentTimeMillis() % 1000) / 1000f

    /**
     * Draws the glow [fraction] (0..1) of the way through its run, at
     * [fade] (1 shown, 0 gone), its rim rounded by [cornerRadius] (pixels)
     * and tinted [primary].
     */
    fun draw(canvas: Canvas, width: Float, height: Float, fraction: Float, fade: Float, cornerRadius: Float, primary: Color) {
        if (width <= 0f || height <= 0f || fade <= 0f || fraction >= 1f || !canvas.isHardwareAccelerated) return
        val t = fraction.coerceIn(0f, 1f)
        // The rim breathes twice, then holds at full strength.
        val pulse = (sin((6f * minOf(t, 2f / 3f) - 0.5f) * PI.toFloat()) + 1f) / 2f * 0.6f + 0.4f
        // From 30 % the colours turn to the primary, all of it by 42.5 %.
        val settle = ((t - 0.3f) / 0.125f).coerceIn(0f, 1f)
        fun color(c: Color) = (if (settle > 0f) lerp(c, primary, settle) else c).toArgb()
        // The sparks: in from 13 %, at half strength from 18 %, out by 50 %.
        val dots = when {
            t < 0.133f -> 0f
            t < 0.183f -> (t - 0.133f) / 0.05f * 0.5f
            t < 0.283f -> 0.5f
            t < 0.5f -> (1f - (t - 0.283f) / 0.217f) * 0.5f
            else -> 0f
        }
        shader.setFloatUniform("randomOffset", randomOffset)
        shader.setFloatUniform("fadeFraction", fade.coerceIn(0f, 1f))
        shader.setFloatUniform("pulseFraction", pulse)
        shader.setFloatUniform("animationFraction", t)
        shader.setFloatUniform("cornerRadius", cornerRadius)
        shader.setFloatUniform("resolution", width, height)
        shader.setColorUniform("colorBlue", primary.toArgb())
        shader.setColorUniform("colorGreen", color(GlowGreen))
        shader.setColorUniform("colorYellow", color(GlowYellow))
        shader.setColorUniform("colorRed", color(GlowRed))
        shader.setColorUniform("colorAuroraBlue", color(GlowBlue))
        shader.setFloatUniform("dotsTimingOpacity", dots)
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

/**
 * Gemini's scrim behind its input card (the Google app's halo drawable): the
 * screen darkens round the card, fully at its edge and 75 dp inside it, fading
 * out over 150 dp outside along a ten-step ease-out curve. Black, at up to
 * 77 % in dark mode and 14 % in light.
 *
 * The Google app sets its strength at run time (cclg); the flags 35 and 61
 * it carries for it are never read. The 77 % is fitted to the owner's
 * screenshot of Gemini in the dark (two halos round its card darken the page
 * to 30 % at the card's side); 24 % left Prism's halo a third as deep. Light
 * mode keeps 14 %: there is no light screenshot to fit.
 */
@RequiresApi(33)
internal class HaloScrim {
    private val shader = RuntimeShader(HALO_SHADER)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@HaloScrim.shader }

    /** Round the card at [left], [top], [right], [bottom] (pixels) with [cornerRadius], at [alpha]. */
    fun draw(
        canvas: Canvas, width: Float, height: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        cornerRadius: Float, density: Float, dark: Boolean, alpha: Float,
    ) {
        if (alpha <= 0f || right <= left || !canvas.isHardwareAccelerated) return
        val color = Color.Black
        shader.setFloatUniform("focalPoint", (left + right) / 2, (top + bottom) / 2)
        shader.setFloatUniform("rectHalfSize", (right - left) / 2, (bottom - top) / 2)
        shader.setFloatUniform("cornerRadius", cornerRadius)
        shader.setFloatUniform("blurRadius", 150f * density)
        shader.setFloatUniform("scrimColor", color.red, color.green, color.blue)
        paint.alpha = (alpha.coerceIn(0f, 1f) * (if (dark) 196f else 35f)).toInt()
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

/** The halo, or null before Android 13. */
internal fun haloScrim(): HaloScrim? = if (Build.VERSION.SDK_INT >= 33) HaloScrim() else null

private const val HALO_SHADER = """
uniform float2 focalPoint;
uniform float2 rectHalfSize;
uniform float cornerRadius;
uniform float blurRadius;
uniform float3 scrimColor;

// Signed distance function for a rounded rectangle (Inigo Quilez).
// Returns negative values inside, zero on the boundary, positive outside.
float sdRoundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// 10-stop ease-out alpha curve matching the legacy RadialGradient.
// Piecewise linear interpolation between:
//   stops:  0.0   0.15  0.3   0.45  0.58  0.7   0.8   0.88  0.95  1.0
//   alpha:  1.0   0.82  0.65  0.48  0.32  0.18  0.09  0.04  0.01  0.0
float scrimAlphaCurve(float t) {
    if (t <= 0.0) return 1.0;
    if (t >= 1.0) return 0.0;
    if (t < 0.15) return mix(1.0,  0.82, t / 0.15);
    if (t < 0.3)  return mix(0.82, 0.65, (t - 0.15) / 0.15);
    if (t < 0.45) return mix(0.65, 0.48, (t - 0.3)  / 0.15);
    if (t < 0.58) return mix(0.48, 0.32, (t - 0.45) / 0.13);
    if (t < 0.7)  return mix(0.32, 0.18, (t - 0.58) / 0.12);
    if (t < 0.8)  return mix(0.18, 0.09, (t - 0.7)  / 0.1);
    if (t < 0.88) return mix(0.09, 0.04, (t - 0.8)  / 0.08);
    if (t < 0.95) return mix(0.04, 0.01, (t - 0.88) / 0.07);
    return mix(0.01, 0.0, (t - 0.95) / 0.05);
}

half4 main(float2 fragCoord) {
    float2 p = fragCoord - focalPoint;
    float dist = sdRoundedBox(p, rectHalfSize, cornerRadius);

    // Normalize SDF distance to [0, 1] for the alpha curve.
    // The fade zone is asymmetric: half blurRadius inside the rect boundary,
    // full blurRadius outside. Total range = 1.5 * blurRadius.
    float innerFade = blurRadius * 0.5;
    float t = clamp((dist + innerFade) / (innerFade + blurRadius), 0.0, 1.0);
    float alpha = scrimAlphaCurve(t);

    // Premultiplied alpha output.
    half3 c = half3(scrimColor);
    half a = half(alpha);
    return half4(c * a, a);
}
"""

private const val GLOW_SHADER = """
uniform half randomOffset;
uniform half fadeFraction;
uniform half pulseFraction;
uniform half animationFraction;
uniform half cornerRadius;
uniform float2 resolution;
const half GLOW_WIDTH = 36.0;
const half SOLID_WIDTH = 4.0;

layout(color) uniform half4 colorBlue;
layout(color) uniform half4 colorGreen;
layout(color) uniform half4 colorYellow;
layout(color) uniform half4 colorRed;
layout(color) uniform half4 colorAuroraBlue;

uniform half dotsTimingOpacity;
const half DOTS_GRID_SIZE = 140.0;
const half DOTS_BLUR_RADIUS = 0.10;
const half DOTS_MAX_RADIUS = 0.20;
const half2 DOTS_MOTION_NOISE_SPEED = half2(0.60, 0.90);
const half DOTS_MOTION_NOISE_SPEED_Z = 1.05;
const half2 DOTS_MOTION_NOISE_SCALE = half2(0.80, 0.95);
const half DOTS_GRID_ROTATION = 0.7853982; // 45 deg in radians (45.0 * PI / 180.0)
const half DOTS_PARTICLE_ROTATION = 1.5707964; // 90 deg in radians (90.0 * PI / 180.0)

const half4 SPARK_COLOR = half4(1.0);
const half SPARK_COLOR_MIX = 0.50;

const half PI = 3.141592653589793;
const half TWO_PI = 6.28318530718;
const half3 hash_multiplier = half3(0.0113, 0.00991, 769.9829189);

half4 normalBlendPreMulti(half4 back, half4 front) {
    return front + back * (1.0 - front.a);
}

half simple_value_hash(half2 uv) {
    return fract(hash_multiplier.z * fract(dot(uv, hash_multiplier.yx)));
}

half3 value_gradient_noise(half2 p) {
    half2 i = floor(p);
    half2 f = fract(p);
    half2 u = f * f * (3.0 - 2.0 * f);
    half2 du = 6.0 * f * (1.0 - f);
    half a = simple_value_hash(i + half2(0, 0));
    half b = simple_value_hash(i + half2(1, 0));
    half c = simple_value_hash(i + half2(0, 1));
    half d = simple_value_hash(i + half2(1, 1));
    half k0 = a;
    half k1 = b - a;
    half k2 = c - a;
    half k3 = a - b - c + d;
    half value = k0 + k1 * u.x + k2 * u.y + k3 * u.x * u.y;
    half2 gradient = du * half2(k1 + k3 * u.y, k2 + k3 * u.x);
    return half3(value, gradient);
}

half2x2 rotate2d(half angle) {
    half s = sin(angle);
    half c = cos(angle);
    return half2x2(c, -s, s, c);
}

half sdBox(half2 p, half2 b) {
    half2 d = abs(p) - b;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

half dotShape(half2 p, half r, half blur) {
    half d = sdBox(p, half2(r));
    d = smoothstep(blur, -blur, d);
    return d;
}

half computeDotsMask(float2 fragCoord, half t) {
    half2 uv = half2((fragCoord - 0.5 * resolution) / resolution.y);
    half dotsT = t * 6.0; // convert 0→1 fraction to ~seconds
    half gridAngle = PI * sin(dotsT * 0.008);
    uv = rotate2d(gridAngle + DOTS_GRID_ROTATION) * uv;
    half2x2 particleRot = rotate2d(-gridAngle + DOTS_PARTICLE_ROTATION);
    half2 p = uv * DOTS_GRID_SIZE;
    half2 id = floor(p);
    half2 f = fract(p);
    half mask = 0.0;

    for (int y = -1; y <= 1; y++)
    for (int x = -1; x <= 1; x++) {
        half2 neighborDir = half2(half(x), half(y));
        half2 neighborID = id + neighborDir;
        half3 n = value_gradient_noise(neighborID * 0.1 + dotsT * DOTS_MOTION_NOISE_SPEED);
        half noiseVal = n.x;
        half2 noiseGrad = 0.5 * (n.yz * 2.0 - 1.0);
        half theta = TWO_PI * noiseVal * DOTS_MOTION_NOISE_SPEED_Z;
        half2 circleCenter = neighborDir + rotate2d(theta) * (noiseGrad * DOTS_MOTION_NOISE_SCALE);
        half2 relPos = particleRot * (f - (0.5 + circleCenter));
        half blur = DOTS_BLUR_RADIUS * noiseVal;
        half radius = DOTS_MAX_RADIUS * noiseVal;
        half circle = dotShape(relPos, radius, blur);
        if (circle > 0.0) mask += circle;
    }
    return clamp(mask, 0.0, 1.0);
}

half4 auroraGradient(half2 uv) {
    half2 center = half2(0.5, 0.75);
    half2 dir = uv - center;
    half rawAngle = (dir.x == 0.0 && dir.y == 0.0) ? 0.0 : atan(dir.y, dir.x);
    half angle = -rawAngle + randomOffset * PI * 2.0 + animationFraction * 4.0;
    half t = fract(angle / (2.0 * PI));

    half4 c1 = colorGreen;
    half4 c2 = colorYellow;
    half4 c3 = colorRed;
    half4 c4 = colorAuroraBlue;

    half4 r;
    if (t < 0.30) r = mix(c1, c2, t / 0.30); else
    if (t < 0.60) r = mix(c2, c3, (t - 0.30) / 0.30); else
    if (t < 0.90) r = mix(c3, c4, (t - 0.60) / 0.30); else
    r = mix(c4, c1, (t - 0.90) / 0.10);
    return r;
}

half gauss(half amp, half t, half scale) {
    half param = t * scale;
    return amp * exp(-param * param);
}

half gaussianWaveTop(half2 uv, half peakHeight, half peakWidth, half glowSize, half xOffset) {
    half xCentered = uv.x - 0.5 + xOffset;
    half waveShape = gauss(peakHeight, xCentered, peakWidth);
    half dist = uv.y - waveShape;
    return (1.0 - smoothstep(glowSize, 0.0, -dist)) * step(0.0, -dist);
}

half gaussianWaveBottom(half2 uv, half peakHeight, half peakWidth, half glowSize, half xOffset) {
    half xCentered = uv.x - 0.5 + xOffset;
    half waveShape = gauss(peakHeight, xCentered, peakWidth);
    half dist = 1.0 - uv.y - waveShape;
    return (1.0 - smoothstep(glowSize, 0.0, -dist)) * step(0.0, -dist);
}

half gaussianWaveLeft(half2 uv, half peakHeight, half peakWidth, half glowSize, half yOffset) {
    half yCentered = uv.y - 0.5 + yOffset;
    half waveShape = gauss(peakHeight, yCentered, peakWidth);
    half dist = uv.x - waveShape;
    return (1.0 - smoothstep(glowSize, 0.0, -dist)) * step(0.0, -dist);
}

half gaussianWaveRight(half2 uv, half peakHeight, half peakWidth, half glowSize, half yOffset) {
    half yCentered = uv.y - 0.5 + yOffset;
    half waveShape = gauss(peakHeight, yCentered, peakWidth);
    half dist = 1.0 - uv.x - waveShape;
    return (1.0 - smoothstep(glowSize, 0.0, -dist)) * step(0.0, -dist);
}

half sdRoundedBox(float2 fragCoord, half r) {
   half2 halfSize = half2(resolution * 0.5);
   half2 pix = half2(fragCoord) - halfSize;
   half2 q = abs(pix) - (halfSize - r);
   return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half cubicEaseOut(half t) {
    half t1 = t - 1.0;
    return t1 * t1 * t1 + 1.0;
}

half revealMask(half2 uv, half t) {
    half2 p = uv - 0.5;
    half angle = (p.x == 0.0 && p.y == 0.0) ? 0.0 : atan(-p.y, abs(p.x));
    half targetAngle = mix(-PI * 0.25, PI * 0.53, cubicEaseOut(t));
    half edgeSoftness = 0.1;
    return 1.0 - smoothstep(targetAngle - edgeSoftness, targetAngle, angle);
}

half appearDisappear(half app_from, half app_to, half disapp_from, half disapp_to, half t) {
    return smoothstep(app_from, app_to, t) * (1.0 - smoothstep(disapp_from, disapp_to, t));
}

half4 main(float2 fragCoord) {
    half2 uv = half2(fragCoord / resolution);
    half t = animationFraction;

    half reveal = revealMask(uv, clamp(t * 6.0, 0.0, 1.0));
    half unveal = revealMask(uv, (1.0 - smoothstep(0.85, 0.95, t)));

    half rightAuroraPos = 0.4 - smoothstep(0.0, 0.65, t) * 0.4;
    half rightAuroraAmp = appearDisappear(0.0, 0.1, 0.4, 0.5, t) * 0.1;
    half rightAuroraScale = 6.0 - t * 5.0;
    half rightAuroraBlur = 0.15;
    half4 rightAuroraWave = auroraGradient(uv) * 0.8 * gaussianWaveRight(uv, rightAuroraAmp, rightAuroraScale, rightAuroraBlur, rightAuroraPos);

    half rightBluePos = -0.4 - smoothstep(0.0, 1.0, t) * 0.1;
    half rightBlueAmp = appearDisappear(0.0, 0.1, 0.5, 0.6, t) * 0.2 + appearDisappear(0.6, 0.85, 0.85, 1.0, t) * 0.2;
    half rightBlueScale = 6.0 + smoothstep(0.4, 0.8, t) * 4.0;
    half rightBlueBlur = 0.3 + smoothstep(0.6, 0.8, t) * 0.5;
    half rightBlueKoef = 0.7;
    half4 rightBlueWave = colorBlue * rightBlueKoef * gaussianWaveRight(uv, rightBlueAmp, rightBlueScale, rightBlueBlur, rightBluePos);

    half bottomAuroraPos = -0.25 + 0.75 * smoothstep(0.0, 1.0, t);
    half bottomAuroraAmp = 0.07 * appearDisappear(0.0, 0.2, 0.3, 0.6, t);
    half bottomAuroraScale = mix(3.0, 2.0, smoothstep(0.0, 1.0, t));
    half bottomAuroraBlur = 0.05 + smoothstep(0.1, 0.5, t) * 0.1;
    half bottomAuroraKoef = 0.8;
    half4 bottomAuroraWave = auroraGradient(uv) * bottomAuroraKoef * gaussianWaveBottom(uv, bottomAuroraAmp, bottomAuroraScale, bottomAuroraBlur, bottomAuroraPos);

    half bottomBluePos = -0.3 + 0.65 * smoothstep(0.0, 1.0, t);
    half bottomBlueAmp = appearDisappear(0.0, 0.3, 0.6, 0.8, t) * 0.2;
    half bottomBlueScale = 1.2 + smoothstep(0.0, 1.0, t) * 4.0;
    half bottomBlueBlur = 0.2 + smoothstep(0.3, 0.9, t) * 0.15;
    half bottomBlueKoef = 0.7;
    half4 bottomBlueWave = colorBlue * bottomBlueKoef * gaussianWaveBottom(uv, bottomBlueAmp, bottomBlueScale, bottomBlueBlur, bottomBluePos);

    half leftAuroraPos = -0.4 - 0.1 * smoothstep(0.0, 1.0, t);
    half leftAuroraAmp = appearDisappear(0.0, 0.2, 0.45, 0.55, t) * 0.11;
    half leftAuroraScale = 4.5 - 1.0 * t + smoothstep(0.6, 0.7, t) * 3.0;
    half leftAuroraBlur = 0.15 + smoothstep(0.6, 0.7, t) * 0.2;
    half4 leftAuroraWave = auroraGradient(uv) * 0.8 * gaussianWaveLeft(uv, leftAuroraAmp, leftAuroraScale, leftAuroraBlur, leftAuroraPos);

    half leftBluePos = -0.4 - 0.1 * smoothstep(0.0, 0.9, t);
    half leftBlueAmp = appearDisappear(0.0, 0.2, 0.65, 0.85, t) * 0.35;
    half leftBlueScale = 4.5 + 1.0 * smoothstep(0.3, 1.0, t);
    half leftBlueBlur = 0.3 + smoothstep(0.5, 0.7, t) * 0.3;
    half4 leftBlueWave = colorBlue * 0.7 * gaussianWaveLeft(uv, leftBlueAmp, leftBlueScale, leftBlueBlur, leftBluePos);

    half4 auroraWaves = bottomAuroraWave + rightAuroraWave + leftAuroraWave;
    half4 blueWaves = bottomBlueWave + rightBlueWave + leftBlueWave;

    half dist = sdRoundedBox(fragCoord, cornerRadius);
    half glowFactor = smoothstep(-GLOW_WIDTH, 0.0, dist);
    half edgeFactor = smoothstep(-SOLID_WIDTH, 0.0, dist);
    half4 edgeGlow = colorBlue * (0.4 * glowFactor + 0.3 * edgeFactor) * pulseFraction;

    half4 waves = auroraWaves + blueWaves * (1.0 - auroraWaves.a);

    if (dotsTimingOpacity > 0.0 && waves.a > 0.0) {
      half dotMask = computeDotsMask(fragCoord, t);
      half3 waveRGB = waves.rgb / max(waves.a, 0.001);
      half3 dotRGB = mix(waveRGB, SPARK_COLOR.rgb, SPARK_COLOR_MIX);
      half dotAlpha = dotMask * waves.a * dotsTimingOpacity;
      half4 dotColor = half4(dotRGB * dotAlpha, dotAlpha);
      waves = normalBlendPreMulti(waves, dotColor);
    }

    half4 result = waves + edgeGlow * (1.0 - waves.a);
    return result * (1.0 - smoothstep(0.95, 1.0, t)) * reveal * unveal * fadeFraction;
}
"""
