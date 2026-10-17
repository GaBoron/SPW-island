// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

/** Immutable song facts are rebuilt only when a lyric row changes. */
internal class LyricsDocument private constructor(val lines: List<LyricLine>) {
    private data class Key(val start: Long, val end: Long, val text: String)
    private val byKey = lines.associateBy { Key(it.startMs, it.endMs, it.text) }
    val usesWordTiming = lines.any { it.hasWordTimingEvidence }

    fun lastStarted(position: Long): LyricLine? {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].startMs <= position) low = middle + 1 else high = middle
        }
        return lines.getOrNull(low - 1)
    }

    fun withUpdates(updates: List<LyricLine>): LyricsDocument {
        if (updates.all { byKey[Key(it.startMs, it.endMs, it.text)] == it }) return this
        val merged = LinkedHashMap(byKey)
        for (line in updates) {
            val key = Key(line.startMs, line.endMs, line.text)
            merged.remove(key)
            merged[key] = line
        }
        return LyricsDocument(merged.values.sortedWith(
            compareBy<LyricLine> { it.startMs }.thenBy { it.endMs }))
    }

    companion object {
        val EMPTY = LyricsDocument(emptyList())
    }
}
