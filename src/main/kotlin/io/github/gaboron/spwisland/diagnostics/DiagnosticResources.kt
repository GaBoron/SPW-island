// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean

/** Sample on the existing watchdog, never on the host/EDT or through a GPU synchronization fence. */
internal class DiagnosticResources {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private val memory = ManagementFactory.getMemoryMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private var previousNs = System.nanoTime()
    private var previousCpuNs = os?.processCpuTime ?: -1L
    private var previousGcCount = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
    private var previousGcMs = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }

    fun sample(now: Long, intervalNs: Long) {
        val elapsed = now - previousNs
        if (elapsed < intervalNs) return
        val cpuNs = os?.processCpuTime ?: -1L
        val gcCount = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
        val gcMs = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
        IslandLog.event("performance", "jvm.resources", mapOf(
            "scope" to "current_jvm_includes_all_plugins", "intervalMs" to elapsed / 1_000_000.0,
            "cpuCorePercent" to if (cpuNs >= 0 && previousCpuNs >= 0)
                (cpuNs - previousCpuNs) * 100.0 / elapsed else null,
            "heapUsedMiB" to memory.heapMemoryUsage.used / 1_048_576.0,
            "heapCommittedMiB" to memory.heapMemoryUsage.committed / 1_048_576.0,
            "nonHeapUsedMiB" to memory.nonHeapMemoryUsage.used / 1_048_576.0,
            "threads" to threads.threadCount, "gcCollections" to gcCount - previousGcCount,
            "gcMs" to gcMs - previousGcMs,
            "gpuAndNativeMemory" to "external_profiler_required"
        ))
        previousNs = now; previousCpuNs = cpuNs
        previousGcCount = gcCount; previousGcMs = gcMs
    }
}
