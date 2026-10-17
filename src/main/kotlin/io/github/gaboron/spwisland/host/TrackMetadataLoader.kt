// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

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
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "SPW Island track metadata").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private var pending: Future<*>? = null
    private var token: Long? = null
    private var closed = false
    @Synchronized fun start() {
        if (closed || pending != null) return
        val initial = timeline.trackGeneration()
        pending = executor.submit {
            val item = runCatching(current).getOrNull() ?: return@submit
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
            publish(generation, track, runCatching(current).getOrNull())
        }
    }
    private fun publish(generation: Long, track: Track, item: PlaybackExtensionPoint.MediaItem?) {
        val value = runCatching { read(track, item) }.getOrDefault(TrackMetadata())
        synchronized(this) { if (!closed) timeline.metadataLoaded(generation, value) }
    }
    @Synchronized override fun close() {
        closed = true; pending?.cancel(true); executor.shutdownNow()
    }
}
