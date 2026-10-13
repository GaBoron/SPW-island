// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.Word
import java.awt.font.TextHitInfo

/** Timing, bidi caret and scrolling decisions do not depend on the drawing backend. */
internal object LyricTextPlacement {
    data class Placement(val origin: Float, val boundary: Float, val lightweight: Boolean,
                         val geometry: List<WordGeometry.Cell>?)

    fun calculate(shaped: ShapedText, text: String, words: List<Word>, position: Long,
                  x: Float, available: Float, karaoke: Boolean, detailed: Boolean): Placement {
        val layout = shaped.layout
        val lightweight = karaoke && words.isNotEmpty() && !detailed
        val segment = if (lightweight) TimedKaraokeBoundary.at(text.length, words, position) else null
        fun caret(offset: Int): Float = when {
            offset <= 0 -> layout.getCaretInfo(TextHitInfo.leading(0))[0]
            offset >= layout.characterCount -> layout.getCaretInfo(TextHitInfo.trailing(layout.characterCount - 1))[0]
            else -> layout.getCaretInfo(TextHitInfo.leading(offset))[0]
        }
        val boundary = segment?.let {
            if (it.start == text.length) shaped.right
            else caret(it.start) + (caret(it.end) - caret(it.start)) * it.progress.toFloat()
        } ?: shaped.left
        val geometry = if (karaoke && words.isNotEmpty() && detailed) WordGeometry.ready(shaped, text, words) else null
        var character = 0
        var activeX = if (layout.isLeftToRight) boundary - shaped.left else shaped.right - boundary
        if (!lightweight) for ((index, word) in words.withIndex()) {
            val end = (character + word.text.length).coerceAtMost(text.length)
            val progress = word.progress(position)
            if (end > character && progress > 0) {
                val bounds = geometry?.get(index)?.bounds ?: layout.getLogicalHighlightShape(character, end).bounds2D
                activeX = (bounds.x + bounds.width * progress).toFloat()
            }
            character = end
        }
        val overflow = (shaped.width - available).coerceAtLeast(0f)
        val scroll = if (words.isNotEmpty()) (activeX - available * .65).coerceIn(0.0, overflow.toDouble()).toFloat()
            else if (overflow > 0) (position.coerceAtLeast(0) / 45.0).coerceAtMost(overflow.toDouble()).toFloat() else 0f
        return Placement((if (overflow == 0f) x + (available - shaped.width) / 2 else x - scroll) - shaped.left,
            boundary, lightweight, geometry)
    }
}
