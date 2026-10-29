// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import com.google.gson.GsonBuilder
import io.github.gaboron.spwisland.platform.LinuxHelper
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export diagnostics and bounded GNOME failure evidence; never copy media, host logs or the environment. */
internal object DiagnosticExport {
    fun openDirectory() {
        val directory = IslandLog.directory
        Files.createDirectories(directory)
        open(directory)
    }
    fun open(path: Path) {
        if (System.getProperty("os.name").startsWith("Linux")) {
            val process = DiagnosticProcesses.start(ProcessBuilder("/usr/bin/xdg-open", path.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD), "diagnostics.directory")
            if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                check(process.exitValue() == 0) { "系统无法打开目录：$path" }
        } else {
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                "系统不支持打开目录：$path"
            }
            Desktop.getDesktop().open(path.toFile())
        }
    }
    fun create(): Path {
        val operation = IslandLog.operation("diagnostics", "export")
        try {
            val gnome = IslandLog.attempt("diagnostics", "gnome.collect_failed") {
                LinuxHelper.gnomeDiagnostics()
            }.getOrElse { error ->
                GsonBuilder().create().toJson(mapOf("available" to false, "error" to DiagnosticPrivacy.text(error)))
            }
            val flushed = IslandLog.flush()
            val snapshot = IslandLog.snapshot()
            val directory = IslandLog.directory
            Files.createDirectories(directory)
            val destination = directory.parent.resolve("diagnostics-${java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${java.util.UUID.randomUUID().toString().take(8)}.zip")
            val partial = destination.resolveSibling("${destination.fileName}.partial")
            ZipOutputStream(Files.newOutputStream(partial, CREATE_NEW)).use { zip ->
                if (gnome != null) {
                    zip.putNextEntry(ZipEntry("gnome-shell.json"))
                    zip.write(gnome.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("environment.json"))
                zip.write(GsonBuilder().setPrettyPrinting().create().toJson(IslandLog.environment() +
                    mapOf("session" to IslandLog.session, "hostLogFlushed" to flushed,
                        "notice" to "Inspect exception stacks before sharing; no automatic upload.")).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("latest-state.json"))
                zip.write(GsonBuilder().setPrettyPrinting().create().toJson(snapshot + mapOf(
                    "notice" to "Latest recorded observations with age; no fresh UI query. Frame production and OS visibility do not verify compositor output."
                )).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val deadline = java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.DAYS)
                for (file in DiagnosticStorage(directory).files()) {
                    val channel = try {
                        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.getLastModifiedTime(file).toInstant() < deadline) continue
                        java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)
                    } catch (_: java.nio.file.NoSuchFileException) {
                        IslandLog.warn("diagnostics", "export.file_rotated", mapOf("file" to file.fileName)); continue
                    }
                    channel.use {
                        zip.putNextEntry(ZipEntry("logs/${file.fileName}"))
                        // Snapshot the open file descriptor: retention may unlink its directory entry.
                        var remaining = channel.size().coerceAtMost(5L * 1_048_576)
                        val input = java.nio.channels.Channels.newInputStream(channel)
                        val buffer = ByteArray(8192)
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            zip.write(buffer, 0, read); remaining -= read
                        }
                        zip.closeEntry()
                    }
                }
            }
            Files.move(partial, destination)
            operation.close()
            return destination
        } catch (error: Throwable) { operation.failed(error); throw error }
    }
}
