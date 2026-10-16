// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.IslandBackgroundStyle
import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.ui.gpu.SwingGlassMaterial
import java.awt.Color
import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Shared material, first-frame presentation and visibility lifecycle for both auxiliary windows. */
internal open class LiquidGlassSurface(private val settings: () -> IslandSettings,
                                       private val report: (Throwable) -> Unit,
                                       private val arc: Double) : JPanel() {
    var surfaceBorder: Color = Color(255, 255, 255, 32)
    private var material: SwingGlassMaterial? = null
    private var window: Window? = null
    private var pendingShow: (() -> Unit)? = null
    val gpuDrawing: Boolean get() = material?.active == true
    private val initializationTimeout = Timer(2000) { material?.initializationTimedOut() }.apply { isRepeats = false }
    private val visibility = object : ComponentAdapter() {
        override fun componentHidden(event: ComponentEvent) {
            val hiddenWindow = window ?: return
            // Skiko's remaining HIDDEN listeners still need the layer attached to its Window.
            SwingUtilities.invokeLater {
                if (window === hiddenWindow && !hiddenWindow.isVisible && pendingShow == null) release()
            }
        }
        override fun componentShown(event: ComponentEvent) { material?.windowShown() }
        override fun componentMoved(event: ComponentEvent) { updateMaterial(); repaint() }
        override fun componentResized(event: ComponentEvent) { updateMaterial(); repaint() }
    }

    init { isOpaque = false }

    override fun addNotify() {
        super.addNotify()
        window = SwingUtilities.getWindowAncestor(this)?.also { it.addComponentListener(visibility) }
    }

    override fun removeNotify() {
        window?.removeComponentListener(visibility); window = null
        release()
        super.removeNotify()
    }

    fun showWhenReady(show: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        if (!usesGlass() || window?.isVisible == true) { show(); return }
        pendingShow = show
        initializationTimeout.restart()
        updateMaterial()
        materialChanged()
    }

    fun cancelShow() = release()

    private fun usesGlass() = settings().let {
        Platform.isWindows() && it.backgroundStyle == IslandBackgroundStyle.LIQUID_GLASS && !it.lowPerformance
    }

    private fun request(): SwingGlassMaterial.Request? {
        val owner = window ?: return null
        if (width <= 1 || height <= 1) return null
        val scale = owner.graphicsConfiguration.defaultTransform
        return SwingGlassMaterial.Request(width, height, arc, background.rgb,
            scale.scaleX.toFloat(), scale.scaleY.toFloat(), owner.x, owner.y)
    }

    private fun renderer(): SwingGlassMaterial? {
        val owner = window ?: return null
        return material ?: SwingGlassMaterial(owner, this, ::materialChanged, report).also { material = it }
    }

    private fun updateMaterial() {
        if (usesGlass() && (pendingShow != null || isShowing)) request()?.let { renderer()?.prepare(it) }
    }

    fun refreshGpuFrame() = updateMaterial()

    fun paintGpuContent(graphics: Graphics2D) {
        validate()
        super.paintChildren(graphics)
    }

    fun invalidateGpuContent() { material?.invalidateContent() }

    override fun paintChildren(graphics: Graphics) {
        if (gpuDrawing) material?.invalidateContent() else super.paintChildren(graphics)
    }

    private fun materialChanged() {
        if (material?.ready == true) {
            initializationTimeout.stop()
            val show = pendingShow
            pendingShow = null
            show?.invoke()
            material?.windowShown()
        }
        if (isShowing) repaint()
    }

    private fun release() {
        initializationTimeout.stop(); pendingShow = null
        material?.close(); material = null
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            if (usesGlass()) request()?.let { renderer()?.prepare(it) } else release()
            if (gpuDrawing) {
                g.composite = AlphaComposite.Clear
                g.fillRect(0, 0, width, height)
                material?.draw()
                return
            }
            val outline = RoundRectangle2D.Double(.5, .5, width - 1.0, height - 1.0, arc, arc)
            g.color = background; g.fill(outline)
            g.color = surfaceBorder; g.draw(outline)
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}
