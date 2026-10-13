// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import java.awt.geom.Rectangle2D
import io.github.gaboron.spwisland.longtone.LongToneAnalysis

/** Shapes the whole line together, so combining marks and bidirectional scripts stay intact. */
object LyricPainter {
    fun draw(g: Graphics2D, text: String, words: List<Word>, position: Long, x: Float, baseline: Float,
             available: Float, font: Font, karaoke: Boolean, color: Color = Color.WHITE,
             detailedKaraoke: Boolean = true,
             fallbackFont: Font = SystemUiFont.derive(font.style, font.size2D),
             contrastOutline: Boolean = false, shapedText: ShapedText? = null,
             longToneAnalysis: LongToneAnalysis? = null) {
        if (text.isEmpty() || available <= 0) return
        val shaped = shapedText ?: LyricTypography.shape(text, font, fallbackFont)
        val layout = shaped.layout
        val placement = LyricTextPlacement.calculate(shaped, text, words, position, x, available, karaoke, detailedKaraoke)
        val (origin, boundary, lightweight, geometry) = placement
        val copy = g.create() as Graphics2D
        try {
            copy.clip(Rectangle2D.Float(x - font.size2D * .16f, baseline - layout.ascent - font.size2D * .3f,
                available + font.size2D * .32f, layout.ascent + layout.descent + font.size2D * .6f))
            if (contrastOutline && (!karaoke || words.isEmpty()))
                LyricContrastOutline.draw(copy, shaped.outline, font.size2D, origin.toDouble(), baseline.toDouble())
            if (karaoke && words.isNotEmpty()) {
                if (lightweight) drawLightweightKaraoke(copy, shaped, boundary, origin, baseline,
                    font.size2D, color, contrastOutline)
                else AmllWordPainter.draw(copy, shaped, text, words, position, origin, baseline,
                    font.size2D, true, color, contrastOutline, geometry,
                    if (geometry != null) longToneAnalysis?.forWords(words) else null)
                return
            }
            copy.color = color
            drawShaped(copy, shaped, origin, baseline)
        } finally { copy.dispose() }
    }

    private fun drawLightweightKaraoke(g: Graphics2D, shaped: ShapedText, boundary: Float,
                                       origin: Float, baseline: Float, fontSize: Float,
                                       color: Color, contrastOutline: Boolean) {
        val width = if (shaped.layout.isLeftToRight) boundary - shaped.left else shaped.right - boundary
        if (contrastOutline)
            LyricContrastOutline.draw(g, shaped.outline, fontSize, origin.toDouble(), baseline.toDouble())
        g.color = Color(126, 129, 138)
        drawShaped(g, shaped, origin, baseline)
        if (width <= 0) return
        val clipX = if (shaped.layout.isLeftToRight) origin + shaped.left else origin + boundary
        g.clip(Rectangle2D.Float(clipX, baseline - shaped.layout.ascent, width,
            shaped.layout.ascent + shaped.layout.descent))
        g.color = color
        drawShaped(g, shaped, origin, baseline)
    }

    internal fun drawShaped(g: Graphics2D, shaped: ShapedText, x: Float, y: Float) {
        shaped.layout.draw(g, x, y)
    }

}
