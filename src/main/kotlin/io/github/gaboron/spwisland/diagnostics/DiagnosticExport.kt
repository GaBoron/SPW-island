// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import com.google.gson.GsonBuilder
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export only diagnostic files; never copy configuration, media, host logs or the process environment. */
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
            val flushed = IslandLog.flush()
            val directory = IslandLog.directory
            Files.createDirectories(directory)
            val destination = directory.parent.resolve("diagnostics-${java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${java.util.UUID.randomUUID().toString().take(8)}.zip")
            val partial = destination.resolveSibling("${destination.fileName}.partial")
            ZipOutputStream(Files.newOutputStream(partial, CREATE_NEW)).use { zip ->
                zip.putNextEntry(ZipEntry("environment.json"))
                zip.write(GsonBuilder().setPrettyPrinting().create().toJson(IslandLog.environment() +
                    mapOf("session" to IslandLog.session, "hostLogFlushed" to flushed,
                        "notice" to "Inspect exception stacks before sharing; no automatic upload.")).toByteArray(Charsets.UTF_8))
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
