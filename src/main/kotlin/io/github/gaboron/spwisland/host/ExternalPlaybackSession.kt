// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors
import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.platform.LocalTrackMetadata
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor

/** External playback owns the timeline until its provider relinquishes the local device. */
internal class ExternalPlaybackSession(
    private val host: PlaybackSource,
    private val performance: () -> PerformanceProfile,
    private val enabled: () -> Boolean,
    private val restoreHost: () -> Unit,
) : PlaybackSource, AutoCloseable {
    private val timeline = PlaybackTimeline()
    val client = ExternalPlaybackClient()
    @Volatile var current: ExternalPlayback? = null
        private set
    private val worker = DiagnosticExecutors.scheduled(IslandLog.threads("SPW Island external playback"))
    private val artwork = DiagnosticExecutors.pool(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        IslandLog.threads("SPW Island external artwork"), ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var closed = false
    private var lyricsRevision = -1L
    private var requestedVolume: Double? = null
    private var volumeRequestedAt = 0L

    @Synchronized fun changeVolume(value: Double, absolute: Boolean): Double? {
        if (!value.isFinite()) return null
        val track = current ?: return null
        val now = System.nanoTime()
        val pending = requestedVolume?.takeIf { now - volumeRequestedAt < 2_000_000_000L }
        val next = (if (absolute) value else (pending ?: track.volumePercent / 100.0) + value).coerceIn(0.0, 1.0)
        if (!client.volume(track, (next * 100).toInt())) return null
        requestedVolume = next
        volumeRequestedAt = now
        return next
    }

    fun start() { worker.execute(::tick) }

    private fun tick() {
        if (closed) return
        try { update(if (enabled()) client.snapshot() else null) }
        finally {
            if (!closed) worker.schedule(::tick, if (enabled()) performance().externalPlaybackIntervalMs else 5_000L,
                TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized override fun snapshot(): PlaybackSnapshot =
        if (current == null) host.snapshot() else timeline.snapshot()

    @Synchronized fun seekPreview(track: ExternalPlayback, positionMs: Long) {
        if (!closed && current?.identity == track.identity) timeline.seek(positionMs)
    }

    @Synchronized private fun update(next: ExternalPlayback?) {
        if (closed) return
        val previous = current
        current = next
        if (next == null) {
            if (previous != null) {
                lyricsRevision = -1L
                timeline.stateChanged(PlaybackStatus.IDLE)
                restoreHost()
            }
            return
        }
        val changedTrack = next.identity != previous?.identity
        if (changedTrack) {
            requestedVolume = null
            timeline.stateChanged(PlaybackStatus.IDLE)
            val token = timeline.trackChanged(Track(next.title, next.artist, "", next.identity))
            timeline.metadataLoaded(token, TrackMetadata(next.durationMs))
            timeline.stateChanged(PlaybackStatus.READY)
            lyricsRevision = -1L
        } else if (next.title != previous.title || next.artist != previous.artist) {
            timeline.updateTrackMetadata(Track(next.title, next.artist, "", next.identity))
        }
        if (!changedTrack && next.durationMs != previous.durationMs) {
            timeline.metadataLoaded(timeline.trackGeneration(), timeline.snapshot().metadata.copy(durationMs = next.durationMs))
        }
        if (changedTrack || next.coverUrl != previous.coverUrl) {
            val token = timeline.trackGeneration()
            timeline.metadataLoaded(token, TrackMetadata(next.durationMs))
            if (next.coverUrl.isNotBlank()) {
                artwork.execute {
                    val value = LocalTrackMetadata.fromArtwork(0, readExternalArtwork(next.coverUrl))
                    synchronized(this) {
                        val selected = current
                        if (!closed && selected?.identity == next.identity && selected.coverUrl == next.coverUrl)
                            timeline.metadataLoaded(token, value.copy(durationMs = selected.durationMs))
                    }
                }
            }
        }
        if (previous?.identity != next.identity || previous.playing != next.playing) timeline.playingChanged(next.playing)
        timeline.positionChanged(next.currentPosition())
        timeline.externalPlaybackRate(next.playbackSpeed)
        val lyrics = client.lyrics(next)
        val revision = lyrics?.revision ?: -1L
        if (revision != lyricsRevision || next.durationMs != previous?.durationMs) {
            lyricsRevision = revision
            val offset = lyrics?.offsetMs ?: 0
            val lines = if (lyrics == null || lyrics.format == "empty") emptyList() else
                ExternalSplReader.read(lyrics.spl, next.durationMs).map { line ->
                    LyricLine((line.startMs + offset).coerceAtLeast(0), (line.endMs + offset).coerceAtLeast(0),
                        line.text, line.translation.takeIf(String::isNotBlank), line.words.map {
                            Word((it.startMs + offset).coerceAtLeast(0), (it.endMs + offset).coerceAtLeast(0), it.text)
                        })
                }
            timeline.lyricsChanged(lines)
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            current = null
        }
        worker.shutdownNow()
        artwork.shutdownNow()
    }
}
