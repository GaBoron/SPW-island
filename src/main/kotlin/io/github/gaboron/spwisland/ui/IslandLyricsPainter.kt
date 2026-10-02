// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import java.awt.geom.Rectangle2D

object IslandLyricsPainter {
    fun draw(g: Graphics2D, current: PlaybackSnapshot, previous: PlaybackSnapshot?, settings: IslandSettings,
             width: Float, height: Float, transition: Double, inset: Float, layout: IslandLyricsLayout,
             previousLayout: IslandLyricsLayout?) {
        val progress = if (transition >= 1) 1.0 else AmllMotion.line(transition * .65)
        val outgoingAlpha = (1 - transition * 3).coerceIn(0.0, 1.0).toFloat()
        val currentRows = layout.rows(height)
        val previousRows = previousLayout?.rows(height).orEmpty()
        val currentLines = currentRows.map { it.block.line }
        if (previous != null && outgoingAlpha > 0) {
            previousRows.filter { it.block.line !in currentLines }.forEach { row ->
                row(g, previous, settings, row, width, height, -settings.fontSize * progress,
                    outgoingAlpha, 1 - .04 * progress, inset)
            }
        }
        currentRows.forEach { currentRow ->
            val old = previousRows.firstOrNull { it.block.line == currentRow.block.line }
            if (old != null) {
                val placed = currentRow.copy(
                    mainBaseline = lerp(old.mainBaseline, currentRow.mainBaseline, progress),
                    subBaseline = lerp(old.subBaseline, currentRow.subBaseline, progress))
                row(g, current, settings, placed, width, height, 0.0, 1f, 1.0, inset)
            } else {
                row(g, current, settings, currentRow, width, height, settings.fontSize * (1 - progress),
                    (transition * 3).coerceIn(0.0, 1.0).toFloat(), .96 + .04 * progress, inset)
            }
        }
    }
    private fun row(g: Graphics2D, snapshot: PlaybackSnapshot, settings: IslandSettings,
                    row: IslandLyricsLayout.Row, width: Float, height: Float,
                    offset: Double, alpha: Float, scale: Double, inset: Float) {
        val block = row.block
        val line = block.line
        val time = snapshot.positionMs + settings.offsetMs
        val copy = g.create() as Graphics2D
        try {
            copy.clip(Rectangle2D.Float(inset - 8, 2f, width - inset * 2 + 16, height - 4f))
            copy.composite = AlphaComposite.SrcOver.derive(alpha)
            copy.translate(width / 2.0, height / 2.0 + offset)
            copy.scale(scale, scale); copy.translate(-width / 2.0, -height / 2.0)
            val available = width - inset * 2
            val palette = IslandPalette.from(settings, snapshot.metadata.coverRgb)
            LyricPainter.draw(copy, block.main, block.timedWords,
                if (block.timedWords.isNotEmpty()) time else (time - (line?.startMs ?: 0)).coerceAtLeast(0),
                inset, row.mainBaseline, available, block.mainFont, settings.karaoke,
                color = palette.lyric,
                detailedKaraoke = settings.performance.detailedKaraoke,
                fallbackFont = block.mainFallbackFont,
                contrastOutline = palette.lyricOutline, shapedText = block.shapedMain)
            block.sub?.let { LyricPainter.draw(copy, it, emptyList(), (time - (line?.startMs ?: 0)).coerceAtLeast(0),
                inset, row.subBaseline, available, block.subFont, false, Color(177, 182, 195),
                fallbackFont = block.subFallbackFont,
                contrastOutline = palette.lyricOutline, shapedText = block.shapedSub) }
        } finally { copy.dispose() }
    }
    private fun lerp(from: Float, to: Float, progress: Double): Float =
        (from + (to - from) * progress).toFloat()
}
