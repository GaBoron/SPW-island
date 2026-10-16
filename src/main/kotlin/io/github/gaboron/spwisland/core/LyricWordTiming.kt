// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

import java.text.BreakIterator
import java.util.Locale

/** Estimated LRC timing is presentation-only; it never becomes evidence for long-tone analysis. */
internal data class LyricWordTiming(val words: List<Word>, val estimated: Boolean = false) {
    companion object {
        fun resolve(line: LyricLine?, usesWordTiming: Boolean, force: Boolean): LyricWordTiming {
            if (line == null) return LyricWordTiming(emptyList())
            if (usesWordTiming && line.timedWords.isNotEmpty()) return LyricWordTiming(line.timedWords)
            if (!force || line.endMs <= line.startMs || line.text.isBlank()) return LyricWordTiming(emptyList())
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(line.text) }
            val cells = buildList {
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    add(line.text.substring(start, end))
                    start = end; end = iterator.next()
                }
            }
            val count = cells.count { it.isNotBlank() }
            if (count == 0) return LyricWordTiming(emptyList())
            val duration = line.endMs - line.startMs
            // Integer division keeps adjacent boundaries identical and the last cell at the line end.
            fun boundary(index: Int) = line.startMs + duration / count * index + duration % count * index / count
            var index = 0
            return LyricWordTiming(cells.map { text ->
                val start = boundary(index)
                if (text.isNotBlank()) index++
                Word(start, boundary(index), text)
            }, estimated = true)
        }
    }
}
