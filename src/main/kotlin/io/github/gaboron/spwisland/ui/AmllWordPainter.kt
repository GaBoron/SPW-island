// SPDX-License-Identifier: GPL-3.0-only
// Links to the AGPL-3.0-only AMLL motion port under GPLv3 section 13; see NOTICE.
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.Word
import io.github.gaboron.spwisland.longtone.LongToneRuleRegistry
import java.awt.*

/** Transforms shaped graphemes, preserving fallback fonts and whole-line shaping. */
object AmllWordPainter {
    private val longToneRules = LongToneRuleRegistry()

    fun draw(g: Graphics2D, shaped: ShapedText, text: String, words: List<Word>, time: Long,
             origin: Float, baseline: Float, fontSize: Float, motion: Boolean, color: Color = Color.WHITE,
             contrastOutline: Boolean = false) {
        val geometry = WordGeometry.ready(shaped, text, words.map { it.text })
        if (geometry == null) {
            drawTimed(g, shaped, words, time, origin, baseline, fontSize, color, contrastOutline)
            return
        }
        for ((wordIndex, word) in words.withIndex()) {
            val longTone = motion && longToneRules.isLongTone(word)
            val cell = geometry[wordIndex]
            val wordBounds = cell.bounds
            val clusters = cell.clusters
            for ((index, cluster) in clusters.withIndex()) {
                val bounds = cluster.bounds
                val area = cluster.shape
                val pose = if (motion) AmllMotion.word(word, time, index, clusters.size,
                    wordIndex == words.lastIndex, longTone) else AmllMotion.Pose()
                val copy = g.create() as Graphics2D
                try {
                    copy.translate(origin.toDouble(), baseline.toDouble())
                    copy.translate(pose.xEm * fontSize + bounds.centerX, pose.yEm * fontSize + bounds.centerY)
                    copy.scale(pose.scale, pose.scale); copy.translate(-bounds.centerX, -bounds.centerY)
                    val progress = word.progress(time)
                    LyricGlow.draw(copy, area, fontSize, color, pose.glow)
                    if (contrastOutline) LyricContrastOutline.draw(copy, area, fontSize)
                    copy.paint = highlight(shaped, wordBounds, progress, fontSize, color)
                    copy.fill(area)
                } finally { copy.dispose() }
            }
        }
    }

    /** Timing is independent of outline readiness; only floating/glow waits for preparation. */
    internal fun drawTimed(g: Graphics2D, shaped: ShapedText, words: List<Word>, time: Long,
                           origin: Float, baseline: Float, fontSize: Float, color: Color = Color.WHITE,
                           contrastOutline: Boolean = false) {
        if (contrastOutline)
            LyricContrastOutline.draw(g, shaped.outline, fontSize, origin.toDouble(), baseline.toDouble())
        var start = 0
        for (word in words) {
            val end = start + word.text.length
            val region = shaped.layout.getLogicalHighlightShape(start, end)
            val copy = g.create() as Graphics2D
            try {
                copy.translate(origin.toDouble(), baseline.toDouble())
                copy.clip(region)
                copy.paint = highlight(shaped, region.bounds2D, word.progress(time), fontSize, color)
                shaped.layout.draw(copy, 0f, 0f)
            } finally { copy.dispose() }
            start = end
        }
    }

    private fun highlight(shaped: ShapedText, bounds: java.awt.geom.Rectangle2D,
                          progress: Double, fontSize: Float, color: Color): Paint {
        val dim = Color(126, 129, 138)
        val ltr = shaped.layout.isLeftToRight
        val boundary = if (ltr) bounds.x + bounds.width * progress else bounds.maxX - bounds.width * progress
        val feather = minOf(fontSize * .45, bounds.width * .6).toFloat().coerceAtLeast(.01f)
        return when {
            progress <= 0 -> dim
            progress >= 1 -> color
            else -> GradientPaint(boundary.toFloat() - feather / 2, 0f, if (ltr) color else dim,
                boundary.toFloat() + feather / 2, 0f, if (ltr) dim else color)
        }
    }
}
