// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import java.awt.Component
import java.awt.Cursor
import java.awt.Window
import java.awt.KeyEventPostProcessor
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Route the native input peer to the original Swing buttons, header drags and scroll panes. */
internal class SwingGlassInput(private val source: Component, private val content: JComponent,
                                private val owner: Window, private val changed: () -> Unit) : AutoCloseable {
    private var hovered: Component? = null
    private var pressed: Component? = null
    private var closed = false
    private val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val keyboard = KeyEventPostProcessor { e ->
        if (!closed && SwingUtilities.getWindowAncestor(e.component) === owner)
            SwingUtilities.invokeLater { if (!closed) changed() }
        false
    }
    private val focusChanged = PropertyChangeListener { e ->
        if (listOf(e.oldValue, e.newValue).any { it is Component && SwingUtilities.getWindowAncestor(it) === owner })
            SwingUtilities.invokeLater { if (!closed) changed() }
    }
    private val listener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) = move(e)
        override fun mouseMoved(e: MouseEvent) = move(e)
        override fun mouseExited(e: MouseEvent) {
            hovered?.let { send(it, e, MouseEvent.MOUSE_EXITED) }; hovered = null; changed()
        }
        override fun mousePressed(e: MouseEvent) {
            owner.requestFocus()
            move(e); pressed = target(e)
            pressed?.let { it.requestFocusInWindow(); send(it, e) }
            changed()
        }
        override fun mouseReleased(e: MouseEvent) {
            pressed?.let { send(it, e) }
            if (closed) return
            pressed = null; move(e); changed()
        }
        override fun mouseClicked(e: MouseEvent) { target(e)?.let { send(it, e) }; changed() }
        override fun mouseDragged(e: MouseEvent) { pressed?.let { send(it, e) }; changed() }
        override fun mouseWheelMoved(e: MouseWheelEvent) {
            var target = deepest(e)
            while (target != null && target.mouseWheelListeners.isEmpty()) target = target.parent
            target?.let {
                val p = SwingUtilities.convertPoint(source, e.point, it)
                it.dispatchEvent(MouseWheelEvent(it, e.id, e.`when`, e.modifiersEx, p.x, p.y,
                    e.xOnScreen, e.yOnScreen, e.clickCount, e.isPopupTrigger, e.scrollType,
                    e.scrollAmount, e.wheelRotation, e.preciseWheelRotation))
            }
            changed()
        }
    }

    init {
        source.addMouseListener(listener); source.addMouseMotionListener(listener)
        source.addMouseWheelListener(listener)
        focus.addKeyEventPostProcessor(keyboard)
        focus.addPropertyChangeListener("focusOwner", focusChanged)
    }

    private fun deepest(e: MouseEvent): Component? {
        val p = SwingUtilities.convertPoint(source, e.point, content)
        return SwingUtilities.getDeepestComponentAt(content, p.x, p.y)
    }

    private fun target(e: MouseEvent): Component? {
        var target = deepest(e)
        while (target != null && target !== content && target !is AbstractButton &&
            target.mouseListeners.isEmpty() && target.mouseMotionListeners.isEmpty()) target = target.parent
        return target
    }

    private fun move(e: MouseEvent) {
        if (closed) return
        val next = target(e)
        if (next !== hovered) {
            hovered?.let { send(it, e, MouseEvent.MOUSE_EXITED) }
            next?.let { send(it, e, MouseEvent.MOUSE_ENTERED) }
            hovered = next
            changed()
        }
        next?.let { send(it, e, MouseEvent.MOUSE_MOVED) }
        source.cursor = next?.cursor ?: Cursor.getDefaultCursor()
        (source as? JComponent)?.toolTipText = (next as? JComponent)?.toolTipText
    }

    private fun send(target: Component, e: MouseEvent, id: Int = e.id) {
        val p = SwingUtilities.convertPoint(source, e.point, target)
        target.dispatchEvent(MouseEvent(target, id, e.`when`, e.modifiersEx, p.x, p.y,
            e.xOnScreen, e.yOnScreen, e.clickCount, e.isPopupTrigger, e.button))
    }

    override fun close() {
        closed = true
        source.removeMouseListener(listener); source.removeMouseMotionListener(listener)
        source.removeMouseWheelListener(listener)
        focus.removeKeyEventPostProcessor(keyboard)
        focus.removePropertyChangeListener("focusOwner", focusChanged)
        (source as? JComponent)?.toolTipText = null
        (pressed as? AbstractButton)?.model?.apply { isArmed = false; isPressed = false }
        pressed = null; hovered = null
    }
}
