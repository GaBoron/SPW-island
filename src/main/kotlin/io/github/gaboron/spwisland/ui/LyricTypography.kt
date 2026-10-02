// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import java.text.BreakIterator
import java.util.Locale

/** Shared shaping from the bundled font for identical measurement and drawing. */
object LyricTypography {
    val context = FontRenderContext(AffineTransform(), true, true)
    private data class Key(val text: String, val font: Font, val fallbackFont: Font, val glyphFallbackFont: Font)
    private val layouts = object : LinkedHashMap<Key, ShapedText>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ShapedText>) = size > 96
    }

    fun shape(text: String, font: Font,
              fallbackFont: Font = SystemUiFont.derive(font.style, font.size2D)): ShapedText {
        val glyphFallbackFont = SystemUiFont.glyphFallback(fallbackFont)
        val key = Key(text, font, fallbackFont, glyphFallbackFont)
        synchronized(layouts) { layouts[key]?.let { return it } }
        require(text.isNotEmpty())
        val attributed = AttributedString(text)
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            val cluster = text.substring(start, end)
            val selectedFont = when {
                font.canDisplayUpTo(cluster) < 0 -> font
                fallbackFont.canDisplayUpTo(cluster) < 0 -> fallbackFont
                glyphFallbackFont.canDisplayUpTo(cluster) < 0 -> glyphFallbackFont
                else -> fallbackFont
            }
            attributed.addAttribute(TextAttribute.FONT, selectedFont, start, end)
            start = end
            end = breaks.next()
        }
        val shaped = ShapedText(TextLayout(attributed.iterator, context))
        return synchronized(layouts) { layouts.getOrPut(key) { shaped } }
    }
}

class ShapedText(val layout: TextLayout) {
    val outline by lazy { layout.getOutline(null) }
    private val glyphs = mutableMapOf<Pair<Int, Int>, java.awt.Shape>()
    @Synchronized fun glyph(start: Int, end: Int): java.awt.Shape = glyphs.getOrPut(start to end) {
        val bounds = layout.getLogicalHighlightShape(start, end).bounds2D
        val minX = if (start == 0) minOf(bounds.x, left.toDouble()) else bounds.x
        val maxX = if (end == layout.characterCount) maxOf(bounds.maxX, right.toDouble()) else bounds.maxX
        Area(outline).apply { intersect(Area(Rectangle2D.Double(minX, top.toDouble() - 1, maxX - minX, height.toDouble() + 2))) }
    }
    // Include ink overhangs as well as advances; ceil is applied only at the window boundary.
    val left = minOf(0f, layout.bounds.minX.toFloat())
    val right = maxOf(layout.advance, layout.bounds.maxX.toFloat())
    val width = right - left
    val top = layout.bounds.minY.toFloat()
    val bottom = layout.bounds.maxY.toFloat()
    val height = bottom - top
}
