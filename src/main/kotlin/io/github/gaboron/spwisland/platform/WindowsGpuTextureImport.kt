// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import org.jetbrains.skia.*
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerProperties
import java.lang.reflect.Field

/** Version-bound Skiko 0.150.1 access; no native object layouts or COM pointer offsets. */
internal class WindowsGpuTextureImport(private val layer: SkiaLayer) {
    data class Adapter(val name: String, val priority: Int)
    private data class Access(val redrawer: Any, val lock: Any, val handler: Any, val context: Field,
                              val adapter: Adapter)
    private var access: Access? = null
    private var incompatible = false
    var failure: String? = null
        private set

    private fun find(): Access? {
        if (incompatible) return null
        return try {
            val redrawer = SkiaLayer::class.java.getMethod("getRedrawer\$skiko").invoke(layer) ?: return null
            access?.takeIf { it.redrawer === redrawer } ?: run {
                if (redrawer.javaClass.name != "org.jetbrains.skiko.redrawer.Direct3DRedrawer") {
                    failure = "当前绘制后端不是 Skiko Direct3D：${redrawer.javaClass.simpleName}"
                    return null
                }
                fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
                val type = redrawer.javaClass
                val lock = field(type, "drawLock").get(redrawer)
                val handler = field(type, "contextHandler").get(redrawer)
                val context = field(Class.forName("org.jetbrains.skiko.context.ContextHandler", false,
                    type.classLoader), "context")
                val name = type.getMethod("getAdapterName").invoke(redrawer) as String
                val properties = field(type, "properties").get(redrawer) as SkiaLayerProperties
                Access(redrawer, lock, handler, context, Adapter(name, properties.adapterPriority.ordinal))
                    .also { access = it; failure = null }
            }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            failure = "Skiko 接口访问失败：${cause.javaClass.simpleName}: ${cause.message}"
            incompatible = true
            null
        }
    }

    val adapter: Adapter? get() = find()?.adapter

    /** Call only while holding this bridge's draw lock. */
    val context: DirectContext? get() = find()?.let { it.context.get(it.handler) as? DirectContext }

    fun <T> locked(block: () -> T): T {
        val lock = find()?.lock ?: access?.lock ?: return block()
        return synchronized(lock, block)
    }

    fun image(resource: Long, width: Int, height: Int): Image? {
        val current = find() ?: error("Skiko Direct3D 导入接口不可用")
        return synchronized(current.lock) {
            // Stock Skiko creates its context on the first playback, after the first recording.
            val context = current.context.get(current.handler) as? DirectContext ?: return@synchronized null
            check(!context.isClosed) { "Skiko Direct3D 上下文已释放" }
            BackendRenderTarget.makeDirect3D(width, height, resource, 87, 1, 1).use { target ->
                checkNotNull(Surface.makeFromBackendRenderTarget(context, target, SurfaceOrigin.TOP_LEFT,
                    SurfaceColorFormat.BGRA_8888, null)) { "Direct3D 共享纹理无法包装为 Skia 表面" }.use { surface ->
                    // A wrapped non-texture RT snapshot is a GPU copy into a Skia-owned image.
                    // Producer allocations are immutable even after this asynchronous copy is queued.
                    surface.makeImageSnapshot()
                }
            }
        }
    }

    fun release(image: Image) = locked { image.close() }
}
