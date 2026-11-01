// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import javax.swing.JPanel
import io.github.gaboron.spwisland.core.performance
import io.github.gaboron.spwisland.diagnostics.DiagnosticLatency
import io.github.gaboron.spwisland.diagnostics.IslandLog

/** Stable translucent backing surface; animation changes the child silhouette, not the native bitmap. */
class IslandSurface(val island: IslandPanel, private val constructedAtNs: Long = System.nanoTime()) : JPanel(null) {
    var presentedFrames = 0L
        private set
    internal var hoverPose = IslandHoverMotion.Pose.SHOWN
    internal val renderCapacity = Dimension()
    internal var gpuRedraw: (() -> Unit)? = null
    @Volatile internal var frameBudgetNs = 16_666_666L
    private var firstFrame = true
    private var diagnosticsClosed = false
    private var previousFrameNs = 0L
    private var backend = "unknown"
    private fun performanceContext() = mapOf("backend" to backend,
        "background" to island.settings.backgroundStyle, "multiLine" to island.settings.experimentalMultiLine)
    private val renderWork = DiagnosticLatency("frame.render", ::performanceContext)
    private val renderInterval = DiagnosticLatency("frame.interval", ::performanceContext)

    @Synchronized internal fun frameProduced(startedNs: Long, renderer: String) {
        if (diagnosticsClosed) return
        backend = renderer
        val now = System.nanoTime()
        val profile = island.settings.performance
        renderWork.record(now - startedNs, frameBudgetNs, profile)
        if (previousFrameNs != 0L) renderInterval.record(startedNs - previousFrameNs, frameBudgetNs, profile)
        previousFrameNs = startedNs
        if (firstFrame) {
            firstFrame = false
            IslandLog.duration("window.first_frame", constructedAtNs, performanceContext() +
                mapOf("evidence" to "paint_commands_only_compositor_unverified"))
        }
    }

    @Synchronized internal fun pauseFrameDiagnostics() {
        if (previousFrameNs != 0L) { renderWork.close(); renderInterval.close() }
        previousFrameNs = 0L
    }
    @Synchronized internal fun closeDiagnostics() {
        diagnosticsClosed = true
        renderWork.close(); renderInterval.close()
    }
    init { isOpaque = false; isDoubleBuffered = false; island.isDoubleBuffered = false; add(island) }
    override fun paintChildren(graphics: Graphics) {
        gpuRedraw?.let { it(); return }
        val started = System.nanoTime()
        val g = graphics.create() as Graphics2D
        try {
            g.translate(island.x.toDouble(), island.y.toDouble())
            island.paintPresentation(g, IslandHoverGeometry.frame(island.width, island.height,
                island.settings.notch, island.settings.cornerRoundness, hoverPose))
            presentedFrames++
            frameProduced(started, "Java2D")
        } finally { g.dispose() }
    }
    override fun paintComponent(graphics: Graphics) {
        if (gpuRedraw != null) return
        val g = graphics.create() as Graphics2D
        try {
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, width, height)
        } finally { g.dispose() }
    }
    fun inputRegion(): Shape {
        val shape = IslandGeometry.silhouette(island.width, island.height, island.settings.notch,
            island.settings.cornerRoundness)
        // The region includes the antialiased outer edge instead of clipping it to a hard pixel boundary.
        val padded = Area(shape).apply { add(Area(BasicStroke(2f).createStrokedShape(shape))) }
        return AffineTransform.getTranslateInstance(island.x.toDouble(), island.y.toDouble()).createTransformedShape(padded)
    }
}
