// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Operation IDs connect begin/result/timeout/recovery without logging input payloads. */
internal class DiagnosticOperation(private val module: String, private val name: String) : AutoCloseable {
    private val id = UUID.randomUUID().toString()
    private val started = System.nanoTime()
    private val finished = AtomicBoolean()
    private val timedOut = AtomicBoolean()
    @Volatile private var owner = Thread.currentThread()
    init {
        active[id] = this
        IslandLog.event(module, "$name.started", mapOf("operation" to id))
    }
    fun runningHere() { owner = Thread.currentThread() }
    fun failed(error: Throwable) {
        if (finished.compareAndSet(false, true)) {
            active.remove(id)
            IslandLog.failure(module, "$name.failed", error, context())
        }
    }
    fun cancel() {
        if (finished.compareAndSet(false, true)) {
            active.remove(id)
            IslandLog.event(module, "$name.cancelled", context())
        }
    }
    override fun close() {
        if (finished.compareAndSet(false, true)) {
            active.remove(id)
            IslandLog.event(module, if (timedOut.get()) "$name.recovered" else "$name.completed", context())
        }
    }
    private fun context() = mapOf("operation" to id, "elapsedMs" to (System.nanoTime() - started) / 1_000_000)
    companion object {
        private val active = ConcurrentHashMap<String, DiagnosticOperation>()
        fun pending() = active.isNotEmpty()
        fun cancelAll() { active.values.forEach { it.cancel() } }
        fun checkTimeouts() {
            active.values.forEach { operation ->
                if (System.nanoTime() - operation.started > 10_000_000_000L && operation.timedOut.compareAndSet(false, true))
                    IslandLog.warn(operation.module, "${operation.name}.timeout", operation.context() + DiagnosticPrivacy.thread(operation.owner))
            }
        }
    }
}
