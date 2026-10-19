// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.ui.*
import org.jetbrains.skia.*
import java.awt.Font
import kotlin.math.roundToInt

internal class IslandGpuControls(private val resources: IslandGpuResources, private val lyrics: IslandGpuLyrics) {
    private val timeFont = SystemUiFont.derive(Font.PLAIN, 10f)
    private val icons = PlaybackIcon.entries.associateWith { icon -> PathBuilder().use { builder ->
        fun rectangle(x: Float, y: Float, w: Float, h: Float) {
            builder.moveTo(x, y).lineTo(x + w, y).lineTo(x + w, y + h).lineTo(x, y + h).closePath()
        }
        fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            builder.moveTo(x1, y1).lineTo(x2, y2).lineTo(x3, y3).closePath()
        }
        when (icon) {
            PlaybackIcon.PLAY -> triangle(5f, 3f, 5f, 15f, 15f, 9f)
            PlaybackIcon.PREVIOUS -> { rectangle(3f, 3f, 2f, 12f); triangle(15f, 3f, 15f, 15f, 5f, 9f) }
            PlaybackIcon.NEXT -> { rectangle(13f, 3f, 2f, 12f); triangle(3f, 3f, 3f, 15f, 13f, 9f) }
            PlaybackIcon.PAUSE -> Unit
        }
        builder.detach()
    } }

    fun draw(canvas: Canvas, frame: IslandGpuFrame, contentX: Float, contentY: Float, contentWidth: Float,
             lyricHeight: Float, infoInset: Float, scaleY: Double) {
        val alpha = IslandExpandedContentTransition.opacity(frame.expansion)
        if (alpha == 0f) return
        val top = kotlin.math.floor((contentY + lyricHeight - 2) * scaleY).toFloat() / scaleY.toFloat()
        val shift = (7 * (1 - alpha) + (frame.expansion - 1).coerceAtLeast(0.0) * 30).toFloat()
        resources.layer(canvas, Rect.makeXYWH(0f, top + shift, frame.width.toFloat(), IslandExpandedContentTransition.STRIP_HEIGHT.toFloat()), alpha) {
            canvas.translate(0f, shift)
            canvas.clipRect(Rect.makeXYWH(0f, top, frame.width.toFloat(), IslandExpandedContentTransition.STRIP_HEIGHT.toFloat()), ClipMode.INTERSECT, true)
            frame.info?.let { info -> canvas.scoped {
                canvas.translate(contentX, contentY)
                lyrics.line(canvas, info, frame.infoText.orEmpty(), emptyList(), 0, infoInset, lyricHeight + 17,
                    contentWidth - infoInset * 2, 12f, false, false, 0xff939cae.toInt(), false, 1f)
            } }
            for (control in frame.controls) canvas.scoped {
                val shiftIcon = if (control.pressed) 1 else 0
                canvas.translate((control.bounds.x + (control.bounds.width - 18) / 2 + shiftIcon).toFloat(),
                    (control.bounds.y + (control.bounds.height - 18) / 2 + shiftIcon).toFloat())
                val paint = resources.fill(control.color)
                if (control.icon == PlaybackIcon.PAUSE) {
                    canvas.drawRRect(RRect.makeXYWH(4f, 3f, 4f, 12f, .5f), paint)
                    canvas.drawRRect(RRect.makeXYWH(11f, 3f, 4f, 12f, .5f), paint)
                } else canvas.drawPath(icons.getValue(control.icon), paint)
            }
            val progress = frame.progress
            val bounds = frame.progressBounds
            if (progress != null && bounds != null) canvas.scoped {
                canvas.translate(bounds.x.toFloat(), bounds.y.toFloat())
                val span = (bounds.width - 96).coerceAtLeast(1)
                val filled = (span * progress.fraction).toFloat()
                canvas.drawRRect(RRect.makeXYWH(48f, 12f, span.toFloat(), 4f, 2f), resources.fill(0xff484d59.toInt()))
                if (filled > 0) canvas.drawRRect(RRect.makeXYWH(48f, 12f, filled, 4f, 2f), resources.fill(progress.color))
                if (progress.knobOpacity > 0f) canvas.drawCircle(50f + filled, 14f, 5f,
                    resources.fill(opacity(progress.color, progress.knobOpacity)))
                labels(canvas, progress.positionLabel, progress.durationLabel, bounds.width, progress.color,
                    1f - progress.volumeMix, -5f * progress.volumeMix)
                labels(canvas, "音量", progress.volumeLabel, bounds.width, progress.color,
                    progress.volumeMix, 5f * (1f - progress.volumeMix))
            }
        }
    }

    private fun labels(canvas: Canvas, left: String, right: String, width: Int, color: Int, alpha: Float, offset: Float) {
        if (alpha <= 0f) return
        val tint = opacity(color, alpha)
        text(canvas, left, 0f, tint, offset)
        val duration = LyricTypography.shape(right, timeFont)
        text(canvas, right, width - duration.layout.advance.roundToInt().toFloat(), tint, offset)
    }

    private fun opacity(color: Int, alpha: Float) =
        (color and 0x00ffffff) or (((color ushr 24) * alpha).roundToInt().coerceIn(0, 255) shl 24)

    private fun text(canvas: Canvas, text: String, x: Float, color: Int, offset: Float) = canvas.scoped {
        canvas.translate(x, 18f + offset)
        canvas.drawPath(resources.path(LyricTypography.shape(text, timeFont).outline), resources.fill(color))
    }

    fun close() { icons.values.forEach(Path::close) }
}
