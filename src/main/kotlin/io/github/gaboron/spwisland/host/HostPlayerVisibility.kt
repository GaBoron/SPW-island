// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.spwisland.host

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.core.performance
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
            IslandLog.failure("host", "player_visibility.failed", error)
            detach()
            target = null
            unavailable(if (error is PluginPermissionDeniedException)
                "未授权 Hook 权限；播放页自动隐藏不可用。" else "无法识别 SPW 播放页；词岛保持显示。")
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
            handle = WorkshopApi.hookRegistrar.afterComposable(site.owner().replace('/', '.'), site.name(), caller,
                parameterTypes = emptyList()) { Observe() }
        }

        @Composable
        private fun Observe() {
            val composer = currentComposer
            val focused = LocalWindowInfo.current.isWindowFocused
            val state = runCatching {
                val host = locals.firstNotNullOfOrNull { local ->
                    @Suppress("UNCHECKED_CAST")
                    val value = composer.consume(local.get(null) as CompositionLocal<Any?>)
                    value?.takeIf(navigator::isInstance)
                } ?: error("SPW 导航上下文不可用")
                expanded.invoke(playerState.get(host)) as Boolean
            }
            // Observe actual changes, rather than querying permissions on every host recomposition.
            LaunchedEffect(token, state.getOrNull(), state.exceptionOrNull()?.javaClass, focused) {
                submit {
                    if (valid(token)) {
                        state.onSuccess { publish(it && focused) }.onFailure { failed(token, it) }
                    }
                }
            }
            DisposableEffect(token) {
                onDispose { submit { if (generation.get() == token) publish(false) } }
            }
        }

        override fun close() {
            val previous = handle
            handle = null
            previous?.close()
        }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        detach()
        target = null
    }
}
