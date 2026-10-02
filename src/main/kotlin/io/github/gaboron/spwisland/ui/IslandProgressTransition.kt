// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.BackgroundProgressMode

/** Keeps the outgoing background style until its finite, reversible fade finishes. */
internal class IslandProgressTransition {
    private val motion = IslandTransitionMotion(false, 0.0, timing = IslandTransitionMotion.Timing(
        open = .18, notchClose = .12, pillClose = .12))
    var opacity = 0.0
        private set
    var mode = BackgroundProgressMode.OFF
        private set
    val animating get() = motion.animating

    fun update(show: Boolean, requestedMode: BackgroundProgressMode, dt: Double, instant: Boolean) {
        if (requestedMode != BackgroundProgressMode.OFF) mode = requestedMode
        opacity = motion.update(show && requestedMode != BackgroundProgressMode.OFF,
            dt, instant, false).coerceIn(0.0, 1.0)
        if (opacity == 0.0 && !animating) mode = requestedMode
    }
}
