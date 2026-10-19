// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import kotlin.math.roundToInt

/** Shared seek/volume gestures; seeking commits once on release and is cancelled on track changes. */
class PlaybackProgress internal constructor(private val seek: (Long) -> Unit,
                                            private val volume: IslandVolumeFeedback) : JComponent() {
    internal data class Presentation(val fraction: Double, val positionLabel: String, val durationLabel: String,
                                     val knobOpacity: Float, val color: Int, val volumeMix: Float,
                                     val volumeLabel: String)
    companion object { const val FIXED_WIDTH = 276 }
    private var snapshot = PlaybackSnapshot(null, null, 0, false, PlaybackStatus.IDLE)
    private var gestureTrack: Track? = null
    private var preview: Long? = null
    private var seekAvailable = false
    internal var presentationInteractive = true
    private val timeFont = SystemUiFont.derive(Font.PLAIN, 10f)
    private var positionSeconds = Long.MIN_VALUE
    private var durationSeconds = Long.MIN_VALUE
    private var positionLabel = "0:00"
    private var durationLabel = "--:--"
    val dragging get() = preview != null || volume.dragging
    init {
        isOpaque = false
        accessibleContext?.accessibleName = "播放进度"
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { volume.hover(overBar(e.x, e.y)) }
            override fun mouseMoved(e: MouseEvent) { volume.hover(overBar(e.x, e.y)) }
            override fun mouseExited(e: MouseEvent) { volume.hover(false) }
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || !presentationInteractive) return
                if (volume.canDrag) {
                    if (overBar(e.x, e.y) && volume.beginDrag(fractionAt(e.x))) repaint()
                    return
                }
                if (!seekAvailable || volume.blocksSeek) return
                gestureTrack = snapshot.track; preview = positionAt(e.x); repaint()
            }
            override fun mouseDragged(e: MouseEvent) {
                volume.hover(overBar(e.x, e.y))
                if (volume.dragging) { volume.dragTo(fractionAt(e.x)); repaint() }
                else if (preview != null) { preview = positionAt(e.x); repaint() }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                if (volume.dragging) {
                    volume.dragTo(fractionAt(e.x)); volume.endDrag()
                    volume.hover(overBar(e.x, e.y)); repaint()
                    return
                }
                if (preview == null) return
                val target = positionAt(e.x)
                val valid = gestureTrack == snapshot.track && seekAvailable && presentationInteractive && !volume.blocksSeek
                if (valid) seek(target)
                preview = null; gestureTrack = null
                repaint()
            }
        }
        addMouseListener(mouse); addMouseMotionListener(mouse)
    }
    fun update(value: PlaybackSnapshot) {
        if (!presentationInteractive) { volume.cancelDrag(); volume.hover(false) }
        if (!presentationInteractive || snapshot.track != value.track || value.metadata.durationMs <= 0 ||
            value.status == PlaybackStatus.IDLE) {
            preview = null; gestureTrack = null
        }
        snapshot = value
        seekAvailable = value.track != null && value.metadata.durationMs > 0 &&
            value.status != PlaybackStatus.IDLE && value.status != PlaybackStatus.ENDED
        isEnabled = seekAvailable || volume.canDrag
        val interactive = presentationInteractive && (volume.canDrag || seekAvailable && !volume.blocksSeek)
        val nextCursor = Cursor.getPredefinedCursor(if (interactive) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        if (cursor != nextCursor) cursor = nextCursor
        val tooltip = if (volume.blocksSeek) "滚轮、点击或拖动调节 SPW 音量" else if (seekAvailable) "点击或拖动跳转"
            else "无法读取本地歌曲时长，进度控制不可用"
        if (toolTipText != tooltip) toolTipText = tooltip
        val accessibleName = if (volume.blocksSeek) "SPW 音量 ${volume.label}" else "播放进度"
        if (accessibleContext?.accessibleName != accessibleName) accessibleContext?.accessibleName = accessibleName
    }
    internal fun cancelGesture() { preview = null; gestureTrack = null; volume.cancelDrag(); volume.hover(false) }
    internal fun presentation(): Presentation {
        val duration = snapshot.metadata.durationMs
        val position = (preview ?: snapshot.positionMs).coerceIn(0, duration.coerceAtLeast(0))
        val seconds = (if (duration > 0) position else snapshot.positionMs).coerceAtLeast(0) / 1000
        if (seconds != positionSeconds) { positionSeconds = seconds; positionLabel = time(seconds) }
        val endSeconds = if (duration > 0) duration / 1000 else -1L
        if (endSeconds != durationSeconds) {
            durationSeconds = endSeconds
            durationLabel = if (endSeconds >= 0) time(endSeconds) else "--:--"
        }
        val mix = volume.mix
        val activeColor = foreground ?: Color.WHITE
        val playbackColor = if (seekAvailable) activeColor else Color.GRAY
        fun channel(from: Int, to: Int) = (from + (to - from) * mix).roundToInt().coerceIn(0, 255)
        val color = Color(channel(playbackColor.red, activeColor.red), channel(playbackColor.green, activeColor.green),
            channel(playbackColor.blue, activeColor.blue))
        return Presentation(volume.fraction(if (duration > 0) position.toDouble() / duration else 0.0),
            positionLabel, durationLabel, if (seekAvailable) 1f else mix.toFloat(), color.rgb, mix.toFloat(), volume.label)
    }
    private fun fractionAt(x: Int) = ((x - 48).toDouble() / (width - 96).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    private fun positionAt(x: Int): Long = (fractionAt(x) * snapshot.metadata.durationMs).toLong()
    private fun overBar(x: Int, y: Int) = x >= 42 && x <= width - 42 && y in 4..24

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val view = presentation()
            val span = (width - 96).coerceAtLeast(1)
            val filled = span * view.fraction
            g.color = Color(72, 77, 89); g.fillRoundRect(48, 12, span, 4, 4, 4)
            g.color = Color(view.color, true)
            g.fill(RoundRectangle2D.Double(48.0, 12.0, filled, 4.0, 4.0, 4.0))
            if (view.knobOpacity > 0f) {
                val knob = g.create() as Graphics2D
                try {
                    knob.composite = AlphaComposite.SrcOver.derive(view.knobOpacity)
                    knob.fill(Ellipse2D.Double(45 + filled, 9.0, 10.0, 10.0))
                } finally { knob.dispose() }
            }
            g.font = timeFont
            labels(g, view.positionLabel, view.durationLabel, 1f - view.volumeMix, -5f * view.volumeMix)
            labels(g, "音量", view.volumeLabel, view.volumeMix, 5f * (1f - view.volumeMix))
        } finally { g.dispose() }
    }
    private fun labels(target: Graphics2D, left: String, right: String, opacity: Float, offset: Float) {
        if (opacity <= 0f) return
        val g = target.create() as Graphics2D
        try {
            g.composite = AlphaComposite.SrcOver.derive(opacity)
            g.drawString(left, 0f, 18f + offset)
            g.drawString(right, (width - g.fontMetrics.stringWidth(right)).toFloat(), 18f + offset)
        } finally { g.dispose() }
    }
    private fun time(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
