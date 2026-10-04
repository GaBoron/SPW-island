// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One monitor of the host's PipeWire playback node. No default-source or system-mix fallback. */
internal class LinuxProcessSpectrum : SpectrumSource {
    @Volatile private var closed = false
    @Volatile private var enabled = false
    @Volatile private var process: Process? = null
    @Volatile private var sample = FloatArray(4)
    @Volatile private var receivedAt = 0L
    @Volatile private var fallback = false
    @Volatile override var status = "实时频谱未启用"
        private set
    private var generation = 0L
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "SPW Island PipeWire").apply { isDaemon = true }
    }

    @Synchronized override fun setEnabled(value: Boolean) {
        if (closed || value == enabled) return
        enabled = value
        val token = ++generation
        runCatching { process?.outputStream?.close() } // EOF lets the helper reap its pw-record child.
        sample = FloatArray(4); receivedAt = 0
        fallback = false
        status = if (value) "正在连接 SPW 音频" else "实时频谱未启用"
        if (value) worker.execute { capture(token) }
    }

    private fun capture(token: Long) {
        var helper: Process? = null
        try {
            synchronized(this) { if (!enabled || closed || generation != token) return }
            val launched = LinuxHelper.startScript("island-spectrum.py", ProcessHandle.current().pid().toString())
            helper = launched
            synchronized(this) {
                if (!enabled || closed || generation != token) { launched.outputStream.close(); return }
                process = launched
            }
            launched.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(this) {
                    if (generation != token || !enabled || closed) return@forEach
                    when {
                        line.startsWith("READY ") -> { status = line.substring(6); fallback = false }
                        line.startsWith("WAIT ") || line.startsWith("FALLBACK ") -> {
                            status = line.substringAfter(' '); fallback = true; sample = FloatArray(4)
                        }
                        else -> {
                            val values = line.split(',').mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
                            if (values.size == 4) {
                                sample = values.map { it.coerceIn(0f, 1f) }.toFloatArray()
                                receivedAt = System.nanoTime()
                            }
                        }
                    }
                }
            } }
            synchronized(this) {
                if (enabled && !closed && generation == token) {
                    fallback = true
                    status = "${status.substringBefore("（")}（使用模拟频谱）"
                }
            }
        } catch (error: Exception) {
            synchronized(this) {
                if (enabled && !closed && generation == token) {
                    status = "实时频谱不可用：${error.message}（使用模拟频谱）"; fallback = true
                    System.err.println("[SPW Island] $status")
                }
            }
        } finally {
            helper?.let {
                runCatching { it.outputStream.close() }
                if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroy()
                if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly()
            }
            synchronized(this) { if (process === helper) process = null }
        }
    }

    override fun levels(): FloatArray = if (enabled && System.nanoTime() - receivedAt < 350_000_000L)
        sample else FloatArray(4)
    override fun usesSyntheticFallback() = enabled && fallback
    override fun close() {
        synchronized(this) {
            if (closed) return
            setEnabled(false)
            closed = true
        }
        worker.shutdown()
    }
}
