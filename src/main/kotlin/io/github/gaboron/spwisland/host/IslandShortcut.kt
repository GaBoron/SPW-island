// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.event.KeyEvent

/** Registration must stay on the plugin start thread; SPW owns bindings and conflicts. */
internal class IslandShortcut(private val toggle: () -> Unit) : AutoCloseable {
    private var registration: AutoCloseable? = null
    @Volatile private var closed = false

    fun start() {
        if (closed || !WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) return
        try {
            registration = WorkshopApi.manager.keyBindingManager.register(
                "toggle-island", "显示 / 隐藏词岛",
                ActionShortcut(KeyEvent.VK_D, ActionShortcut.CONTROL or ActionShortcut.SHIFT),
                hasGlobal = true,
                handler = Runnable {
                    if (!closed && WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) toggle()
                }
            )
        } catch (_: PluginPermissionDeniedException) {
            // Permission can be revoked between the check and registration.
        }
    }

    override fun close() {
        closed = true
        registration?.close()
        registration = null
    }
}
