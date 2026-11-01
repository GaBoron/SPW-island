// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import io.github.gaboron.spwisland.diagnostics.DiagnosticProcesses

/** Runs the private process-loopback reader only while live spectrum is enabled. */
internal class ProcessSpectrum(private val notifyFallback: (String) -> Unit = {}) : SpectrumSource {
    @Volatile private var closed = false
    @Volatile private var enabled = false
    @Volatile private var process: Process? = null
    @Volatile private var sample = FloatArray(4)
    @Volatile private var receivedAt = 0L
    @Volatile private var syntheticFallback = false
    @Volatile private var fallbackNotified = false
    @Volatile override var status = "正在连接 SPW 音频"
        private set
    private var generation = 0L
    private val worker = DiagnosticExecutors.pool(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), IslandLog.threads("SPW Island audio"), ThreadPoolExecutor.AbortPolicy())

    @Synchronized override fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        val token = ++generation
        worker.queue.clear()
        process?.destroy()
        sample = FloatArray(4)
        receivedAt = 0
        if (value) {
            status = "正在连接 SPW 音频"
            syntheticFallback = false
            worker.execute { capture(token) }
        } else {
            status = "低性能模式已停用实时频谱"
        }
    }

    @Synchronized private fun active(token: Long) = enabled && !closed && generation == token

    private fun capture(token: Long) {
        var launched: Process? = null
        try {
            if (!active(token)) return
            val executable = WindowsNativeResources.extract("/native/spw-spectrum.exe")
            if (!DotNetFrameworkRuntime.canRun(executable)) {
                fallback(
                    token,
                    "实时频谱不可用：.NET Framework 4 无法启动，已改用模拟频谱",
                    "实时频谱需要 Microsoft .NET Framework 4.8。请安装或修复后重启 SPW；当前已改用模拟频谱。"
                )
                return
            }
            if (!active(token)) return
            val helper = DiagnosticProcesses.start(ProcessBuilder(executable.toString(),
                ProcessHandle.current().pid().toString()), "spectrum.windows")
            launched = helper
            synchronized(this) {
                if (!active(token)) {
                    helper.destroy()
                    return
                }
                process = helper
            }
            helper.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(this) {
                    if (!active(token)) return@forEach
                    if (line == "READY") {
                        status = "SPW 进程音频频谱"
                        IslandLog.state("spectrum", "capture", "ready")
                    } else {
                        val values = line.split(',').mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
                        if (values.size == 4) {
                            sample = values.map { it.coerceIn(0f, 1f) }.toFloatArray()
                            receivedAt = System.nanoTime()
                        } else if (line.isNotBlank()) {
                            status = "频谱不可用：$line"
                            IslandLog.warn("spectrum", "protocol.unexpected", mapOf("message" to line))
                        }
                    }
                }
            } }
            if (active(token) && helper.waitFor() != 0) {
                fallback(
                    token,
                    "频谱不可用（需要 Windows 20348+ 与共享音频输出）：$status",
                    "实时频谱启动失败，当前已改用模拟频谱。请确认系统版本和共享音频输出可用。"
                )
            }
        } catch (error: Exception) {
            IslandLog.failure("ProcessSpectrum", "caught.error", error);
            if (active(token)) {
                fallback(
                    token,
                    "频谱不可用：${error.message}",
                    "实时频谱启动失败，当前已改用模拟频谱。"
                )
            }
        } finally {
            // Reap on the capture worker. stop() must never wait for an audio helper on the host UI thread.
            launched?.let { helper ->
                helper.destroy()
                try {
                    if (!helper.waitFor(1, TimeUnit.SECONDS)) helper.destroyForcibly()
                } catch (_: InterruptedException) {
                    helper.destroyForcibly()
                    Thread.currentThread().interrupt()
                }
            }
            synchronized(this) {
                if (process === launched) process = null
                if (generation == token) sample = FloatArray(4)
            }
        }
    }

    private fun fallback(token: Long, detail: String, notice: String) {
        val notify = synchronized(this) {
            if (!active(token)) return
            syntheticFallback = true
            status = detail
            IslandLog.state("spectrum", "capture", "fallback", mapOf("reason" to detail))
            (!fallbackNotified).also { fallbackNotified = true }
        }
        // A host UI call may acquire its plugin monitor; never hold the capture lock here.
        if (notify && active(token)) notifyFallback(notice)
    }

    override fun usesSyntheticFallback(): Boolean = enabled && syntheticFallback
    override fun levels(): FloatArray = if (enabled && System.nanoTime() - receivedAt < 350_000_000) sample else FloatArray(4)
    override fun close() {
        val helper = synchronized(this) {
            if (closed) return
            closed = true
            enabled = false
            generation++
            sample = FloatArray(4)
            process
        }
        helper?.destroy()
        worker.shutdownNow()
    }
}
