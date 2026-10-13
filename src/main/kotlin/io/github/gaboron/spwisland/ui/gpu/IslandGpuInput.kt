// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.ui.IslandSurface
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.AbstractButton
import javax.swing.SwingUtilities
import io.github.gaboron.spwisland.ui.PlaybackProgress

/** Routes the native canvas's events to the existing Swing controls, including captured drags. */
internal class IslandGpuInput(private val source: Component, private val surface: IslandSurface,
                              private val redraw: () -> Unit) : AutoCloseable {
    private var hover: Component? = null
    private var pressed: Component? = null
    private val listener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) = move(e)
        override fun mouseMoved(e: MouseEvent) = move(e)
        override fun mouseExited(e: MouseEvent) { hover?.let { send(it, e, MouseEvent.MOUSE_EXITED) }; hover = null }
        override fun mousePressed(e: MouseEvent) {
            move(e); pressed = target(e); pressed?.let { send(it, e) }; redraw()
        }
        override fun mouseReleased(e: MouseEvent) {
            val captured = pressed
            captured?.let { send(it, e) }; pressed = null; move(e); redraw()
        }
        override fun mouseClicked(e: MouseEvent) { target(e)?.let { send(it, e) }; redraw() }
        override fun mouseDragged(e: MouseEvent) { pressed?.let { send(it, e) }; redraw() }
    }

    init { source.addMouseListener(listener); source.addMouseMotionListener(listener) }

    private fun target(event: MouseEvent): Component? {
        val panel = surface.island
        val point = SwingUtilities.convertPoint(source, event.point, panel)
        if (!panel.contains(point)) return null
        return SwingUtilities.getDeepestComponentAt(panel, point.x, point.y)
    }

    private fun move(event: MouseEvent) {
        val next = target(event)
        if (next !== hover) {
            hover?.let { send(it, event, MouseEvent.MOUSE_EXITED) }
            next?.let { send(it, event, MouseEvent.MOUSE_ENTERED) }
            hover = next
        }
        next?.let { send(it, event, MouseEvent.MOUSE_MOVED) }
        source.cursor = next?.cursor ?: Cursor.getDefaultCursor()
        (source as? JComponent ?: source.parent as? JComponent)?.toolTipText = (next as? JComponent)?.toolTipText
    }

    private fun send(target: Component, event: MouseEvent, id: Int = event.id) {
        val point = SwingUtilities.convertPoint(source, event.point, target)
        target.dispatchEvent(MouseEvent(target, id, event.`when`, event.modifiersEx, point.x, point.y,
            event.xOnScreen, event.yOnScreen, event.clickCount, event.isPopupTrigger, event.button))
    }

    override fun close() {
        source.removeMouseListener(listener); source.removeMouseMotionListener(listener)
        hover?.let { it.dispatchEvent(MouseEvent(it, MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(), 0, -1, -1, 0, false)) }
        (pressed as? AbstractButton)?.model?.apply { isArmed = false; isPressed = false }
        (pressed as? PlaybackProgress)?.cancelGesture()
        hover = null; pressed = null
    }
}
