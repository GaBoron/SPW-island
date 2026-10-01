// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.Word

/** Finds only the active timed cell, avoiding per-character outline work in low-performance mode. */
object TimedKaraokeBoundary {
    data class Segment(val start: Int, val end: Int, val progress: Double)

    fun at(textLength: Int, words: List<Word>, position: Long): Segment {
        var start = 0
        for (word in words) {
            val end = (start + word.text.length).coerceAtMost(textLength)
            if (position < word.startMs) return Segment(start, end, 0.0)
            if (position < word.endMs) {
                return Segment(start, end, word.progress(position))
            }
            // Zero-duration cells, including spaces, finish immediately at their start time.
            start = end
        }
        return Segment(textLength, textLength, 1.0)
    }
}
