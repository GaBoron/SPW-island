// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

data class Track(val title: String, val artist: String, val path: String) : java.io.Serializable
data class Word(val startMs: Long, val endMs: Long, val text: String) : java.io.Serializable {
    fun progress(position: Long): Double = when {
        position < startMs -> 0.0
        endMs <= startMs -> 1.0
        else -> ((position - startMs).toDouble() / (endMs - startMs)).coerceIn(0.0, 1.0)
    }
}
data class LyricLine(val startMs: Long, val endMs: Long, val text: String,
                     val translation: String?, val words: List<Word>) : java.io.Serializable {
    // Keep every trustworthy cell, including a single cell spanning the full line. Whether those
    // cells represent line or word timing can only be decided from the complete song.
    val timedWords: List<Word> = words.takeIf { cells ->
        cells.isNotEmpty() && cells.joinToString("") { it.text } == text &&
            cells.all { it.endMs >= it.startMs } &&
            cells.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }
    }.orEmpty()
    val hasWordTimingEvidence: Boolean = timedWords.any { it.startMs != startMs || it.endMs != endMs }
}

enum class PlaybackStatus { IDLE, BUFFERING, READY, ENDED }
data class PlaybackSnapshot(val track: Track?, val line: LyricLine?, val positionMs: Long,
                            val playing: Boolean, val status: PlaybackStatus,
                            val metadata: TrackMetadata = TrackMetadata(),
                            val lyrics: List<LyricLine> = emptyList(),
                            val playbackRate: Double = 1.0,
                            val usesWordTiming: Boolean = lyrics.ifEmpty { listOfNotNull(line) }
                                .any { it.hasWordTimingEvidence }) : java.io.Serializable

class CoverArtwork(val width: Int, val height: Int, internal val argb: IntArray) : java.io.Serializable
data class TrackMetadata(val durationMs: Long = 0, val coverRgb: Int? = null,
                         val cover: CoverArtwork? = null) : java.io.Serializable
