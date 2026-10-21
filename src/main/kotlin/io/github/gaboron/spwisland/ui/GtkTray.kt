// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors

import io.github.gaboron.spwisland.platform.LinuxHelper
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Native Linux tray using GTK or a desktop-rendered D-Bus menu. */
internal class GtkTray(private val entries: () -> List<PopupMenuEntry>,
                       private val selected: (Int) -> Unit,
                       private val report: (Throwable) -> Unit) : AutoCloseable {
    private val icon = ApplicationIdentity.exportIcon()
    private val process = try {
        val image = ApplicationIdentity.icon
        val pixels = ByteBuffer.allocate(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) pixels.putInt(image.getRGB(x, y))
        LinuxHelper.startTray(ApplicationIdentity.NAME, icon, image.width, image.height,
            Base64.getEncoder().encodeToString(pixels.array()))
    }
        catch (error: Exception) {
            IslandLog.failure("GtkTray", "caught.error", error); Files.deleteIfExists(icon); throw error }
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val sender = DiagnosticExecutors.single { Thread(it, "SPW tray updates").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("GtkTray") } }
    private var previous: List<PopupMenuEntry>? = null
    private var closed = false
    private val timer = Timer(250) { IslandLog.guard("GtkTray", "Timer.callback_failed") { update() } }

    init {
        update()
        timer.start()
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val command = line.toIntOrNull() ?: run {
                            if (line.isNotBlank()) IslandLog.warn("tray", "protocol.unexpected", mapOf("message" to line))
                            return@forEach
                        }
                        SwingUtilities.invokeLater {
                            IslandLog.guard("GtkTray", "invokeLater.callback_failed") {
                                if (!closed && entries().any { it.id == command &&
                                it.kind in setOf(PopupMenuKind.ACTION, PopupMenuKind.TOGGLE) }) selected(command)
                            }
                        }
                    }
                }
                val exit = process.waitFor()
                SwingUtilities.invokeLater {
                    IslandLog.guard("GtkTray", "invokeLater.callback_failed") { unavailable(IllegalStateException("Linux 托盘已退出（$exit）")) }
                }
            } catch (error: Exception) {
                IslandLog.failure("GtkTray", "caught.error", error);
                SwingUtilities.invokeLater { IslandLog.guard("GtkTray", "invokeLater.callback_failed") { unavailable(error) } }
            }
        }, "SPW tray commands").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("GtkTray") }.start()
    }

    private fun update() {
        if (closed) return
        val current = entries()
        if (current == previous) return
        previous = current
        sender.execute {
            try {
                current.forEach { entry ->
                    val label = entry.label.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                    writer.write("${entry.kind}\t${entry.id}\t${if (entry.selected) 1 else 0}\t$label\n")
                }
                writer.write("END\n"); writer.flush()
            } catch (error: Exception) {
                IslandLog.failure("GtkTray", "caught.error", error);
                SwingUtilities.invokeLater { IslandLog.guard("GtkTray", "invokeLater.callback_failed") { unavailable(error) } }
            }
        }
    }

    private fun unavailable(error: Exception) {
        if (closed) return
        close()
        report(IllegalStateException(
            "Linux 托盘不可用，可继续从 SPW 插件设置页管理词岛；具体原因见日志", error))
    }

    override fun close() {
        if (closed) return
        closed = true
        timer.stop(); sender.shutdownNow(); process.destroy()
        Thread({
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            IslandLog.attempt("GtkTray", "close.attempt1") { writer.close() }
            IslandLog.attempt("GtkTray", "close.attempt2") { Files.deleteIfExists(icon) }
        }, "SPW tray cleanup").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("GtkTray") }.start()
    }
}
