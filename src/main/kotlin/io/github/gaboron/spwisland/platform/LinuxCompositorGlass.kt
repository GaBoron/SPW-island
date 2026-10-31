// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.google.gson.Gson
import com.sun.jna.Native
import io.github.gaboron.spwisland.core.GlassStatus
import io.github.gaboron.spwisland.core.IslandBackgroundStyle
import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.core.performance
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.awt.Shape
import java.awt.Window
import java.awt.geom.PathIterator
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

/** Sends geometry, never desktop pixels. All process and D-Bus work stays off the EDT. */
internal class LinuxCompositorGlass : AutoCloseable {
    private val gnome = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').any { it.equals("GNOME", true) }
    private val kde = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').any { it.equals("KDE", true) }
    private val compositor = if (kde) "KWin" else "GNOME"
    private val gson = Gson()
    private val worker = DiagnosticExecutors.single {
        Thread(it, "SPW Island compositor glass").apply {
            isDaemon = true
            uncaughtExceptionHandler = IslandLog.uncaught("LinuxCompositorGlass")
        }
    }
    private var pending: String? = null
    private var running = false
    private var enabled = false
    private var closed = false
    private var geometryKey: Any? = null
    private var geometry: List<DoubleArray> = emptyList()
    private var nextUpdate = 0L
    private var lastReply: String? = null
    @Volatile private var helper: Process? = null
    private var input: BufferedReader? = null
    private var output: BufferedWriter? = null
    @Volatile private var readyAt = 0L
    @Volatile private var detail = when {
        kde && System.getenv("XDG_SESSION_TYPE") != "wayland" -> "KWin 液态玻璃当前适配 Plasma 6 的 Wayland 会话。"
        kde -> "请从插件设置安装 / 修复 Linux 液态玻璃支持；需要匹配本机 KWin 的原生组件。"
        gnome -> "首次安装或旧入口迁移后需重新登录一次；此后渲染模块随插件自动更新。"
        else -> "当前提供 GNOME 与 KDE Plasma 合成器适配。"
    }

    val supported: Boolean get() = gnome || kde && System.getenv("XDG_SESSION_TYPE") == "wayland"
    val active: Boolean get() = enabled && System.nanoTime() - readyAt < 1_500_000_000L && readyAt != 0L
    val status: GlassStatus get() = if (active) GlassStatus("$compositor 合成器", detail)
        else GlassStatus("经典背景", detail)

    @Synchronized fun update(window: Window, settings: IslandSettings, shape: Shape, key: Any, opacity: Double) {
        if (closed) return
        val use = supported && settings.enabled && settings.backgroundStyle == IslandBackgroundStyle.LIQUID_GLASS &&
            settings.performance.renderLiquidGlass && window.isDisplayable
        if (!use) { deactivate(); return }
        enabled = true
        val now = System.nanoTime()
        val nextKey = listOf(key, window.width, window.height)
        if (geometryKey == nextKey && now < nextUpdate) return
        nextUpdate = now + settings.performance.diagnosticSampleIntervalNs
        if (geometryKey != nextKey) {
            geometry = contour(shape, window.width, window.height)
            geometryKey = nextKey
        }
        if (geometry.size !in 3..256) {
            detail = "当前轮廓超出合成器适配范围。"
            deactivate()
            return
        }
        val payload = mutableMapOf<String, Any>("pid" to ProcessHandle.current().pid(), "title" to window.name,
            "points" to geometry, "opacity" to opacity,
            "maskAlpha" to (maxOf(1, settings.opacity / 2) * 255 / 100) / 255.0)
        if (kde) payload["windowId"] = Native.getWindowID(window)
        pending = gson.toJson(payload)
        dispatch()
    }

    /** Hidden and low-performance windows leave no compositor animation or helper running. */
    @Synchronized fun deactivate() {
        if (!enabled && (pending == "STOP" || pending == null && helper == null)) return
        enabled = false
        readyAt = 0L
        geometryKey = null
        pending = "STOP"
        if (!closed) dispatch()
    }

    private fun dispatch() {
        if (running) return
        running = true
        worker.execute {
            try {
                while (true) {
                    val message = synchronized(this) { pending.also { pending = null } } ?: break
                    if (message == "STOP") { disconnect(); continue }
                    try {
                        if (helper?.isAlive != true) {
                            disconnect()
                            val process = LinuxHelper.startGlass()
                            helper = process
                            input = process.inputStream.bufferedReader()
                            output = process.outputStream.bufferedWriter()
                        }
                        output!!.apply { write(message); newLine(); flush() }
                        val reply = input!!.readLine() ?: error("$compositor 玻璃桥接已断开")
                        if (reply != lastReply) {
                            IslandLog.state("linux.glass", "bridge", reply.take(1200))
                            lastReply = reply
                        }
                        synchronized(this) {
                            if (enabled && !closed) {
                                val ready = reply == "READY" || reply.startsWith("READY ")
                                readyAt = if (ready) System.nanoTime() else 0L
                                detail = if (reply == "READY") "后景、模糊、折射、色散和高光在 $compositor 内处理。"
                                    else reply.removePrefix(if (ready) "READY " else "WAIT ")
                            }
                        }
                    } catch (error: Exception) {
                        IslandLog.failure("LinuxCompositorGlass", "bridge.failed", error)
                        readyAt = 0L
                        detail = "$compositor 配套组件不可用：${error.message}"
                        disconnect()
                    }
                }
            } finally {
                synchronized(this) {
                    running = false
                    if (!closed && pending != null) dispatch()
                }
            }
        }
    }

    private fun disconnect() {
        // EOF asks the bridge to remove its Shell actor; the Shell also expires abandoned leases.
        runCatching { output?.close() }
        helper?.let { process ->
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroy()
        }
        runCatching { input?.close() }
        helper = null
        input = null
        output = null
    }

    private fun contour(shape: Shape, width: Int, height: Int): List<DoubleArray> {
        if (width <= 0 || height <= 0) return emptyList()
        val iterator = shape.getPathIterator(null, .2)
        val point = DoubleArray(6)
        val points = ArrayList<DoubleArray>()
        while (!iterator.isDone && points.size <= 256) {
            when (iterator.currentSegment(point)) {
                PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO ->
                    points += doubleArrayOf(point[0] / width, point[1] / height)
            }
            iterator.next()
        }
        return points
    }

    @Synchronized override fun close() {
        if (closed) return
        enabled = false
        readyAt = 0L
        pending = null
        closed = true
        worker.execute { disconnect() }
        worker.shutdown()
    }
}
