// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.ui.*
import org.jetbrains.skia.*
import io.github.gaboron.spwisland.platform.WindowsDesktopBackdrop

/** Native Canvas scene: no Swing painting, Graphics2D objects or per-primitive translation adapter. */
internal class IslandGpuScene : AutoCloseable {
    private val resources = IslandGpuResources()
    private val lyrics = IslandGpuLyrics(resources)
    private val sides = IslandGpuSides(resources)
    private val background = IslandGpuBackground(resources)
    private val controls = IslandGpuControls(resources, lyrics)
    val glassFailure get() = background.glassFailure
    val glassWaitingForCrop get() = background.waitingForCrop

    fun paint(canvas: Canvas, frame: IslandGpuFrame, hover: IslandHoverGeometry.Frame, scaleY: Double,
              backdrop: WindowsDesktopBackdrop.Sample? = null, context: DirectContext? = null) {
        if (frame.width <= 0 || frame.height <= 0) return
        val bounds = hover.shape.bounds2D
        resources.layer(canvas, Rect.makeLTRB(bounds.minX.toFloat() - 1, bounds.minY.toFloat() - 1,
            bounds.maxX.toFloat() + 1, bounds.maxY.toFloat() + 1), hover.opacity.toFloat()) {
            canvas.clipPath(resources.path(hover.shape), ClipMode.INTERSECT, true)
            val palette = IslandPalette.from(frame.settings, frame.snapshot.metadata.coverRgb)
            val liquid = background.draw(canvas, frame, hover, palette, backdrop)
            lyrics.adaptiveContrast.begin(if (liquid) background.materialShader else null,
                canvas.localToDeviceAsMatrix33, context)
            resources.layer(canvas, Rect.makeXYWH(0f, hover.contentOffsetY.toFloat(), frame.width.toFloat(), frame.height.toFloat()),
                hover.contentOpacity.toFloat()) {
                canvas.translate(0f, hover.contentOffsetY.toFloat())
                canvas.clipRect(Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), ClipMode.INTERSECT, true)
                content(canvas, frame, palette, scaleY)
            }
            if (liquid) background.drawHighlight(canvas, hover)
        }
    }

    private fun content(canvas: Canvas, frame: IslandGpuFrame, palette: IslandPalette, scaleY: Double) {
        val outerWidth = frame.animatedWidth.toFloat()
        val contentHeight = frame.animatedHeight.toFloat()
        val outerX = when (frame.anchor.horizontal) {
            HorizontalAnchor.LEFT -> 0f
            HorizontalAnchor.CENTER -> frame.width / 2f - outerWidth / 2
            HorizontalAnchor.RIGHT -> frame.width - outerWidth
        }
        val inset = IslandGeometry.frameInset(frame.animatedHeight, frame.settings.notch, frame.settings.cornerRoundness).toFloat()
        val contentWidth = (outerWidth - inset * 2).coerceAtLeast(1f)
        val contentX = outerX + inset
        val contentY = when (frame.anchor.vertical) {
            VerticalAnchor.TOP -> 0f
            VerticalAnchor.CENTER -> frame.height / 2f - contentHeight / 2
            VerticalAnchor.BOTTOM -> frame.height - contentHeight
        }
        val lyricHeight = (contentHeight - frame.expansion * IslandTextBlock.EXPANDED_HEIGHT).toFloat().coerceAtLeast(1f)
        val layout = IslandContentLayout.from(frame.lyrics.blocks.first().mainLineHeight, frame.settings.sideContent.showsSides)
        canvas.scoped {
            canvas.clipPath(resources.path(IslandGeometry.silhouette(frame.width, frame.height, frame.settings.notch,
                frame.settings.cornerRoundness)), ClipMode.INTERSECT, true)
            canvas.translate(contentX, contentY)
            sides.leading(canvas, frame.settings.sideContent, frame.snapshot.metadata.cover, frame.bands,
                layout.leadingCenterX, lyricHeight / 2, layout.leadingSize, palette.spectrum.rgb)
            lyrics.rows(canvas, frame, contentWidth, lyricHeight, layout.textInset)
            sides.trailing(canvas, frame.settings.sideContent, frame.snapshot, frame.bands, layout.statusCenterX(contentWidth),
                lyricHeight / 2, layout.leadingSize, palette.spectrum.rgb, frame.settings.performance.animateLayout)
        }
        controls.draw(canvas, frame, contentX, contentY, contentWidth, lyricHeight, layout.infoInset, scaleY)
    }

    override fun close() { controls.close(); lyrics.adaptiveContrast.close(); background.close(); resources.close() }
}
