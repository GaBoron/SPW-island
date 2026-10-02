// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import javax.swing.JPanel

/** Stable translucent backing surface; animation changes the child silhouette, not the native bitmap. */
class IslandSurface(val island: IslandPanel) : JPanel(null) {
    internal var hoverPose = IslandHoverMotion.Pose.SHOWN
    internal val renderCapacity = Dimension()
    init { isOpaque = false; isDoubleBuffered = false; island.isDoubleBuffered = false; add(island) }
    override fun paintChildren(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(island.x.toDouble(), island.y.toDouble())
            island.paintPresentation(g, IslandHoverGeometry.frame(island.width, island.height,
                island.settings.notch, island.settings.cornerRoundness, hoverPose))
        } finally { g.dispose() }
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, width, height)
        } finally { g.dispose() }
    }
    fun inputRegion(): Shape {
        val shape = IslandGeometry.silhouette(island.width, island.height, island.settings.notch,
            island.settings.cornerRoundness)
        // The region includes the antialiased outer edge instead of clipping it to a hard pixel boundary.
        val padded = Area(shape).apply { add(Area(BasicStroke(2f).createStrokedShape(shape))) }
        return AffineTransform.getTranslateInstance(island.x.toDouble(), island.y.toDouble()).createTransformedShape(padded)
    }
}
