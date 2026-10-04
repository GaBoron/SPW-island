// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JToolTip
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicButtonUI

internal data class DialogButtonColors(val fill: Color, val hover: Color, val pressed: Color,
                                      val text: Color, val border: Color, val focus: Color)

/** One shape for painting and hit testing; no second background painted by the system LAF. */
internal class DialogButton(text: String, private val colors: DialogButtonColors,
                            size: Dimension, private val closeIcon: Boolean = false) : JButton(text) {
    // GTK/Synth default-button styling can replace fonts, borders and animate the background.
    override fun updateUI() {
        setUI(BasicButtonUI())
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        border = EmptyBorder(0, 0, 0, 0)
        font = SystemUiFont.derive(Font.PLAIN, 12f)
    }

    override fun createToolTip(): JToolTip = super.createToolTip().apply {
        font = SystemUiFont.derive(Font.PLAIN, 12f)
    }
    init {
        preferredSize = size
        minimumSize = size
        maximumSize = size
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        border = EmptyBorder(0, 0, 0, 0)
        font = SystemUiFont.derive(Font.PLAIN, 12f)
        foreground = colors.text
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        if (closeIcon) toolTipText = text
        accessibleContext?.accessibleName = text
    }

    private fun outline(inset: Double = 1.0) = RoundRectangle2D.Double(inset, inset,
        (width - inset * 2).coerceAtLeast(0.0), (height - inset * 2).coerceAtLeast(0.0), 10.0, 10.0)

    override fun contains(x: Int, y: Int) = outline().contains(x.toDouble(), y.toDouble())

    override fun paint(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.clip(outline())
            super.paint(g)
        } finally { g.dispose() }
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = when {
                !isEnabled -> colors.fill
                model.isPressed && model.isArmed -> colors.pressed
                model.isRollover -> colors.hover
                else -> colors.fill
            }
            g.fill(outline())
            g.color = colors.border
            g.draw(outline())
            if (isFocusOwner) {
                g.color = colors.focus
                g.stroke = BasicStroke(1.5f)
                g.draw(outline(3.5))
            }
            g.color = if (isEnabled) colors.text else Color(colors.text.red, colors.text.green, colors.text.blue, 100)
            if (closeIcon) {
                val x = width / 2.0
                val y = height / 2.0
                g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(x - 4.5, y - 4.5, x + 4.5, y + 4.5))
                g.draw(Line2D.Double(x + 4.5, y - 4.5, x - 4.5, y + 4.5))
            } else {
                g.font = font
                val metrics = g.fontMetrics
                g.drawString(text, (width - metrics.stringWidth(text)) / 2,
                    (height - metrics.height) / 2 + metrics.ascent)
            }
        } finally { g.dispose() }
    }
}
