// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.*
import com.sun.jna.platform.unix.X11
import java.awt.Shape
import java.awt.Window

/** Shapes only input. Changing the bounding shape during animation causes compositor flashes. */
internal class X11InputRegion : AutoCloseable {
    private interface ShapeApi : Library {
        fun XShapeCombineRectangles(display: X11.Display, window: X11.Window, kind: Int,
            x: Int, y: Int, rectangles: Pointer?, count: Int, operation: Int, ordering: Int)
    }
    private val x = X11.INSTANCE
    private val display = checkNotNull(x.XOpenDisplay(null))
    private val shape = Native.load("Xext", ShapeApi::class.java)
    private var last: List<Any>? = null
    fun update(window: Window, region: Shape, key: List<Any>) {
        if (last == key) return
        val bounds = region.bounds
        val rows = ArrayList<IntArray>()
        // The island silhouette is convex: each scanline has one contiguous input span.
        for (y in bounds.y until bounds.y + bounds.height) {
            var left = bounds.x
            var right = bounds.x + bounds.width
            while (left < right && !region.contains(left + .5, y + .5)) left++
            while (right > left && !region.contains(right - .5, y + .5)) right--
            if (right > left) rows += intArrayOf(left, y, right - left)
        }
        if (rows.isEmpty()) {
            shape.XShapeCombineRectangles(display, X11.Window(Native.getWindowID(window)),
                2, 0, 0, null, 0, 0, 3)
            x.XFlush(display)
            last = key
            return
        }
        Memory(rows.size * 8L).use { data ->
            rows.forEachIndexed { index, row ->
                val offset = index * 8L // XRectangle: signed x/y, unsigned width/height (16 bits each)
                data.setShort(offset, row[0].toShort()); data.setShort(offset + 2, row[1].toShort())
                data.setShort(offset + 4, row[2].toShort()); data.setShort(offset + 6, 1)
            }
            shape.XShapeCombineRectangles(display, X11.Window(Native.getWindowID(window)),
                2, 0, 0, data, rows.size, 0, 3) // ShapeInput, ShapeSet, YXBanded
            x.XFlush(display)
        }
        last = key
    }
    override fun close() { x.XCloseDisplay(display) }
}
