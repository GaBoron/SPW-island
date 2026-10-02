// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Builds rounded-superellipse corners while preserving a circular stadium at full roundness. */
internal object ContinuousCornerPath {
    private const val CORNER_SEGMENTS = 24
    private const val CONTINUOUS_EXPONENT = 4.0
    private const val CIRCULAR_TRANSITION_START = .6
    private const val CIRCLE_BEZIER = .5522847498307936

    fun roundedRectangle(width: Double, height: Double, roundness: Int): Shape =
        roundedRectangle(width, height, roundness.toDouble())

    fun roundedRectangle(width: Double, height: Double, roundness: Double): Shape {
        if (width <= 0.0 || height <= 0.0) return Path2D.Double()
        val radius = radius(width, height, roundness)
        if (radius <= 0.0) return Rectangle2D.Double(0.0, 0.0, width, height)
        if (roundness >= 100) {
            return RoundRectangle2D.Double(0.0, 0.0, width, height, radius * 2.0, radius * 2.0)
        }
        val exponent = exponent(roundness)
        return Path2D.Double().apply {
            moveTo(radius, 0.0)
            lineTo(width - radius, 0.0)
            appendTopRight(this, width, 0.0, radius, exponent)
            lineTo(width, height - radius)
            appendBottomRight(this, width, height, radius, exponent)
            lineTo(radius, height)
            appendBottomLeft(this, 0.0, height, radius, exponent)
            lineTo(0.0, radius)
            appendTopLeft(this, 0.0, 0.0, radius, exponent)
            closePath()
        }
    }

    private fun radius(width: Double, height: Double, roundness: Double): Double =
        minOf(width, height) / 2.0 * roundness.coerceIn(0.0, 100.0) / 100.0

    fun exponent(roundness: Int): Double = exponent(roundness.toDouble())

    private fun exponent(roundness: Double): Double {
        val amount = roundness.coerceIn(0.0, 100.0) / 100.0
        if (amount <= CIRCULAR_TRANSITION_START) return CONTINUOUS_EXPONENT
        val circularBlend = (amount - CIRCULAR_TRANSITION_START) / (1.0 - CIRCULAR_TRANSITION_START)
        return CONTINUOUS_EXPONENT + (2.0 - CONTINUOUS_EXPONENT) * circularBlend
    }

    fun appendBottomRight(path: Path2D.Double, right: Double, bottom: Double,
                          radius: Double, exponent: Double) =
        appendBottomRight(path, right, bottom, radius, radius, exponent)

    fun appendBottomRight(path: Path2D.Double, right: Double, bottom: Double,
                          radiusX: Double, radiusY: Double, exponent: Double) {
        if (exponent <= 2.0) {
            path.curveTo(right, bottom - radiusY + radiusY * CIRCLE_BEZIER,
                right - radiusX + radiusX * CIRCLE_BEZIER, bottom, right - radiusX, bottom)
            return
        }
        append(path, exponent) { angle, power ->
            right - radiusX + radiusX * cos(angle).pow(power) to
                bottom - radiusY + radiusY * sin(angle).pow(power)
        }
    }

    fun appendBottomLeft(path: Path2D.Double, left: Double, bottom: Double,
                         radius: Double, exponent: Double) =
        appendBottomLeft(path, left, bottom, radius, radius, exponent)

    fun appendBottomLeft(path: Path2D.Double, left: Double, bottom: Double,
                         radiusX: Double, radiusY: Double, exponent: Double) {
        if (exponent <= 2.0) {
            path.curveTo(left + radiusX - radiusX * CIRCLE_BEZIER, bottom,
                left, bottom - radiusY + radiusY * CIRCLE_BEZIER, left, bottom - radiusY)
            return
        }
        append(path, exponent) { angle, power ->
            left + radiusX - radiusX * sin(angle).pow(power) to
                bottom - radiusY + radiusY * cos(angle).pow(power)
        }
    }

    private fun appendTopRight(path: Path2D.Double, right: Double, top: Double,
                               radius: Double, exponent: Double) =
        append(path, exponent) { angle, power ->
            right - radius + radius * sin(angle).pow(power) to
                top + radius - radius * cos(angle).pow(power)
        }

    private fun appendTopLeft(path: Path2D.Double, left: Double, top: Double,
                              radius: Double, exponent: Double) =
        append(path, exponent) { angle, power ->
            left + radius - radius * cos(angle).pow(power) to
                top + radius - radius * sin(angle).pow(power)
        }

    private inline fun append(path: Path2D.Double, exponent: Double,
                              point: (Double, Double) -> Pair<Double, Double>) {
        val power = 2.0 / exponent
        val points = (0..CORNER_SEGMENTS).map { point(PI / 2.0 * it / CORNER_SEGMENTS, power) }
        for (index in 1..CORNER_SEGMENTS) {
            val from = points[index - 1]
            val to = points[index]
            val before = points[(index - 2).coerceAtLeast(0)]
            val after = points[(index + 1).coerceAtMost(CORNER_SEGMENTS)]
            val start = if (index == 1) edgeTangent(to.first - from.first, to.second - from.second)
                else (to.first - before.first) / 2.0 to (to.second - before.second) / 2.0
            val end = if (index == CORNER_SEGMENTS) edgeTangent(to.first - from.first, to.second - from.second)
                else (after.first - from.first) / 2.0 to (after.second - from.second) / 2.0
            path.curveTo(from.first + start.first / 3.0, from.second + start.second / 3.0,
                to.first - end.first / 3.0, to.second - end.second / 3.0, to.first, to.second)
        }
    }

    private fun edgeTangent(dx: Double, dy: Double): Pair<Double, Double> =
        if (abs(dx) > abs(dy)) dx to 0.0 else 0.0 to dy
}
