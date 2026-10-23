// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit

/** Windows remembers the notification icon's GUID together with this executable's full path. */
internal object WindowsTrayExecutable {
    @Synchronized fun prepare(): Path {
        val root = WindowsApplicationData.local()
        val directory = root.resolve("spw-island").resolve("tray").toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val executable = directory.resolve("spw-island-tray.exe")
        val bytes = WindowsTrayExecutable::class.java.getResourceAsStream("/native/spw-island-tray.exe").use {
            checkNotNull(it) { "插件中缺少 Windows 托盘程序" }.readBytes()
        }
        // Serialize installation across plugin class loaders and SPW processes.
        FileChannel.open(directory.resolve("install.lock"), CREATE, WRITE).use { channel ->
            installLock(channel).use {
                if (!Files.exists(executable) || !Files.readAllBytes(executable).contentEquals(bytes)) {
                    val pending = directory.resolve("spw-island-tray.exe.update")
                    Files.write(pending, bytes)
                    try {
                        Files.move(pending, executable, ATOMIC_MOVE, REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(pending, executable, REPLACE_EXISTING)
                    }
                    IslandLog.event("tray.windows", "executable.installed")
                }
            }
        }
        IslandLog.event("tray.windows", "executable.prepared", mapOf("redirected" to
            !root.toString().equals(System.getenv("LOCALAPPDATA"), ignoreCase = true)))
        return executable
    }

    private fun installLock(channel: FileChannel): FileLock {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        do {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) return lock
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        error("Windows 托盘程序正在更新，请稍后重新启用插件")
    }
}
