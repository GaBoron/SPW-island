// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.core.LyricLine
import io.github.gaboron.spwisland.core.PlaybackTimeline
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Coalesces missing lyric-axis requests without reflecting through the host on its callback thread. */
internal class HostLyricsRecovery(private val timeline: PlaybackTimeline,
                                  private val probe: () -> List<LyricLine>?,
                                  private val enabled: () -> Boolean) : AutoCloseable {
    private data class LineKey(val start: Long, val text: String)
    private data class Request(val generation: Long, val line: LineKey)
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "SPW Island host lyrics").apply { isDaemon = true } }).apply {
        allowCoreThreadTimeOut(true)
    }
    private var generation = Long.MIN_VALUE
    private var knownLines: Set<LineKey> = emptySet()
    private var pending: Request? = null
    private var probing = false
    private var retryAfter = 0L
    private var closed = false

    @Synchronized fun request(line: LyricLine) {
        if (closed || line.text.isBlank() || !enabled()) return
        val token = timeline.lyricsGeneration()
        if (generation != token) {
            generation = token
            knownLines = emptySet()
            retryAfter = 0L
            pending = null
        }
        val key = LineKey(line.startMs, line.text)
        if (key in knownLines || System.nanoTime() < retryAfter) return
        pending = Request(token, key)
        if (probing) return
        probing = true
        try { worker.execute(::recover) }
        catch (_: RejectedExecutionException) { probing = false; pending = null }
    }

    private fun recover() {
        while (true) {
            val request = synchronized(this) {
                val next = pending
                if (closed || next == null) { probing = false; return }
                pending = null
                next
            }
            val document = if (enabled()) runCatching(probe).getOrNull() else null
            val matches = document?.any { LineKey(it.startMs, it.text) == request.line } == true
            val accepted = document != null && matches && enabled() && timeline.lyricsLoaded(request.generation, document)
            val keys = if (accepted && document != null)
                document.mapTo(HashSet()) { LineKey(it.startMs, it.text) } else emptySet()
            synchronized(this) {
                if (closed) { probing = false; return }
                if (generation == request.generation) {
                    if (accepted) {
                        knownLines = keys
                        retryAfter = 0L
                        if (pending?.line?.let { it in knownLines } == true) pending = null
                    } else {
                        // A missing/late host document is optional; retry on a later callback, not in a busy loop.
                        retryAfter = System.nanoTime() + 2_000_000_000L
                        pending = null
                    }
                }
            }
        }
    }

    @Synchronized override fun close() {
        closed = true; pending = null; knownLines = emptySet(); worker.shutdownNow()
    }
}
