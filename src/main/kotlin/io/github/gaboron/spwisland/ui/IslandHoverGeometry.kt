// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Shape
import java.awt.Insets
import java.awt.geom.AffineTransform
import kotlin.math.ceil

/** Maps visibility motion to a sticky notch or a centred capsule/circle, without scaling content. */
internal object IslandHoverGeometry {
    data class Frame(val shape: Shape, val maskKey: Any, val opacity: Double,
                     val contentOpacity: Double, val contentOffsetY: Double)

    fun frame(width: Int, height: Int, notch: Boolean, roundness: Int,
              pose: IslandHoverMotion.Pose): Frame {
        val reveal = pose.reveal
        val w = (width - 1.0).coerceAtLeast(0.0)
        val h = (height - 1.0).coerceAtLeast(0.0)
        val shape = if (reveal == 1.0) IslandGeometry.silhouette(width, height, notch, roundness)
            else if (notch) IslandNotchGeometry.silhouette(w, h, roundness, reveal)
            else pill(w, h, roundness, reveal)
        return Frame(shape, listOf(width, height, notch, roundness, reveal),
            smooth(reveal / .08), smooth((reveal - .62) / .38),
            if (notch) (reveal - 1.0) * h * .35 else 0.0)
    }

    /** Reserve both the spreading attachment and the reveal crest before the animation starts. */
    fun padding(width: Int, height: Int, notch: Boolean): Insets {
        val spread = if (notch) IslandNotchGeometry.attachmentSpread(width.toDouble(), height.toDouble()) else 0.0
        val horizontal = ceil(maxOf(spread, width * .04) + 2.0).toInt()
        val bottom = if (notch) ceil(height * .06 + 2.0).toInt() else 2
        return Insets(if (notch) 0 else 2, horizontal, bottom, horizontal)
    }

    private fun pill(width: Double, height: Double, roundness: Int, reveal: Double): Shape {
        val diameter = minOf(width, height)
        val circle = smooth(reveal / CIRCLE_PHASE)
        val elongation = ((reveal - CIRCLE_PHASE) / (1.0 - CIRCLE_PHASE)).coerceAtLeast(0.0)
        // Zero slope at the circle joins the two phases; the unit slope at full width carries the crest.
        val stretch = if (elongation <= 1.0) elongation * elongation * (2.0 - elongation) else elongation
        val actualHeight = diameter * circle + (height - diameter) * stretch.coerceAtMost(1.0)
        val actualWidth = diameter * circle + (width - diameter) * stretch
        val corner = 100.0 + (roundness - 100.0) * smooth(elongation)
        val shape = ContinuousCornerPath.roundedRectangle(actualWidth, actualHeight, corner)
        return AffineTransform.getTranslateInstance(
            .5 + (width - actualWidth) / 2.0, .5 + (height - actualHeight) / 2.0
        ).createTransformedShape(shape)
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private const val CIRCLE_PHASE = .38
}
