// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

/** Persisted defaults for the complete plugin configuration, including settings hidden on one platform. */
object IslandSettingsDefaults {
    private val defaults = IslandSettings()

    val values: Map<String, Any> = linkedMapOf(
        "enabled" to defaults.enabled,
        "translation" to defaults.translation,
        "karaoke" to defaults.karaoke,
        "experimental_multi_line" to defaults.experimentalMultiLine,
        "hide_paused" to defaults.hidePaused,
        "hide_fullscreen" to true,
        "click_through" to defaults.clickThrough,
        "auto_hide_on_hover" to defaults.autoHideOnHover,
        "reduced_motion" to defaults.lowPerformance,
        "shape" to "pill",
        "corner_roundness" to defaults.cornerRoundness,
        "lyric_cover_color" to defaults.lyricCoverColor,
        "background_cover_color" to defaults.backgroundCoverColor,
        "background_style" to defaults.backgroundStyle.storageName,
        "background_progress" to "off",
        "spectrum_cover_color" to defaults.spectrumCoverColor,
        "fixed_width" to defaults.fixedWidth,
        "leading_content" to "cover_spectrum",
        "font_family" to defaults.fontFamily,
        "font_weight" to defaults.fontWeight.storageName,
        "font_size" to defaults.fontSize,
        "max_width" to defaults.maxWidth,
        "opacity" to defaults.opacity,
        "offset_ms" to defaults.offsetMs,
        "screen" to defaults.screen,
        "position_x" to Int.MIN_VALUE,
        "position_y" to Int.MIN_VALUE,
        "position_anchor" to "",
        "center_x" to Int.MIN_VALUE,
        "top" to Int.MIN_VALUE,
        "vertical_anchor" to "free"
    )
}
