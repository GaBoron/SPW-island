// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import java.awt.Color
import java.awt.Shape
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.JWindow

/** DirectComposition bypasses AWT's alpha hit map. A shaped, nonfocusable input peer owns mouse capture. */
internal class WindowsGpuInput(private val owner: Window) : AutoCloseable {
    val component = JPanel(null).apply {
        background = Color.BLACK
        isDoubleBuffered = false
    }
    private val window = JWindow(owner).apply {
        type = Window.Type.UTILITY
        focusableWindowState = false
        isAutoRequestFocus = false
        isAlwaysOnTop = true
        background = Color.BLACK
        contentPane = component
        // Zero alpha is excluded from Windows hit testing. The minimum nonzero uniform
        // alpha keeps input active without an AWT per-pixel bitmap or repainting lyrics.
        opacity = 1f / 255f
    }
    private var regionKey: Any? = null
    private val visibility = object : ComponentAdapter() {
        override fun componentHidden(event: ComponentEvent) {
            // A queued HIDDEN notification can arrive after the owner has already reopened.
            if (!owner.isVisible) window.isVisible = false
        }
    }

    init { owner.addComponentListener(visibility) }

    fun update(key: Any, clickThrough: Boolean, shape: () -> Shape) {
        if (clickThrough || !owner.isVisible) {
            window.isVisible = false
            return
        }
        if (window.bounds != owner.bounds) window.bounds = owner.bounds
        if (regionKey != key) { window.shape = shape(); regionKey = key }
        if (!window.isVisible) window.isVisible = true
    }

    override fun close() { owner.removeComponentListener(visibility); window.dispose() }
}
