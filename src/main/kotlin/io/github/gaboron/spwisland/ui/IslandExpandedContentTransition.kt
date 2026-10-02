// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor

/** Keeps an opaque control strip resident, then applies its shared fade and translation on composition. */
internal class IslandExpandedContentTransition {
    private var pixels: BufferedImage? = null

    fun prepare(width: Int, scale: AffineTransform) = reserve(
        ceil(width * scale.scaleX).toInt(), ceil(STRIP_HEIGHT * scale.scaleY).toInt())

    fun paint(target: Graphics2D, width: Int, top: Double, expansion: Double, draw: (Graphics2D) -> Unit) {
        val sx = target.transform.scaleX
        val sy = target.transform.scaleY
        val rasterTop = floor(top * sy) / sy
        reserve(ceil(width * sx).toInt(), ceil(STRIP_HEIGHT * sy).toInt())
        val buffer = pixels!!
        val layer = buffer.createGraphics()
        try {
            layer.composite = AlphaComposite.Clear; layer.fillRect(0, 0, buffer.width, buffer.height)
            layer.composite = AlphaComposite.SrcOver
            layer.scale(sx, sy)
            layer.translate(0.0, -rasterTop)
            layer.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            layer.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            layer.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            draw(layer)
        } finally { layer.dispose() }
        val alpha = opacity(expansion)
        val shift = 7.0 * (1.0 - alpha) + (expansion - 1.0).coerceAtLeast(0.0) * 30.0
        val presentation = target.create() as Graphics2D
        try {
            val composite = presentation.composite as? AlphaComposite ?: AlphaComposite.SrcOver
            presentation.composite = composite.derive(composite.alpha * alpha)
            presentation.drawImage(buffer, AffineTransform().apply {
                translate(0.0, rasterTop + shift); scale(1.0 / sx, 1.0 / sy)
            }, null)
        } finally { presentation.dispose() }
    }

    private fun reserve(width: Int, height: Int) {
        val w = ((maxOf(width, pixels?.width ?: 0, 1) + 63) / 64) * 64
        val h = ((maxOf(height, pixels?.height ?: 0, 1) + 63) / 64) * 64
        if (pixels == null || pixels!!.width < w || pixels!!.height < h)
            pixels = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
    }

    companion object {
        private const val STRIP_HEIGHT = IslandTextBlock.EXPANDED_HEIGHT + 14
        fun visible(expansion: Double): Boolean = opacity(expansion) > .001f

        private fun opacity(expansion: Double): Float {
            val progress = ((expansion.coerceIn(0.0, 1.0) - .08) / .92).coerceIn(0.0, 1.0)
            return (progress * progress * (3.0 - 2.0 * progress)).toFloat()
        }
    }
}
