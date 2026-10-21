// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.PerformanceProfile
import io.github.gaboron.spwisland.diagnostics.DiagnosticPulse
import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.platform.NativeWindowDiagnostics
import java.awt.GraphicsEnvironment
import java.awt.Window

/** Cheap frame heartbeats; platform samples follow the centralized performance budget. */
internal class WindowDiagnostics : AutoCloseable {
    private val ticks = DiagnosticPulse("window", "frame_dispatch")
    private val frames = DiagnosticPulse("rendering", "frame_production")
    private var tickCount = 0L
    private var nextSample = 0L
    private var issueStarted = 0L
    private var issueReported = 0L
    private var issueCount = 0L
    init { ticks.expected = true; IslandLog.event("window", "window.created") }
    fun tick(profile: PerformanceProfile) {
        ticks.timeoutNs = profile.diagnosticStallTimeoutNs
        frames.timeoutNs = profile.diagnosticStallTimeoutNs
        ticks.beat(++tickCount)
    }
    fun sample(window: Window, reason: String, profile: PerformanceProfile, presented: Long,
               renderer: String, inputAvailable: Boolean = true, topmostAvailable: Boolean = true,
               pointerValid: Boolean? = null) {
        frames.beat(presented)
        frames.expected = reason == "visible"
        val now = System.nanoTime()
        if (now < nextSample) return
        nextSample = now + profile.diagnosticSampleIntervalNs
        IslandLog.state("window", "display_policy", reason)
        val onscreen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.intersects(window.bounds) }
        val native = IslandLog.attempt("window", "native_state.failed") { NativeWindowDiagnostics.read(window) }.getOrDefault(emptyMap())
        val context = mapOf("awtVisible" to window.isVisible, "displayable" to window.isDisplayable,
            "onScreen" to onscreen, "width" to window.width, "height" to window.height,
            "x" to window.x, "y" to window.y, "renderer" to renderer,
            "inputAvailable" to inputAvailable, "topmostAvailable" to topmostAvailable) + native
        val status = when {
            reason != "visible" -> "hidden_by_policy"
            !window.isDisplayable -> "peer_missing"
            !window.isVisible -> "awt_hidden"
            !onscreen || window.width <= 0 || window.height <= 0 -> "invalid_bounds"
            native["nativeExists"] == false || native["nativeVisible"] == false -> "os_reports_invisible"
            native["minimized"] == true -> "os_reports_minimized"
            native["nativeTopmost"] == false -> "os_reports_not_topmost"
            else -> "internally_visible_compositor_unverified"
        }
        IslandLog.state("window", "observed_state", status, context)
        IslandLog.state("rendering", "backend", renderer)
        IslandLog.state("input", "global_pointer", pointerValid?.toString() ?: "unused")
        native["overlappingTopmostAbove"]?.let { IslandLog.state("window", "z_order_above", it.toString()) }
        if (status != "hidden_by_policy" && status != "internally_visible_compositor_unverified") {
            issueCount++
            if (issueStarted == 0L || now - issueReported >= 60_000_000_000L) {
                if (issueStarted == 0L) issueStarted = now
                IslandLog.warn("window", "visibility.inconsistent", context + mapOf("state" to status,
                    "observations" to issueCount, "durationMs" to (now - issueStarted) / 1_000_000))
                issueReported = now
            }
        } else if (issueStarted != 0L) {
            IslandLog.event("window", "visibility.recovered", mapOf("observations" to issueCount,
                "durationMs" to (now - issueStarted) / 1_000_000, "state" to status))
            issueStarted = 0; issueCount = 0
        }
    }
    override fun close() { ticks.close(); frames.close(); IslandLog.event("window", "window.closed") }
}
