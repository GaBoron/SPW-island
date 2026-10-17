// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

enum class SpectrumMode { LIVE, SYNTHETIC }

/** Central performance budget for rendering, background work and optional host integration. */
enum class PerformanceProfile(
    val maxFrameRate: Int,
    val animateLayout: Boolean,
    val detailedKaraoke: Boolean,
    val renderBackgroundProgress: Boolean,
    val spectrumMode: SpectrumMode,
    val screenCheckIntervalNs: Long,
    val topmostCheckIntervalNs: Long
) {
    STANDARD(120, true, true, true, SpectrumMode.LIVE, 400_000_000L, 100_000_000L),
    LOW(15, false, false, false, SpectrumMode.SYNTHETIC, 1_500_000_000L, 500_000_000L)
}

val IslandSettings.performance: PerformanceProfile
    get() = if (lowPerformance) PerformanceProfile.LOW else PerformanceProfile.STANDARD
