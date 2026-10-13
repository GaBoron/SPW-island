// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.*
import org.jetbrains.skia.*
import kotlin.math.sin

internal class IslandGpuSides(private val resources: IslandGpuResources) {
    fun leading(canvas: Canvas, mode: SideContent, artwork: CoverArtwork?, bands: FloatArray,
                x: Float, y: Float, size: Float, color: Int) {
        when (mode) {
            SideContent.SPECTRUM -> spectrum(canvas, bands, x, y, size, color)
            SideContent.COVER, SideContent.COVER_SPECTRUM -> {
                val scale = size / 32f
                val bounds = Rect.makeXYWH(x - size / 2, y - size / 2, size, size)
                val shape = RRect.makeXYWH(bounds.left, bounds.top, size, size, 4.5f * scale)
                if (artwork == null) {
                    canvas.drawRRect(shape, resources.fill((color and 0xffffff) or (55 shl 24)))
                    canvas.drawCircle(x, y, 7f * scale, resources.stroke((color and 0xffffff) or (180 shl 24), 1f))
                    canvas.drawCircle(x, y, 2f * scale, resources.fill((color and 0xffffff) or (180 shl 24)))
                } else {
                    val source = minOf(artwork.width, artwork.height).toFloat()
                    canvas.scoped {
                        canvas.clipRRect(shape, ClipMode.INTERSECT, true)
                        canvas.drawImageRect(resources.cover(artwork), Rect.makeXYWH((artwork.width - source) / 2,
                            (artwork.height - source) / 2, source, source), bounds, SamplingMode.MITCHELL,
                            resources.fill(-1), true)
                    }
                    canvas.drawRRect(shape, resources.stroke(0x23ffffff, 1f))
                }
            }
            SideContent.NONE -> Unit
        }
    }

    fun trailing(canvas: Canvas, mode: SideContent, snapshot: PlaybackSnapshot, bands: FloatArray,
                 x: Float, y: Float, size: Float, color: Int, animate: Boolean) {
        when (mode) {
            SideContent.COVER_SPECTRUM -> spectrum(canvas, bands, x, y, size, color)
            SideContent.SPECTRUM, SideContent.COVER -> {
                if (snapshot.line == null && snapshot.lyrics.isEmpty() && snapshot.playing) {
                    for (i in 0..2) {
                        val alpha = if (animate) (150 + 90 * sin(snapshot.positionMs / 350.0 - i)).toInt() else 150
                        canvas.drawCircle(x - 6 + i * 7, y, 2f, resources.fill((alpha shl 24) or 0xbeccdd))
                    }
                } else if (snapshot.playing) canvas.drawCircle(x, y, 3f, resources.fill(0xff738395.toInt()))
                else {
                    canvas.drawRRect(RRect.makeXYWH(x - 6, y - 5, 3f, 10f, 1f), resources.fill(0xff738395.toInt()))
                    canvas.drawRRect(RRect.makeXYWH(x, y - 5, 3f, 10f, 1f), resources.fill(0xff738395.toInt()))
                }
            }
            SideContent.NONE -> Unit
        }
    }

    private fun spectrum(canvas: Canvas, bands: FloatArray, x: Float, y: Float, size: Float, color: Int) {
        val scale = size / 32f
        val paint = resources.fill(color)
        for (i in 0 until 4) {
            val height = 2 * scale + bands.getOrElse(i) { 0f }.coerceIn(0f, 1f) * (size - 2 * scale)
            canvas.drawRRect(RRect.makeXYWH(x - 11 * scale + i * 6 * scale, y - height / 2,
                3 * scale, height, 1.5f * scale), paint)
        }
    }
}
