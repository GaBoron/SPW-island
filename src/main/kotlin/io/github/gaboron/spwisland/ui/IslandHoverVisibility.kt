// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Point
import java.awt.Shape

/** Combines requested visibility and stable hover suppression through one reversible reveal motion. */
internal class IslandHoverVisibility {
    private var entryRegion: Shape? = null
    private val motion = IslandHoverMotion()
    private var pose = IslandHoverMotion.Pose.SHOWN
    val animating: Boolean get() = motion.animating

    /** A pause can retire the native window only after its last visible frame has collapsed. */
    fun shouldRender(show: Boolean, windowVisible: Boolean, animate: Boolean): Boolean =
        show || animate && windowVisible && (pose.reveal > 0.0 || animating)

    fun update(enabled: Boolean, mouse: Point?, region: Shape, dt: Double, instant: Boolean,
               notch: Boolean, show: Boolean = true): IslandHoverMotion.Pose {
        // An unavailable/stale pointer must fail open, including after compositor restart.
        if (!enabled || mouse == null) entryRegion = null
        else {
            if (entryRegion?.contains(mouse) == false) entryRegion = null
            if (entryRegion == null && region.contains(mouse)) entryRegion = region
        }
        pose = motion.update(show && (!enabled || entryRegion == null), dt, instant, notch)
        return pose
    }
}
