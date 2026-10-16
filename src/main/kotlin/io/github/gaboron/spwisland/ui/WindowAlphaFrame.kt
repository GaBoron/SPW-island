// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** Replaces complete device-scale alpha pixels, including the transparent corners of child repaints. */
internal class WindowAlphaFrame(private val arc: Double) {
    private var frame: BufferedImage? = null
    private var mask: BufferedImage? = null
    private var maskKey: Any? = null

    fun paint(graphics: Graphics, width: Int, height: Int, draw: (Graphics2D) -> Unit) {
        if (width <= 0 || height <= 0) return
        val target = graphics as Graphics2D
        val sx = target.transform.scaleX
        val sy = target.transform.scaleY
        val w = ceil(width * sx).toInt().coerceAtLeast(1)
        val h = ceil(height * sy).toInt().coerceAtLeast(1)
        if (frame?.width != w || frame?.height != h) {
            frame = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE).apply { accelerationPriority = 0f }
            mask = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE).apply { accelerationPriority = 0f }
            maskKey = null
        }
        val nextKey = listOf(width, height, sx, sy)
        if (maskKey != nextKey) {
            checkNotNull(mask).createGraphics().let { g ->
                try {
                    g.composite = AlphaComposite.Clear; g.fillRect(0, 0, w, h)
                    g.composite = AlphaComposite.Src; g.color = Color.WHITE
                    g.scale(sx, sy)
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.fill(RoundRectangle2D.Double(.5, .5, width - 1.0, height - 1.0, arc, arc))
                } finally { g.dispose() }
            }
            maskKey = nextKey
        }
        checkNotNull(frame).createGraphics().let { g ->
            try {
                g.composite = AlphaComposite.Clear; g.fillRect(0, 0, w, h)
                g.composite = AlphaComposite.SrcOver
                val content = g.create() as Graphics2D
                try {
                    content.scale(sx, sy)
                    content.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    content.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    draw(content)
                } finally { content.dispose() }
                g.composite = AlphaComposite.DstIn
                g.drawImage(mask, 0, 0, null)
            } finally { g.dispose() }
        }
        val presentation = target.create() as Graphics2D
        try {
            presentation.composite = AlphaComposite.Src
            presentation.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            presentation.drawImage(frame, 0, 0, width, height, null)
        } finally { presentation.dispose() }
    }
}
