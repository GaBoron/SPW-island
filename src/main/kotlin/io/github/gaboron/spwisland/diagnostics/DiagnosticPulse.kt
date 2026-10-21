// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.util.concurrent.ConcurrentHashMap

/** Watch only observable progress. No screenshots, JVM hooks or calls into the EDT from the writer. */
internal class DiagnosticPulse(private val module: String, private val name: String) : AutoCloseable {
    @Volatile private var lastProgress = System.nanoTime()
    @Volatile private var value = Long.MIN_VALUE
    @Volatile private var owner = Thread.currentThread()
    @Volatile var expected = false
        set(next) { if (next != field) lastProgress = System.nanoTime(); field = next }
    @Volatile var timeoutNs = 5_000_000_000L
    private var firstFailure = 0L
    private var lastReport = 0L
    private var observations = 0L
    init { active[this] = true }
    fun beat(next: Long) {
        if (next != value) { value = next; owner = Thread.currentThread(); lastProgress = System.nanoTime() }
    }
    private fun check(now: Long) {
        if (expected && now - lastProgress >= timeoutNs) {
            observations++
            if (firstFailure == 0L) {
                firstFailure = now
                IslandLog.warn(module, "$name.stalled", mapOf("noProgressMs" to (now - lastProgress) / 1_000_000) +
                    DiagnosticPrivacy.thread(owner))
                lastReport = now
            } else if (now - lastReport >= 60_000_000_000L) {
                IslandLog.warn(module, "$name.still_stalled", mapOf("durationMs" to (now - firstFailure) / 1_000_000,
                    "observations" to observations))
                lastReport = now
            }
        } else if (firstFailure != 0L) {
            IslandLog.event(module, if (expected) "$name.recovered" else "$name.monitoring_paused",
                mapOf("durationMs" to (now - firstFailure) / 1_000_000, "observations" to observations))
            firstFailure = 0; observations = 0
        }
    }
    override fun close() { expected = false; active.remove(this) }
    companion object {
        private val active = ConcurrentHashMap<DiagnosticPulse, Boolean>()
        fun clear() { active.clear() }
        fun checkAll() { val now = System.nanoTime(); active.keys.forEach { it.check(now) } }
    }
}
