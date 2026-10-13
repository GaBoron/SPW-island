// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.longtone.LongToneAnalysis
import io.github.gaboron.spwisland.ui.*
import java.awt.Rectangle

/** EDT snapshot consumed by the native painter; Swing components only retain interaction state. */
internal data class IslandGpuFrame(
    val width: Int, val height: Int, val animatedWidth: Double, val animatedHeight: Double,
    val anchor: IslandAnchor, val settings: IslandSettings, val snapshot: PlaybackSnapshot,
    val outgoing: PlaybackSnapshot?, val lyrics: IslandLyricsLayout, val previousLyrics: IslandLyricsLayout?,
    val longTones: LongToneAnalysis, val bands: FloatArray, val expansion: Double, val transition: Double,
    val progressMode: BackgroundProgressMode, val progressOpacity: Double,
    val info: ShapedText?, val infoText: String?, val controls: List<Control>, val progress: PlaybackProgress.Presentation?,
    val progressBounds: Rectangle?
) {
    data class Control(val bounds: Rectangle, val icon: PlaybackIcon, val color: Int, val pressed: Boolean)
}
