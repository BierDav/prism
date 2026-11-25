// The overlay's drag-to-open (core/OverlayGesture.kt) on a plain JVM:
// Gemini's growth cap, its commit decision, the threshold haptics, the fades,
// the blow-up and the composer's corners.
//
//   java -cp <core classes>:kotlin-stdlib.jar OverlayGestureTestKt

import at.quickme.prism.core.OverlayGesture
import kotlin.math.abs
import kotlin.system.exitProcess

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

private fun near(a: Float, b: Float) = abs(a - b) < 0.01f

fun main() {
    val d = 3f // px per dp
    // A 2400 px screen, 150 px status bar: avail = 2400 - 150 - 24 = 2226; a third is 742.
    check(near(OverlayGesture.maxExtra(2400f, 150f, card = 300f, column = 900f, density = d), 442f), "a short card grows to a third of the screen")
    check(near(OverlayGesture.maxExtra(2400f, 150f, card = 700f, column = 1300f, density = d), 300f), "a tall card still grows by 100 dp")
    check(near(OverlayGesture.maxExtra(2400f, 150f, card = 700f, column = 2100f, density = d), 126f), "never past the space the column leaves")
    check(near(OverlayGesture.maxExtra(2400f, 150f, card = 700f, column = 2400f, density = d), 1f), "at least 1 px with no space left")

    check(OverlayGesture.progress(-10f, 400f) == 0f && OverlayGesture.progress(200f, 400f) == 0.5f, "growth is linear from 0")
    check(OverlayGesture.progress(900f, 400f) == 1f, "and stops at the cap")
    check(OverlayGesture.fadeOut(0f) == 1f && near(OverlayGesture.fadeOut(0.1f), 0.5f) && OverlayGesture.fadeOut(0.2f) == 0f && OverlayGesture.fadeOut(0.7f) == 0f, "fades over the first fifth")

    check(!OverlayGesture.commits(144f, 0f, d), "48 dp exactly doesn't commit")
    check(OverlayGesture.commits(145f, 0f, d), "past 48 dp commits")
    check(OverlayGesture.commits(2f, -1001f, d), "a fling up over 1000 px/s commits from any distance")
    check(!OverlayGesture.commits(100f, -1000f, d) && !OverlayGesture.commits(100f, 3000f, d), "a slower or downward fling doesn't")

    check(OverlayGesture.crossing(140f, 150f, d) == 1, "crossing up")
    check(OverlayGesture.crossing(150f, 140f, d) == -1, "crossing back down")
    check(OverlayGesture.crossing(150f, 160f, d) == 0 && OverlayGesture.crossing(10f, 20f, d) == 0, "no crossing")

    check(near(OverlayGesture.scale(1080f, 960f, d), 1.25f), "blown up to the screen plus 40 dp")
    check(OverlayGesture.pivot(48f, 48f) == 0.5f && OverlayGesture.pivot(0f, 0f) == 0.5f && near(OverlayGesture.pivot(300f, 100f), 0.75f), "pivot in proportion to the edges")

    check(OverlayGesture.composerRadiusDp(80f) == 40f && OverlayGesture.composerRadiusDp(94.9f) == 40f, "a pill below 95 dp")
    check(OverlayGesture.composerRadiusDp(95f) == 28f && OverlayGesture.composerRadiusDp(126f) == 28f, "28 dp from 95 dp")

    println(if (failed == 0) "all passed" else "$failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
