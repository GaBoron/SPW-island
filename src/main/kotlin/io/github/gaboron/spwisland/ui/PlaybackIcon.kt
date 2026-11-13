// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import io.github.gaboron.spwisland.core.PlaybackMode
import javax.swing.Icon

/** Matching filled transport symbols, independent of the selected font. */
enum class PlaybackIcon : Icon {
    PREVIOUS, PLAY, PAUSE, NEXT, SEQUENTIAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE;

    /** The same filled geometry is used by Swing and Skia, including the stroked mode symbols. */
    val shape: Shape by lazy {
        val path = Path2D.Float()
        fun polygon(vararg points: Double) {
            path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            path.closePath()
        }
        fun rectangle(x: Double, y: Double, width: Double, height: Double) {
            path.append(Rectangle2D.Double(x, y, width, height), false)
        }
        fun lines(draw: Path2D.Float.() -> Unit) {
            val stroke = Path2D.Float().apply(draw)
            path.append(BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(stroke), false)
        }
        when (this) {
            PAUSE -> {
                path.append(RoundRectangle2D.Double(4.0, 3.0, 4.0, 12.0, 1.0, 1.0), false)
                path.append(RoundRectangle2D.Double(11.0, 3.0, 4.0, 12.0, 1.0, 1.0), false)
            }
            PLAY -> polygon(5.0, 3.0, 5.0, 15.0, 15.0, 9.0)
            PREVIOUS -> { rectangle(3.0, 3.0, 2.0, 12.0); polygon(15.0, 3.0, 15.0, 15.0, 5.0, 9.0) }
            NEXT -> { rectangle(13.0, 3.0, 2.0, 12.0); polygon(3.0, 3.0, 3.0, 15.0, 13.0, 9.0) }
            SEQUENTIAL -> {
                lines { moveTo(3.0, 5.0); lineTo(14.0, 5.0); moveTo(3.0, 13.0); lineTo(14.0, 13.0) }
                polygon(12.0, 2.0, 16.0, 5.0, 12.0, 8.0)
                polygon(12.0, 10.0, 16.0, 13.0, 12.0, 16.0)
            }
            REPEAT_ALL, REPEAT_ONE -> {
                lines {
                    moveTo(3.0, 8.0); lineTo(3.0, 7.0); curveTo(3.0, 4.0, 5.0, 4.0, 7.0, 4.0); lineTo(13.0, 4.0)
                    moveTo(15.0, 10.0); lineTo(15.0, 11.0); curveTo(15.0, 14.0, 13.0, 14.0, 11.0, 14.0); lineTo(5.0, 14.0)
                }
                polygon(12.0, 1.0, 16.0, 4.0, 12.0, 7.0)
                polygon(6.0, 11.0, 2.0, 14.0, 6.0, 17.0)
                if (this == REPEAT_ONE) lines {
                    moveTo(7.5, 8.0); lineTo(9.0, 6.5); lineTo(9.0, 11.5)
                }
            }
            SHUFFLE -> {
                lines {
                    moveTo(3.0, 4.0); lineTo(5.0, 4.0); curveTo(8.0, 4.0, 10.0, 14.0, 13.0, 14.0); lineTo(15.0, 14.0)
                    moveTo(3.0, 14.0); lineTo(5.0, 14.0); curveTo(8.0, 14.0, 10.0, 4.0, 13.0, 4.0); lineTo(15.0, 4.0)
                }
                polygon(12.0, 1.0, 16.0, 4.0, 12.0, 7.0)
                polygon(12.0, 11.0, 16.0, 14.0, 12.0, 17.0)
            }
        }
        path
    }

    companion object {
        fun forMode(mode: PlaybackMode?): PlaybackIcon = when (mode) {
            PlaybackMode.SEQUENTIAL, null -> SEQUENTIAL
            PlaybackMode.REPEAT_ALL -> REPEAT_ALL
            PlaybackMode.REPEAT_ONE -> REPEAT_ONE
            PlaybackMode.SHUFFLE -> SHUFFLE
        }
    }
    override fun getIconWidth() = 18
    override fun getIconHeight() = 18
    override fun paintIcon(c: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = c.foreground
            g.fill(shape)
        } finally { g.dispose() }
    }
}
