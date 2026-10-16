// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.longtone.LongToneAnalysis
import io.github.gaboron.spwisland.ui.*
import org.jetbrains.skia.*
import java.awt.Color
import java.awt.geom.Rectangle2D

/** Draws cached native glyph paths directly, sharing the existing timing and font shaping. */
internal class IslandGpuLyrics(private val resources: IslandGpuResources) {
    val adaptiveContrast = IslandLyricContrast()

    fun rows(canvas: Canvas, frame: IslandGpuFrame, width: Float, height: Float, inset: Float) {
        IslandLyricPresentation.rows(frame.lyrics, frame.previousLyrics, height, frame.transition, frame.settings.fontSize).forEach { pose ->
            val snapshot = if (pose.previous) frame.outgoing ?: return@forEach else frame.snapshot
            val block = pose.layout.block
            val palette = IslandPalette.from(frame.settings, snapshot.metadata.coverRgb)
            val time = snapshot.positionMs + frame.settings.offsetMs
            canvas.scoped {
                canvas.clipRect(Rect.makeXYWH(inset - 8, 2f, width - inset * 2 + 16, height - 4f), ClipMode.INTERSECT, true)
                canvas.translate(width / 2, height / 2 + pose.offset.toFloat())
                canvas.scale(pose.scale.toFloat(), pose.scale.toFloat()); canvas.translate(-width / 2, -height / 2)
                line(canvas, block.shapedMain, block.main, block.timedWords,
                    if (block.timedWords.isNotEmpty()) time else (time - (block.line?.startMs ?: 0)).coerceAtLeast(0),
                    inset, pose.layout.mainBaseline, width - inset * 2, block.mainFont.size2D,
                    frame.settings.karaoke, frame.settings.performance.detailedKaraoke, palette.lyric.rgb,
                    palette.lyricOutline, pose.opacity, frame.longTones, adaptive = true,
                    allowLongTone = block.allowLongTone)
                block.shapedSub?.let { shaped -> line(canvas, shaped, block.sub.orEmpty(), emptyList(),
                    (time - (block.line?.startMs ?: 0)).coerceAtLeast(0), inset, pose.layout.subBaseline,
                    width - inset * 2, block.subFont.size2D, false, false, 0xffb1b6c3.toInt(),
                    palette.lyricOutline, pose.opacity, frame.longTones, adaptive = true) }
            }
        }
    }

    fun line(canvas: Canvas, shaped: ShapedText, text: String, words: List<Word>, time: Long,
             x: Float, baseline: Float, available: Float, size: Float, karaoke: Boolean, detailed: Boolean,
             color: Int, contrast: Boolean, alpha: Float, longTones: LongToneAnalysis? = null,
             adaptive: Boolean = false, allowLongTone: Boolean = true) {
        if (text.isEmpty() || available <= 0) return
        val adaptiveLine = adaptive && adaptiveContrast.active
        val placement = LyricTextPlacement.calculate(shaped, text, words, time, x, available, karaoke, detailed)
        canvas.scoped {
            canvas.clipRect(Rect.makeXYWH(x - size * .16f, baseline - shaped.layout.ascent - size * .3f,
                available + size * .32f, shaped.layout.ascent + shaped.layout.descent + size * .6f), ClipMode.INTERSECT, true)
            canvas.translate(placement.origin, baseline)
            if (adaptiveLine) adaptiveContrast.prepareLine(canvas,
                maxOf(shaped.left, x - placement.origin), minOf(shaped.right, x + available - placement.origin),
                shaped.layout.ascent, shaped.layout.descent, color)
            when {
                !karaoke || words.isEmpty() -> {
                    val path = resources.path(shaped.outline)
                    val paint = resources.fill(color, alpha)
                    outline(canvas, path, size, contrast, alpha, adaptiveLine)
                    canvas.drawPath(path, paint)
                }
                placement.lightweight -> {
                    val path = resources.path(shaped.outline)
                    outline(canvas, path, size, contrast, alpha, adaptiveLine)
                    canvas.drawPath(path, resources.fill(DIM, alpha))
                    val width = if (shaped.layout.isLeftToRight) placement.boundary - shaped.left else shaped.right - placement.boundary
                    if (width > 0) {
                        val start = if (shaped.layout.isLeftToRight) shaped.left else placement.boundary
                        canvas.clipRect(Rect.makeXYWH(start, -shaped.layout.ascent, width, shaped.layout.ascent + shaped.layout.descent), ClipMode.INTERSECT, true)
                        canvas.drawPath(path, resources.fill(color, alpha))
                    }
                }
                placement.geometry == null -> timed(canvas, shaped, words, time, size, color, contrast, alpha, adaptiveLine)
                else -> words(canvas, shaped, words, placement.geometry, time, size, color, contrast, alpha,
                    longTones.takeIf { allowLongTone }, adaptiveLine)
            }
        }
    }

