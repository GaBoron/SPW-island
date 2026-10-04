// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Child-to-child crossings can produce EXIT before ENTER. Never consult stale XQueryPointer data. */
internal class PointerPresence(private val exitDelayNs: Long = 120_000_000L) {
    private var entered = false
    private var exitAt: Long? = null
    fun enter() { entered = true; exitAt = null }
    fun exit(now: Long) { if (entered && exitAt == null) exitAt = now }
    fun active(now: Long): Boolean = entered && (exitAt?.let { now - it < exitDelayNs } ?: true)
    fun reset() { entered = false; exitAt = null }
}

/** Tracks the whole island, including controls and the progress bar, without grabbing input. */
internal class IslandPointerPresence(private val owner: Window) : AutoCloseable {
    private val presence = PointerPresence()
    private var suppressed = false
    private val listener = AWTEventListener { event ->
        val mouse = event as? MouseEvent ?: return@AWTEventListener
        val source = mouse.component
        if (suppressed) return@AWTEventListener
        if (source !== owner && SwingUtilities.getWindowAncestor(source) !== owner) {
            // Owned dialogs can steal the pointer without delivering an EXIT to the island.
            if (mouse.id in listOf(MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED))
                presence.reset()
            return@AWTEventListener
        }
        when (mouse.id) {
            MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> presence.enter()
            MouseEvent.MOUSE_EXITED -> presence.exit(System.nanoTime())
        }
    }
    init { Toolkit.getDefaultToolkit().addAWTEventListener(listener,
        AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK) }
    fun active(now: Long) = presence.active(now)
    fun reset() = presence.reset()
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        presence.reset()
    }
    override fun close() = Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
}
