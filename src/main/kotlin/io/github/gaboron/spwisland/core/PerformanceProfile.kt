// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

enum class SpectrumMode { LIVE, SYNTHETIC }

/** Central performance budget for rendering, background work and optional host integration. */
enum class PerformanceProfile(
    val maxFrameRate: Int,
    val animateLayout: Boolean,
    val detailedKaraoke: Boolean,
    val renderBackgroundProgress: Boolean,
    val renderLiquidGlass: Boolean,
    val spectrumMode: SpectrumMode,
    val screenCheckIntervalNs: Long,
    val topmostCheckIntervalNs: Long,
    val topmostRefreshIntervalNs: Long,
    val topmostRetryIntervalNs: Long,
    val diagnosticSampleIntervalNs: Long,
    val diagnosticStallTimeoutNs: Long
) {
    STANDARD(120, true, true, true, true, SpectrumMode.LIVE,
        screenCheckIntervalNs = 400_000_000L,
        topmostCheckIntervalNs = 100_000_000L,
        topmostRefreshIntervalNs = 1_000_000_000L,
        topmostRetryIntervalNs = 1_000_000_000L,
        diagnosticSampleIntervalNs = 1_000_000_000L,
        diagnosticStallTimeoutNs = 5_000_000_000L),
    LOW(15, false, false, false, false, SpectrumMode.SYNTHETIC,
        screenCheckIntervalNs = 1_500_000_000L,
        topmostCheckIntervalNs = 500_000_000L,
        topmostRefreshIntervalNs = 5_000_000_000L,
        topmostRetryIntervalNs = 5_000_000_000L,
        diagnosticSampleIntervalNs = 5_000_000_000L,
        diagnosticStallTimeoutNs = 15_000_000_000L)
}

val IslandSettings.performance: PerformanceProfile
    get() = if (lowPerformance) PerformanceProfile.LOW else PerformanceProfile.STANDARD
