// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** File events trigger reads; a short retry window handles partial writes and atomic replacement. */
internal class IslandConfigWatch(private val path: Path, private val refresh: () -> Boolean,
                                 private val report: (Throwable) -> Unit) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "SPW Island settings sync").apply { isDaemon = true }
    }
    private val service = runCatching { path.fileSystem.newWatchService() }.onFailure(report).getOrNull()
    private var key: WatchKey? = null
    private var pending: ScheduledFuture<*>? = null
    private var generation = 0L
    private var retryUntil = 0L
    @Volatile private var closed = false

    fun start() {
        executor.scheduleWithFixedDelay({
            if (!closed) { register(); request(0) }
        }, 0, 5, TimeUnit.SECONDS)
        service?.let { watcher ->
            Thread({
                try {
                    while (!closed) {
                        val eventKey = watcher.take()
                        val changed = eventKey.pollEvents().any {
                            it.kind() == OVERFLOW || it.context() == path.fileName
                        }
                        if (!eventKey.reset()) synchronized(lock) { if (key === eventKey) key = null }
                        if (changed) request()
                    }
                } catch (_: ClosedWatchServiceException) {
                    // Closing the watcher wakes its blocking reader.
                } catch (error: Exception) {
                    if (!closed) report(error)
                }
            }, "SPW Island config events").apply { isDaemon = true }.start()
        }
    }

    private fun register(): Unit = synchronized(lock) {
        if (closed || service == null || key?.isValid == true || !Files.isDirectory(path.parent)) return
        runCatching { key = path.parent.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }
            .onFailure(report)
        Unit
    }

    fun request(delayMs: Long = 50): Unit = synchronized(lock) {
        if (closed) return
        retryUntil = System.nanoTime() + 2_000_000_000L
        pending?.cancel(false)
        schedule(++generation, delayMs)
    }

    private fun schedule(token: Long, delayMs: Long) {
        pending = executor.schedule(job@{
            if (closed || synchronized(lock) { token != generation }) return@job
            val complete = runCatching(refresh).onFailure(report).getOrDefault(false)
            synchronized(lock) {
                if (!closed && token == generation) {
                    pending = null
                    if (!complete && System.nanoTime() < retryUntil) schedule(token, 250)
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        synchronized(lock) { closed = true; pending?.cancel(false); key?.cancel() }
        try { service?.close() } finally { executor.shutdownNow() }
    }
}
