// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.core.*

/** Retain submitted edits until a snapshot produced after their host command arrives. */
internal class RemoteSettingsStore(private val send: (String, List<String>) -> Long) : SettingsStore {
    private var confirmed = IslandSettings(hideFullscreen = false)
    private data class Edit(val requestId: Long, val apply: (IslandSettings) -> IslandSettings)
    private val pending = mutableListOf<Edit>()
    @Synchronized fun accept(state: IslandState): Boolean {
        val before = read()
        state.settings?.let { confirmed = it }
        pending.removeAll { it.requestId <= state.acknowledgedCommand }
        return read() != before
    }
    @Synchronized override fun read() = pending.fold(confirmed) { value, edit -> edit.apply(value) }
    @Synchronized private fun edit(action: String, arguments: List<String> = emptyList(),
                                   apply: (IslandSettings) -> IslandSettings) {
        // Hold the lock across send and registration so even an immediate reply cannot race the edit.
        pending += Edit(send(action, arguments), apply)
    }
    override fun set(key: String, value: Any) = edit("set", listOf(key,
        if (value is Boolean) "boolean" else "string", value.toString())) { it.withSetting(key, value) }
    override fun savePosition(screen: String, x: Int, y: Int, anchor: IslandAnchor) =
        edit("position", listOf(screen, x.toString(), y.toString(), anchor.storageName)) {
            it.copy(screen = screen, positionX = x, positionY = y, positionAnchor = anchor,
                legacyCenterX = null, legacyTop = null)
        }
    override fun resetPosition() = edit("reset") {
        it.copy(screen = "", positionX = null, positionY = null, positionAnchor = null,
            legacyCenterX = null, legacyTop = null)
    }
    override fun resetAll() = edit("reset_all") { IslandSettings(hideFullscreen = false) }
}

private fun IslandSettings.withSetting(key: String, value: Any): IslandSettings = when (key) {
    "enabled" -> copy(enabled = value as Boolean)
    "translation" -> copy(translation = value as Boolean)
    "karaoke" -> copy(karaoke = value as Boolean)
    "experimental_multi_line" -> copy(experimentalMultiLine = value as Boolean)
    "hide_paused" -> copy(hidePaused = value as Boolean)
    "hide_fullscreen" -> copy(hideFullscreen = value as Boolean)
    "click_through" -> copy(clickThrough = value as Boolean)
    "auto_hide_on_hover" -> copy(autoHideOnHover = value as Boolean)
    "reduced_motion" -> copy(lowPerformance = value as Boolean)
    "shape" -> copy(notch = value == "notch")
    "lyric_cover_color" -> copy(lyricCoverColor = value as Boolean)
    "background_cover_color" -> copy(backgroundCoverColor = value as Boolean)
    "spectrum_cover_color" -> copy(spectrumCoverColor = value as Boolean)
    "fixed_width" -> copy(fixedWidth = value as Boolean)
    else -> this
}
