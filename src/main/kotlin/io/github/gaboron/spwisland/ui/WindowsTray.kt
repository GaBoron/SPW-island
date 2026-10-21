// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.diagnostics.IslandLog

import java.awt.Point
import java.io.ByteArrayOutputStream
import io.github.gaboron.spwisland.platform.WindowsNativeResources
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import io.github.gaboron.spwisland.diagnostics.DiagnosticProcesses

/** Hosts only the Windows notification icon under the plugin's own executable identity. */
internal class WindowsTray(private val toggle: () -> Unit, private val menu: (Point) -> Unit,
                           private val report: (Throwable) -> Unit) : AutoCloseable {
    private val process: Process
    @Volatile private var closed = false

    init {
        val executable = WindowsNativeResources.extract("/native/spw-island-tray.exe")
        val icon = ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(ApplicationIdentity.icon, "png", bytes)) { "托盘图标编码失败" }
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
        process = DiagnosticProcesses.start(ProcessBuilder(executable.toString(), icon,
            ProcessHandle.current().pid().toString()), "tray.windows")
        val ready = CountDownLatch(1)
        var startupError: Throwable? = null
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (ready.count > 0) {
                            if (line != "READY") startupError = IllegalStateException("Windows 托盘启动失败：$line")
                            ready.countDown()
                        } else when {
                            line == "TOGGLE" -> SwingUtilities.invokeLater { IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) toggle() } }
                            line.startsWith("MENU\t") -> {
                                val coords = line.split('\t')
                                val x = coords.getOrNull(1)?.toIntOrNull()
                                val y = coords.getOrNull(2)?.toIntOrNull()
                                if (x != null && y != null) SwingUtilities.invokeLater {
                                    IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) menu(Point(x, y)) }
                                }
                            }
                        }
                    }
                }
                if (ready.count > 0) {
                    startupError = IllegalStateException("Windows 托盘未能启动（${process.waitFor()}）")
                    ready.countDown()
                } else if (!closed) SwingUtilities.invokeLater {
                    IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) { close(); report(IllegalStateException("Windows 托盘已退出")) } }
                }
            } catch (error: Exception) {
                IslandLog.failure("WindowsTray", "caught.error", error);
                if (ready.count > 0) { startupError = error; ready.countDown() }
                else SwingUtilities.invokeLater { IslandLog.guard("WindowsTray", "invokeLater.callback_failed") { if (!closed) { close(); report(error) } } }
            }
        }, "SPW Island Windows tray events").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("WindowsTray") }.start()
        if (!ready.await(3, TimeUnit.SECONDS) || startupError != null) {
            close()
            throw startupError ?: IllegalStateException("Windows 托盘启动超时")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        IslandLog.attempt("WindowsTray", "close.attempt1") { process.outputStream.bufferedWriter().apply { write("STOP\n"); flush() } }
        Thread({
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        }, "SPW Island Windows tray cleanup").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("WindowsTray") }.start()
    }
}
