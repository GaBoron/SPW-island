// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.core.performance
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import javax.swing.SwingUtilities

/** Optional host observation. Discovery and permission checks never run on the render path. */
internal class HostPlayerVisibility(
    private val settings: () -> IslandSettings,
    private val changed: () -> Unit
) : AutoCloseable {
    private val worker = DiagnosticExecutors.scheduled(IslandLog.threads("SPW Island player visibility"))
    private val generation = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var observation: Observation? = null
    @Volatile var hidden = false
        private set
    private var pending: ScheduledFuture<*>? = null
    private var attempted = false
    private var notified: String? = null
    // Only member descriptors are reused; no persistent cache or host-state serialization.
    private var target: HostPlayerTarget? = null

    fun refresh() {
        if (closed) return
        val disabled = !wanted()
        if (disabled) detach()
        submit {
            pending?.cancel(false)
            if (disabled) { attempted = false; notified = null }
            reconcile()
        }
    }

    private fun wanted(): Boolean = !closed && settings().let { it.enabled && it.hideSpwPlayerForeground }

    private fun submit(action: () -> Unit) {
        try { worker.execute(action) }
        catch (_: RejectedExecutionException) { if (!closed) throw IllegalStateException("播放页观察任务已停止") }
    }

    /** Serialized by the worker. Turning the setting off leaves no periodic permission task. */
    private fun reconcile() {
        try { reconcileState() }
        catch (error: Exception) {
            if (!closed) {
                detach()
                IslandLog.failure("host", "player_visibility.permission_failed", error)
                unavailable("无法读取播放页观察权限；词岛保持显示。")
            }
        } finally {
            if (!closed && wanted()) pending = worker.schedule(::reconcile,
                settings().performance.visibilityCheckIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun reconcileState() {
        if (closed) return
        if (!wanted()) {
            detach()
            attempted = false
            notified = null
            return
        }
        val granted = WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)
        if (!granted) {
            detach()
            attempted = false
            unavailable("未授权 Hook 权限；播放页自动隐藏不可用。")
        } else if (!attempted) {
            attempted = true
            val token = generation.incrementAndGet()
            try {
                val host = WorkshopApi.instance.javaClass
                val plan = target ?: HostPlayerTarget.discover(host).also { target = it }
                val prepared = Observation(plan, host.classLoader, token)
                SwingUtilities.invokeLater {
                    try {
                        if (valid(token)) {
                            observation = prepared
                            prepared.install()
                            if (!valid(token)) prepared.close()
                            else submit {
                                if (valid(token)) {
                                    notified = null
                                    IslandLog.state("host", "player_visibility", "active")
                                }
                            }
                        }
                    } catch (error: Throwable) { failed(token, error) }
                }
            } catch (error: Exception) { failed(token, error) }
        }
    }

    private fun valid(token: Long): Boolean = generation.get() == token && wanted() &&
        WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)

    private fun publish(value: Boolean) {
        if (hidden != value) {
            hidden = value
            IslandLog.state("window", "host_player_foreground", value.toString())
            changed()
        }
    }

    private fun unavailable(message: String) {
        if (notified != message) {
            notified = message
            IslandLog.warn("host", "player_visibility.unavailable", mapOf("reason" to message))
            IslandLog.attempt("host", "player_visibility.notify_failed") {
                WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
            }
        }
    }

    private fun failed(token: Long, error: Throwable) {
        submit {
            if (closed || generation.get() != token) return@submit
            val cause = if (error is InvocationTargetException) error.targetException else error
            IslandLog.failure("host", "player_visibility.failed", cause)
            detach()
            target = null
            unavailable(if (cause is PluginPermissionDeniedException)
                "未授权 Hook 权限；播放页自动隐藏不可用。" else "无法接入 SPW 播放页；词岛保持显示。")
        }
    }

    private fun detach() {
        generation.incrementAndGet()
        val previous = observation
        observation = null
        publish(false)
        if (previous != null) SwingUtilities.invokeLater {
            IslandLog.attempt("host", "player_visibility.release_failed") { previous.close() }
        }
    }

    private inner class Observation(plan: HostPlayerTarget, private val loader: ClassLoader,
                                    private val token: Long) : AutoCloseable {
        private val site = plan.composition()
        private val caller = plan.caller().replace('/', '.')
        private val navigator = Class.forName(plan.navigator().replace('/', '.'), false, loader)
        private fun field(member: HostPlayerTarget.Member): Field =
            Class.forName(member.owner().replace('/', '.'), false, loader).getDeclaredField(member.name()).also {
                check(it.type.descriptorString() == member.descriptor() && it.trySetAccessible())
            }
        private val locals = plan.locals().map(::field)
        private val playerState = field(plan.playerState())
        private val expanded = plan.expanded().let { member ->
            val signature = MethodType.fromMethodDescriptorString(member.descriptor(), loader)
            Class.forName(member.owner().replace('/', '.'), false, loader)
                .getDeclaredMethod(member.name(), *signature.parameterArray()).also {
                    check(it.returnType == signature.returnType() && it.trySetAccessible())
                }
        }
        private var handle: AutoCloseable? = null

        fun install() {
            // Hook lambdas, Composer and all Kotlin/Compose helpers must resolve in the host runtime.
            // The island's renderer/font picker deliberately keep their private runtime.
            val bridgeLoader = HostObservationLoader(loader, HostPlayerVisibility::class.java.classLoader)
            val bridge = bridgeLoader.loadClass(BRIDGE_CLASS)
            val constructor = bridge.getConstructor(Any::class.java, String::class.java, String::class.java,
                String::class.java, Class::class.java, Field::class.java, java.lang.reflect.Method::class.java,
                List::class.java, Consumer::class.java, Consumer::class.java)
            val changed = Consumer<Boolean> { value -> submit { if (valid(token)) publish(value) } }
            val failure = Consumer<Throwable> { error -> failed(token, error) }
            handle = constructor.newInstance(WorkshopApi.hookRegistrar, site.owner().replace('/', '.'), site.name(),
                caller, navigator, playerState, expanded, locals, changed, failure) as AutoCloseable
        }

        override fun close() {
            val previous = handle
            handle = null
            previous?.close()
        }
    }

    /** Define only the packaged observer bridge; every dependency is delegated to the host first. */
    private class HostObservationLoader(parent: ClassLoader, private val source: ClassLoader) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            if (name != BRIDGE_CLASS && !name.startsWith(BRIDGE_CLASS + "$")) throw ClassNotFoundException(name)
            val bytes = source.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readAllBytes() }
                ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private companion object {
        const val BRIDGE_CLASS = "io.github.gaboron.spwisland.host.observation.HostPlayerObservation"
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        detach()
        target = null
    }
}
