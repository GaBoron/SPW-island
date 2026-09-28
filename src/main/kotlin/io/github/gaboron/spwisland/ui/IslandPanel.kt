// SPDX-License-Identifier: GPL-3.0-only
// Visual adaptation of Dynamic Lyrics Island by Lyricify / WXRIW; see NOTICE.
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import javax.swing.*
import kotlin.math.roundToInt

interface PlaybackActions { fun previous(); fun toggle(); fun next(); fun seek(positionMs: Long) {} }

class IslandPanel(private val actions: PlaybackActions) : JPanel(null) {
    private val alphaMask = IslandAlphaMask()
    override fun paint(graphics: Graphics) = alphaMask.paint(
        graphics as Graphics2D, this, (parent as? IslandSurface)?.revealScale ?: 1.0
    ) { super.paint(it) }
    var settings = IslandSettings()
    var anchor = IslandAnchor.TOP_CENTER
    var snapshot = PlaybackSnapshot(null, null, 0, false, PlaybackStatus.IDLE)
    var expanded = false
    var expansion: Double? = null
    var animatedWidth = 280.0
    var animatedHeight = 58.0
    var transition = 1.0
    var outgoing: PlaybackSnapshot? = null
    val progress = PlaybackProgress(::seekPlayback).also { add(it) }
    private fun seekPlayback(positionMs: Long) {
        actions.seek(positionMs)
        // Publish the committed position before the gesture preview is removed.
        // Layout may run before the next timer tick, so update the panel snapshot too.
        snapshot = snapshot.copy(positionMs = positionMs)
        progress.update(snapshot)
    }
    private val bands = FloatArray(4)
    fun updateSpectrum(levels: FloatArray, dt: Double) {
        for (i in bands.indices) {
            val target = levels.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val speed = if (target > bands[i]) 28 else 9
            bands[i] += (target - bands[i]) * (1 - kotlin.math.exp(-dt * speed)).toFloat()
        }
    }
    private val previous = control("上一首", PlaybackIcon.PREVIOUS) { actions.previous() }
    private val play = control("播放或暂停", PlaybackIcon.PLAY) { actions.toggle() }
    private val next = control("下一首", PlaybackIcon.NEXT) { actions.next() }

