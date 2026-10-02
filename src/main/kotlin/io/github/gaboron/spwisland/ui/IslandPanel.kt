// SPDX-License-Identifier: GPL-3.0-only
// Visual adaptation of Dynamic Lyrics Island by Lyricify / WXRIW; see NOTICE.
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import javax.swing.*
import kotlin.math.roundToInt

interface PlaybackActions { fun previous(); fun toggle(); fun next(); fun seek(positionMs: Long) {} }

class IslandPanel(private val actions: PlaybackActions, report: (Throwable) -> Unit = {}) : JPanel(null), AutoCloseable {
    private val alphaMask = IslandAlphaMask()
    private val expandedContent = IslandExpandedContentTransition()
    private val backgroundProgress = IslandProgressTransition()
    private val lyricLayouts = IslandLyricsLayout.Cache()
    private val lyricsPreparation = IslandLyricsPreparation(lyricLayouts, report)
    private var measuredSnapshot: PlaybackSnapshot? = null
    private var measuredSettings: IslandSettings? = null
    private var measuredLayout: IslandLyricsLayout? = null
    private val infoFont = SystemUiFont.derive(Font.PLAIN, 12f)
    private var infoTrack: Track? = null
    private var infoLabel = IslandLyricsPreparation.trackLabel(null)
    private var infoShape: ShapedText? = null
    override fun paint(graphics: Graphics) = paintPresentation(graphics as Graphics2D,
        IslandHoverGeometry.frame(width, height, settings.notch, settings.cornerRoundness,
            IslandHoverMotion.Pose.SHOWN))

