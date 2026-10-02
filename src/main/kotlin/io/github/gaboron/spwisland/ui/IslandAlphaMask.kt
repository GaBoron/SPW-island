// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** Reuses pixel buffers and an antialiased mask instead of clipping the window to a binary region. */
internal class IslandAlphaMask {
    private var pixels: BufferedImage? = null
    private var mask: BufferedImage? = null
    private var foreground: BufferedImage? = null
    private var key: List<Any>? = null

    fun prepare(panel: IslandPanel, scale: AffineTransform) =
        reserveFor(panel, scale.scaleX, scale.scaleY, 1, 1)

    fun paint(target: Graphics2D, panel: IslandPanel, frame: IslandHoverGeometry.Frame,
              drawBackground: (Graphics2D) -> Unit, drawContent: (Graphics2D) -> Unit) {
        if (panel.width <= 0 || panel.height <= 0) return
        // Only device scaling reaches the rasterizer; hover motion changes the mask, never text density.
        val sx = target.transform.scaleX
        val sy = target.transform.scaleY
        val bounds = frame.shape.bounds.apply { grow(1, 1) }
        val w = ceil(bounds.width * sx).toInt().coerceAtLeast(1)
        val h = ceil(bounds.height * sy).toInt().coerceAtLeast(1)
        reserveFor(panel, sx, sy, w, h)
        // A resident, unmasked content layer keeps text and controls warm even at zero visibility.
        foreground!!.createGraphics().let { layer ->
            try {
                layer.composite = AlphaComposite.Clear
                layer.fillRect(0, 0, foreground!!.width, foreground!!.height)
                layer.composite = AlphaComposite.SrcOver
                layer.clipRect(0, 0, ceil(panel.width * sx).toInt(), ceil(panel.height * sy).toInt())
                layer.scale(sx, sy)
                renderingHints(layer)
                drawContent(layer)
            } finally { layer.dispose() }
        }
        if (frame.opacity <= 0.0) return
        val next = listOf(bounds, frame.maskKey, sx, sy)
        if (key != next) {
            mask!!.createGraphics().let { g ->
                g.clipRect(0, 0, w, h)
                g.composite = AlphaComposite.Clear; g.fillRect(0, 0, w, h)
                g.composite = AlphaComposite.Src; g.color = Color.WHITE
                g.scale(sx, sy)
                g.translate(-bounds.x.toDouble(), -bounds.y.toDouble())
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.fill(frame.shape)
                g.dispose()
            }
            key = next
        }
        pixels!!.createGraphics().let { g ->
            g.clipRect(0, 0, w, h)
            g.composite = AlphaComposite.Clear; g.fillRect(0, 0, w, h)
            g.composite = AlphaComposite.SrcOver
            val content = g.create() as Graphics2D
            content.scale(sx, sy)
            content.translate(-bounds.x.toDouble(), -bounds.y.toDouble())
            renderingHints(content)
            try { drawBackground(content) } finally { content.dispose() }
            // All hover phases use the same content path; only this final composite changes.
            g.composite = AlphaComposite.SrcOver.derive(frame.contentOpacity.toFloat())
            g.drawImage(foreground, AffineTransform.getTranslateInstance(
                -bounds.x * sx, (-bounds.y + frame.contentOffsetY) * sy), null)
            g.composite = AlphaComposite.DstIn; g.drawImage(mask, 0, 0, null)
            g.dispose()
        }
        val presentation = target.create() as Graphics2D
        try {
            val composite = presentation.composite as? AlphaComposite ?: AlphaComposite.SrcOver
            presentation.composite = composite.derive(composite.alpha * frame.opacity.toFloat())
            presentation.drawImage(pixels, bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height,
                0, 0, w, h, null)
        } finally { presentation.dispose() }
    }

    private fun reserveFor(panel: IslandPanel, sx: Double, sy: Double, minimumWidth: Int, minimumHeight: Int) {
        // The native canvas can be clipped by a screen edge; reserve the full morph in the raster buffers.
        val reserved = (panel.parent as? IslandSurface)?.renderCapacity ?: panel.size
        val width = ceil(maxOf(panel.width, panel.parent?.width ?: 0, reserved.width) * sx).toInt().coerceAtLeast(minimumWidth)
        val height = ceil(maxOf(panel.height, panel.parent?.height ?: 0, reserved.height) * sy).toInt().coerceAtLeast(minimumHeight)
        val capacityW = ((maxOf(width, pixels?.width ?: 0) + 63) / 64) * 64
        val capacityH = ((maxOf(height, pixels?.height ?: 0) + 63) / 64) * 64
        if (pixels == null || pixels!!.width < capacityW || pixels!!.height < capacityH) {
            pixels = BufferedImage(capacityW, capacityH, BufferedImage.TYPE_INT_ARGB_PRE)
            mask = BufferedImage(capacityW, capacityH, BufferedImage.TYPE_INT_ARGB_PRE)
            foreground = BufferedImage(capacityW, capacityH, BufferedImage.TYPE_INT_ARGB_PRE)
            key = null
        }
    }

    private fun renderingHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }
}
