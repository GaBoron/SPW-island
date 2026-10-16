// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import com.sun.jna.Pointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.SwingUtilities
import kotlin.math.ceil

/** Swing controls are uploaded only after a UI change, independently of desktop/GPU frame cadence. */
internal class SwingGlassContent(private val paint: (Graphics2D) -> Unit) : AutoCloseable {
    private var dirty = true
    private var size: Any? = null
    var image: Image? = null
        private set

    fun invalidate() { dirty = true }

    fun update(width: Int, height: Int, scaleX: Float, scaleY: Float) {
        check(SwingUtilities.isEventDispatchThread())
        val key = listOf(width, height, scaleX, scaleY)
        if (!dirty && size == key) return
        val w = ceil(width * scaleX).toInt().coerceAtLeast(1)
        val h = ceil(height * scaleY).toInt().coerceAtLeast(1)
        val pixels = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        pixels.createGraphics().let { g ->
            try {
                g.composite = AlphaComposite.SrcOver
                g.scale(scaleX.toDouble(), scaleY.toDouble())
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                paint(g)
            } finally { g.dispose() }
        }
        val argb = (pixels.raster.dataBuffer as DataBufferInt).data
        val next = Data.makeUninitialized(Math.multiplyExact(argb.size, 4)).use { bytes ->
            Pointer(bytes.writableData()).write(0, argb, 0, argb.size)
            Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, w * 4)
        }
        image?.close(); image = next
        size = key; dirty = false
    }

    override fun close() { image?.close(); image = null; dirty = true; size = null }
}
