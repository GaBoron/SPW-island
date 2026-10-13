// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import com.sun.jna.Platform
import io.github.gaboron.spwisland.platform.SkikoRuntime
import io.github.gaboron.spwisland.platform.WindowsGpuInput
import io.github.gaboron.spwisland.ui.IslandSurface
import io.github.gaboron.spwisland.ui.IslandHoverGeometry
import org.jetbrains.skiko.*
import javax.swing.SwingUtilities
import java.awt.Window

/** Owns the native GPU layer, bounded vector cache and input bridge for one overlay. */
internal class IslandGpuRenderer(private val owner: Window, private val surface: IslandSurface,
                                  private val report: (Throwable) -> Unit) : AutoCloseable {
    private var scene: IslandGpuScene? = null
    private var layer: SkiaLayer? = null
    private var input: IslandGpuInput? = null
    private var nativeInput: WindowsGpuInput? = null
    private var failed = System.getProperty("spw.island.renderer") == "software"
    var frames = 0L
        private set
    val active get() = layer != null
    val fallbackRendering get() = failed
    val rendererInfo get() = layer?.renderInfo ?: "Java2D 兼容绘制"

    fun install() {
        if (failed || layer != null || !owner.isDisplayable) return
        try {
            SkikoRuntime.load()
            val painter = IslandGpuScene().also { scene = it }
            val created = SkiaLayer(isVsyncEnabled = true, isVsyncFramelimitFallbackEnabled = true,
                frameBuffering = FrameBuffering.DOUBLE,
                renderApi = if (Platform.isWindows()) GraphicsApi.DIRECT3D else GraphicsApi.OPENGL)
            layer = created
            created.transparency = true
            created.isFocusable = false
            created.renderDelegate = SkikoRenderDelegate { canvas, _, _, _ ->
                try {
                    check(created.renderApi == GraphicsApi.DIRECT3D || created.renderApi == GraphicsApi.OPENGL) {
                        "GPU 后端不可用：${created.renderApi}"
                    }
                    canvas.clear(0)
                    val scale = owner.graphicsConfiguration.defaultTransform
                    canvas.scoped {
                        canvas.scale(scale.scaleX.toFloat(), scale.scaleY.toFloat())
                        canvas.translate(surface.island.x.toFloat(), surface.island.y.toFloat())
                        val frame = surface.island.gpuFrame()
                        painter.paint(canvas, frame, IslandHoverGeometry.frame(frame.width, frame.height,
                            frame.settings.notch, frame.settings.cornerRoundness, surface.hoverPose), scale.scaleY)
                    }
                    frames++
                } catch (error: Throwable) {
                    SwingUtilities.invokeLater { fallback(error) }
                }
            }
            surface.island.releaseBuffers(); surface.island.gpuDrawing = true
            surface.gpuRedraw = ::draw
            surface.add(created); surface.setComponentZOrder(created, 0)
            resize()
            if (Platform.isWindows()) nativeInput = WindowsGpuInput(owner)
            input = IslandGpuInput(nativeInput?.component ?: created.canvas, surface, ::draw)
        } catch (error: Throwable) { fallback(error) }
    }

    private fun resize() {
        layer?.let {
            if (it.x != 0 || it.y != 0 || it.width != surface.width || it.height != surface.height)
                it.setBounds(0, 0, surface.width, surface.height)
        }
    }

    fun draw() {
        val current = layer ?: return
        try {
            resize()
            if (owner.isDisplayable && current.isDisplayable) {
                val settings = surface.island.settings
                val scale = owner.graphicsConfiguration.defaultTransform
                nativeInput?.update(listOf(surface.island.bounds, settings.notch,
                    settings.cornerRoundness, scale.scaleX, scale.scaleY),
                    settings.clickThrough || settings.autoHideOnHover, surface::inputRegion)
                current.needRender()
            }
        } catch (error: Throwable) { fallback(error) }
    }

    private fun fallback(error: Throwable) {
        if (failed) return
        failed = true
        release()
        report(IllegalStateException("词岛 GPU 绘制不可用，已切换兼容绘制", error))
        surface.repaint()
    }

    fun release() {
        input?.close(); input = null
        nativeInput?.close(); nativeInput = null
        val previous = layer
        layer = null; surface.gpuRedraw = null; surface.island.gpuDrawing = false
        previous?.let { if (it.isDisplayable && it.parent != null) surface.remove(it)
            else { if (it.parent != null) surface.remove(it); it.detach() } }
        scene?.close(); scene = null
    }

    override fun close() = release()
}
