// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.diagnostics.IslandLog

/** Shared quick settings and playback commands for the surface menu and native trays. */
internal class IslandMenuCommands(
    private val store: SettingsStore,
    private val showAbout: () -> Unit,
    private val actions: PlaybackActions,
    private val playback: () -> PlaybackSnapshot
) {
    fun entries(): List<PopupMenuEntry> {
        val value = store.read()
        val snapshot = playback()
        return buildList {
            add(action(PLAY_PAUSE, if (snapshot.playing) "暂停" else "播放"))
            add(action(PREVIOUS, "上一首"))
            add(action(NEXT, "下一首"))
            snapshot.playbackMode?.let { add(action(PLAYBACK_MODE, "播放模式：${it.label}（切换）")) }
            add(separator())
            add(toggle(TRANSLATION, "显示翻译", value.translation))
            add(toggle(KARAOKE, "逐字高亮", value.karaoke))
            add(toggle(SCROLL_VOLUME, "滚轮调节音量", value.scrollVolume))
            add(separator())
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
        when (command) {
            RECOVER -> recordSettingsAction("recover") { executeCommand(command) }
            RESET_POSITION -> recordSettingsAction("reset_position") { executeCommand(command) }
            else -> executeCommand(command)
        }
    }

    private fun executeCommand(command: Int) {
        val value = store.read()
        when (command) {
            PLAY_PAUSE -> actions.toggle()
            PREVIOUS -> actions.previous()
            NEXT -> actions.next()
            PLAYBACK_MODE -> if (playback().playbackMode != null) actions.cyclePlaybackMode()
            TRANSLATION -> store.set("translation", !value.translation)
            KARAOKE -> store.set("karaoke", !value.karaoke)
            SCROLL_VOLUME -> store.set("scroll_volume", !value.scrollVolume)
            LOW_PERFORMANCE -> store.set("reduced_motion", !value.lowPerformance)
            CLICK_THROUGH -> {
                val enable = !value.clickThrough && !value.autoHideOnHover
                if (!enable && value.autoHideOnHover) store.set("auto_hide_on_hover", false)
                store.set("click_through", enable)
            }
            AUTO_HIDE -> store.set("auto_hide_on_hover", !value.autoHideOnHover)
            ENABLED -> store.set("enabled", !value.enabled)
            RECOVER -> {
                store.set("auto_hide_on_hover", false)
                store.set("hide_spw_player_foreground", false)
                store.set("click_through", false)
                store.set("enabled", true)
            }
            RESET_POSITION -> store.resetPosition()
            ABOUT -> showAbout()
        }
    }

    private fun recordSettingsAction(name: String, action: () -> Unit) {
        val operation = IslandLog.operation("menu", name)
        try {
            action()
            IslandLog.event("menu", "$name.settings_submitted", mapOf("presentationVerified" to false))
            operation.close()
        } catch (error: Throwable) {
            operation.failed(error)
            throw error
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
        const val TRANSLATION = 108
        const val KARAOKE = 109
        const val SCROLL_VOLUME = 110
        const val RECOVER = 201
        const val RESET_POSITION = 202
        const val ABOUT = 203
        const val PLAY_PAUSE = 301
        const val PREVIOUS = 302
        const val NEXT = 303
        const val PLAYBACK_MODE = 304
    }
}
