// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Desktop Trash can be slow. It must not delay fault records or progress sampling. */
internal class DiagnosticRetention(private val storage: DiagnosticStorage) : AutoCloseable {
    private val pending = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor(IslandLog.threads("SPW Island log retention"))
    fun request() {
        if (closed.get() || !pending.compareAndSet(false, true)) return
        try {
            worker.execute {
                val operation = IslandLog.operation("diagnostics", "retention")
                try { storage.maintain(); operation.close() }
                catch (error: Throwable) { operation.failed(error) }
                finally { pending.set(false) }
            }
        } catch (error: Exception) {
            pending.set(false)
            if (!closed.get()) IslandLog.failure("diagnostics", "retention.schedule_failed", error)
        }
    }
    override fun close() { closed.set(true); worker.shutdownNow() }
}
