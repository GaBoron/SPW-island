// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D

/** A top attachment and tangent-connected sides shared by resting and retracting notches. */
internal object IslandNotchGeometry {
    fun bodyStretch(width: Double, height: Double): Double = minOf(height * 1.25, width * .16)

    fun attachmentSpread(width: Double, height: Double): Double =
        bodyStretch(width, height) + minOf(height * .85, width * .14)

    fun bodyRadius(width: Double, height: Double, roundness: Int): Double {
        val limit = minOf((width - 20).coerceAtLeast(0.0) / 2, (height - 14).coerceAtLeast(0.0))
        return minOf(height * .45, 32.0, limit) * roundness.coerceIn(0, 100) / 100.0
    }

    fun silhouette(width: Double, height: Double, roundness: Int, reveal: Double = 1.0): Shape {
        if (width <= 0.0 || height <= 0.0 || reveal <= 0.0) return Path2D.Double()
        val collapse = (1.0 - reveal).coerceIn(0.0, 1.0)
        val blend = collapse * collapse * (3.0 - 2.0 * collapse)
        val stretch = collapse * (2.0 - collapse)
        val rebound = (reveal - 1.0).coerceAtLeast(0.0)
        val center = width / 2.0
        val halfBody = (center - 10.0).coerceAtLeast(0.0) * (1.0 + .65 * rebound) +
            bodyStretch(width, height) * stretch
        val halfAttachment = center * (1.0 + .65 * rebound) +
            attachmentSpread(width, height) * stretch
        val right = center + halfBody
        val left = center - halfBody
        val rootDrop = minOf(14.0, height) * (1.0 - .18 * blend)
        val radius = bodyRadius(width, height, roundness)
        val radiusX = (radius + (halfBody * .85 - radius) * blend).coerceIn(0.0, halfBody)
        val radiusY = (radius + (height - rootDrop - radius) * blend).coerceIn(0.0, height - rootDrop)
        val exponent = ContinuousCornerPath.exponent(roundness).let { it + (2.0 - it) * blend }
        val path = Path2D.Double().apply {
            moveTo(center - halfAttachment, 0.0)
            lineTo(center + halfAttachment, 0.0)
            curveTo(right, 0.0, right, rootDrop * (8.0 / 14.0), right, rootDrop)
            lineTo(right, height - radiusY)
            ContinuousCornerPath.appendBottomRight(this, right, height, radiusX, radiusY, exponent)
            lineTo(left + radiusX, height)
            ContinuousCornerPath.appendBottomLeft(this, left, height, radiusX, radiusY, exponent)
            lineTo(left, rootDrop)
            curveTo(left, rootDrop * (8.0 / 14.0), left, 0.0, center - halfAttachment, 0.0)
            closePath()
        }
        return AffineTransform(1.0, 0.0, 0.0, reveal, .5, 0.0).createTransformedShape(path)
    }
}
