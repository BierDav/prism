// How both apps' Machines views say the hosts' metrics (GET
// /v1/machines/metrics): sizes, rates, shares and uptimes, short enough for a
// phone's row.

@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package at.quickme.prism.shared

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.native.HiddenFromObjC

object HostFormat {
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    /** Bytes in binary units: "512 MB", "29.6 GB", "1 TB". */
    fun bytes(n: Double): String {
        var v = abs(n)
        var unit = 0
        while (v >= 999.5 && unit < UNITS.lastIndex) {
            v /= 1024
            unit++
        }
        val text = if (unit == 0 || v >= 99.95) v.roundToLong().toString() else fixed(v, 1).removeSuffix(".0")
        return (if (n < 0) "-" else "") + text + " " + UNITS[unit]
    }

    /** For Kotlin callers with a Long; Swift has the Double one. */
    @HiddenFromObjC
    fun bytes(n: Long): String = bytes(n.toDouble())

    /** Bytes per second: "1.2 MB/s". */
    fun rate(bytesPerSecond: Double): String = bytes(bytesPerSecond) + "/s"

    /** A share 0–1 as a whole percentage: "42%". */
    fun percent(share: Double): String = "${(share.coerceIn(0.0, 1.0) * 100).roundToLong()}%"

    /** A duration in its two largest units: "5 d 3 h", "3 h 12 min", "12 min". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val d = s / 86400
        val h = s % 86400 / 3600
        val m = s % 3600 / 60
        return when {
            d > 0 -> if (h > 0) "$d d $h h" else "$d d"
            h > 0 -> if (m > 0) "$h h $m min" else "$h h"
            else -> "$m min"
        }
    }

    /** A load average: "3.42", "12.1", "105". */
    fun load(v: Double): String = when {
        v < 10 -> fixed(v, 2)
        v < 100 -> fixed(v, 1)
        else -> v.roundToLong().toString()
    }

    /** How full something is: 0 fine, 1 high (from 80%), 2 nearly full (from 92%). */
    fun level(share: Double): Int = when {
        share >= 0.92 -> 2
        share >= 0.80 -> 1
        else -> 0
    }

    /** [v] with exactly [decimals] digits after the point, rounded half up (as "%.Nf"). */
    private fun fixed(v: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val r = abs(v * scale).roundToLong()
        val sign = if (v < 0 && r != 0L) "-" else ""
        return sign + (r / scale) + "." + (r % scale).toString().padStart(decimals, '0')
    }
}
