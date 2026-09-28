// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Shape

/** Gives bright cover-colored glyphs a dark edge over a translucent island. */
internal object LyricContrastOutline {
    fun draw(g: Graphics2D, shape: Shape, fontSize: Float, x: Double = 0.0, y: Double = 0.0) {
        val copy = g.create() as Graphics2D
        try {
            copy.translate(x, y)
            copy.color = Color(0, 0, 0, 200)
            copy.stroke = BasicStroke((fontSize * .09f).coerceAtLeast(1.5f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            copy.draw(shape)
        } finally { copy.dispose() }
    }
}
