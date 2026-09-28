// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import java.awt.font.TextHitInfo
import java.awt.geom.Rectangle2D

/** Shapes the whole line together, so combining marks and bidirectional scripts stay intact. */
object LyricPainter {
    fun draw(g: Graphics2D, text: String, words: List<Word>, position: Long, x: Float, baseline: Float,
             available: Float, font: Font, karaoke: Boolean, color: Color = Color.WHITE,
             detailedKaraoke: Boolean = true,
             fallbackFont: Font = SystemUiFont.derive(font.style, font.size2D),
             contrastOutline: Boolean = false) {
        if (text.isEmpty() || available <= 0) return
        val shaped = LyricTypography.shape(text, font, fallbackFont)
        val layout = shaped.layout
        val lightweight = karaoke && words.isNotEmpty() && !detailedKaraoke
        val segment = if (lightweight) TimedKaraokeBoundary.at(text.length, words, position) else null
        val boundary = segment?.let {
            if (it.start == text.length) shaped.right
            else {
                val start = caret(layout, it.start)
                val end = caret(layout, it.end)
                start + (end - start) * it.progress.toFloat()
            }
        } ?: shaped.left
        val geometry = if (karaoke && words.isNotEmpty() && detailedKaraoke) {
            WordGeometry.ready(shaped, text, words.map { it.text })
        } else null
        var character = 0
        var activeX = if (layout.isLeftToRight) boundary - shaped.left else shaped.right - boundary
        if (!lightweight) {
            for ((index, word) in words.withIndex()) {
                val end = (character + word.text.length).coerceAtMost(text.length)
                if (end > character && word.progress(position) > 0) {
                    val bounds = geometry?.get(index)?.bounds ?: layout.getLogicalHighlightShape(character, end).bounds2D
                    activeX = (bounds.x + bounds.width * word.progress(position)).toFloat()
                }
                character = end
            }
        }
        // Keep the sung word in view; untimed long lines make one smooth pass over their lifetime.
        val overflow = (shaped.width - available).coerceAtLeast(0f)
        val scroll = if (words.isNotEmpty()) (activeX - available * 0.65).coerceIn(0.0, overflow.toDouble()).toFloat()
            else if (overflow > 0) ((position.coerceAtLeast(0) / 45.0).coerceAtMost(overflow.toDouble())).toFloat() else 0f
        val origin = (if (overflow == 0f) x + (available - shaped.width) / 2 else x - scroll) - shaped.left
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
                    font.size2D, true, color, contrastOutline)
                return
            }
            copy.color = color
            layout.draw(copy, origin, baseline)
        } finally { copy.dispose() }
    }

    private fun drawLightweightKaraoke(g: Graphics2D, shaped: ShapedText, boundary: Float,
                                       origin: Float, baseline: Float, fontSize: Float,
                                       color: Color, contrastOutline: Boolean) {
        val width = if (shaped.layout.isLeftToRight) boundary - shaped.left else shaped.right - boundary
        if (contrastOutline)
            LyricContrastOutline.draw(g, shaped.outline, fontSize, origin.toDouble(), baseline.toDouble())
        g.color = Color(126, 129, 138)
        shaped.layout.draw(g, origin, baseline)
        if (width <= 0) return
        val clipX = if (shaped.layout.isLeftToRight) origin + shaped.left else origin + boundary
        g.clip(Rectangle2D.Float(clipX, baseline - shaped.layout.ascent, width,
            shaped.layout.ascent + shaped.layout.descent))
        g.color = color
        shaped.layout.draw(g, origin, baseline)
    }

    private fun caret(layout: java.awt.font.TextLayout, offset: Int): Float = when {
        offset <= 0 -> layout.getCaretInfo(TextHitInfo.leading(0))[0]
        offset >= layout.characterCount -> layout.getCaretInfo(TextHitInfo.trailing(layout.characterCount - 1))[0]
        else -> layout.getCaretInfo(TextHitInfo.leading(offset))[0]
    }
}
