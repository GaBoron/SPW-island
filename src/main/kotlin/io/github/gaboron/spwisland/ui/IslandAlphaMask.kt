// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** Reuses pixel buffers and an antialiased mask instead of clipping the window to a binary region. */
class IslandAlphaMask {
    private var pixels: BufferedImage? = null
    private var mask: BufferedImage? = null
    private var key: List<Any>? = null

    fun paint(target: Graphics2D, panel: IslandPanel, presentationScale: Double,
              draw: (Graphics2D) -> Unit) {
        if (panel.width <= 0 || panel.height <= 0 || presentationScale <= 0.0) return
        // The parent animates the finished island. Rasterize text at device resolution,
        // independently of that animation, so a hidden line cannot return as soft pixels.
        val sx = target.transform.scaleX / presentationScale
        val sy = target.transform.scaleY / presentationScale
        val w = ceil(panel.width * sx).toInt().coerceAtLeast(1)
        val h = ceil(panel.height * sy).toInt().coerceAtLeast(1)
        val capacityW = ceil(maxOf(panel.width, panel.parent?.width ?: 0) * sx).toInt().coerceAtLeast(w)
        val capacityH = ceil(maxOf(panel.height, panel.parent?.height ?: 0) * sy).toInt().coerceAtLeast(h)
        if (pixels == null || pixels!!.width < capacityW || pixels!!.height < capacityH) {
            pixels = BufferedImage(capacityW, capacityH, BufferedImage.TYPE_INT_ARGB_PRE)
            mask = BufferedImage(capacityW, capacityH, BufferedImage.TYPE_INT_ARGB_PRE)
            key = null
        }
        val next = listOf(panel.width, panel.height, panel.settings.notch, panel.settings.cornerRoundness,
            panel.anchor.vertical, sx, sy)
        if (key != next) {
            mask!!.createGraphics().let { g ->
                g.composite = AlphaComposite.Clear; g.fillRect(0, 0, capacityW, capacityH)
                g.composite = AlphaComposite.Src; g.color = Color.WHITE
                g.scale(sx, sy)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.fill(IslandGeometry.silhouette(panel.width, panel.height, panel.settings.notch,
                    panel.settings.cornerRoundness, panel.anchor))
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
            try { draw(content) } finally { content.dispose() }
            g.composite = AlphaComposite.DstIn; g.drawImage(mask, 0, 0, null)
            g.dispose()
        }
        target.drawImage(pixels, 0, 0, panel.width, panel.height, 0, 0, w, h, null)
    }
}
