// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors

import io.github.gaboron.spwisland.core.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future

/** One bounded worker, latest request wins; generation checks reject late results after A/B/A switches. */
internal class TrackMetadataLoader(
    private val timeline: PlaybackTimeline,
    private val current: () -> PlaybackExtensionPoint.MediaItem? = WorkshopTrackMetadata::current,
    private val read: (Track, PlaybackExtensionPoint.MediaItem?) -> TrackMetadata = WorkshopTrackMetadata::read
) : AutoCloseable {
    private val executor = DiagnosticExecutors.pool(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "SPW Island track metadata").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("TrackMetadataLoader") } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private var pending: Future<*>? = null
    private var token: Long? = null
    private var closed = false
    @Synchronized fun start() {
        if (closed || pending != null) return
        val initial = timeline.trackGeneration()
        pending = executor.submit {
            val item = IslandLog.attempt("TrackMetadataLoader", "start.attempt1", current).getOrNull() ?: return@submit
            val track = item.toTrack()
            val generation = synchronized(this) {
                if (closed) return@submit
                timeline.recoverTrack(initial, track)?.also { token = it } ?: return@submit
            }
            publish(generation, track, item)
        }
    }
    @Synchronized fun load(track: Track) {
        if (closed) return
        val generation = timeline.trackChanged(track)
        if (token == generation) return
        token = generation
        pending?.cancel(true)
        pending = executor.submit {
            publish(generation, track, IslandLog.attempt("TrackMetadataLoader", "load.attempt2", current).getOrNull())
        }
    }
    private fun publish(generation: Long, track: Track, item: PlaybackExtensionPoint.MediaItem?) {
        val operation = IslandLog.operation("metadata", "read")
        val result = IslandLog.attempt("TrackMetadataLoader", "publish.attempt3") { read(track, item) }
        result.exceptionOrNull()?.let(operation::failed) ?: operation.close()
        val value = result.getOrDefault(TrackMetadata())
        IslandLog.event("metadata", "read.result", mapOf("generation" to generation,
            "durationAvailable" to (value.durationMs > 0), "coverAvailable" to (value.cover != null)))
        synchronized(this) { if (!closed) timeline.metadataLoaded(generation, value) }
    }
    @Synchronized override fun close() {
        closed = true; pending?.cancel(true); executor.shutdownNow()
    }
}
