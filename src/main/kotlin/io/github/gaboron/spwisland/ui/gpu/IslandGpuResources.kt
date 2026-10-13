// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.CoverArtwork
import io.github.gaboron.spwisland.ui.ShapedText
import org.jetbrains.skia.*
import java.awt.Shape
import java.awt.geom.PathIterator
import java.util.LinkedHashMap
import org.jetbrains.skia.Paint as NativePaint

/** Native paths are prepared once per immutable geometry; no Graphics2D state enters this renderer. */
internal class IslandGpuResources : AutoCloseable {
    private val paths = LinkedHashMap<Shape, Path>(128, .75f, true)
    private val covers = LinkedHashMap<CoverArtwork, Image>(4, .75f, true)
    private val regions = LinkedHashMap<Triple<ShapedText, Int, Int>, Shape>(32, .75f, true)
    private val fill = NativePaint().apply { isAntiAlias = true }
    private val stroke = NativePaint().apply {
        isAntiAlias = true; mode = PaintMode.STROKE
        strokeCap = PaintStrokeCap.ROUND; strokeJoin = PaintStrokeJoin.ROUND
    }
    private val opacity = NativePaint()
    private var shader: Shader? = null
    private var fillColor: Int? = null
    private var fillAlpha = Float.NaN
    private var fillBlend: BlendMode? = null
    private var strokeColor: Int? = null
    private var strokeAlpha = Float.NaN
    private var strokeSize = Float.NaN

    fun fill(color: Int, alpha: Float = 1f, blend: BlendMode = BlendMode.SRC_OVER): NativePaint {
        if (shader != null) { fill.shader = null; shader?.close(); shader = null; fillColor = null }
        if (fillColor != color) { fill.color = color; fillColor = color; fillAlpha = Float.NaN }
        val combined = (color ushr 24) / 255f * alpha
        if (fillAlpha != combined) { fill.setAlphaf(combined); fillAlpha = combined }
        if (fillBlend != blend) { fill.blendMode = blend; fillBlend = blend }
        return fill
    }

    fun stroke(color: Int, width: Float, alpha: Float = 1f): NativePaint {
        if (strokeColor != color) { stroke.color = color; strokeColor = color; strokeAlpha = Float.NaN }
        val combined = (color ushr 24) / 255f * alpha
        if (strokeAlpha != combined) { stroke.setAlphaf(combined); strokeAlpha = combined }
        if (strokeSize != width) { stroke.strokeWidth = width; strokeSize = width }
        return stroke
    }

    fun gradient(start: Float, end: Float, colors: IntArray, stops: FloatArray, alpha: Float = 1f): NativePaint {
        val next = Shader.makeLinearGradient(start, 0f, end, 0f, Gradient(Gradient.Colors(
            colors.map { Color4f((it ushr 16 and 255) / 255f, (it ushr 8 and 255) / 255f,
                (it and 255) / 255f, (it ushr 24) / 255f) }.toTypedArray(), stops, FilterTileMode.CLAMP)))
        fill.shader = next; shader?.close(); shader = next
        fill.setAlphaf(alpha); fill.blendMode = BlendMode.SRC_OVER
        fillColor = null; fillAlpha = alpha; fillBlend = BlendMode.SRC_OVER
        return fill
    }

    fun path(shape: Shape): Path = paths[shape] ?: PathBuilder().use { builder ->
        val iterator = shape.getPathIterator(null)
        builder.setFillType(if (iterator.windingRule == PathIterator.WIND_EVEN_ODD) PathFillMode.EVEN_ODD else PathFillMode.WINDING)
        val p = FloatArray(6)
        while (!iterator.isDone) {
            when (iterator.currentSegment(p)) {
                PathIterator.SEG_MOVETO -> builder.moveTo(p[0], p[1])
                PathIterator.SEG_LINETO -> builder.lineTo(p[0], p[1])
                PathIterator.SEG_QUADTO -> builder.quadTo(p[0], p[1], p[2], p[3])
                PathIterator.SEG_CUBICTO -> builder.cubicTo(p[0], p[1], p[2], p[3], p[4], p[5])
                PathIterator.SEG_CLOSE -> builder.closePath()
            }
            iterator.next()
        }
        builder.detach()
    }.also {
        paths[shape] = it
        if (paths.size > 512) paths.entries.iterator().run { next().value.close(); remove() }
    }

    fun region(shaped: ShapedText, start: Int, end: Int): Path {
        val key = Triple(shaped, start, end)
        val shape = regions[key] ?: shaped.layout.getLogicalHighlightShape(start, end).also {
            regions[key] = it
            if (regions.size > 128) regions.entries.iterator().run { next(); remove() }
        }
        return path(shape)
    }

    fun cover(source: CoverArtwork): Image = covers[source] ?: run {
        val bytes = ByteArray(source.argb.size * 4)
        for (i in source.argb.indices) {
            val value = source.argb[i]; val alpha = value ushr 24
            bytes[i * 4] = ((value and 255) * alpha / 255).toByte()
            bytes[i * 4 + 1] = ((value ushr 8 and 255) * alpha / 255).toByte()
            bytes[i * 4 + 2] = ((value ushr 16 and 255) * alpha / 255).toByte()
            bytes[i * 4 + 3] = alpha.toByte()
        }
        Image.makeRaster(ImageInfo(source.width, source.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, source.width * 4)
    }.also {
        covers[source] = it
        if (covers.size > 4) covers.entries.iterator().run { next().value.close(); remove() }
    }

    inline fun layer(canvas: Canvas, bounds: Rect, alpha: Float, draw: () -> Unit) {
        val save = if (alpha == 1f) canvas.save() else {
            opacity.setAlphaf(alpha.coerceIn(0f, 1f)); canvas.saveLayer(bounds, opacity)
        }
        try { draw() } finally { canvas.restoreToCount(save) }
    }

    override fun close() {
        fill.close(); stroke.close(); opacity.close(); shader?.close(); shader = null
        paths.values.forEach(Path::close); paths.clear(); regions.clear()
        covers.values.forEach(Image::close); covers.clear()
    }
}

internal inline fun Canvas.scoped(draw: () -> Unit) {
    val count = save()
    try { draw() } finally { restoreToCount(count) }
}
