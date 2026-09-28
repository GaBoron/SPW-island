// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Point
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Hosts only the Windows notification icon under the plugin's own executable identity. */
internal class WindowsTray(private val toggle: () -> Unit, private val menu: (Point) -> Unit,
                           private val report: (Throwable) -> Unit) : AutoCloseable {
    private val process: Process
    @Volatile private var closed = false

    init {
        val executable = WindowsTray::class.java.getResource("/native/spw-island-tray.exe")
            ?: error("插件中缺少 native/spw-island-tray.exe")
        check(executable.protocol == "file") {
            "请使用 SPW 插件 ZIP 安装托盘程序"
        }
        val icon = ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(ApplicationIdentity.icon, "png", bytes)) { "托盘图标编码失败" }
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
        process = ProcessBuilder(Path.of(executable.toURI()).toString(), icon,
            ProcessHandle.current().pid().toString())
            .redirectErrorStream(true).start()
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
                            line == "TOGGLE" -> SwingUtilities.invokeLater { if (!closed) toggle() }
                            line.startsWith("MENU\t") -> {
                                val coords = line.split('\t')
                                val x = coords.getOrNull(1)?.toIntOrNull()
                                val y = coords.getOrNull(2)?.toIntOrNull()
                                if (x != null && y != null) SwingUtilities.invokeLater {
                                    if (!closed) menu(Point(x, y))
                                }
                            }
                        }
                    }
                }
                if (ready.count > 0) {
                    startupError = IllegalStateException("Windows 托盘未能启动（${process.waitFor()}）")
                    ready.countDown()
                } else if (!closed) SwingUtilities.invokeLater {
                    if (!closed) { close(); report(IllegalStateException("Windows 托盘已退出")) }
                }
            } catch (error: Exception) {
                if (ready.count > 0) { startupError = error; ready.countDown() }
                else SwingUtilities.invokeLater { if (!closed) { close(); report(error) } }
            }
        }, "SPW Island Windows tray events").apply { isDaemon = true }.start()
        if (!ready.await(3, TimeUnit.SECONDS) || startupError != null) {
            close()
            throw startupError ?: IllegalStateException("Windows 托盘启动超时")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { process.outputStream.bufferedWriter().apply { write("STOP\n"); flush() } }
        Thread({
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        }, "SPW Island Windows tray cleanup").apply { isDaemon = true }.start()
    }
}
