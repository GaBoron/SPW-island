// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.geom.AffineTransform
import java.awt.Shape

object IslandGeometry {
    private data class SilhouetteKey(val width: Int, val height: Int, val notch: Boolean,
                                     val roundness: Int)
    private var cachedKey: SilhouetteKey? = null
    private var cachedShape: Shape? = null

    fun contentInset(width: Int, height: Int, notch: Boolean, top: Int, bottom: Int,
                     cornerRoundness: Int = 95): Float {
        val shape = silhouette(width, height, notch, cornerRoundness)
        var inset = 0
        for (y in top.coerceAtLeast(1)..bottom.coerceAtMost(height - 2)) {
            var left = 0; var right = width / 2
            while (left < right) {
                val middle = (left + right) / 2
                if (shape.contains(middle.toDouble(), y.toDouble())) right = middle else left = middle + 1
            }
            inset = maxOf(inset, left)
        }
        return inset + 4f
    }

    /** Width reserved outside the element-safe rectangle for each curved side. */
    fun frameInset(height: Double, notch: Boolean, cornerRoundness: Int): Double {
        val h = (height - 1.0).coerceAtLeast(0.0)
        val scale = cornerRoundness.coerceIn(0, 100) / 100.0
        val radius = if (notch) {
            minOf(h * .45, 32.0) * scale + 10.0
        } else h / 2.0 * scale
        return .5 + radius
    }

    @Synchronized
    fun silhouette(width: Int, height: Int, notch: Boolean, cornerRoundness: Int = 95): Shape {
        val key = SilhouetteKey(width, height, notch, cornerRoundness.coerceIn(0, 100))
        if (key == cachedKey) return cachedShape!!
        val w = (width - 1).coerceAtLeast(0).toDouble()
        val h = (height - 1).coerceAtLeast(0).toDouble()
        if (!notch) {
            val pill = ContinuousCornerPath.roundedRectangle(w, h, cornerRoundness)
            val centered = AffineTransform.getTranslateInstance(.5, .5).createTransformedShape(pill)
            return cache(key, centered)
        }
        return cache(key, IslandNotchGeometry.silhouette(w, h, cornerRoundness))
    }

    fun progressEdgeInset(width: Int, height: Int, notch: Boolean, cornerRoundness: Int): Double =
        if (notch) 10.5 + IslandNotchGeometry.bodyRadius(width - 1.0, height - 1.0, cornerRoundness)
        else minOf(frameInset(height.toDouble(), false, cornerRoundness), width / 2.0)

    private fun cache(key: SilhouetteKey, shape: Shape): Shape {
        cachedKey = key
        cachedShape = shape
        return shape
    }
}
