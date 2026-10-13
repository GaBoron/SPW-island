// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.ui.*
import org.jetbrains.skia.*
import java.awt.Color
import kotlin.math.roundToInt

internal class IslandGpuBackground(private val resources: IslandGpuResources) {
    fun draw(canvas: Canvas, frame: IslandGpuFrame, shape: IslandHoverGeometry.Frame, palette: IslandPalette) {
        val path = resources.path(shape.shape)
        val alpha = frame.settings.opacity * 255 / 100
        val base = palette.background.rgb and 0xffffff
        canvas.drawPath(path, resources.fill(base or (alpha shl 24)))
        val duration = frame.snapshot.metadata.durationMs
        val progress = if (duration > 0) (frame.snapshot.positionMs.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
        if (progress > 0 && frame.snapshot.track != null && frame.snapshot.status != PlaybackStatus.IDLE &&
            frame.settings.performance.renderBackgroundProgress && frame.progressOpacity > 0) {
            when (frame.progressMode) {
                BackgroundProgressMode.FILL -> canvas.scoped {
                    canvas.clipRect(Rect.makeXYWH(0f, 0f, (frame.width * progress).toFloat(), frame.height.toFloat()), ClipMode.INTERSECT, true)
                    val bg = Color.RGBtoHSB(palette.background.red, palette.background.green, palette.background.blue, null)
                    val fg = Color.RGBtoHSB(palette.lyric.red, palette.lyric.green, palette.lyric.blue, null)
                    val filled = Color(Color.HSBtoRGB(bg[0], bg[1] * .82f, (bg[2] + .10f).coerceAtMost(fg[2] * .35f)))
                    fun mix(a: Int, b: Int) = (a + (b - a) * frame.progressOpacity).roundToInt().coerceIn(0, 255)
                    val color = (alpha shl 24) or (mix(palette.background.red, filled.red) shl 16) or
                        (mix(palette.background.green, filled.green) shl 8) or mix(palette.background.blue, filled.blue)
                    canvas.drawPath(path, resources.fill(color, blend = BlendMode.SRC))
                }
                BackgroundProgressMode.TOP_LINE -> {
                    val inset = IslandGeometry.progressEdgeInset(frame.width, frame.height, frame.settings.notch, frame.settings.cornerRoundness)
                    val start = inset.coerceAtLeast(2.0).toFloat()
                    val end = (frame.width - inset).coerceAtLeast(start.toDouble()).toFloat()
                    val y = if (frame.settings.notch) shape.shape.bounds2D.maxY - .75 else shape.shape.bounds2D.minY + .75
                    edge(canvas, start, end, end, y.toFloat(), palette.lyric.rgb,
                        (42 * frame.progressOpacity).roundToInt())
                    edge(canvas, start, end, start + (end - start) * progress.toFloat(), y.toFloat(), palette.lyric.rgb,
                        (palette.lyric.alpha * frame.progressOpacity).roundToInt())
                }
                BackgroundProgressMode.OFF -> Unit
            }
        }
        canvas.drawPath(path, resources.stroke(0x13ffffff, 1f))
    }

    private fun edge(canvas: Canvas, start: Float, end: Float, filled: Float, y: Float, color: Int, alpha: Int) {
        val rgb = color and 0xffffff
        val paint = resources.gradient(start, end, intArrayOf(rgb, rgb or (alpha shl 24), rgb or (alpha shl 24), rgb),
            floatArrayOf(0f, .06f, .94f, 1f))
        // A native rounded rectangle is the filled form of this 1.5 px round-capped line.
        canvas.drawRRect(RRect.makeXYWH(start - .75f, y - .75f, filled - start + 1.5f, 1.5f, .75f), paint)
    }
}
