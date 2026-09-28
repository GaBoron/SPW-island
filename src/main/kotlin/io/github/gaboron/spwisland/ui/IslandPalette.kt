// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.IslandSettings
import java.awt.Color
import kotlin.math.roundToInt
import kotlin.math.pow

data class IslandPalette(val lyric: Color, val background: Color, val spectrum: Color,
                         val lyricOutline: Boolean) {
    companion object {
        private val DEFAULT_BACKGROUND = Color(7, 8, 12)
        private val DIM_LYRIC = Color(126, 129, 138)
        private val SUB_LYRIC = Color(177, 182, 195)
        private const val BACKGROUND_BRIGHTNESS = .11f
        private const val MAX_BACKGROUND_SATURATION = .65f
        private const val MAX_LYRIC_SATURATION = .42f
        private const val MIN_COVER_CONTRAST = 10.0
        private const val MIN_TRANSPARENT_CONTRAST = 4.5

        fun from(settings: IslandSettings, coverRgb: Int?): IslandPalette {
            val cover = coverRgb?.let { Color(it) }
            val hsb = cover?.let { Color.RGBtoHSB(it.red, it.green, it.blue, null) }
            // The sampled hue is only an accent: keep the background dark and the lyric light.
            val dark = hsb?.let {
                Color.getHSBColor(it[0], it[1].coerceAtMost(MAX_BACKGROUND_SATURATION), BACKGROUND_BRIGHTNESS)
            }
            val background = if (settings.backgroundCoverColor) dark ?: DEFAULT_BACKGROUND else DEFAULT_BACKGROUND
            val sampledBright = hsb?.let { Color.getHSBColor(it[0], it[1].coerceAtMost(.55f), 1f) }
            val lyric = if (settings.lyricCoverColor && hsb != null)
                brightCoverColor(hsb[0], hsb[1], background) else Color.WHITE
            val lyricOutline = settings.lyricCoverColor && settings.backgroundCoverColor && cover != null && run {
                // White content behind a translucent island is the hard case for pale lyric colors.
                val visibleBackground = overWhite(background, settings.opacity)
                minOf(contrast(lyric, visibleBackground), contrast(DIM_LYRIC, visibleBackground),
                    contrast(SUB_LYRIC, visibleBackground)) < MIN_TRANSPARENT_CONTRAST
            }
            return IslandPalette(
                lyric, background,
                if (settings.spectrumCoverColor) sampledBright ?: Color.WHITE else Color.WHITE,
                lyricOutline)
        }

        private fun brightCoverColor(hue: Float, saturation: Float, background: Color): Color {
            val preferred = saturation.coerceAtMost(MAX_LYRIC_SATURATION)
            val color = Color.getHSBColor(hue, preferred, 1f)
            if (contrast(color, background) >= MIN_COVER_CONTRAST) return color
            var low = 0f
            var high = preferred
            repeat(8) {
                val middle = (low + high) / 2f
                if (contrast(Color.getHSBColor(hue, middle, 1f), background) >= MIN_COVER_CONTRAST)
                    low = middle else high = middle
            }
            return Color.getHSBColor(hue, low, 1f)
        }

        private fun overWhite(color: Color, opacity: Int): Color {
            val alpha = opacity.coerceIn(0, 100) / 100.0
            fun channel(value: Int) = (value * alpha + 255 * (1 - alpha)).roundToInt()
            return Color(channel(color.red), channel(color.green), channel(color.blue))
        }

        private fun contrast(first: Color, second: Color): Double {
            val lighter = maxOf(luminance(first), luminance(second))
            val darker = minOf(luminance(first), luminance(second))
            return (lighter + .05) / (darker + .05)
        }

        private fun luminance(color: Color): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
        }
    }
}
