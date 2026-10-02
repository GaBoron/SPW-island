// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

/** Finite Hermite travel with optional overshoot at either end and velocity continuity on reversal. */
internal class IslandTransitionMotion(initialOpen: Boolean, private val openOvershoot: Double,
                                      private val closeOvershoot: Double = 0.0,
                                      private val timing: Timing = Timing()) {
    data class Timing(val open: Double = .44, val notchClose: Double = .38,
                      val pillClose: Double = .40, val settle: Double = .16)
    private var open = initialOpen
    private var position = if (initialOpen) 1.0 else 0.0
    private var velocity = 0.0
    private var start = position
    private var startVelocity = 0.0
    private var end = position
    private var elapsed = 0.0
    private var duration = 0.0
    private var returnAfterCrest = false
    var animating = false
        private set

    fun update(show: Boolean, dt: Double, instant: Boolean, notch: Boolean): Double {
        if (instant) {
            open = show
            position = if (show) 1.0 else 0.0
            velocity = 0.0
            animating = false
            returnAfterCrest = false
            return position
        }
        if (show != open) {
            open = show
            val distance = (if (show) 1.0 - position else position).coerceIn(0.0, 1.0)
            val crest = if (show) 1.0 + openOvershoot * distance else 0.0 - closeOvershoot * distance
            val travel = if (show) timing.open else if (notch) timing.notchClose else timing.pillClose
            begin(crest, travel * distance.coerceAtLeast(.3))
            returnAfterCrest = crest != (if (show) 1.0 else 0.0)
        }
        var remaining = dt.coerceAtLeast(0.0)
        while (animating) {
            val step = minOf(remaining, duration - elapsed)
            elapsed += step
            remaining -= step
            sample()
            if (elapsed < duration) break
            position = end
            velocity = 0.0
            if (returnAfterCrest) {
                returnAfterCrest = false
                begin(if (open) 1.0 else 0.0, timing.settle)
            } else animating = false
            if (remaining <= 0.0) break
        }
        return position
    }

    private fun begin(target: Double, seconds: Double) {
        start = position
        startVelocity = velocity
        end = target
        duration = seconds
        elapsed = 0.0
        animating = true
    }

    private fun sample() {
        val t = (elapsed / duration).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        position = (2 * t3 - 3 * t2 + 1) * start +
            (t3 - 2 * t2 + t) * duration * startVelocity + (-2 * t3 + 3 * t2) * end
        velocity = ((6 * t2 - 6 * t) * start +
            (3 * t2 - 4 * t + 1) * duration * startVelocity + (-6 * t2 + 6 * t) * end) / duration
    }
}
