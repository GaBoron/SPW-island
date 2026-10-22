// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Own files only, ten-day retention and a 64 MiB active-log budget. Cleanup always uses Trash. */
internal class DiagnosticStorage(private val directory: Path) {
    private data class Entry(val path: Path, val size: Long, val modified: Instant)
    private var cleanupWarning = false
    private fun entries(): List<Entry> = if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) emptyList() else
        Files.list(directory).use { paths -> paths.filter { ownFile(it) && !it.fileName.toString().endsWith(".lease") }.toList().mapNotNull { path ->
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                Entry(path, attributes.size(), attributes.lastModifiedTime().toInstant())
            } catch (_: NoSuchFileException) { null } // Another process completed the same retention pass.
        } }
    fun files(): List<Path> = entries().map { it.path }.sorted()
    fun prepare() { Files.createDirectories(directory) }

    fun maintain() {
        prepare()
        val files = entries().sortedBy { it.modified }
        var bytes = files.sumOf { it.size }
        val deadline = Instant.now().minus(10, ChronoUnit.DAYS)
        var failed = false
        for (entry in files) {
            val file = entry.path
            if (entry.modified >= deadline && bytes <= MAX_BYTES - 4 * 1_048_576) continue
            if (DiagnosticFileLease.active(file)) continue
            if (recycleOrWarn(file)) bytes -= entry.size else failed = true
        }
        // Closed and crashed writers can leave sidecars; recycle them only after the log is gone.
        val leases = Files.list(directory).use { paths ->
            paths.filter { ownFile(it) && it.fileName.toString().endsWith(".lease") }.toList()
        }
        for (lease in leases) {
            val log = lease.resolveSibling(lease.fileName.toString().removeSuffix(".lease"))
            if (!Files.exists(log, NOFOLLOW_LINKS) && !DiagnosticFileLease.active(log) && !recycleOrWarn(lease)) failed = true
        }
        if (!failed && cleanupWarning) { cleanupWarning = false; IslandLog.event("diagnostics", "retention.recovered") }
    }

    fun hasCapacity(): Boolean = entries().sumOf { it.size } < MAX_BYTES

    private fun ownFile(path: Path): Boolean = path.toAbsolutePath().normalize().parent == directory.toAbsolutePath().normalize() &&
        Files.isRegularFile(path, NOFOLLOW_LINKS) && path.fileName.toString().matches(Regex("island-[A-Za-z0-9.-]+\\.(jsonl|stderr)(\\.lease)?"))

    private fun recycleOrWarn(path: Path): Boolean {
        if (recycle(path) || !Files.exists(path, NOFOLLOW_LINKS)) return true
        if (!cleanupWarning) {
            cleanupWarning = true
            IslandLog.warn("diagnostics", "retention.trash_unavailable", mapOf("file" to path.fileName,
                "action" to "retained; file logging pauses at capacity"))
        }
        return false
    }

    private fun recycle(path: Path): Boolean {
        if (!ownFile(path)) return false
        return try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                    Desktop.getDesktop().moveToTrash(path.toFile())
            } else {
                val process = ProcessBuilder("/usr/bin/gio", "trash", "--", path.toAbsolutePath().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (process.waitFor(3, TimeUnit.SECONDS)) process.exitValue() == 0
                else { process.destroy(); false }
            }
        } catch (error: Exception) { IslandLog.failure("diagnostics", "retention.recycle_failed", error); false }
    }

    companion object {
        const val MAX_BYTES = 64L * 1_048_576
        fun defaultDirectory(): Path {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val root = if (windows) System.getenv("LOCALAPPDATA")?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            else System.getenv("XDG_STATE_HOME")?.takeIf { Path.of(it).isAbsolute }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".local", "state")
            return root.resolve("spw-island").resolve("logs").toAbsolutePath().normalize()
        }
    }
}
