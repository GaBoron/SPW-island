// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.JFrame
import kotlin.math.ceil

/** Complete the alpha frame off-screen before a single Src blit to the Linux drawable. */
internal class BufferedIslandWindow(configuration: GraphicsConfiguration) : JFrame(configuration) {
    var presentedFrames = 0L
        private set
    private var frame: BufferedImage? = null
    var gpuRendering = false
    fun releaseFrame() { frame?.flush(); frame = null }
    override fun update(graphics: Graphics) = paint(graphics)
    override fun paint(graphics: Graphics) {
        if (!Platform.isLinux() || gpuRendering) { super.paint(graphics); return }
        if (width <= 0 || height <= 0) return
        val target = graphics as Graphics2D
        val sx = target.transform.scaleX
        val sy = target.transform.scaleY
        val w = ceil(width * sx).toInt().coerceAtLeast(1)
        val h = ceil(height * sy).toInt().coerceAtLeast(1)
        if (frame?.width != w || frame?.height != h) {
            frame = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE).apply { accelerationPriority = 0f }
        }
        val pixels = frame!!
        val buffer = pixels.createGraphics()
        try {
            buffer.composite = AlphaComposite.Clear
            buffer.fillRect(0, 0, w, h)
            buffer.composite = AlphaComposite.SrcOver
            buffer.scale(sx, sy)
            super.paint(buffer)
        } finally { buffer.dispose() }
        val presentation = target.create() as Graphics2D
        try {
            presentation.composite = AlphaComposite.Src
            presentation.drawImage(pixels, 0, 0, width, height, null)
            presentedFrames++
        } finally { presentation.dispose() }
    }
}
