// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import io.github.gaboron.spwisland.core.PerformanceProfile

/** Fixed-size histogram: no per-frame records or sample arrays; percentiles are bucket upper bounds. */
internal class DiagnosticLatency(
    private val name: String,
    private val details: () -> Map<String, Any?> = { emptyMap() }
) : AutoCloseable {
    private val buckets = LongArray(256)
    private val session = IslandLog.session
    private var profile: PerformanceProfile? = null
    private var started = System.nanoTime()
    private var count = 0L
    private var totalNs = 0L
    private var maximumNs = 0L
    private var overBudget = 0L

    @Synchronized fun record(durationNs: Long, budgetNs: Long, current: PerformanceProfile) {
        if (session != IslandLog.session) return
        val now = System.nanoTime()
        if (profile != current) {
            publish(now)
            profile = current
        }
        val elapsed = durationNs.coerceAtLeast(0)
        val micros = (elapsed / 1_000 + if (elapsed % 1_000 > 0) 1 else 0).coerceAtLeast(1)
        val exponent = 63 - java.lang.Long.numberOfLeadingZeros(micros)
        val base = 1L shl exponent
        val fraction = ((micros - base) * 4 / base).toInt()
        buckets[exponent * 4 + fraction]++
        count++
        totalNs += elapsed
        maximumNs = maxOf(maximumNs, elapsed)
        if (budgetNs > 0 && elapsed > budgetNs) overBudget++
        if (now - started >= current.performanceReportIntervalNs) publish(now)
    }

    private fun percentile(fraction: Double): Double {
        val rank = kotlin.math.ceil(count * fraction).toLong()
        var seen = 0L
        for (index in buckets.indices) {
            seen += buckets[index]
            if (seen >= rank) {
                val base = (1L shl (index / 4)).toDouble()
                return base * (1 + (index % 4 + 1) / 4.0) / 1_000
            }
        }
        return maximumNs / 1_000_000.0
    }

    private fun publish(now: Long) {
        if (count > 0 && session == IslandLog.session) {
            IslandLog.event("performance", name, mapOf(
                "profile" to profile, "samples" to count, "windowMs" to (now - started) / 1_000_000.0,
                "meanMs" to totalNs / count / 1_000_000.0,
                "p50UpperMs" to percentile(.50), "p95UpperMs" to percentile(.95),
                "p99UpperMs" to percentile(.99), "maxMs" to maximumNs / 1_000_000.0,
                "overBudget" to overBudget, "evidence" to "cpu_wall_time_not_gpu_completion"
            ) + details())
        }
        buckets.fill(0)
        count = 0; totalNs = 0; maximumNs = 0; overBudget = 0
        started = now
    }

    @Synchronized override fun close() { publish(System.nanoTime()) }
}
