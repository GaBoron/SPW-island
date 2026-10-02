// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

/** Finite Hermite motion: one reveal crest, one return, and continuous velocity on reversal. */
internal class IslandHoverMotion {
    data class Pose(val reveal: Double, val animating: Boolean = false) {
        val fullyShown: Boolean get() = !animating && reveal == 1.0
        companion object {
            val SHOWN = Pose(1.0)
            val HIDDEN = Pose(0.0)
        }
    }

    private val motion = IslandTransitionMotion(true, .04)
    val animating: Boolean get() = motion.animating

    fun update(show: Boolean, dt: Double, instant: Boolean, notch: Boolean): Pose {
        val reveal = motion.update(show, dt, instant, notch)
        return Pose(reveal.coerceAtLeast(0.0), motion.animating)
    }
}
