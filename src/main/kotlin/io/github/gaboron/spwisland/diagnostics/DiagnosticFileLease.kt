// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Publish ownership before creating a log; mark it closed only after its writer has stopped. */
internal class DiagnosticFileLease private constructor(private val path: Path) : AutoCloseable {
    override fun close() {
        // A failed close keeps the lease active until the process exits, rather than risking data loss.
        IslandLog.attempt("diagnostics", "lease.close_failed") {
            FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val state = ByteBuffer.wrap("closed".toByteArray(Charsets.UTF_8))
                while (state.hasRemaining()) channel.write(state)
            }
        }
    }

    companion object {
        fun path(log: Path): Path = log.resolveSibling("${log.fileName}.lease")

        fun create(log: Path): DiagnosticFileLease {
            val owner = ProcessHandle.current()
            val start = owner.info().startInstant().orElse(null)?.toEpochMilli() ?: -1L
            val path = path(log)
            Files.writeString(path, "active\n${owner.pid()}\n$start\n", CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            return DiagnosticFileLease(path)
        }

        fun active(log: Path): Boolean {
            val lease = path(log)
            return try {
                val attributes = Files.readAttributes(lease, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || attributes.size() > 128) return true
                val record = Files.readString(lease).lineSequence().toList()
                if (record.firstOrNull() == "closed") return false
                val pid = record.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return true
                val owner = ProcessHandle.of(pid).orElse(null) ?: return false
                if (!owner.isAlive) return false
                val recordedStart = record.getOrNull(2)?.toLongOrNull()?.takeIf { it >= 0 }
                val actualStart = owner.info().startInstant().orElse(null)?.toEpochMilli()
                // Start time prevents a recycled PID from indefinitely protecting a dead writer's files.
                recordedStart == null || actualStart == null || recordedStart == actualStart
            } catch (_: NoSuchFileException) {
                false // Legacy log, or another retention pass already recycled its lease.
            } catch (error: Exception) {
                IslandLog.failure("diagnostics", "lease.read_failed", error)
                true // Unknown ownership must not permit cleanup of a potentially active file.
            }
        }
    }
}
