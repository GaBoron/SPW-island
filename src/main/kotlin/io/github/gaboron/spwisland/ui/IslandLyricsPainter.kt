// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import java.awt.geom.Rectangle2D
import io.github.gaboron.spwisland.longtone.LongToneAnalysis

object IslandLyricsPainter {
    fun draw(g: Graphics2D, current: PlaybackSnapshot, previous: PlaybackSnapshot?, settings: IslandSettings,
             width: Float, height: Float, transition: Double, inset: Float, layout: IslandLyricsLayout,
             previousLayout: IslandLyricsLayout?, longTones: LongToneAnalysis) {
        IslandLyricPresentation.rows(layout, previousLayout, height, transition, settings.fontSize).forEach { pose ->
            val snapshot = if (pose.previous) previous ?: return@forEach else current
            row(g, snapshot, settings, pose.layout, width, height, pose.offset,
                pose.opacity, pose.scale, inset, longTones)
        }
    }
    private fun row(g: Graphics2D, snapshot: PlaybackSnapshot, settings: IslandSettings,
                    row: IslandLyricsLayout.Row, width: Float, height: Float,
                    offset: Double, alpha: Float, scale: Double, inset: Float, longTones: LongToneAnalysis) {
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
                contrastOutline = palette.lyricOutline, shapedText = block.shapedMain, longToneAnalysis = longTones)
            block.sub?.let { LyricPainter.draw(copy, it, emptyList(), (time - (line?.startMs ?: 0)).coerceAtLeast(0),
                inset, row.subBaseline, available, block.subFont, false, Color(177, 182, 195),
                fallbackFont = block.subFallbackFont,
                contrastOutline = palette.lyricOutline, shapedText = block.shapedSub) }
        } finally { copy.dispose() }
    }
}
