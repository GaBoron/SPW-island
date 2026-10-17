// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

/** Serializes host callbacks and interpolates the host's one-second clock on a monotonic clock. */
fun interface PlaybackSource { fun snapshot(): PlaybackSnapshot }

class PlaybackTimeline(private val nanoTime: () -> Long = System::nanoTime) : PlaybackSource {
    companion object {
        private const val SEEK_ACK_WINDOW_NS = 2_500_000_000L
        private const val SEEK_ACK_TOLERANCE_MS = 2_000L
    }
    private var track: Track? = null
    private var line: LyricLine? = null
    private var callbackLyrics = LyricsDocument.EMPTY
    private var documentLyrics = LyricsDocument.EMPTY
    private var mergedLyrics = LyricsDocument.EMPTY
    private var position = 0L
    private var anchor = nanoTime()
    private var playing = false
    private var status = PlaybackStatus.IDLE
    private var metadata = TrackMetadata()
    private var generation = 0L
    private var pendingSeek: Long? = null
    private var pendingSeekDeadline = 0L
    // Host position and lyric callbacks can still arrive after pause; keep the displayed frame stable.
    private var pausedPresentation: PausedPresentation? = null
    private var awaitingPausedSeekLine = false
    private val heartbeatRecovery = PlaybackHeartbeatRecovery()
    private val rateTracker = PlaybackRateTracker()

    @Synchronized fun trackChanged(value: Track): Long {
        if (track != value) {
            track = value
            generation++
            metadata = TrackMetadata()
            line = null
            callbackLyrics = LyricsDocument.EMPTY
            documentLyrics = LyricsDocument.EMPTY
            mergedLyrics = LyricsDocument.EMPTY
            position = 0
            anchor = nanoTime()
            pendingSeek = null
            pausedPresentation = null
            awaitingPausedSeekLine = false
            heartbeatRecovery.trackChanged()
            rateTracker.reset()
        }
        return generation
    }
    @Synchronized fun metadataLoaded(token: Long, value: TrackMetadata) {
        if (generation == token && track != null) metadata = value
    }
    @Synchronized fun trackGeneration(): Long = generation

