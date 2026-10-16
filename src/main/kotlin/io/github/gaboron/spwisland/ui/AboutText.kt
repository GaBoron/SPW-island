// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import javax.swing.JTextArea
import javax.swing.plaf.basic.BasicTextAreaUI
import javax.swing.text.DefaultCaret
import javax.swing.text.Position
import javax.swing.text.View
import kotlin.math.ceil

internal interface WidthAware { fun heightFor(width: Int): Int }

/** Passive, width-aware text shared by the About body and grouped runtime status. */
internal class WrappingText(text: String, color: Color, private val naturalWidth: Int,
                            size: Float = 12f, bold: Boolean = false) : JTextArea(text), WidthAware {
    init {
        setUI(BasicTextAreaUI())
        isOpaque = false; isEditable = false; isFocusable = false
        cursor = Cursor.getDefaultCursor()
        caret = object : DefaultCaret() {
            override fun setDot(dot: Int, bias: Position.Bias) = super.setDot(0, bias)
            override fun moveDot(dot: Int, bias: Position.Bias) = super.setDot(0, bias)
        }
        highlighter = null
        lineWrap = true; wrapStyleWord = true
        foreground = color
        font = SystemUiFont.derive(if (bold) Font.BOLD else Font.PLAIN, size)
        border = null; margin = Insets(0, 0, 0, 0)
    }
    override fun heightFor(width: Int): Int {
        if (text.isEmpty()) return 0
        val view = (ui as javax.swing.plaf.TextUI).getRootView(this)
        view.setSize(width.coerceAtLeast(1).toFloat(), Float.MAX_VALUE)
        return ceil(view.getPreferredSpan(View.Y_AXIS).toDouble()).toInt()
    }
    override fun getPreferredSize() = Dimension(naturalWidth, heightFor(width.takeIf { it > 0 } ?: naturalWidth))
}
