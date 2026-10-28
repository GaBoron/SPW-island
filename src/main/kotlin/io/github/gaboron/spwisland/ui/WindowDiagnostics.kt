// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.PerformanceProfile
import io.github.gaboron.spwisland.diagnostics.DiagnosticPulse
import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.platform.AwtGraphicsDiagnostics
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
    private var sampledFrames: Long? = null
    private var sampledAt = 0L
    init {
        ticks.expected = true
        IslandLog.event("window", "window.created")
        IslandLog.state("window", "monitoring", "active")
    }
    fun tick(profile: PerformanceProfile) {
        ticks.timeoutNs = profile.diagnosticStallTimeoutNs
        frames.timeoutNs = profile.diagnosticStallTimeoutNs
        ticks.beat(++tickCount)
    }
    fun sample(window: Window, reason: String, profile: PerformanceProfile, presented: Long,
               renderer: () -> Map<String, Any?>, inputAvailable: Boolean = true,
               topmostAvailable: Boolean = true, pointerValid: Boolean? = null,
               details: () -> Map<String, Any?> = { emptyMap() }) {
        frames.beat(presented)
        frames.expected = reason == "visible"
        val now = System.nanoTime()
        if (now < nextSample) return
        nextSample = now + profile.diagnosticSampleIntervalNs
        val rendering = IslandLog.attempt("rendering", "sample.failed", renderer)
            .getOrElse { mapOf("renderer" to "unknown", "error" to it.javaClass.name) }
        val frameProgress = mapOf("produced" to presented,
            "sincePreviousSample" to sampledFrames?.let { (presented - it).coerceAtLeast(0) },
            "intervalMs" to sampledAt.takeIf { it != 0L }?.let { (now - it) / 1_000_000 },
            "evidence" to "production_only_compositor_unverified")
        sampledFrames = presented
        sampledAt = now
        IslandLog.state("window", "display_policy", reason)
        val onscreen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.intersects(window.bounds) }
        val native = IslandLog.attempt("window", "native_state.failed") { NativeWindowDiagnostics.read(window) }.getOrDefault(emptyMap())
        val graphics = IslandLog.attempt("window", "graphics_configuration.failed") {
            AwtGraphicsDiagnostics.read(window.graphicsConfiguration)
        }.getOrDefault(emptyMap())
        val context = mapOf("awtVisible" to window.isVisible, "displayable" to window.isDisplayable,
            "onScreen" to onscreen, "width" to window.width, "height" to window.height,
            "x" to window.x, "y" to window.y, "renderer" to rendering["renderer"],
            "inputAvailable" to inputAvailable, "topmostAvailable" to topmostAvailable,
            "frameProgress" to frameProgress, "awtGraphics" to graphics,
            "backgroundAlpha" to window.background?.alpha, "opacity" to window.opacity) + native +
            IslandLog.attempt("window", "sample_details.failed", details).getOrDefault(emptyMap())
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
        IslandLog.state("rendering", "backend", rendering["renderer"].toString(), rendering)
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
    override fun close() {
        ticks.close()
        frames.close()
        IslandLog.state("window", "monitoring", "stopped")
        IslandLog.event("window", "window.closed")
    }
}
