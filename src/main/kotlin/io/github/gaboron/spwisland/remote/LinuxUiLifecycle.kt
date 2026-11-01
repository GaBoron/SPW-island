// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One startup attempt per runtime; a late child is closed instead of joining the next enable cycle. */
internal class LinuxUiLifecycle(
    private val create: () -> LinuxIslandProcess,
    private val report: (Throwable) -> Unit
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var started = false
    private var process: LinuxIslandProcess? = null
    private var aboutRequested = false
    private val worker = DiagnosticExecutors.pool(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), IslandLog.threads("SPW Island UI startup"), ThreadPoolExecutor.AbortPolicy())

    fun start(): Unit = synchronized(lock) {
        if (closed || started) return
        started = true
        val submitted = System.nanoTime()
        worker.execute {
            try {
                if (synchronized(lock) { closed }) return@execute
                IslandLog.duration("linux.ui.queue", submitted)
                val created = IslandLog.timed("linux.ui.construct", create)
                val accepted = synchronized(lock) {
                    if (closed) false else {
                        process = created
                        if (aboutRequested) created.about()
                        true
                    }
                }
                if (!accepted) created.close()
            } catch (error: Throwable) {
                IslandLog.failure("lifecycle", "linux.ui.start_failed", error)
                if (!synchronized(lock) { closed }) report(error)
            } finally { worker.shutdown() }
        }
    }

    fun about() = synchronized(lock) {
        if (!closed) {
            aboutRequested = true
            process?.about()
        }
    }

    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            process.also { process = null }
        }
        worker.shutdownNow()
        previous?.close()
    }
}
