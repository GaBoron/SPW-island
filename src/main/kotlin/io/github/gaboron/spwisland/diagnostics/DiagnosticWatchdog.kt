// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Progress sampling must continue even while the file writer is waiting on the filesystem. */
internal class DiagnosticWatchdog : AutoCloseable {
    @Volatile var intervalNs = 1_000_000_000L
    @Volatile private var closed = false
    private val worker = DiagnosticExecutors.scheduled(IslandLog.threads("SPW Island diagnostic watchdog"))
    init { schedule() }
    private fun schedule() {
        if (closed) return
        try {
            worker.schedule({
                if (!closed) {
                    IslandLog.attempt("diagnostics", "watchdog.failed") {
                        DiagnosticOperation.checkTimeouts()
                        DiagnosticPulse.checkAll()
                    }
                    schedule()
                }
            }, intervalNs, TimeUnit.NANOSECONDS)
        } catch (error: RejectedExecutionException) {
            if (!closed) IslandLog.failure("diagnostics", "watchdog.schedule_failed", error)
        }
    }
    override fun close() { closed = true; worker.shutdownNow() }
}
