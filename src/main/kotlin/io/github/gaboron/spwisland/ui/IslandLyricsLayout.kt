// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.Dimension
import kotlin.math.ceil

/** Measures a variable number of lyric and translation rows as one animated island body. */
class IslandLyricsLayout private constructor(snapshot: PlaybackSnapshot, private val settings: IslandSettings,
                                             selectedLines: List<LyricLine>) {
    val blocks = selectedLines
        .map { IslandTextBlock(snapshot, settings, it) }
        .ifEmpty { listOf(IslandTextBlock(snapshot, settings)) }
    private val rowGap = if (blocks.size > 1) maxOf(8f, settings.fontSize * .36f) else 0f
    private val contentHeight = blocks.sumOf { it.height.toDouble() }.toFloat() + rowGap * (blocks.size - 1)
    private val contentLayout = IslandContentLayout.from(
        blocks.first().mainLineHeight, settings.sideContent.showsSides
    )
    val preferredHeight = maxOf(settings.fontSize + 28, ceil(contentHeight + 28).toInt(),
        contentLayout.minimumLyricHeight)

    data class Row(val block: IslandTextBlock, val mainBaseline: Float, val subBaseline: Float)

    fun rows(areaHeight: Float): List<Row> {
        var top = (areaHeight - contentHeight) / 2
        return blocks.map { block ->
            val row = Row(block, top - block.shapedMain.top,
                top + block.shapedMain.height + block.gap - (block.shapedSub?.top ?: 0f))
            top += block.height + rowGap
            row
        }
    }

    fun size(maxWidth: Int, expanded: Boolean): Dimension {
        val motionPad = blocks.maxOf { block ->
            if (settings.karaoke && block.timedWords.isNotEmpty()) settings.fontSize * .32f else 0f
        }
        val horizontal = IslandContentLayout.from(
            blocks.first().mainLineHeight, settings.sideContent.showsSides
        )
        val needed = ceil(blocks.maxOf { maxOf(it.shapedMain.width + motionPad, it.shapedSub?.width ?: 0f) } +
            horizontal.textInset * 2).toInt()
        val height = preferredHeight + if (expanded) IslandTextBlock.EXPANDED_HEIGHT else 0
        val frameInset = ceil(IslandGeometry.frameInset(
            height.toDouble(), settings.notch, settings.cornerRoundness
        )).toInt()
        val limit = (maxWidth - frameInset * 2).coerceAtLeast(1)
        val natural = maxOf(needed, IslandContentLayout.minimumWidth(expanded))
        val safeWidth = (if (settings.fixedWidth) limit else natural).coerceAtMost(limit)
        return Dimension((safeWidth + frameInset * 2).coerceAtMost(maxWidth.coerceAtLeast(1)), height)
    }

    class Cache {
        private data class Key(val lines: List<LyricLine>, val title: String?, val wordTiming: Boolean,
                               val settings: IslandSettings)
        private val cache = object : LinkedHashMap<Key, IslandLyricsLayout>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, IslandLyricsLayout>) = size > 24
        }

        fun forSnapshot(snapshot: PlaybackSnapshot, settings: IslandSettings,
                        lines: List<LyricLine> = ActiveLyrics.select(snapshot, settings.experimentalMultiLine)): IslandLyricsLayout {
            val key = Key(lines, snapshot.track?.title, snapshot.usesWordTiming, settings)
            synchronized(cache) { cache[key]?.let { return it } }
            // Cold font measurement also runs on the preparation worker; never hold the cache lock for it.
            val measured = IslandLyricsLayout(snapshot, settings, lines)
            return synchronized(cache) { cache.getOrPut(key) { measured } }
        }
    }
}
