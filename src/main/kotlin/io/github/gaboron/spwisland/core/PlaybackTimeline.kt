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
    private var callbackLyrics: List<LyricLine> = emptyList()
    private var documentLyrics: List<LyricLine> = emptyList()
    private var mergedLyrics: List<LyricLine> = emptyList()
    private var position = 0L
    private var anchor = nanoTime()
    private var playing = false
    private var status = PlaybackStatus.IDLE
    private var metadata = TrackMetadata()
    private var generation = 0L
    private var lyricsEpoch = 0L
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
            lyricsEpoch++
            metadata = TrackMetadata()
            line = null
            callbackLyrics = emptyList()
            documentLyrics = emptyList()
            mergedLyrics = emptyList()
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
    @Synchronized fun lineChanged(value: LyricLine?) {
        // Null/blank callbacks mark instrumental gaps, not a request to erase the last lyric.
        if (value != null && value.text.isNotBlank()) {
            line = value
            // Public callbacks already carry start/end time. Retaining emitted lines is enough to
            // reconstruct every overlap even when the experimental host-document probe is unavailable.
            callbackLyrics = mergeLyrics(callbackLyrics, listOf(value))
            mergedLyrics = mergeLyrics(documentLyrics, callbackLyrics)
            if (awaitingPausedSeekLine) {
                pausedPresentation = PausedPresentation(track, value,
                    pausedPresentation?.positionMs ?: position, mergedLyrics)
                awaitingPausedSeekLine = false
            }
        }
    }
    @Synchronized fun lyricsGeneration(): Long = lyricsEpoch

    fun lyricsLoaded(token: Long, value: List<LyricLine>): Boolean {
        val document = mergeLyrics(emptyList(), value.filter {
            it.text.isNotBlank() && it.startMs >= 0 && it.endMs >= it.startMs
        })
        return synchronized(this) {
            if (lyricsEpoch != token || track == null || status == PlaybackStatus.ENDED) return false
            documentLyrics = document
            mergedLyrics = mergeLyrics(documentLyrics, callbackLyrics)
            true
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
            pausedPresentation = PausedPresentation(track, line, position,
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
            lyricsEpoch++
            playing = false
            line = null
            callbackLyrics = emptyList()
            documentLyrics = emptyList()
            mergedLyrics = emptyList()
            pendingSeek = null
            pausedPresentation = null
            awaitingPausedSeekLine = false
            if (value == PlaybackStatus.IDLE) { track = null; position = 0; metadata = TrackMetadata(); generation++ }
        }
    }
    @Synchronized override fun snapshot(): PlaybackSnapshot {
        val now = currentPosition()
        val current = PlaybackSnapshot(track, line, now, playing && status == PlaybackStatus.READY, status, metadata,
            mergedLyrics, rateTracker.rate)
        val paused = pausedPresentation
        return if (!current.playing && paused != null && paused.track == track) current.copy(
            line = paused.line, positionMs = paused.positionMs, lyrics = paused.lyrics
        ) else current
    }
    private data class PausedPresentation(val track: Track?, val line: LyricLine?,
                                          val positionMs: Long, val lyrics: List<LyricLine>)
    private data class LineKey(val start: Long, val end: Long, val text: String)
    private fun mergeLyrics(existing: List<LyricLine>, updates: List<LyricLine>): List<LyricLine> {
        val merged = LinkedHashMap<LineKey, LyricLine>(existing.size + updates.size)
        for (line in existing) merged[LineKey(line.startMs, line.endMs, line.text)] = line
        for (line in updates) {
            val key = LineKey(line.startMs, line.endMs, line.text)
            // A replacement moves to the end before stable sorting, matching callback precedence.
            merged.remove(key)
            merged[key] = line
        }
        return merged.values.sortedWith(compareBy<LyricLine> { it.startMs }.thenBy { it.endMs })
    }

    private fun currentPosition(): Long = position + if (playing && status == PlaybackStatus.READY) {
        // Freeze on a missing host heartbeat rather than letting stale lyrics run indefinitely.
        (((nanoTime() - anchor) / 1_000_000.0).coerceIn(0.0, 2500.0) * rateTracker.rate).toLong()
    } else 0
}
