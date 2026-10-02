// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.core.BackgroundProgressMode
import io.github.gaboron.spwisland.core.PlaybackSnapshot
import io.github.gaboron.spwisland.core.PlaybackStatus
import io.github.gaboron.spwisland.core.performance
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.Shape
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.roundToInt

/** Paints whole-track progress inside the island silhouette without owning playback timing. */
internal object IslandBackgroundProgress {
    fun draw(g: Graphics2D, shape: Shape, width: Int, height: Int,
             snapshot: PlaybackSnapshot, settings: IslandSettings, palette: IslandPalette,
             mode: BackgroundProgressMode, opacity: Double) {
        if (opacity <= 0.0 || mode == BackgroundProgressMode.OFF ||
            !settings.performance.renderBackgroundProgress) return
        val duration = snapshot.metadata.durationMs
        if (snapshot.track == null || duration <= 0 || snapshot.status == PlaybackStatus.IDLE) return
        val progress = (snapshot.positionMs.toDouble() / duration).coerceIn(0.0, 1.0)
        if (progress <= 0.0) return

        when (mode) {
            BackgroundProgressMode.FILL ->
                drawFill(g, shape, width, height, progress, settings, palette, opacity)
            BackgroundProgressMode.TOP_LINE ->
                drawEdgeLine(g, shape, width, height, progress, settings, palette, opacity)
            BackgroundProgressMode.OFF -> Unit
        }
    }

    private fun drawFill(g: Graphics2D, shape: Shape, width: Int, height: Int, progress: Double,
                         settings: IslandSettings, palette: IslandPalette, opacity: Double) {
        val clip = g.clip
        val composite = g.composite
        try {
            g.clip(Rectangle2D.Double(0.0, 0.0, width * progress, height.toDouble()))
            // Replace the base fill instead of stacking alpha, so completed areas do not become opaquer.
            g.composite = AlphaComposite.Src
            val alpha = settings.opacity * 255 / 100
            val base = palette.background
            val filled = progressColor(palette, alpha)
            fun mix(start: Int, end: Int) = (start + (end - start) * opacity).roundToInt().coerceIn(0, 255)
            g.color = Color(mix(base.red, filled.red), mix(base.green, filled.green),
                mix(base.blue, filled.blue), alpha)
            g.fill(shape)
        } finally {
            g.composite = composite
            g.clip = clip
        }
    }

    private fun drawEdgeLine(g: Graphics2D, shape: Shape, width: Int, height: Int,
                            progress: Double, settings: IslandSettings,
                            palette: IslandPalette, opacity: Double) {
        val edgeInset = IslandGeometry.progressEdgeInset(width, height, settings.notch, settings.cornerRoundness)
        val start = edgeInset.coerceAtLeast(2.0)
        val end = (width - edgeInset).coerceAtLeast(start)
        val filledEnd = start + (end - start) * progress
        val y = if (settings.notch) shape.bounds2D.maxY - .75 else shape.bounds2D.minY + .75
        val stroke = g.stroke
        val paint = g.paint
        try {
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val active = withAlpha(palette.lyric, (palette.lyric.alpha * opacity).roundToInt())
            val track = withAlpha(palette.lyric, (42 * opacity).roundToInt())
            g.paint = fadedEnds(start, end, track)
            g.draw(Line2D.Double(start, y, end, y))
            g.paint = fadedEnds(start, end, active)
            g.draw(Line2D.Double(start, y, filledEnd, y))
        } finally {
            g.paint = paint
            g.stroke = stroke
        }
    }

    private fun fadedEnds(start: Double, end: Double, color: Color): Paint = LinearGradientPaint(
        Point2D.Double(start, 0.0), Point2D.Double(end, 0.0),
        floatArrayOf(0f, .06f, .94f, 1f),
        arrayOf(withAlpha(color, 0), color, color, withAlpha(color, 0)))

    private fun withAlpha(color: Color, alpha: Int) =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun progressColor(palette: IslandPalette, alpha: Int): Color {
        val backgroundHsb = Color.RGBtoHSB(
            palette.background.red, palette.background.green, palette.background.blue, null)
        val lyricHsb = Color.RGBtoHSB(palette.lyric.red, palette.lyric.green, palette.lyric.blue, null)
        val brightness = (backgroundHsb[2] + .10f).coerceAtMost(lyricHsb[2] * .35f)
        val rgb = Color.getHSBColor(backgroundHsb[0], backgroundHsb[1] * .82f, brightness)
        return Color(rgb.red, rgb.green, rgb.blue, alpha)
    }
}
