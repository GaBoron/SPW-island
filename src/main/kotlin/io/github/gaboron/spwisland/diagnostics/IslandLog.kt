// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadFactory

/** Process-local service, explicitly started by the plugin or the independent UI entry point. */
internal object IslandLog {
    @Volatile private var writer: DiagnosticWriter? = null
    @Volatile private var watchdog: DiagnosticWatchdog? = null
    private var finishing = false
    @Volatile private var closeGeneration = 0L
    @Volatile private var runContext: Map<String, Any?> = emptyMap()
    val session: String get() = writer?.session ?: "uninitialized"
    val directory: Path get() = writer?.directory ?: DiagnosticStorage.defaultDirectory()

    @Synchronized fun start(role: String, context: Map<String, Any?> = emptyMap()) {
        closeGeneration++
        if (writer != null) { finishing = false; return }
        finishing = false
        runContext = context
        val inherited = System.getenv("SPW_ISLAND_LOG_SESSION")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }
        val id = if (role == "ui") inherited ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
        val path = if (role == "ui") System.getenv("SPW_ISLAND_LOG_DIRECTORY")?.let(Path::of)
            ?: DiagnosticStorage.defaultDirectory() else DiagnosticStorage.defaultDirectory()
        try { writer = DiagnosticWriter(path, id, role) }
        catch (error: Exception) {
            System.err.println("[SPW Island] Cannot start diagnostics: ${DiagnosticPrivacy.text(error)}")
            return
        }
        try { watchdog = DiagnosticWatchdog() }
        catch (error: Exception) { failure("diagnostics", "watchdog.start_failed", error) }
        event("lifecycle", "diagnostics.started", environment() + context)
    }

    fun event(module: String, event: String, context: Map<String, Any?> = emptyMap()) =
        emit("INFO", module, event, context, null)
    fun warn(module: String, event: String, context: Map<String, Any?> = emptyMap()) =
        emit("WARN", module, event, context, null)
    fun failure(module: String, event: String, error: Throwable, context: Map<String, Any?> = emptyMap()) =
        emit("ERROR", module, event, context, error)

    private fun emit(level: String, module: String, event: String, context: Map<String, Any?>, error: Throwable?) {
        // Diagnostics must never turn a recoverable failure into a host failure.
        try {
            writer?.offer(DiagnosticEvent(Instant.now().toString(), System.nanoTime(), level, module, event,
                Thread.currentThread().name, context, error))
        } catch (_: Throwable) { /* Last-resort logging cannot recurse into itself. */ }
    }

    inline fun <T> attempt(module: String, event: String, action: () -> T): Result<T> =
        runCatching(action).onFailure { failure(module, event, it) }
    inline fun <T> guard(module: String, event: String, action: () -> T): T {
        try { return action() }
        catch (error: Throwable) { failure(module, event, error); throw error }
    }

    /** Install only on threads owned by this plugin; never change the JVM default handler. */
    fun threads(name: String): ThreadFactory = ThreadFactory { task ->
        Thread(task, name).apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure(name, "thread.uncaught", error) }
        }
    }
    fun uncaught(module: String) = Thread.UncaughtExceptionHandler { _, error -> failure(module, "thread.uncaught", error) }

    fun thread(name: String, action: () -> Unit): Thread = threads(name).newThread(action)

    fun state(module: String, key: String, value: String, context: Map<String, Any?> = emptyMap()) {
        writer?.state(module, key, value, context)
    }

    fun operation(module: String, name: String): DiagnosticOperation = DiagnosticOperation(module, name)
    fun cadence(intervalNs: Long) { writer?.idleIntervalNs = intervalNs; watchdog?.intervalNs = intervalNs }
    fun flush(timeoutMs: Long = 2_000): Boolean = writer?.flush(timeoutMs) ?: false
    fun stop() {
        val previous = synchronized(this) {
            watchdog?.close(); watchdog = null
            DiagnosticOperation.cancelAll()
            DiagnosticPulse.clear()
            writer.also { writer = null; closeGeneration++ }
        }
        previous?.close()
    }
    @Synchronized fun finish() {
        val previous = writer ?: return
        if (finishing) return
        finishing = true
        val token = ++closeGeneration
        thread("SPW Island diagnostic shutdown") {
            // EDT disposal and native/helper cleanup are asynchronous and still need their sink.
            Thread.sleep(3_000)
            val deadline = System.nanoTime() + 12_000_000_000L
            while (token == closeGeneration && DiagnosticOperation.pending() && System.nanoTime() < deadline) Thread.sleep(1_000)
            synchronized(this) {
                if (writer === previous && finishing && token == closeGeneration) {
                    DiagnosticOperation.checkTimeouts()
                    stop()
                }
            }
        }.start()
    }

    fun environment(): Map<String, Any?> = mapOf(
        "os" to System.getProperty("os.name"), "osVersion" to System.getProperty("os.version"),
        "arch" to System.getProperty("os.arch"), "java" to System.getProperty("java.version"),
        "desktop" to System.getenv("XDG_CURRENT_DESKTOP"), "sessionType" to System.getenv("XDG_SESSION_TYPE"),
        "processors" to Runtime.getRuntime().availableProcessors(), "maxMemoryMiB" to Runtime.getRuntime().maxMemory() / 1_048_576) + runContext
}

internal data class DiagnosticEvent(val time: String, val monotonicNs: Long, val level: String,
    val module: String, val event: String, val thread: String, val context: Map<String, Any?>,
    val error: Throwable? = null)
