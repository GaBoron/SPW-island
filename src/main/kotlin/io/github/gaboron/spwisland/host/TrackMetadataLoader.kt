// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors

import io.github.gaboron.spwisland.core.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future

/** One worker and one pending request; generation checks reject late results after A/B/A switches. */
internal class TrackMetadataLoader(
    private val timeline: PlaybackTimeline,
    private val current: () -> PlaybackExtensionPoint.MediaItem? = WorkshopTrackMetadata::current,
    private val read: (Track, PlaybackExtensionPoint.MediaItem?) -> TrackMetadata = WorkshopTrackMetadata::read,
    private val performance: () -> PerformanceProfile = { PerformanceProfile.STANDARD },
    private val enabled: () -> Boolean = { true },
) : AutoCloseable {
    private val executor = DiagnosticExecutors.scheduled(IslandLog.threads("SPW Island track metadata")).apply {
        removeOnCancelPolicy = true
    }
    private var pending: Future<*>? = null
    private var token: Long? = null
    private var recoveryGeneration: Long? = null
    private var started = false
    private var closed = false
    private var observationQueued = false
    private var lastObservationAt = 0L

    @Synchronized fun start() {
        if (closed || started) return
        started = true
        recoverMissingTrack(allowIdle = true)
    }

    /** Recovery and foreign-source observation share the metadata worker and the performance budget. */
    @Synchronized fun recoverMissingTrack(allowIdle: Boolean = false) {
        if (closed || !started) return
        val generation = timeline.missingTrackGeneration(allowIdle) ?: return
        if (recoveryGeneration == generation) return
        pending?.cancel(true)
        recoveryGeneration = generation
        pending = executor.submit { recover(generation, allowIdle) }
    }

    private fun recover(generation: Long, allowIdle: Boolean) {
        if (!recoveryIsCurrent(generation, allowIdle)) return
        val item = IslandLog.attempt("TrackMetadataLoader", "recovery.current_failed", current).getOrNull()
        if (item == null) {
            synchronized(this) {
                if (!recoveryIsCurrent(generation, allowIdle)) return
                // Resume can precede host media availability. Retry only while playback is active,
                // including paused READY state, so recovery does not depend on another callback.
                if (timeline.missingTrackGeneration() == generation) {
                    pending = executor.schedule({ recover(generation, allowIdle = false) },
                        performance().metadataRecoveryIntervalMs, TimeUnit.MILLISECONDS)
                } else recoveryGeneration = null
            }
            return
        }
        val track = item.toTrack()
        val recovered = synchronized(this) {
            if (!recoveryIsCurrent(generation, allowIdle)) return
            recoveryGeneration = null
            timeline.recoverTrack(generation, track)?.also { token = it } ?: return
        }
        IslandLog.event("metadata", "track.recovered", mapOf("generation" to recovered))
        publish(recovered, track, item)
    }

    @Synchronized private fun recoveryIsCurrent(generation: Long, allowIdle: Boolean): Boolean {
        if (closed || recoveryGeneration != generation) return false
        if (timeline.missingTrackGeneration(allowIdle) == generation) return true
        recoveryGeneration = null
        return false
    }

    @Synchronized fun load(track: Track, item: PlaybackExtensionPoint.MediaItem? = null) {
        if (closed) return
        val generation = timeline.trackChanged(track)
        if (token == generation) return
        token = generation
        lastObservationAt = System.nanoTime()
        recoveryGeneration = null
        pending?.cancel(true)
        pending = executor.submit {
            publish(generation, track, item ?: IslandLog.attempt("TrackMetadataLoader", "load.attempt2", current).getOrNull())
        }
    }

    /** Host position/state callbacks also detect source changes that omitted a lyric-load callback. */
    @Synchronized fun observeCurrent(force: Boolean = false) {
        if (closed || !started || !enabled() || observationQueued) return
        val snapshot = timeline.snapshot()
        if (!force && snapshot.status in setOf(PlaybackStatus.IDLE, PlaybackStatus.ENDED)) return
        val now = System.nanoTime()
        if (!force && lastObservationAt != 0L && now - lastObservationAt <
            TimeUnit.MILLISECONDS.toNanos(performance().metadataRecoveryIntervalMs)) return
        lastObservationAt = now
        observationQueued = true
        val expectedGeneration = timeline.trackGeneration()
        executor.execute {
            try {
                val item = IslandLog.attempt("TrackMetadataLoader", "observe.current_failed", current).getOrNull()
                    ?: return@execute
                synchronized(this) {
                    if (closed || !enabled() || timeline.trackGeneration() != expectedGeneration) return@execute
                    val selected = timeline.snapshot()
                    val track = item.toTrack()
                    if (selected.track != track) load(track, item)
                    else if (item.duration > 0 && item.duration != selected.metadata.durationMs)
                        timeline.metadataLoaded(timeline.trackGeneration(), selected.metadata.copy(durationMs = item.duration))
                }
            } finally { synchronized(this) { observationQueued = false } }
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
        closed = true
        recoveryGeneration = null
        pending?.cancel(true)
        executor.shutdownNow()
    }
}
