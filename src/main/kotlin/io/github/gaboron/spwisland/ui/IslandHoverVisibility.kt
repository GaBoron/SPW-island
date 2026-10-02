// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Point
import java.awt.Shape

/** Keeps the entry silhouette stable while hidden, independently of animated rendering and lyrics. */
internal class IslandHoverVisibility {
    private var entryRegion: Shape? = null
    private val motion = IslandHoverMotion()
    val animating: Boolean get() = motion.animating

    fun update(enabled: Boolean, mouse: Point?, region: Shape, dt: Double, instant: Boolean,
               notch: Boolean): IslandHoverMotion.Pose {
        if (!enabled) entryRegion = null
        else if (mouse != null) {
            if (entryRegion?.contains(mouse) == false) entryRegion = null
            if (entryRegion == null && region.contains(mouse)) entryRegion = region
        }
        return motion.update(!enabled || entryRegion == null, dt, instant, notch)
    }
}
