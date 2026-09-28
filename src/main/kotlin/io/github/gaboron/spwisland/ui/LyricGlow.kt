// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Shape
import kotlin.math.min

/** Paints a restrained glow only while a long tone is emphasized. */
object LyricGlow {
    fun draw(g: Graphics2D, shape: Shape, fontSize: Float, baseColor: Color, emphasis: Double) {
        val strength = emphasis.coerceIn(0.0, 1.0)
        if (strength <= .001) return
        val tint = cleanTint(baseColor)
        drawLayer(g, shape, tint, fontSize * .16f, (strength * 58).toInt())
        drawLayer(g, shape, tint, fontSize * .06f, (strength * 124).toInt())
    }

    private fun cleanTint(color: Color): Color {
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        return Color.getHSBColor(hsb[0], min(hsb[1], .18f), 1f)
    }

    private fun drawLayer(g: Graphics2D, shape: Shape, color: Color, width: Float, alpha: Int) {
        if (alpha <= 0) return
        g.color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
        g.stroke = BasicStroke(width.coerceAtLeast(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
    }
}
