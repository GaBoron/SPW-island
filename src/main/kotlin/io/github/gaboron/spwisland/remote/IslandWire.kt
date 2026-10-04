// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.core.*
import java.io.Serializable

/** Private, anonymous parent/child pipes. No sockets, host objects, or arbitrary class loading. */
internal data class IslandState(
    val snapshot: PlaybackSnapshot,
    val settings: IslandSettings?,
    val metadata: TrackMetadata?,
    val lyrics: List<LyricLine>?,
    val about: Boolean = false,
    val acknowledgedCommand: Long = 0
) : Serializable

internal data class IslandCommand(val action: String, val arguments: List<String> = emptyList(),
                                  val requestId: Long = 0) : Serializable

/** Small audio frames travel separately; lyric/cover payloads keep their original cadence. */
internal data class IslandSpectrum(val levels: FloatArray, val fallback: Boolean, val status: String) : Serializable

internal class RemoteSpectrum {
    @Volatile private var frame = IslandSpectrum(FloatArray(4), false, "正在连接 SPW 音频")
    @Volatile private var received = 0L
    fun accept(value: IslandSpectrum) {
        frame = value.copy(levels = if (value.levels.size == 4) value.levels.map {
            if (it.isFinite()) it.coerceIn(0f, 1f) else 0f
        }.toFloatArray() else FloatArray(4))
        received = System.nanoTime()
    }
    fun levels() = if (System.nanoTime() - received < 350_000_000L) frame.levels else FloatArray(4)
    fun fallback() = frame.fallback
    fun status() = frame.status
}

internal class RemotePlayback(private val nanoTime: () -> Long = System::nanoTime) : PlaybackSource {
    private var value = PlaybackSnapshot(null, null, 0, false, PlaybackStatus.IDLE)
    private var received = nanoTime()
    private data class Seek(val requestId: Long, val track: Track?, val position: Long, val started: Long)
    private var pending: Seek? = null
    @Synchronized fun seek(positionMs: Long, send: (Long) -> Long) {
        val target = positionMs.coerceIn(0, value.metadata.durationMs.coerceAtLeast(0))
        val requestId = send(target)
        pending = Seek(requestId, value.track, target, nanoTime())
    }
    @Synchronized fun accept(state: IslandState) {
        value = state.snapshot.copy(metadata = state.metadata ?: value.metadata, lyrics = state.lyrics ?: value.lyrics)
        received = nanoTime()
        pending?.let {
            if (value.track != it.track || state.acknowledgedCommand >= it.requestId) pending = null
        }
    }
    @Synchronized override fun snapshot(): PlaybackSnapshot {
        val seek = pending
        val position = seek?.position ?: value.positionMs
        val elapsed = if (value.playing) (((nanoTime() - (seek?.started ?: received)) / 1_000_000.0)
            .coerceIn(0.0, 2500.0) * value.playbackRate).toLong() else 0
        return value.copy(positionMs = (position + elapsed).coerceAtMost(
            value.metadata.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE), line = if (seek != null) null else value.line)
    }
}
