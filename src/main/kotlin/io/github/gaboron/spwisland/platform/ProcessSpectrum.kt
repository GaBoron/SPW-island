// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SPW Island audio").apply { isDaemon = true }
    }

    @Synchronized override fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        if (value) {
            status = "正在连接 SPW 音频"
            syntheticFallback = false
            worker.execute(::capture)
        } else {
            status = "低性能模式已停用实时频谱"
            sample = FloatArray(4)
            process?.destroy()
        }
    }

    private fun capture() {
        var launched: Process? = null
        try {
            if (!enabled || closed) return
            val resource = ProcessSpectrum::class.java.getResource("/native/spw-spectrum.exe")
                ?: error("插件中缺少 native/spw-spectrum.exe")
            check(resource.protocol == "file") { "请使用 SPW 插件 ZIP 安装频谱程序" }
            val executable = Path.of(resource.toURI())
            if (!DotNetFrameworkRuntime.canRun(executable)) {
                fallback(
                    "实时频谱不可用：.NET Framework 4 无法启动，已改用模拟频谱",
                    "实时频谱需要 Microsoft .NET Framework 4.8。请安装或修复后重启 SPW；当前已改用模拟频谱。"
                )
                return
            }
            val helper = ProcessBuilder(executable.toString(), ProcessHandle.current().pid().toString())
                .redirectErrorStream(true).start()
            launched = helper
            synchronized(this) {
                if (!enabled || closed) {
                    helper.destroy()
                    return
                }
                process = helper
            }
            helper.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line == "READY") status = "SPW 进程音频频谱"
                else {
                    val values = line.split(',').mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
                    if (values.size == 4) {
                        sample = values.map { it.coerceIn(0f, 1f) }.toFloatArray()
                        receivedAt = System.nanoTime()
                    } else if (line.isNotBlank()) {
                        status = "频谱不可用：$line"
                        System.err.println("[SPW Island] $status")
                    }
                }
            } }
            if (enabled && !closed && helper.waitFor() != 0) {
                fallback(
                    "频谱不可用（需要 Windows 20348+ 与共享音频输出）：$status",
                    "实时频谱启动失败，当前已改用模拟频谱。请确认系统版本和共享音频输出可用。"
                )
            }
        } catch (error: Exception) {
            if (enabled && !closed) {
                fallback(
                    "频谱不可用：${error.message}",
                    "实时频谱启动失败，当前已改用模拟频谱。"
                )
            }
        } finally {
            synchronized(this) { if (process === launched) process = null }
            sample = FloatArray(4)
        }
    }

    private fun fallback(detail: String, notice: String) {
        syntheticFallback = true
        status = detail
        System.err.println("[SPW Island] $status")
        if (!fallbackNotified) {
            fallbackNotified = true
            notifyFallback(notice)
        }
    }

    override fun usesSyntheticFallback(): Boolean = enabled && syntheticFallback
    override fun levels(): FloatArray = if (System.nanoTime() - receivedAt < 350_000_000) sample else FloatArray(4)
    override fun close() {
        val helper = synchronized(this) {
            if (closed) return
            closed = true
            enabled = false
            process
        }
        helper?.let {
            runCatching { it.outputStream.bufferedWriter().apply { write("stop\n"); flush() } }
            if (!it.waitFor(800, TimeUnit.MILLISECONDS)) it.destroy()
            if (!it.waitFor(200, TimeUnit.MILLISECONDS)) it.destroyForcibly()
        }
        worker.shutdownNow()
        worker.awaitTermination(200, TimeUnit.MILLISECONDS)
    }
}
