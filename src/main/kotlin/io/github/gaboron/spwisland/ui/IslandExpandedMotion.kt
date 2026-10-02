// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

/** Shares hover timing, while allowing one main rebound beyond both control-area endpoints. */
internal class IslandExpandedMotion {
    private val motion = IslandTransitionMotion(false, OPEN_OVERSHOOT, CLOSE_OVERSHOOT)
    val animating: Boolean get() = motion.animating

    fun update(expanded: Boolean, dt: Double, instant: Boolean, notch: Boolean): Double =
        motion.update(expanded, dt, instant, notch)

    companion object {
        const val OPEN_OVERSHOOT = .07
        const val CLOSE_OVERSHOOT = .05
    }
}
