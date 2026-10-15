// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import org.jetbrains.skia.*
import java.awt.Shape
import java.awt.geom.PathIterator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Cached segment distances and original Path coverage, separate from the optical programs. */
internal class IslandGlassContour : AutoCloseable {
    private var key: Any? = null
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    var shader: Shader? = null
        private set
    var coverage: Shader? = null
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set
    var generation = 0L
        private set

    fun update(path: Path, shape: Shape, geometry: Any, scaleX: Float, scaleY: Float) {
        val nextKey = listOf(geometry, scaleX, scaleY)
        if (key == nextKey) return
        val bounds = path.bounds
        val margin = IslandGlassShaders.DISTANCE_RANGE + 2f
        val x = floor((bounds.left - margin) * scaleX)
        val y = floor((bounds.top - margin) * scaleY)
        val width = (ceil((bounds.right + margin) * scaleX) - x).coerceAtLeast(1f)
        val height = (ceil((bounds.bottom + margin) * scaleY) - y).coerceAtLeast(1f)
        val program = builder ?: run {
            val compiled = RuntimeEffect.makeForShader(IslandGlassShaders.SEGMENT_DISTANCE)
            effect = compiled
            RuntimeShaderBuilder(compiled).also { builder = it }
        }
        val next = record(width, height, x, y, scaleX, scaleY) { canvas ->
            // Opaque gray data: DARKEN is the minimum of all segment distances.
            // No stacked antialiased strokes or clipping alter those distances.
            Paint().use { paint ->
                paint.color = Color.WHITE
                canvas.drawPaint(paint)
                paint.isAntiAlias = false
                paint.blendMode = BlendMode.DARKEN
                val iterator = shape.getPathIterator(null, .03 / max(scaleX, scaleY))
                val point = DoubleArray(6)
                var startX = 0f; var startY = 0f
                var previousX = 0f; var previousY = 0f
                while (!iterator.isDone) {
                    when (iterator.currentSegment(point)) {
                        PathIterator.SEG_MOVETO -> {
                            startX = point[0].toFloat(); startY = point[1].toFloat()
                            previousX = startX; previousY = startY
                        }
                        PathIterator.SEG_LINETO -> {
                            val endX = point[0].toFloat(); val endY = point[1].toFloat()
                            segment(canvas, paint, program, previousX, previousY, endX, endY)
                            previousX = endX; previousY = endY
                        }
                        PathIterator.SEG_CLOSE -> {
                            segment(canvas, paint, program, previousX, previousY, startX, startY)
                            previousX = startX; previousY = startY
                        }
                    }
                    iterator.next()
                }
            }
        }
        val nextCoverage = try {
            record(width, height, x, y, scaleX, scaleY) { canvas ->
                Paint().use { paint ->
                    paint.color = Color.WHITE
                    // This mask chooses the sign, not the final visible antialiasing.
                    paint.isAntiAlias = true
                    canvas.drawPath(path, paint)
                }
            }
        } catch (error: Throwable) { next.close(); throw error }
        shader?.close(); coverage?.close()
        shader = next; coverage = nextCoverage; key = nextKey; generation++
        originX = x; originY = y; this.scaleX = scaleX; this.scaleY = scaleY
    }

    private fun segment(canvas: Canvas, paint: Paint, program: RuntimeShaderBuilder,
                        x1: Float, y1: Float, x2: Float, y2: Float) {
        if ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) < .000001f) return
        program.uniform("start", x1, y1); program.uniform("end", x2, y2)
        program.makeShader().use { shader ->
            paint.shader = shader
            val range = IslandGlassShaders.DISTANCE_RANGE + 1f
            canvas.drawRect(Rect.makeLTRB(min(x1, x2) - range, min(y1, y2) - range,
                max(x1, x2) + range, max(y1, y2) + range), paint)
            paint.shader = null
        }
    }

    private inline fun record(width: Float, height: Float, x: Float, y: Float,
                              scaleX: Float, scaleY: Float,
                              draw: (Canvas) -> Unit): Shader = PictureRecorder().use { recorder ->
        val canvas = recorder.beginRecording(Rect.makeWH(width, height))
        canvas.translate(-x, -y); canvas.scale(scaleX, scaleY)
        draw(canvas)
        recorder.finishRecordingAsPicture().use { picture ->
            picture.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, FilterMode.LINEAR)
        }
    }

    override fun close() {
        shader?.close(); shader = null
        coverage?.close(); coverage = null
        builder?.close(); builder = null
        effect?.close(); effect = null; key = null
    }
}
