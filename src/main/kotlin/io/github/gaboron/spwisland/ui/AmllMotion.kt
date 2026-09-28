// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from AMLL contributors, applemusic-like-lyrics, DOM lyric-line.ts.
// Source revision 58ccd3ffae7ec4e9a6d1cdb0dd88ac8c767f68a8; see NOTICE.
// 2026-09-04: GaBoron ported floating/emphasis curves to a Java2D time sampler.
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.Word
import kotlin.math.*

object AmllMotion {
    data class Pose(val xEm: Double = 0.0, val yEm: Double = 0.0, val scale: Double = 1.0, val glow: Double = 0.0)
    fun word(word: Word, time: Long, character: Int, count: Int, last: Boolean, longTone: Boolean): Pose {
        var duration = max(1000.0, (word.endMs - word.startMs).toDouble())
        val elapsed = (time - word.startMs).toDouble()
        val lift = -.05 * bezier((elapsed / duration).coerceIn(0.0, 1.0), 0.0, 0.0, .58, 1.0)
        if (!longTone) return Pose(yEm = lift)
        var amount = duration / 2000
        amount = (if (amount > 1) sqrt(amount) else amount.pow(3)) * .6
        var blur = duration / 3000
        blur = (if (blur > 1) sqrt(blur) else blur.pow(3)) * .5
        if (last) { amount *= 1.6; blur *= 1.5; duration *= 1.2 }
        amount = min(1.2, amount); blur = min(.8, blur)
        val progress = ((elapsed - duration / 2.5 / max(1, count) * character) / duration).coerceIn(0.0, 1.0)
        val emphasis = if (progress < .5) bezier(progress * 2, .2, .4, .58, 1.0)
            else 1 - bezier((progress - .5) * 2, .3, 0.0, .58, 1.0)
        val floatProgress = ((elapsed + 400) / (duration * 1.4)).coerceIn(0.0, 1.0)
        return Pose(-emphasis * .03 * amount * (count / 2.0 - character),
            lift - emphasis * .025 * amount - sin(floatProgress * PI) * .05,
            1 + emphasis * .1 * amount, emphasis * blur)
    }
    // Independent damped-spring line transition; starts with zero position and velocity.
    fun line(seconds: Double): Double = 1 - exp(-10 * seconds) * (cos(14 * seconds) + 10.0 / 14 * sin(14 * seconds))
    private fun bezier(x: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        fun curve(t: Double, a: Double, b: Double) = 3 * (1 - t).pow(2) * t * a + 3 * (1 - t) * t * t * b + t.pow(3)
        var low = 0.0; var high = 1.0
        repeat(18) { val middle = (low + high) / 2; if (curve(middle, x1, x2) < x) low = middle else high = middle }
        return curve((low + high) / 2, y1, y2)
    }
}
