// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Reads the host queue off the render thread; only confirmed modes reach the playback snapshot. */
internal class HostPlaybackMode(
    private val settings: () -> IslandSettings,
    private val hostActive: () -> Boolean,
    private val changed: (PlaybackMode?) -> Unit,
    private val report: (Throwable) -> Unit
) : AutoCloseable {
    private class Binding {
        private val loader = WorkshopApi::class.java.classLoader
        private val controllerType = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
        private val modeType = Class.forName("com.xuncorp.spc.core.queue.PlaybackQueue\$Mode", false, loader)
        private val stateType = Class.forName("com.xuncorp.spc.core.queue.PlaybackQueueState", false, loader)
        private val flowType = Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader)
        private val controller = controllerType.getField("INSTANCE").get(null)
        private val queue = controllerType.getMethod("getPlaybackQueueState").also {
            check(flowType.isAssignableFrom(it.returnType))
        }
        private val value = flowType.getMethod("getValue")
        // The queue-state accessor is obfuscated. Its unique return type is the contract.
        private val mode: Method = stateType.methods.single {
            it.parameterCount == 0 && it.returnType == modeType
        }
        private val change = controllerType.getMethod("changePlaybackMode", modeType).also {
            check(it.returnType == Void.TYPE)
        }
        private val modes = modeType.enumConstants.associateBy { (it as Enum<*>).name }
        private val names = mapOf(
            PlaybackMode.SEQUENTIAL to "CircleEnd", PlaybackMode.REPEAT_ALL to "Circle",
            PlaybackMode.REPEAT_ONE to "RepeatOne", PlaybackMode.SHUFFLE to "Random"
        )

        init { check(modes.keys.containsAll(names.values)) { "SPW 播放模式不兼容" } }

        fun read(): PlaybackMode {
            val state = value.invoke(queue.invoke(controller))
            check(stateType.isInstance(state)) { "SPW 播放队列状态不兼容" }
            val name = (mode.invoke(state) as Enum<*>).name
            return names.entries.single { it.value == name }.key
        }

        fun cycle() { change.invoke(controller, modes.getValue(names.getValue(read().next()))) }
    }

    private val worker = DiagnosticExecutors.scheduled(IslandLog.threads("SPW Island playback mode")).apply {
        removeOnCancelPolicy = true
    }
    private val binding by lazy { runCatching { Binding() } }
    @Volatile private var closed = false
    private var pending: ScheduledFuture<*>? = null
    private var failureLogged = false

    fun refresh() = submit { schedule(0) }

    fun cycle() = submit {
        if (!hostActive()) return@submit
        try {
            binding.getOrThrow().cycle()
            // The host applies queue commands asynchronously. Publish its acknowledgement on a later read.
            schedule(150)
        } catch (error: Exception) {
            failed(error)
            report(IllegalStateException("当前 SPW 的播放模式切换不可用", cause(error)))
        }
    }

    private fun submit(action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) action() } }
        catch (error: RejectedExecutionException) { if (!closed) throw error }
    }

    private fun schedule(delay: Long) {
        pending?.cancel(false)
        if (!closed) pending = worker.schedule(::sample, delay, TimeUnit.MILLISECONDS)
    }

    private fun sample() {
        if (closed) return
        val current = settings()
        if (!current.enabled) {
            changed(null)
            return
        }
        try {
            changed(if (hostActive()) binding.getOrThrow().read() else null)
        } catch (error: Exception) { failed(error) }
        if (!closed) schedule(current.performance.playbackModeIntervalMs)
    }

    private fun failed(error: Exception) {
        changed(null)
        if (!failureLogged) {
            failureLogged = true
            IslandLog.failure("host", "playback_mode.unavailable", cause(error))
        }
    }

    private fun cause(error: Exception): Throwable =
        if (error is InvocationTargetException) error.targetException else error

    override fun close() {
        closed = true
        worker.shutdownNow()
        changed(null)
    }
}
