// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import java.awt.MouseInfo
import java.awt.Point
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** A click-through window cannot observe its own crossing events on Wayland. */
internal class LinuxGlobalPointer : AutoCloseable {
    private val wayland = System.getenv("XDG_SESSION_TYPE") == "wayland" || !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
    private val kde = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').any { it.equals("KDE", true) }
    private val gnome = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').any { it.equals("GNOME", true) }
    private val desktop = if (gnome) "GNOME" else "KWin"
    @Volatile private var process: Process? = null
    @Volatile private var point: Point? = null
    @Volatile private var received = 0L
    @Volatile private var closed = false
    private var enabled = false
    private var generation = 0L
    @Volatile var status = if (!wayland) "悬停隐藏：X11" else if (kde || gnome) "悬停隐藏：等待 $desktop" else
        "当前 Wayland 桌面不支持穿透时悬停隐藏"
        private set
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "SPW Island pointer bridge").apply { isDaemon = true }
    }

    @Synchronized fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        val token = ++generation
        runCatching { process?.outputStream?.close() }
        point = null; received = 0
        if (!value || !wayland || (!kde && !gnome)) return
        status = "悬停隐藏：正在连接 $desktop"
        worker.execute {
            while (synchronized(this) { !closed && enabled && generation == token }) {
                bridge(token)
                Thread.sleep(2000)
            }
        }
    }

    private fun bridge(token: Long) {
        var helper: Process? = null
        try {
            synchronized(this) { if (closed || !enabled || token != generation) return }
            val launched = LinuxHelper.startScript("island-pointer.py", ProcessHandle.current().pid().toString(),
                if (gnome) "gnome" else "kwin")
            helper = launched
            synchronized(this) {
                if (closed || !enabled || token != generation) { launched.outputStream.close(); return }
                process = launched
            }
            launched.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(this) {
                    if (token != generation || !enabled || closed) return@forEach
                    val parts = line.split(' ')
                    val x = parts.getOrNull(1)?.toDoubleOrNull()?.takeIf(Double::isFinite)
                    val y = parts.getOrNull(2)?.toDoubleOrNull()?.takeIf(Double::isFinite)
                    if (parts.firstOrNull() == "POINT" && x != null && y != null) {
                        point = Point(x.roundToInt(), y.roundToInt()); received = System.nanoTime()
                        status = "悬停隐藏：$desktop"
                    } else if (line.startsWith("UNAVAILABLE ")) {
                        point = null; status = line.substringAfter(' ')
                    }
                }
            } }
        } catch (error: Exception) {
            synchronized(this) { if (generation == token) status = "悬停隐藏不可用：${error.message}" }
        } finally {
            helper?.let {
                runCatching { it.outputStream.close() }
                if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroy()
                if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly()
            }
            synchronized(this) {
                if (process === helper) process = null
                if (generation == token) {
                    point = null
                    if (status == "悬停隐藏：$desktop") status = "悬停隐藏未连接，词岛保持显示"
                }
            }
        }
    }

    fun location(): Point? = if (!wayland) runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        else if (process?.isAlive == true && System.nanoTime() - received < 750_000_000L) point else null

    override fun close() {
        synchronized(this) {
            if (closed) return
            setEnabled(false); closed = true
        }
        worker.shutdown()
    }
}
