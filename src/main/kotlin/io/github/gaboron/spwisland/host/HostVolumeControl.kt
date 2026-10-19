// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.swing.SwingUtilities

/** Updates SPW 1.19's player, Compose state and saved volume without the hotkey's toast. */
internal class HostVolumeControl(private val report: (Throwable) -> Unit) : AutoCloseable {
    private data class Binding(val config: Any, val read: Method, val update: Method,
                               val controller: Any, val change: Method, val storage: Any, val save: Method)
    private val binding by lazy { runCatching {
        val loader = WorkshopApi::class.java.classLoader
        val config = Class.forName("com.xuncorp.voxzen.util.AppConfig", false, loader)
        val controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
        val storage = Class.forName("com.xuncorp.voxzen.util.Spkv", false, loader)
        val floatType = Float::class.javaPrimitiveType!!
        val read = config.getMethod("getVolume")
        val update = config.getMethod("updateVolume", floatType)
        val change = controller.getMethod("changeVolume", floatType)
        val save = storage.getMethod("encode", String::class.java, floatType)
        check(read.returnType == floatType && listOf(update, change, save).all { it.returnType == Void.TYPE }) {
            "SPW 音量接口签名不兼容"
        }
        Binding(config.getField("INSTANCE").get(null), read, update,
            controller.getField("INSTANCE").get(null), change, storage.getField("INSTANCE").get(null), save)
    } }
    @Volatile private var closed = false
    private var failureReported = false

    fun adjust(delta: Double, completed: (Double?) -> Unit) = change(delta, false, completed)
    fun set(value: Double, completed: (Double?) -> Unit) = change(value, true, completed)

    private fun change(input: Double, absolute: Boolean, completed: (Double?) -> Unit) {
        val operation = {
            val value = if (closed || !input.isFinite()) null else try {
                val target = binding.getOrThrow()
                val current = target.read.invoke(target.config) as Float
                check(current.isFinite() && current in 0f..1f) { "SPW 返回了无效音量" }
                val next = if (absolute) input.coerceIn(0.0, 1.0).toFloat() else
                    (current + input.coerceIn(-1.0, 1.0).toFloat()).coerceIn(0f, 1f)
                if (next != current) {
                    target.update.invoke(target.config, next)
                    target.change.invoke(target.controller, next)
                    target.save.invoke(target.storage, "volume", next)
                }
                (target.read.invoke(target.config) as Float).toDouble().also {
                    check(it.isFinite() && it in 0.0..1.0) { "SPW 返回了无效音量" }
                }
            } catch (error: Exception) {
                if (!failureReported) {
                    failureReported = true
                    val cause = (error as? InvocationTargetException)?.targetException ?: error
                    report(IllegalStateException("当前 SPW 的词岛音量控制不可用：${cause.message}", cause))
                }
                null
            }
            completed(value)
        }
        if (SwingUtilities.isEventDispatchThread()) operation() else SwingUtilities.invokeLater(operation)
    }

    override fun close() { closed = true }
}
