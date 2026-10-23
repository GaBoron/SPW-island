// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D

/** Intersect only nearby contours; parsing the whole line for every grapheme exhausts the host heap. */
internal class LyricGlyphOutline private constructor(private val contours: List<Contour>, private val winding: Int) {
    private data class Contour(val path: Path2D.Double, val bounds: Rectangle2D, val segments: Int)
    private var work = 0

    fun clipped(bounds: Rectangle2D): Shape? {
        val selected = Path2D.Double(winding)
        var segments = 0
        for (contour in contours) {
            if (!contour.bounds.intersects(bounds)) continue
            segments += contour.segments
            if (segments > 2_048 || work + segments > 131_072) return null
            selected.append(contour.path, false)
        }
        work += segments
        return Area(selected).apply { intersect(Area(bounds)) }
    }

    companion object {
        fun from(shape: Shape): LyricGlyphOutline? {
            val iterator = shape.getPathIterator(null)
            val winding = iterator.windingRule
            val contours = mutableListOf<Contour>()
            val point = DoubleArray(6)
            var path = Path2D.Double(winding)
            var count = 0
            var total = 0
            fun finish() {
                if (count > 0) contours += Contour(path, path.bounds2D, count)
                path = Path2D.Double(winding); count = 0
            }
            while (!iterator.isDone) {
                if (++total > 32_768) return null
                when (iterator.currentSegment(point)) {
                    PathIterator.SEG_MOVETO -> { finish(); path.moveTo(point[0], point[1]) }
                    PathIterator.SEG_LINETO -> path.lineTo(point[0], point[1])
                    PathIterator.SEG_QUADTO -> path.quadTo(point[0], point[1], point[2], point[3])
                    PathIterator.SEG_CUBICTO -> path.curveTo(point[0], point[1], point[2], point[3], point[4], point[5])
                    PathIterator.SEG_CLOSE -> path.closePath()
                }
                count++
                iterator.next()
            }
            finish()
            return LyricGlyphOutline(contours, winding)
        }
    }
}