    private fun words(canvas: Canvas, shaped: ShapedText, words: List<Word>, geometry: List<WordGeometry.Cell>,
                      time: Long, size: Float, color: Int, contrast: Boolean, alpha: Float,
                      longTones: LongToneAnalysis?, adaptive: Boolean) {
        val flags = longTones?.forWords(words)
        for ((wordIndex, word) in words.withIndex()) {
            val cell = geometry[wordIndex]
            val paint = highlight(shaped.layout.isLeftToRight, cell.bounds, word.progress(time), size, color, alpha)
            for ((index, cluster) in cell.clusters.withIndex()) {
                val pose = AmllMotion.word(word, time, index, cell.clusters.size, wordIndex == words.lastIndex, flags?.get(wordIndex) ?: false)
                val path = resources.path(cluster.shape)
                val bounds = cluster.bounds
                canvas.scoped {
                    canvas.translate((pose.xEm * size + bounds.centerX).toFloat(), (pose.yEm * size + bounds.centerY).toFloat())
                    canvas.scale(pose.scale.toFloat(), pose.scale.toFloat())
                    canvas.translate(-bounds.centerX.toFloat(), -bounds.centerY.toFloat())
                    glow(canvas, path, size, color, pose.glow, alpha)
                    outline(canvas, path, size, contrast, alpha, adaptive)
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    private fun timed(canvas: Canvas, shaped: ShapedText, words: List<Word>, time: Long, size: Float,
                      color: Int, contrast: Boolean, alpha: Float, adaptive: Boolean) {
        val path = resources.path(shaped.outline)
        outline(canvas, path, size, contrast, alpha, adaptive)
        var start = 0
        for (word in words) {
            val end = start + word.text.length
            canvas.scoped {
                val region = resources.region(shaped, start, end)
                canvas.clipPath(region, ClipMode.INTERSECT, true)
                val paint = highlight(shaped.layout.isLeftToRight,
                    shaped.layout.getLogicalHighlightShape(start, end).bounds2D, word.progress(time), size, color, alpha)
                canvas.drawPath(path, paint)
            }
            start = end
        }
    }

    private fun outline(canvas: Canvas, path: Path, size: Float,
                        contrast: Boolean, alpha: Float, adaptive: Boolean) {
        if (adaptive && adaptiveContrast.draw(canvas, path,
                resources.stroke(0xc8000000.toInt(), (size * .09f).coerceAtLeast(1.5f), alpha))) return
        if (contrast) outline(canvas, path, size, alpha)
    }

    private fun highlight(ltr: Boolean, bounds: Rectangle2D, progress: Double, size: Float, color: Int, alpha: Float): Paint {
        if (progress <= 0) return resources.fill(DIM, alpha)
        if (progress >= 1) return resources.fill(color, alpha)
        val boundary = (if (ltr) bounds.x + bounds.width * progress else bounds.maxX - bounds.width * progress).toFloat()
        val feather = minOf(size * .45, bounds.width * .6).toFloat().coerceAtLeast(.01f)
        return resources.gradient(boundary - feather / 2, boundary + feather / 2,
            if (ltr) intArrayOf(color, DIM) else intArrayOf(DIM, color), floatArrayOf(0f, 1f), alpha)
    }

    private fun outline(canvas: Canvas, path: Path, size: Float, alpha: Float) =
        canvas.drawPath(path, resources.stroke(0xc8000000.toInt(), (size * .09f).coerceAtLeast(1.5f), alpha))

    private fun glow(canvas: Canvas, path: Path, size: Float, color: Int, emphasis: Double, alpha: Float) {
        val strength = emphasis.coerceIn(0.0, 1.0)
        if (strength <= .001) return
        val hsb = Color.RGBtoHSB(color ushr 16 and 255, color ushr 8 and 255, color and 255, null)
        val tint = Color.HSBtoRGB(hsb[0], minOf(hsb[1], .18f), 1f) and 0xffffff
        val wide = (strength * 58).toInt(); val narrow = (strength * 124).toInt()
        if (wide > 0) canvas.drawPath(path, resources.stroke(tint or (wide shl 24), (size * .16f).coerceAtLeast(1f), alpha))
        if (narrow > 0) canvas.drawPath(path, resources.stroke(tint or (narrow shl 24), (size * .06f).coerceAtLeast(1f), alpha))
    }

    companion object { private const val DIM = 0xff7e818a.toInt() }
}
