// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.SettingsStore
import io.github.gaboron.spwisland.platform.SystemTheme
import java.awt.*
import javax.swing.*

/** Recovery controls remain available in SPW settings even if the tray is unavailable. */
class IslandMenu(private val store: SettingsStore, private val report: (Throwable) -> Unit,
                 private val owner: Window, status: () -> List<IslandStatusEntry> = { emptyList() }) : AutoCloseable {
    private var tray: TrayIcon? = null
    private var windowsTray: WindowsTray? = null
    private var linuxTray: GtkTray? = null
    private val commands = IslandMenuCommands(store, ::about)
    private val popup = if (Platform.isLinux()) null else LightweightPopupMenu(owner, report)
    private val aboutDialog = AboutDialog(owner, report, status)
    val aboutVisible: Boolean get() = aboutDialog.isVisible
    private fun action(block: () -> Unit) { try { block() } catch (e: Exception) { report(e) } }
    fun popup(owner: Component, x: Int, y: Int) {
        // Linux surface right-click is deferred; all quick settings live in the native tray.
        if (Platform.isLinux()) return
        val origin = owner.locationOnScreen
        showPopup(Point(origin.x + x, origin.y + y))
    }
    fun installTray() {
        if (Platform.isLinux()) {
            if (linuxTray == null) linuxTray = GtkTray(commands::entries,
                { command -> action { commands.execute(command) } }, report)
            return
        }
        popup?.prewarm(commands.entries(), !SystemTheme.isLight())
        if (Platform.isWindows() && windowsTray == null) {
            try {
                windowsTray = WindowsTray(
                    { action { store.set("enabled", !store.read().enabled) } },
                    ::showPopup, report)
                return
            } catch (error: Exception) { report(error) }
        }
        if (!SystemTray.isSupported() || tray != null) return
        val created = TrayIcon(ApplicationIdentity.icon, ApplicationIdentity.NAME).apply {
            isImageAutoSize = true
            addActionListener { SwingUtilities.invokeLater { action { store.set("enabled", !store.read().enabled) } } }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(event: java.awt.event.MouseEvent) = showPopup(event)
                override fun mouseReleased(event: java.awt.event.MouseEvent) = showPopup(event)
                private fun showPopup(event: java.awt.event.MouseEvent) {
                    if (!event.isPopupTrigger) return
                    val anchor = Point(event.xOnScreen, event.yOnScreen)
                    if (SwingUtilities.isEventDispatchThread()) this@IslandMenu.showPopup(anchor)
                    else SwingUtilities.invokeLater { this@IslandMenu.showPopup(anchor) }
                }
            })
        }
        SystemTray.getSystemTray().add(created)
        tray = created
    }
    private fun showPopup(anchor: Point) {
        popup?.show(
            commands.entries(),
            useDark = !SystemTheme.isLight(),
            anchor = anchor
        ) { command ->
            action { commands.execute(command) }
        }
    }
    fun about() = aboutDialog.show()
    override fun close() {
        popup?.close()
        linuxTray?.close(); linuxTray = null
        windowsTray?.close(); windowsTray = null
        aboutDialog.close()
        tray?.let { SystemTray.getSystemTray().remove(it) }; tray = null
    }
}