    init { isOpaque = false; getAccessibleContext().accessibleName = "SPW 灵动词岛" }
    private fun control(label: String, glyph: Icon, clicked: () -> Unit) = JButton(glyph).apply {
        toolTipText = label; accessibleContext.accessibleName = label
        isFocusable = false; isContentAreaFilled = false; isBorderPainted = false
        margin = Insets(0, 0, 0, 0)
        foreground = Color(223, 228, 237); font = SystemUiFont.derive(Font.PLAIN, 16f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { clicked() }; this@IslandPanel.add(this)
    }
    fun desiredSize(availableWidth: Int): Dimension {
        return IslandLyricsLayout(snapshot, settings).size(
            minOf(settings.maxWidth, availableWidth), expanded, anchor)
    }
    fun collapsedHeight(availableWidth: Int): Int =
        IslandLyricsLayout(snapshot, settings).size(
            minOf(settings.maxWidth, availableWidth), false, anchor).height
    override fun doLayout() {
        progress.update(snapshot)
        val reveal = expansion ?: if (expanded) 1.0 else 0.0
        val controlsVisible = IslandExpandedContentTransition.visible(reveal)
        val lyricAreaBottom = (height - reveal * IslandTextBlock.EXPANDED_HEIGHT).roundToInt()
        progress.isVisible = controlsVisible
        progress.setBounds((width - PlaybackProgress.FIXED_WIDTH) / 2,
            lyricAreaBottom + 60, PlaybackProgress.FIXED_WIDTH, 28)
        progress.foreground = IslandPalette.from(settings, snapshot.metadata.coverRgb).lyric
        val buttons = listOf(previous, play, next)
        buttons.forEachIndexed { index, button ->
            button.isVisible = controlsVisible
            button.setBounds(width / 2 - 81 + index * 56, lyricAreaBottom + 26, 50, 30)
        }
        play.icon = if (snapshot.playing) PlaybackIcon.PAUSE else PlaybackIcon.PLAY
        play.toolTipText = if (snapshot.playing) "暂停" else "播放"
    }
    override fun paintChildren(graphics: Graphics) {
        val reveal = expansion ?: if (expanded) 1.0 else 0.0
        val layer = IslandExpandedContentTransition.layer(graphics, reveal) ?: return
        try { super.paintChildren(layer) } finally { layer.dispose() }
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            // A resized translucent surface must not retain pixels from the previous silhouette.
            val composite = g.composite
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, width, height)
            g.composite = composite
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            val shape = IslandGeometry.silhouette(width, height, settings.notch, settings.cornerRoundness, anchor)
            val palette = IslandPalette.from(settings, snapshot.metadata.coverRgb)
            val background = palette.background
            val reveal = expansion ?: if (expanded) 1.0 else 0.0
            g.color = Color(background.red, background.green, background.blue, settings.opacity * 255 / 100)
            g.fill(shape)
            IslandBackgroundProgress.draw(g, shape, width, height, anchor, snapshot, settings, palette, reveal)
            g.color = Color(255, 255, 255, 19); g.draw(shape)
            val outerWidth = animatedWidth.toFloat()
            val contentHeight = animatedHeight.toFloat()
            val outerX = when (anchor.horizontal) {
                HorizontalAnchor.LEFT -> 0f
                HorizontalAnchor.CENTER -> (width / 2).toFloat() - outerWidth / 2f
                HorizontalAnchor.RIGHT -> width - outerWidth
            }
            val frameInset = IslandGeometry.frameInset(contentHeight.toDouble(), settings.notch,
                settings.cornerRoundness, anchor).toFloat()
            val contentWidth = (outerWidth - frameInset * 2f).coerceAtLeast(1f)
            val contentX = outerX + frameInset
            val contentY = when (anchor.vertical) {
                VerticalAnchor.TOP -> 0f
                VerticalAnchor.CENTER -> (height / 2).toFloat() - contentHeight / 2f
                VerticalAnchor.BOTTOM -> height - contentHeight
            }
            g.translate(contentX.toDouble(), contentY.toDouble())
            g.clip(java.awt.geom.AffineTransform.getTranslateInstance(
                -contentX.toDouble(), -contentY.toDouble()
            ).createTransformedShape(shape))
            val block = IslandTextBlock(snapshot, settings)
            val lyricAreaHeight = (contentHeight - reveal * IslandTextBlock.EXPANDED_HEIGHT).toFloat().coerceAtLeast(1f)
            val lyricAreaTop = 0f
            val animation = if (settings.performance.animateLayout) transition else 1.0
            val layout = IslandContentLayout.from(
                block.mainLineHeight, settings.sideContent.showsSides
            )
            IslandLeadingContent.draw(g, settings.sideContent, snapshot.metadata.cover,
                bands, layout.leadingCenterX, lyricAreaTop + lyricAreaHeight / 2f,
                layout.leadingSize, palette.spectrum)
            val lyrics = g.create() as Graphics2D
            try {
                lyrics.translate(0.0, lyricAreaTop.toDouble())
                IslandLyricsPainter.draw(lyrics, snapshot, outgoing, settings, contentWidth,
                    lyricAreaHeight, animation, layout.textInset)
            } finally { lyrics.dispose() }
            IslandTrailingContent.draw(g, settings.sideContent, snapshot, bands,
                layout.statusCenterX(contentWidth), lyricAreaTop + lyricAreaHeight / 2f,
                layout.leadingSize, palette.spectrum, settings.performance.animateLayout)
            IslandExpandedContentTransition.layer(g, reveal)?.let { info ->
                try {
                    val label = listOfNotNull(snapshot.track?.title, snapshot.track?.artist)
                        .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "在 SPW 中播放音乐" }
                    LyricPainter.draw(info, label, emptyList(), 0, layout.infoInset,
                        lyricAreaHeight + 17f,
                        contentWidth - layout.infoInset * 2,
                        SystemUiFont.derive(Font.PLAIN, 12f), false, Color(147, 156, 174))
                } finally { info.dispose() }
            }
        } finally { g.dispose() }
    }
}
