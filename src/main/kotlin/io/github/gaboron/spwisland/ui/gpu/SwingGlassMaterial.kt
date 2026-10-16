// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.core.PerformanceProfile
import io.github.gaboron.spwisland.platform.SkikoRuntime
import io.github.gaboron.spwisland.platform.WindowsDesktopBackdrop
import io.github.gaboron.spwisland.platform.WindowsGpuInput
import io.github.gaboron.spwisland.platform.WindowsGpuTextureImport
import io.github.gaboron.spwisland.ui.IslandFrameClock
import io.github.gaboron.spwisland.ui.LiquidGlassSurface
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skiko.FrameBuffering
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Point
import java.awt.Window
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JLayeredPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.ceil

/** Same Direct3D/shared-texture presentation and display cadence as the island; no glass readback to Swing. */
internal class SwingGlassMaterial(private val owner: Window, private val surface: LiquidGlassSurface,
                                  private val changed: () -> Unit,
                                  private val report: (Throwable) -> Unit) : AutoCloseable {
    data class Request(val width: Int, val height: Int, val arc: Double, val tint: Int,
                       val scaleX: Float, val scaleY: Float, val screenX: Int, val screenY: Int)
    private val clock = IslandFrameClock()
    private val ticker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "island-dialog-frames").apply { isDaemon = true }
    }
    private var tick: ScheduledFuture<*>? = null
    private var layer: SkiaLayer? = null
    private var bridge: WindowsGpuTextureImport? = null
    private var backdrop: WindowsDesktopBackdrop? = null
    private var inputPeer: WindowsGpuInput? = null
    private var input: SwingGlassInput? = null
    private var content: SwingGlassContent? = null
    private var optics: SwingGlassOptics? = null
    private var resources: IslandGpuResources? = null
    private var request: Request? = null
    private var geometry: Triple<Int, Int, Double>? = null
    private var shape: RoundRectangle2D? = null
    private var drawing = false
    private var firstGlass = false
    private var failed = false
    private var failureQueued = false
    private var closed = false
    val ready: Boolean get() = firstGlass || failed
    val active: Boolean get() = layer != null && !failed && !closed

    fun prepare(next: Request) {
        if (closed || failed) return
        val previous = request
        request = next
        try {
            if (layer == null) install()
            if (previous?.width != next.width || previous.height != next.height ||
                previous.scaleX != next.scaleX || previous.scaleY != next.scaleY) content?.invalidate()
            if (previous != next) draw()
        } catch (error: Throwable) { failLater(error) }
    }

    private fun install() {
        SkikoRuntime.load()
        resources = IslandGpuResources()
        optics = SwingGlassOptics()
        content = SwingGlassContent(surface::paintGpuContent)
        val created = SkiaLayer(isVsyncEnabled = true, isVsyncFramelimitFallbackEnabled = true,
            frameBuffering = FrameBuffering.DOUBLE, renderApi = GraphicsApi.DIRECT3D)
        layer = created
        created.transparency = true
        created.isFocusable = false
        val gpu = WindowsGpuTextureImport(created).also { bridge = it }
        created.renderDelegate = SkikoRenderDelegate { canvas, _, _, _ ->
            drawing = true
            try {
                check(created.renderApi == GraphicsApi.DIRECT3D) { "辅助窗口 Direct3D 绘制不可用" }
                gpu.locked { paint(canvas) }
            } catch (error: Throwable) { failLater(error) }
            finally { drawing = false }
        }
        val parent = (owner as RootPaneContainer).layeredPane
        parent.add(created, JLayeredPane.DRAG_LAYER)
        resize()
        // Stock Skiko creates the D3D context during playback. Warm it while the owner is hidden.
        created.renderImmediately()
        backdrop = WindowsDesktopBackdrop(::failLater, gpu) { draw() }
        inputPeer = WindowsGpuInput(owner)
        input = SwingGlassInput(checkNotNull(inputPeer).component, surface, owner, ::invalidateContent)
    }

    private fun resize() {
        val current = layer ?: return
        val next = request ?: return
        val parent = current.parent ?: return
        val p = SwingUtilities.convertPoint(surface, Point(), parent)
        if (current.x != p.x || current.y != p.y || current.width != next.width || current.height != next.height)
            current.setBounds(p.x, p.y, next.width, next.height)
    }

    fun invalidateContent() {
        if (!active) return
        content?.invalidate()
        if (!drawing) draw()
    }

    fun draw() {
        if (!active || drawing) return
        val current = layer ?: return
        val next = request ?: return
        try {
            resize()
            inputPeer?.update(listOf(next.width, next.height, next.arc, owner.graphicsConfiguration), false) {
                RoundRectangle2D.Double(0.0, 0.0, next.width.toDouble(), next.height.toDouble(), next.arc, next.arc)
            }
            val p = SwingUtilities.convertPoint(surface, Point(), owner)
            val padding = IslandGlassShaders.CAPTURE_PADDING
            val px = ceil(padding * next.scaleX).toInt()
            val py = ceil(padding * next.scaleY).toInt()
            backdrop?.request(owner, inputPeer?.captureWindow,
                (p.x * next.scaleX).toInt() - px, (p.y * next.scaleY).toInt() - py,
                ceil(next.width * next.scaleX).toInt() + px * 2,
                ceil(next.height * next.scaleY).toInt() + py * 2, allowHidden = true)
            if (owner.isVisible) current.needRender() else current.renderImmediately()
        } catch (error: Throwable) { failLater(error) }
    }

    private fun paint(canvas: Canvas) {
        val next = request ?: return
        val cache = checkNotNull(resources)
        val foreground = checkNotNull(content)
        foreground.update(next.width, next.height, next.scaleX, next.scaleY)
        val nextGeometry = Triple(next.width, next.height, next.arc)
        if (geometry != nextGeometry) {
            geometry = nextGeometry
            shape = RoundRectangle2D.Double(.5, .5, next.width - 1.0, next.height - 1.0, next.arc, next.arc)
        }
        val path = cache.path(checkNotNull(shape))
        val p = SwingUtilities.convertPoint(surface, Point(), owner)
        val sample = backdrop?.sample(p.x, p.y, next.scaleX, next.scaleY)
        canvas.clear(0)
        var glass = false
        canvas.scoped {
            canvas.scale(next.scaleX, next.scaleY)
            // Retain the previous shader when the crop/monitor is changing.
            glass = checkNotNull(optics).draw(canvas, path, sample, next.arc, next.tint)
            if (!glass) canvas.drawPath(path, cache.fill(next.tint))
            canvas.drawPath(path, cache.stroke(surface.surfaceBorder.rgb, 1f))
            canvas.scoped {
                canvas.clipPath(path, org.jetbrains.skia.ClipMode.INTERSECT, true)
                foreground.image?.let { image -> canvas.drawImageRect(image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(next.width.toFloat(), next.height.toFloat()), SamplingMode.LINEAR, null, true) }
            }
        }
        if (glass && !firstGlass) {
            firstGlass = true
            SwingUtilities.invokeLater { if (!closed && !failed) changed() }
        }
    }

    fun windowShown() {
        if (!active || !owner.isVisible || tick != null) return
        nextFrame()
    }

    private fun nextFrame() {
        tick = null
        if (!active || !owner.isVisible) return
        clock.frameStarted(System.nanoTime())
        // Also observes live DPI and geometry changes without rebuilding the Swing content each frame.
        surface.refreshGpuFrame()
        draw()
        val interval = clock.interval(owner.graphicsConfiguration.device, PerformanceProfile.STANDARD)
        tick = ticker.schedule({ SwingUtilities.invokeLater { if (!closed) nextFrame() } },
            clock.delay(interval), TimeUnit.NANOSECONDS)
    }

    fun initializationTimedOut() {
        if (!ready && !closed) fail(IllegalStateException("辅助窗口液态玻璃初始化超时，本次使用经典背景"))
    }

    private fun failLater(error: Throwable) {
        if (closed || failed || failureQueued) return
        failureQueued = true
        SwingUtilities.invokeLater { if (!closed) fail(error) }
    }

    private fun fail(error: Throwable) {
        if (closed || failed) return
        failed = true
        release()
        report(IllegalStateException("辅助窗口 GPU 绘制不可用，使用经典背景", error))
        changed()
        surface.repaint()
    }

    private fun release() {
        tick?.cancel(false); tick = null
        val releaseScene = {
            backdrop?.close(); backdrop = null
            optics?.close(); optics = null
            content?.close(); content = null
            resources?.close(); resources = null
        }
        bridge?.locked(releaseScene) ?: releaseScene()
        input?.close(); input = null
        inputPeer?.close(); inputPeer = null
        val previous = layer
        layer = null
        previous?.let {
            if (it.isDisplayable && it.parent != null) it.parent.remove(it)
            else { it.parent?.remove(it); it.detach() }
        }
        bridge = null
    }

    override fun close() {
        if (closed) return
        closed = true
        ticker.shutdownNow()
        release()
    }
}
