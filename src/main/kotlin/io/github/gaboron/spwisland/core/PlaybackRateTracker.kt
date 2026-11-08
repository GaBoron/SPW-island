// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

import kotlin.math.abs

/** Infers media time per wall-clock second from the host's position callbacks. */
internal class PlaybackRateTracker {
    private var previousPosition: Long? = null
    private var previousTimeNs = 0L
    var rate = 1.0
        private set

    fun useRate(value: Double) {
        if (value.isFinite() && value in .1..8.0) rate = value
    }

    fun reset() {
        rate = 1.0
        discontinuity()
    }

    fun discontinuity() { previousPosition = null }

    fun positionChanged(positionMs: Long, timeNs: Long, playing: Boolean) {
        if (!playing) {
            discontinuity()
            return
        }
        val previous = previousPosition
        val elapsedMs = (timeNs - previousTimeNs) / 1_000_000.0
        if (previous != null && elapsedMs < 300.0) return
        if (previous != null && elapsedMs in 300.0..5_000.0) {
            val observed = (positionMs - previous) / elapsedMs
            if (observed in 0.25..4.0) {
                rate = if (abs(observed - rate) > 0.3) observed else rate * 0.7 + observed * 0.3
            }
        }
        previousPosition = positionMs
        previousTimeNs = timeNs
    }
}
