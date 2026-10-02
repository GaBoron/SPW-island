// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.HorizontalAnchor
import io.github.gaboron.spwisland.core.IslandAnchor
import io.github.gaboron.spwisland.core.VerticalAnchor
import java.awt.GraphicsConfiguration
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.abs

/** Pure nine-grid anchor policy for island windows on a desktop work area. */
object IslandPlacement {
    data class DragPlacement(val topLeft: Point, val anchor: IslandAnchor)

    fun workArea(configuration: GraphicsConfiguration): Rectangle {
        val screen = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        return Rectangle(screen.x + insets.left, screen.y + insets.top,
            (screen.width - insets.left - insets.right).coerceAtLeast(1),
            (screen.height - insets.top - insets.bottom).coerceAtLeast(1))
    }

    /** Selects one of nine anchors from the island centre's screen third. */
    fun automaticAnchor(screen: Rectangle, bounds: Rectangle, notch: Boolean = false): IslandAnchor {
        val centerX = bounds.x.toLong() + bounds.width / 2
        val centerY = bounds.y.toLong() + bounds.height / 2
        val horizontal = when (third(centerX - screen.x, screen.width)) {
            0 -> HorizontalAnchor.LEFT
            2 -> HorizontalAnchor.RIGHT
            else -> HorizontalAnchor.CENTER
        }
        val vertical = if (notch) VerticalAnchor.TOP else when (third(centerY - screen.y, screen.height)) {
            0 -> VerticalAnchor.TOP
            2 -> VerticalAnchor.BOTTOM
            else -> VerticalAnchor.CENTER
        }
        return IslandAnchor(horizontal, vertical)
    }

    /** Magnetizes a dragged island to the work-area edges and centre lines. */
    fun snapDrag(screen: Rectangle, bounds: Rectangle, threshold: Int = SNAP_DISTANCE,
                 notch: Boolean = false): DragPlacement {
        val horizontalTargets = listOf(
            screen.x to HorizontalAnchor.LEFT,
            screen.x + (screen.width - bounds.width) / 2 to HorizontalAnchor.CENTER,
            screen.x + screen.width - bounds.width to HorizontalAnchor.RIGHT
        )
        val verticalTargets = listOf(
            screen.y to VerticalAnchor.TOP,
            screen.y + (screen.height - bounds.height) / 2 to VerticalAnchor.CENTER,
            screen.y + screen.height - bounds.height to VerticalAnchor.BOTTOM
        )
        val horizontal = nearest(bounds.x, horizontalTargets, threshold)
        val vertical = if (notch) screen.y to VerticalAnchor.TOP else nearest(bounds.y, verticalTargets, threshold)
        val snappedBounds = Rectangle(horizontal?.first ?: bounds.x, vertical?.first ?: bounds.y,
            bounds.width, bounds.height)
        val automatic = automaticAnchor(screen, snappedBounds, notch)
        return DragPlacement(snappedBounds.location, IslandAnchor(
            horizontal?.second ?: automatic.horizontal,
            vertical?.second ?: automatic.vertical
        ))
    }

    /** Notches retain their horizontal anchor, but always attach to the active work area's top. */
    fun attachToTop(screen: Rectangle, point: Point, anchor: IslandAnchor): Pair<Point, IslandAnchor> =
        Point(point.x, screen.y) to IslandAnchor(anchor.horizontal, VerticalAnchor.TOP)

    /** Converts current bounds to the fixed point used by its selected anchor. */
    fun anchorPoint(bounds: Rectangle, anchor: IslandAnchor): Point {
        val x = bounds.x + when (anchor.horizontal) {
            HorizontalAnchor.LEFT -> 0
            HorizontalAnchor.CENTER -> bounds.width / 2
            HorizontalAnchor.RIGHT -> bounds.width
        }
        val y = bounds.y + when (anchor.vertical) {
            VerticalAnchor.TOP -> 0
            VerticalAnchor.CENTER -> bounds.height / 2
            VerticalAnchor.BOTTOM -> bounds.height
        }
        return Point(x, y)
    }

    /** Places a resized island around a stable nine-grid anchor point. */
    fun bounds(screen: Rectangle, point: Point, width: Int, height: Int, anchor: IslandAnchor): Rectangle {
        val w = width.coerceIn(1, screen.width.coerceAtLeast(1))
        val h = height.coerceIn(1, screen.height.coerceAtLeast(1))
        val proposedX = point.x.toLong() - when (anchor.horizontal) {
            HorizontalAnchor.LEFT -> 0
            HorizontalAnchor.CENTER -> w / 2
            HorizontalAnchor.RIGHT -> w
        }
        val proposedY = point.y.toLong() - when (anchor.vertical) {
            VerticalAnchor.TOP -> 0
            VerticalAnchor.CENTER -> h / 2
            VerticalAnchor.BOTTOM -> h
        }
        val x = proposedX.coerceIn(screen.x.toLong(), screen.maxX.toLong() - w)
        val y = proposedY.coerceIn(screen.y.toLong(), screen.maxY.toLong() - h)
        return Rectangle(x.toInt(), y.toInt(), w, h)
    }

    /** Keeps the translucent backing window still while it already contains the island. */
    fun stableCanvasBounds(screen: Rectangle, island: Rectangle, preferred: Rectangle,
                           current: Rectangle): Rectangle {
        if (current.width != preferred.width || current.height != preferred.height) return preferred
        val minX = maxOf(screen.x, island.x + island.width - current.width)
        val maxX = minOf(screen.x + screen.width - current.width, island.x)
        val minY = maxOf(screen.y, island.y + island.height - current.height)
        val maxY = minOf(screen.y + screen.height - current.height, island.y)
        if (minX > maxX || minY > maxY) return preferred
        return Rectangle(current.x.coerceIn(minX, maxX), current.y.coerceIn(minY, maxY),
            current.width, current.height)
    }

    private fun third(offset: Long, length: Int): Int =
        ((offset.coerceIn(0, length.toLong()) * 3) / length.coerceAtLeast(1)).toInt().coerceAtMost(2)

    private fun <T> nearest(value: Int, targets: List<Pair<Int, T>>, threshold: Int): Pair<Int, T>? =
        targets.minByOrNull { abs(it.first - value) }?.takeIf { abs(it.first - value) <= threshold }

    private const val SNAP_DISTANCE = 24
}
