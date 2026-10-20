// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Platform
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Persistent, bounded diagnostics independent of the SPW console.
 *
 * Logs describe lifecycle, native window state, rendering health and failures; they
 * deliberately exclude lyrics, track names, media paths and user configuration values.
 * Separate JVMs (the Linux UI) get separate files in the same directory.
 */
object IslandLog {
    private const val FILE_SIZE = 4 * 1024 * 1024
    private const val FILE_COUNT = 6
    private val states = ConcurrentHashMap<String, String>()
    private val warnings = ConcurrentHashMap<String, Long>()
    private val logger = Logger.getLogger("spw-island.diagnostics.${ProcessHandle.current().pid()}.${System.nanoTime()}")
    val directory: Path = resolveDirectory()
    @Volatile private var fileEnabled = false

    init {
        logger.useParentHandlers = false
        logger.level = Level.ALL
        try {
            Files.createDirectories(directory)
            pruneFiles()
            val name = "island-${System.currentTimeMillis()}-pid${ProcessHandle.current().pid()}-%g.log"
            val handler = FileHandler(directory.resolve(name).toString(), FILE_SIZE, FILE_COUNT, true)
            handler.level = Level.ALL
            handler.formatter = object : Formatter() {
                override fun format(record: LogRecord): String = buildString {
                    append(Instant.ofEpochMilli(record.millis))
                    append(" [").append(record.level.name).append("] ")
                    append(record.message).append(10.toChar())
                    record.thrown?.let { error ->
                        val buffer = StringWriter()
                        error.printStackTrace(PrintWriter(buffer))
                        append(buffer)
                    }
                }
            }
            logger.addHandler(handler)
            fileEnabled = true
            info("logging", "file ready; pid=${ProcessHandle.current().pid()}; os=${System.getProperty("os.name")}; " +
                "java=${System.getProperty("java.version")}; dir=$directory")
        } catch (error: Throwable) {
            System.err.println("[SPW Island] Unable to create diagnostic logs in $directory: $error")
        }
    }

    private fun resolveDirectory(): Path {
        val home = System.getProperty("user.home", ".")
        return if (Platform.isWindows()) {
            Paths.get(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: home,
                "SPW-Island", "logs")
        } else {
            val state = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
                ?: Paths.get(home, ".local", "state").toString()
            Paths.get(state, "spw-island", "logs")
        }
    }

    private fun pruneFiles() {
        // Rotation bounds each process; expiry also bounds the accumulation from old launches.
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
        Files.list(directory).use { stream ->
            val files = stream.filter { it.fileName.toString().startsWith("island-") &&
                it.fileName.toString().endsWith(".log") }
                .sorted(compareByDescending<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0) })
                .toList()
            var kept = 0L
            for (path in files) runCatching {
                val length = Files.size(path)
                val expired = Files.getLastModifiedTime(path).toMillis() < cutoff
                if (expired || kept + length > 128L * 1024 * 1024) Files.deleteIfExists(path)
                else kept += length
            }
        }
    }

    private fun record(level: Level, scope: String, message: String, error: Throwable? = null) {
        val line = "[${Thread.currentThread().name}] [$scope] $message"
        if (fileEnabled) logger.log(level, line, error)
        else {
            System.err.println("[SPW Island] $line")
            error?.printStackTrace(System.err)
        }
    }

    fun info(scope: String, message: String) = record(Level.INFO, scope, message)
    fun warn(scope: String, message: String) = record(Level.WARNING, scope, message)
    fun error(scope: String, message: String, error: Throwable? = null) =
        record(Level.SEVERE, scope, message, error)

    /** Log transitions once, without emitting an identical record on every animation frame. */
    fun state(key: String, value: String) {
        val previous = states.put(key, value)
        if (previous != value) info(key, "state: ${previous ?: "(initial)"} -> $value")
    }

    /**
     * Report a persisted anomaly immediately, then at most once a minute until it recovers.
     * Each key represents a stable invariant, never an individual frame/track.
     */
    fun anomaly(key: String, detail: String) {
        val now = System.nanoTime()
        val previous = warnings[key]
        if (previous == null || now - previous >= TimeUnit.MINUTES.toNanos(1)) {
            warnings[key] = now
            warn(key, detail)
        }
    }

    fun recovered(key: String, detail: String) {
        if (warnings.remove(key) != null) info(key, "recovered: $detail")
    }

    fun flush() = logger.handlers.forEach { runCatching { it.flush() } }
}
