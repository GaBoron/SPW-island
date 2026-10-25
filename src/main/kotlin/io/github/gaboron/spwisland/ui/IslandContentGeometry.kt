// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.HorizontalAnchor
import io.github.gaboron.spwisland.core.IslandAnchor
import io.github.gaboron.spwisland.core.VerticalAnchor

/** Shares continuous content coordinates while matching the native window's integer anchors. */
internal data class IslandContentGeometry(val x: Float, val y: Float, val width: Float, val lyricHeight: Float) {
    companion object {
        fun from(width: Int, height: Int, animatedWidth: Double, animatedHeight: Double,
                 anchor: IslandAnchor, notch: Boolean, cornerRoundness: Int, expansion: Double): IslandContentGeometry {
            // IslandPlacement.bounds uses integer half sizes. A floating half here would move
            // centered text by half a pixel whenever the rounded window size changes parity.
            val outerX = when (anchor.horizontal) {
                HorizontalAnchor.LEFT -> 0.0
                HorizontalAnchor.CENTER -> width / 2 - animatedWidth / 2
                HorizontalAnchor.RIGHT -> width - animatedWidth
            }
            val outerY = when (anchor.vertical) {
                VerticalAnchor.TOP -> 0.0
                VerticalAnchor.CENTER -> height / 2 - animatedHeight / 2
                VerticalAnchor.BOTTOM -> height - animatedHeight
            }
            val inset = IslandGeometry.frameInset(animatedHeight, notch, cornerRoundness)
            return IslandContentGeometry((outerX + inset).toFloat(), outerY.toFloat(),
                (animatedWidth - inset * 2).coerceAtLeast(1.0).toFloat(),
                (animatedHeight - expansion * IslandTextBlock.EXPANDED_HEIGHT).coerceAtLeast(1.0).toFloat())
        }
    }
}
