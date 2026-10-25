// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.diagnostics.IslandLog

import java.awt.Point
import java.io.ByteArrayOutputStream
import io.github.gaboron.spwisland.platform.WindowsTrayExecutable
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import io.github.gaboron.spwisland.diagnostics.DiagnosticProcesses

/** Hosts only the Windows notification icon under the plugin's own executable identity. */
internal class WindowsTray(private val toggle: () -> Unit, private val menu: (Point) -> Unit,
                           private val report: (Throwable) -> Unit) : AutoCloseable {
    private val process: Process
    @Volatile private var closed = false

    init {
        val executable = WindowsTrayExecutable.prepare()
        val icon = ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(ApplicationIdentity.icon, "png", bytes)) { "托盘图标编码失败" }
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
        process = DiagnosticProcesses.start(ProcessBuilder(executable.toString(), icon,
            ProcessHandle.current().pid().toString()), "tray.windows")
        val ready = CompletableFuture<Unit>()
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line == "READY" -> {
                                if (ready.complete(Unit)) IslandLog.event("tray.windows", "ready", mapOf("childPid" to process.pid()))
                                else unexpected(line)
                            }
                            line.startsWith("ERROR\t") -> throw IllegalStateException(
                                "Windows 托盘${if (ready.isDone) "运行" else "启动"}失败：${line.substringAfter('\t')}")
                            line.startsWith("REGISTERED\t") -> {
                                val fields = line.split('\t')
                                if (fields.size == 3 && fields[1] in setOf("startup", "explorer_restart") &&
                                    runCatching { UUID.fromString(fields[2]) }.isSuccess) {
                                    IslandLog.event("tray.windows", "icon.registered", mapOf(
                                        "childPid" to process.pid(), "reason" to fields[1], "guid" to fields[2]))
                                } else unexpected(line)
                            }
                            !ready.isDone -> unexpected(line)
                            line == "TOGGLE" -> SwingUtilities.invokeLater { IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) toggle() } }
                            line.startsWith("MENU\t") -> {
                                val coords = line.split('\t')
                                val x = coords.getOrNull(1)?.toIntOrNull()
                                val y = coords.getOrNull(2)?.toIntOrNull()
                                if (coords.size == 3 && x != null && y != null) SwingUtilities.invokeLater {
                                    IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) menu(Point(x, y)) }
                                } else unexpected(line)
                            }
                            else -> unexpected(line)
                        }
                    }
                }
                // stdout can reach EOF just before the OS publishes the process exit code.
                val exit = if (process.waitFor(200, TimeUnit.MILLISECONDS)) process.exitValue().toString()
                    else "输出管道已关闭，进程仍在运行"
                throw IllegalStateException("Windows 托盘${if (ready.isDone) "已退出" else "未能启动"}（$exit）")
            } catch (error: Exception) {
                if (closed) return@Thread
                IslandLog.failure("WindowsTray", "caught.error", error)
                if (ready.completeExceptionally(error)) IslandLog.failure("tray.windows", "startup.output_failed", error)
                else SwingUtilities.invokeLater { IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) { close(); report(error) } } }
            }
        }, "SPW Island Windows tray events").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("WindowsTray") }.start()
        try {
            ready.get(3, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            val error = when (failure) {
                is ExecutionException -> failure.cause ?: failure
                is TimeoutException -> if (process.isAlive) IllegalStateException("Windows 托盘启动超时（程序仍在运行）", failure)
                    else IllegalStateException("Windows 托盘未能启动（${process.exitValue()}）", failure)
                else -> failure
            }
            IslandLog.failure("tray.windows", "startup.failed", error, mapOf("childPid" to process.pid()))
            try { close() }
            finally { if (failure is InterruptedException) Thread.currentThread().interrupt() }
            throw error
        }
    }

    private fun unexpected(line: String) = IslandLog.warn("tray.windows", "protocol.unexpected",
        mapOf("childPid" to process.pid(), "message" to line.take(512)))

    override fun close() {
        if (closed) return
        closed = true
        IslandLog.attempt("WindowsTray", "close.attempt1") { process.outputStream.bufferedWriter().apply { write("STOP\n"); flush() } }
        Thread({
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        }, "SPW Island Windows tray cleanup").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("WindowsTray") }.start()
    }
}
