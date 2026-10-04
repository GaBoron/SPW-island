// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.SettingsStore
/** Owns the shared menu model and translates native selections into plugin settings operations. */
internal class IslandMenuCommands(
    private val store: SettingsStore,
    private val showAbout: () -> Unit
) {
    fun entries(): List<PopupMenuEntry> {
        val value = store.read()
        return buildList {
            add(toggle(ENABLED, "显示词岛", value.enabled))
            if (Platform.isWindows() || Platform.isLinux()) {
                add(toggle(CLICK_THROUGH, "鼠标穿透", value.clickThrough || value.autoHideOnHover))
                add(toggle(AUTO_HIDE, "悬停隐藏", value.autoHideOnHover))
            }
            add(toggle(LOW_PERFORMANCE, "低性能模式", value.lowPerformance))
            add(separator())
            add(action(RECOVER, "找回词岛"))
            add(action(RESET_POSITION, "重置位置"))
            add(separator())
            add(action(ABOUT, "关于与状态"))
        }
    }

    fun execute(command: Int) {
        val value = store.read()
        when (command) {
            LOW_PERFORMANCE -> store.set("reduced_motion", !value.lowPerformance)
            CLICK_THROUGH -> {
                val enable = !value.clickThrough && !value.autoHideOnHover
                if (!enable && value.autoHideOnHover) store.set("auto_hide_on_hover", false)
                store.set("click_through", enable)
            }
            AUTO_HIDE -> store.set("auto_hide_on_hover", !value.autoHideOnHover)
            ENABLED -> store.set("enabled", !value.enabled)
            RECOVER -> { store.set("auto_hide_on_hover", false); store.set("click_through", false); store.set("enabled", true) }
            RESET_POSITION -> store.resetPosition()
            ABOUT -> showAbout()
        }
    }

    private fun separator() = PopupMenuEntry(PopupMenuKind.SEPARATOR)
    private fun toggle(id: Int, label: String, selected: Boolean) =
        PopupMenuEntry(PopupMenuKind.TOGGLE, id, selected, label)
    private fun action(id: Int, label: String) = PopupMenuEntry(PopupMenuKind.ACTION, id, label = label)

    private companion object {
        const val LOW_PERFORMANCE = 101
        const val CLICK_THROUGH = 105
        const val AUTO_HIDE = 106
        const val ENABLED = 107
        const val RECOVER = 201
        const val RESET_POSITION = 202
        const val ABOUT = 203
    }
}
