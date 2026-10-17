// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import io.github.gaboron.spwisland.core.GlassStatus
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Small SDR desktop crops only. WinRT, D3D11 and capture exclusion live on the DLL's worker. */
internal class WindowsDesktopBackdrop(private val report: (Throwable) -> Unit,
                                      private val gpu: WindowsGpuTextureImport?,
                                      private val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
                                      private val redraw: () -> Unit) : AutoCloseable {
    data class Pixels(val serial: Long, val data: Data?, val texture: Image?, val x: Int, val y: Int,
                      val width: Int, val height: Int)
    data class Sample(val pixels: Pixels, val islandX: Float, val islandY: Float, val scaleX: Float, val scaleY: Float)

    private interface Api : Library {
        fun GlassCreate(): Pointer?
        fun GlassCreateGpuWithPreference(adapter: WString, priority: Int): Pointer?
        fun GlassUseCpu(capture: Pointer)
        fun GlassAcquireGpu(capture: Pointer, metadata: LongArray): Pointer?
        fun GlassReleaseGpu(lease: Pointer)
        fun GlassRequest(capture: Pointer, owner: Pointer, input: Pointer?, x: Int, y: Int, width: Int, height: Int)
        fun GlassRead(capture: Pointer, previous: Long, pixels: Pointer?, capacity: Int, metadata: LongArray): Int
        fun GlassWait(capture: Pointer, timeout: Int): Int
        fun GlassError(capture: Pointer, message: ByteArray, capacity: Int)
        fun GlassGpuError(capture: Pointer, message: ByteArray, capacity: Int)
        fun GlassStop(capture: Pointer)
        fun GlassDestroy(capture: Pointer)
    }

    private var api: Api? = null
    @Volatile private var capture: Pointer? = null
    private var image: Pixels? = null
    private var listener: Thread? = null
    private val redrawPending = AtomicBoolean(false)
    private val metadata = LongArray(10)
    private var rateStarted = System.nanoTime()
    private var receivedFrames = 0
    private var updateRate = 0
    private var unavailable = false
    private var gpuFallback: String? = null
    private var nativeGpuReasonRead = false
    var status = GlassStatus("等待桌面图像")
        private set

    fun request(owner: Window, input: Window?, x: Int, y: Int, width: Int, height: Int,
                allowHidden: Boolean = false) {
        if (unavailable || !owner.isDisplayable || !allowHidden && !owner.isVisible) return
        try {
            val native = api ?: load().also { api = it }
            val current = capture ?: run {
                val adapter = gpu?.adapter
                if (gpu != null && adapter == null && gpu.failure == null) {
                    status = GlassStatus("等待绘制初始化", "正在连接 Skiko Direct3D。")
                    return
                }
                gpuFallback = gpu?.failure
                checkNotNull(adapter?.let { native.GlassCreateGpuWithPreference(WString(it.name), it.priority) }
                    ?: native.GlassCreate()) {
                    "无法启动桌面捕获线程"
                }.also { capture = it; listen(native, it) }
            }
            native.GlassRequest(current, Native.getWindowPointer(owner),
                input?.takeIf { it.isDisplayable }?.let(Native::getWindowPointer), x, y, width, height)
        } catch (error: Throwable) { fail(error) }
    }

    fun sample(islandX: Int, islandY: Int, scaleX: Float, scaleY: Float): Sample? {
        val native = api ?: return null
        val current = capture ?: return null
        if (unavailable) return null
        return try {
            // GPU frames borrow an immutable native resource; the fallback writes into Skia Data.
            var result = native.GlassRead(current, image?.serial ?: 0L, null, 0, metadata)
            if (result == 4) {
                if (gpuFallback != null) return null
                val lease = native.GlassAcquireGpu(current, metadata) ?: return null
                try {
                    val next = checkNotNull(gpu).image(metadata[8], metadata[3].toInt(), metadata[4].toInt())
                    if (next != null) {
                        val previous = image
                        image = Pixels(metadata[0], null, next, metadata[1].toInt(), metadata[2].toInt(),
                            metadata[3].toInt(), metadata[4].toInt())
                        dispose(previous); receivedFrames++
                    }
                } catch (error: Throwable) {
                    gpuFallback = error.message ?: error.javaClass.simpleName
                    native.GlassUseCpu(current)
                    return null
                } finally { native.GlassReleaseGpu(lease) }
            }
            for (attempt in 0 until 2) {
                if (result != 2) break
                val size = Math.multiplyExact(Math.multiplyExact(metadata[3].toInt(), metadata[4].toInt()), 4)
                check(size in 1..64_000_000) { "桌面裁片尺寸无效" }
                val next = Data.makeUninitialized(size)
                try {
                    result = native.GlassRead(current, image?.serial ?: 0L, Pointer(next.writableData()), size, metadata)
                    if (result == 1) {
                        val previous = image
                        image = Pixels(metadata[0], next, null, metadata[1].toInt(), metadata[2].toInt(),
                            metadata[3].toInt(), metadata[4].toInt())
                        dispose(previous)
                        receivedFrames++
                    }
                } finally { if (image?.data !== next) next.close() }
            }
            if (result < 0) {
                val message = ByteArray(1024)
                native.GlassError(current, message, message.size)
                error(message.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                    .ifBlank { "桌面捕获已中断" })
            }
            if (result == 3) return null // Session changed; never paint the previous monitor's image.
            val now = System.nanoTime()
            if (now - rateStarted >= 1_000_000_000L) {
                updateRate = (receivedFrames * 1_000_000_000L / (now - rateStarted)).toInt()
                receivedFrames = 0; rateStarted = now
            }
            image?.let {
                if (gpu != null && it.texture == null && !nativeGpuReasonRead) {
                    val reason = ByteArray(1024)
                    native.GlassGpuError(current, reason, reason.size)
                    val message = reason.takeWhile { byte -> byte != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                    if (gpuFallback == null) gpuFallback = message.ifBlank { "未取得 GPU 共享通路，原生端未报告原因" }
                    nativeGpuReasonRead = true
                    System.err.println("[SPW Island] 液态玻璃 CPU 中转：$gpuFallback")
                }
                val transport = if (it.texture != null) "GPU 共享纹理" else "CPU 中转"
                status = GlassStatus(transport, gpuFallback.orEmpty(), gpuFallback != null,
                    updateRate, (metadata[7] + 500) / 1000)
                Sample(it, metadata[5] + islandX * scaleX, metadata[6] + islandY * scaleY, scaleX, scaleY)
            }
        } catch (error: Throwable) { fail(error); null }
    }

    private fun dispose(pixels: Pixels?) {
        pixels?.data?.close()
        pixels?.texture?.let { checkNotNull(gpu).release(it) }
    }

    private fun listen(native: Api, current: Pointer) {
        listener = Thread({
            try {
                while (capture === current) {
                    val result = native.GlassWait(current, 250)
                    if (capture !== current) break
                    if (result != 0 && redrawPending.compareAndSet(false, true)) dispatch {
                        redrawPending.set(false)
                        if (capture === current) redraw()
                    }
                    if (result < 0) break
                }
            } catch (error: Throwable) {
                dispatch { if (capture === current) { fail(error); redraw() } }
            }
        }, "SPW Island backdrop frames").apply { isDaemon = true; start() }
    }

    private fun fail(error: Throwable) {
        unavailable = true
        close()
        status = GlassStatus("使用经典背景", error.message ?: error.javaClass.simpleName, warning = true)
        report(IllegalStateException("液态玻璃：${status.summary}，${status.detail}", error))
    }

    override fun close() {
        val previous = capture
        val native = api
        val previousListener = listener
        capture = null; listener = null
        nativeGpuReasonRead = false; gpuFallback = null
        dispose(image); image = null
        if (previous != null && native != null) {
            native.GlassStop(previous)
            // WinRT session shutdown and a pending system access request must never block the EDT.
            CompletableFuture.runAsync {
                previousListener?.join()
                native.GlassDestroy(previous)
            }
        }
    }

    private fun load(): Api {
        val resource = checkNotNull(javaClass.getResource("/native/spw-island-glass.dll")) { "缺少 Windows 桌面捕获资源" }
        check(resource.protocol == "file") { "请使用插件 .spmod 安装桌面捕获资源" }
        return Native.load(Path.of(resource.toURI()).toString(), Api::class.java)
    }
}
