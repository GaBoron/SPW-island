// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

import kotlin.math.abs

object PlaybackRateRegression {
    @JvmStatic fun main(args: Array<String>) {
        val tracker = PlaybackRateTracker()
        tracker.positionChanged(0, 0, true)
        tracker.positionChanged(200, 100_000_000, true)
        close(tracker.rate, 1.0)
        tracker.positionChanged(1000, 500_000_000, true)
        close(tracker.rate, 2.0)

        // Small timing jitter should not abruptly replace the observed speed.
        tracker.positionChanged(2050, 1_000_000_000, true)
        check(tracker.rate > 2.0 && tracker.rate < 2.1)
        val speed = tracker.rate
        tracker.positionChanged(60_000, 1_500_000_000, true)
        close(tracker.rate, speed) // A seek is not playback speed.
        tracker.positionChanged(100, 2_000_000_000, true)
        close(tracker.rate, speed) // Backward seeks are also discontinuities.

        tracker.positionChanged(100, 3_000_000_000, false)
        tracker.positionChanged(20_000, 4_000_000_000, true)
        close(tracker.rate, speed)
        tracker.positionChanged(20_500, 4_500_000_000, true)
        close(tracker.rate, 1.0)
        tracker.positionChanged(60_000, 12_000_000_000, true)
        close(tracker.rate, 1.0) // Long callback gaps must not estimate a new rate.

        tracker.useRate(1.5)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 8.01)) {
            tracker.useRate(invalid)
            close(tracker.rate, 1.5)
        }
        tracker.discontinuity()
        tracker.positionChanged(0, 13_000_000_000, true)
        close(tracker.rate, 1.5)
        tracker.reset()
        close(tracker.rate, 1.0)
        tracker.positionChanged(0, 14_000_000_000, true)
        close(tracker.rate, 1.0)
        println("Playback rate, seek, pause, callback-gap and reset checks passed")
    }

    private fun close(actual: Double, expected: Double) {
        check(abs(actual - expected) < 0.000001) { "Expected $expected, got $actual" }
    }
}