    /** Startup recovery preserves any position, state and lyrics already replayed by the host. */
    @Synchronized fun recoverTrack(token: Long, value: Track): Long? {
        if (generation != token || track != null || status == PlaybackStatus.ENDED) return null
        track = value
        return ++generation
    }
    @Synchronized fun lineChanged(value: LyricLine?) {
        // Null/blank callbacks mark instrumental gaps, not a request to erase the last lyric.
        if (value != null && value.text.isNotBlank()) {
            line = value
            // Public callbacks already carry start/end time. Retaining emitted lines is enough to
            // reconstruct overlaps while the host's full snapshot is loading.
            val updated = callbackLyrics.withUpdates(listOf(value))
            if (updated !== callbackLyrics) {
                callbackLyrics = updated
                mergedLyrics = if (documentLyrics.lines.isEmpty()) updated
                    else documentLyrics.withUpdates(updated.lines)
            }
            if (awaitingPausedSeekLine) {
                pausedPresentation = PausedPresentation(track, value,
                    pausedPresentation?.positionMs ?: position, mergedLyrics)
                awaitingPausedSeekLine = false
            }
        }
    }
    fun lyricsChanged(value: List<LyricLine>) {
        val document = LyricsDocument.EMPTY.withUpdates(value.filter {
            it.text.isNotBlank() && it.startMs >= 0 && it.endMs >= it.startMs
        })
        synchronized(this) {
            if (status == PlaybackStatus.ENDED) return
            // Every full snapshot is authoritative, including empty reload/clear notifications.
            callbackLyrics = LyricsDocument.EMPTY
            documentLyrics = document
            mergedLyrics = document
            val paused = pausedPresentation
            val retained = if (paused != null) paused.line else line
            line = retained?.takeIf { it in document.lines }
                ?: document.lastStarted(paused?.positionMs ?: currentPosition())
            pausedPresentation = paused?.copy(line = line, lyrics = document)
        }
    }
    @Synchronized fun positionChanged(value: Long) {
        val now = nanoTime()
        val next = value.coerceAtLeast(0)
        pendingSeek?.let { target ->
            // SPW can deliver one last pre-seek clock update before acknowledging the new position.
            if (now < pendingSeekDeadline && kotlin.math.abs(next - target) > SEEK_ACK_TOLERANCE_MS) return
            pendingSeek = null
        }
        if (heartbeatRecovery.positionChanged(next)) {
            // A plugin installed while SPW is already playing misses the earlier state callbacks.
            // Consecutive forward heartbeats prove the clock is running without guessing from one seek.
            playing = true
            if (status == PlaybackStatus.IDLE) status = PlaybackStatus.READY
        }
        rateTracker.positionChanged(next, now, playing && status == PlaybackStatus.READY)
        position = next
        anchor = now
    }
    @Synchronized fun seek(value: Long) {
        position = value.coerceAtLeast(0)
        anchor = nanoTime()
        pendingSeek = position
        pendingSeekDeadline = anchor + SEEK_ACK_WINDOW_NS
        heartbeatRecovery.seeked()
        rateTracker.discontinuity()
        // Await the host's replacement line; do not retain text from before a seek.
        line = null
        if (!playing) {
            pausedPresentation = PausedPresentation(track, null, position,
                mergedLyrics)
            awaitingPausedSeekLine = true
        }
    }
    @Synchronized fun playingChanged(value: Boolean) {
        val wasPlaying = playing
        position = currentPosition()
        anchor = nanoTime()
        heartbeatRecovery.playingChanged()
        rateTracker.discontinuity()
        playing = value
        if (value) {
            pausedPresentation = null
            awaitingPausedSeekLine = false
        } else if (wasPlaying) {
            pausedPresentation = PausedPresentation(track, line ?: mergedLyrics.lastStarted(position), position,
                mergedLyrics)
            awaitingPausedSeekLine = false
        }
        if (value && status == PlaybackStatus.IDLE) status = PlaybackStatus.READY
    }
    @Synchronized fun stateChanged(value: PlaybackStatus) {
        position = currentPosition()
        anchor = nanoTime()
        status = value
        heartbeatRecovery.stateChanged(value)
        if (value != PlaybackStatus.READY) rateTracker.discontinuity()
        if (value == PlaybackStatus.IDLE || value == PlaybackStatus.ENDED) {
            playing = false
            line = null
            callbackLyrics = LyricsDocument.EMPTY
            documentLyrics = LyricsDocument.EMPTY
            mergedLyrics = LyricsDocument.EMPTY
            pendingSeek = null
            pausedPresentation = null
            awaitingPausedSeekLine = false
            if (value == PlaybackStatus.IDLE) { track = null; position = 0; metadata = TrackMetadata(); generation++ }
        }
    }
    @Synchronized override fun snapshot(): PlaybackSnapshot {
        val now = currentPosition()
        val currentLine = line ?: mergedLyrics.lastStarted(now)
        val current = PlaybackSnapshot(track, currentLine, now, playing && status == PlaybackStatus.READY, status, metadata,
            mergedLyrics.lines, rateTracker.rate, mergedLyrics.usesWordTiming)
        val paused = pausedPresentation
        return if (!current.playing && paused != null && paused.track == track) current.copy(
            line = paused.line, positionMs = paused.positionMs, lyrics = paused.lyrics.lines,
            usesWordTiming = paused.lyrics.usesWordTiming
        ) else current
    }
    private data class PausedPresentation(val track: Track?, val line: LyricLine?,
                                          val positionMs: Long, val lyrics: LyricsDocument)

    private fun currentPosition(): Long = position + if (playing && status == PlaybackStatus.READY) {
        // Freeze on a missing host heartbeat rather than letting stale lyrics run indefinitely.
        (((nanoTime() - anchor) / 1_000_000.0).coerceIn(0.0, 2500.0) * rateTracker.rate).toLong()
    } else 0
}
