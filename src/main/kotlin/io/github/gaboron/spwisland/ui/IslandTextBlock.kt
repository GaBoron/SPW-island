// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.Dimension
import kotlin.math.ceil

/** One layout specification owns text selection, insets, measured width and vertical centering. */
class IslandTextBlock(snapshot: PlaybackSnapshot, private val settings: IslandSettings,
                      selectedLine: LyricLine? = snapshot.line) {
    val line = selectedLine
    val main = line?.text?.takeIf { it.isNotBlank() } ?: snapshot.track?.title?.takeIf { it.isNotBlank() } ?: "SPW"
    val mainFont = SystemUiFont.lyric(settings.fontFamily, settings.fontWeight, settings.fontSize.toFloat())
    val mainFallbackFont = SystemUiFont.lyricFallback(settings.fontWeight, settings.fontSize.toFloat())
    val sub = line?.translation?.takeIf { settings.translation && it.isNotBlank() }
    val subFont = mainFont.deriveFont(settings.fontSize * .7f)
    val subFallbackFont = mainFallbackFont.deriveFont(settings.fontSize * .7f)
    val mainLineHeight = mainFont.getLineMetrics("Ag", LyricTypography.context).height
    val shapedMain = LyricTypography.shape(main, mainFont, mainFallbackFont)
    val shapedSub = sub?.let { LyricTypography.shape(it, subFont, subFallbackFont) }
    val timedWords = line?.timedWords.takeIf { snapshot.usesWordTiming }.orEmpty()
    val gap = if (sub == null) 0f else 8f
    val height = shapedMain.height + gap + (shapedSub?.height ?: 0f)
    val preferredHeight = maxOf(settings.fontSize + 28, ceil(height + 28).toInt(),
        IslandContentLayout.from(mainLineHeight, settings.sideContent.showsSides).minimumLyricHeight)

    fun size(maxWidth: Int, expanded: Boolean): Dimension {
        val motionPad = if (settings.karaoke && timedWords.isNotEmpty()) settings.fontSize * .32f else 0f
        val content = IslandContentLayout.from(
            mainLineHeight, settings.sideContent.showsSides
        )
        val needed = ceil(maxOf(shapedMain.width + motionPad, shapedSub?.width ?: 0f) +
            content.textInset * 2).toInt()
        val height = preferredHeight + if (expanded) EXPANDED_HEIGHT else 0
        val frameInset = ceil(IslandGeometry.frameInset(
            height.toDouble(), settings.notch, settings.cornerRoundness
        )).toInt()
        val limit = (maxWidth - frameInset * 2).coerceAtLeast(1)
        val natural = maxOf(needed, IslandContentLayout.minimumWidth(expanded))
        val safeWidth = if (settings.fixedWidth) limit else natural
        return Dimension((safeWidth.coerceAtMost(limit) + frameInset * 2)
            .coerceAtMost(maxWidth.coerceAtLeast(1)), height)
    }
    fun mainBaseline(lyricAreaHeight: Float): Float = (lyricAreaHeight - height) / 2 - shapedMain.top
    fun subBaseline(lyricAreaHeight: Float): Float = (lyricAreaHeight - height) / 2 + shapedMain.height + gap - (shapedSub?.top ?: 0f)

    companion object {
        const val EXPANDED_HEIGHT = 98
    }
}