    internal fun paintPresentation(target: Graphics2D, frame: IslandHoverGeometry.Frame) =
        alphaMask.paint(target, this, frame,
            drawBackground = { paintBackground(it, frame.shape) },
            drawContent = ::paintContent
        )
    var settings = IslandSettings()
    var anchor = IslandAnchor.TOP_CENTER
    var snapshot = PlaybackSnapshot(null, null, 0, false, PlaybackStatus.IDLE)
    var expanded = false
    var expansion: Double? = null
    internal var controlsAnimating = false
    internal var hoverSettled = true
    internal val progressAnimating get() = backgroundProgress.animating
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
        addActionListener {
            val reveal = expansion ?: if (expanded) 1.0 else 0.0
            if (hoverSettled && IslandExpandedContentTransition.visible(reveal)) clicked()
        }
        this@IslandPanel.add(this)
    }
    fun desiredSize(availableWidth: Int, expanded: Boolean = this.expanded): Dimension {
        return lyricsLayout().size(
            minOf(settings.maxWidth, availableWidth), expanded)
    }
    fun collapsedHeight(availableWidth: Int): Int =
        lyricsLayout().size(
            minOf(settings.maxWidth, availableWidth), false).height
    internal val reservedContentHeight: Int
        get() = maxOf(lyricsLayout().preferredHeight, lyricsPreparation.preparedHeight) +
            kotlin.math.ceil(IslandTextBlock.EXPANDED_HEIGHT * (1.0 + IslandExpandedMotion.OPEN_OVERSHOOT)).toInt() + 2

    internal fun prepareLyrics(lines: List<LyricLine>) {
        measuredLayout = lyricLayouts.forSnapshot(snapshot, settings, lines)
        measuredSnapshot = snapshot; measuredSettings = settings
        lyricsPreparation.update(snapshot, settings)
    }

    internal fun prepareBuffers(scale: java.awt.geom.AffineTransform) {
        alphaMask.prepare(this, scale)
        expandedContent.prepare(maxOf(width, (parent as? IslandSurface)?.renderCapacity?.width ?: 0), scale)
    }

    internal fun updateProgressTransition(dt: Double) {
        val reveal = expansion ?: if (expanded) 1.0 else 0.0
        val valid = settings.performance.renderBackgroundProgress && snapshot.track != null &&
            snapshot.metadata.durationMs > 0 && snapshot.status != PlaybackStatus.IDLE
        backgroundProgress.update(valid && hoverSettled && !controlsAnimating && !expanded && reveal == 0.0,
            settings.backgroundProgress, dt, !settings.performance.animateLayout)
    }

    private fun lyricsLayout(): IslandLyricsLayout {
        if (measuredSnapshot !== snapshot || measuredSettings != settings) {
            measuredLayout = lyricLayouts.forSnapshot(snapshot, settings)
            measuredSnapshot = snapshot; measuredSettings = settings
        }
        return measuredLayout!!
    }
    override fun doLayout() {
        val reveal = expansion ?: if (expanded) 1.0 else 0.0
        progress.presentationInteractive = hoverSettled && IslandExpandedContentTransition.visible(reveal)
        progress.update(snapshot)
        val lyricAreaBottom = (height - reveal * IslandTextBlock.EXPANDED_HEIGHT).roundToInt()
        progress.setBounds((width - PlaybackProgress.FIXED_WIDTH) / 2,
            lyricAreaBottom + 60, PlaybackProgress.FIXED_WIDTH, 28)
        progress.foreground = IslandPalette.from(settings, snapshot.metadata.coverRgb).lyric
        val buttons = listOf(previous, play, next)
        buttons.forEachIndexed { index, button ->
            button.setBounds(width / 2 - 81 + index * 56, lyricAreaBottom + 26, 50, 30)
        }
        play.icon = if (snapshot.playing) PlaybackIcon.PAUSE else PlaybackIcon.PLAY
        play.toolTipText = if (snapshot.playing) "暂停" else "播放"
    }
    private fun paintBackground(g: Graphics2D, shape: Shape) {
        val palette = IslandPalette.from(settings, snapshot.metadata.coverRgb)
        val background = palette.background
        g.color = Color(background.red, background.green, background.blue, settings.opacity * 255 / 100)
        g.fill(shape)
        IslandBackgroundProgress.draw(g, shape, width, height, snapshot, settings, palette,
            backgroundProgress.mode, backgroundProgress.opacity)
        g.color = Color(255, 255, 255, 19); g.draw(shape)
    }

    private fun paintContent(graphics: Graphics2D) {
        val g = graphics.create() as Graphics2D
        try {
            val shape = IslandGeometry.silhouette(width, height, settings.notch, settings.cornerRoundness)
            val palette = IslandPalette.from(settings, snapshot.metadata.coverRgb)
            val reveal = expansion ?: if (expanded) 1.0 else 0.0
            val outerWidth = animatedWidth.toFloat()
            val contentHeight = animatedHeight.toFloat()
            val outerX = when (anchor.horizontal) {
                HorizontalAnchor.LEFT -> 0f
                HorizontalAnchor.CENTER -> (width / 2).toFloat() - outerWidth / 2f
                HorizontalAnchor.RIGHT -> width - outerWidth
            }
            val frameInset = IslandGeometry.frameInset(contentHeight.toDouble(), settings.notch,
                settings.cornerRoundness).toFloat()
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
            val lyricLayout = lyricsLayout()
            val lyricAreaHeight = (contentHeight - reveal * IslandTextBlock.EXPANDED_HEIGHT).toFloat().coerceAtLeast(1f)
            val lyricAreaTop = 0f
            val animation = if (settings.performance.animateLayout) transition else 1.0
            val layout = IslandContentLayout.from(
                lyricLayout.blocks.first().mainLineHeight, settings.sideContent.showsSides
            )
            IslandLeadingContent.draw(g, settings.sideContent, snapshot.metadata.cover,
                bands, layout.leadingCenterX, lyricAreaTop + lyricAreaHeight / 2f,
                layout.leadingSize, palette.spectrum)
            val lyrics = g.create() as Graphics2D
            try {
                lyrics.translate(0.0, lyricAreaTop.toDouble())
                val previousLayout = outgoing?.takeIf { animation < 1.0 }
                    ?.let { lyricLayouts.forSnapshot(it, settings) }
                IslandLyricsPainter.draw(lyrics, snapshot, outgoing, settings, contentWidth,
                    lyricAreaHeight, animation, layout.textInset, lyricLayout, previousLayout)
            } finally { lyrics.dispose() }
            IslandTrailingContent.draw(g, settings.sideContent, snapshot, bands,
                layout.statusCenterX(contentWidth), lyricAreaTop + lyricAreaHeight / 2f,
                layout.leadingSize, palette.spectrum, settings.performance.animateLayout)
            expandedContent.paint(graphics, width, contentY + lyricAreaHeight - 2.0, reveal) { layer ->
                val info = layer.create() as Graphics2D
                try {
                    info.translate(contentX.toDouble(), contentY.toDouble())
                    if (infoTrack != snapshot.track) {
                        infoTrack = snapshot.track
                        infoLabel = IslandLyricsPreparation.trackLabel(infoTrack)
                        infoShape = null
                    }
                    val shaped = infoShape ?: LyricTypography.shape(infoLabel, infoFont).also { infoShape = it }
                    LyricPainter.draw(info, infoLabel, emptyList(), 0, layout.infoInset,
                        lyricAreaHeight + 17f,
                        contentWidth - layout.infoInset * 2,
                        infoFont, false, Color(147, 156, 174), fallbackFont = infoFont, shapedText = shaped)
                } finally { info.dispose() }
                super.paintChildren(layer)
            }
        } finally { g.dispose() }
    }

    override fun close() = lyricsPreparation.close()
}
