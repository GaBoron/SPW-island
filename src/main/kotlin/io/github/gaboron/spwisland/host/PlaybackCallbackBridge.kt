// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.core.LyricLine
import io.github.gaboron.spwisland.core.PlaybackStatus
import io.github.gaboron.spwisland.core.Track
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

/** Preserves host callbacks delivered before the plugin runtime is ready. */
internal class PlaybackCallbackBridge {
    private var runtime: IslandRuntime? = null
    private var track: Track? = null
    private var mediaItem: PlaybackExtensionPoint.MediaItem? = null
    private var status: PlaybackStatus? = null
    private var playing: Boolean? = null
    private var position: Long? = null
    private var line: LyricLine? = null
    private var lyrics: List<LyricLine>? = null

    @Synchronized fun attach(target: IslandRuntime) {
        track?.let { target.trackChanged(it, mediaItem) }
        status?.let(target::stateChanged)
        position?.let(target::positionChanged)
        playing?.let(target::playingChanged)
        lyrics?.let(target::lyricsChanged)
        line?.let(target::lineChanged)
        clearPending()
        runtime = target
    }

    @Synchronized fun detach(target: IslandRuntime) {
        if (runtime === target) runtime = null
        clearPending()
    }

    @Synchronized fun trackChanged(value: Track, item: PlaybackExtensionPoint.MediaItem? = null) {
        runtime?.let { it.trackChanged(value, item); return }
        track = value
        mediaItem = item
        line = null
        lyrics = null
        position = null
    }

    @Synchronized fun stateChanged(value: PlaybackStatus) {
        runtime?.let { it.stateChanged(value); return }
        status = value
        if (value == PlaybackStatus.IDLE || value == PlaybackStatus.ENDED) {
            playing = false
            line = null
            lyrics = null
            if (value == PlaybackStatus.IDLE) {
                track = null
                mediaItem = null
                position = 0
            }
        }
    }

    @Synchronized fun playingChanged(value: Boolean) {
        runtime?.let { it.playingChanged(value); return }
        playing = value
    }

    @Synchronized fun positionChanged(value: Long) {
        runtime?.let { it.positionChanged(value); return }
        position = value
    }

    @Synchronized fun seek(value: Long) {
        runtime?.let { it.seekChanged(value); return }
        position = value
        line = null
    }

    @Synchronized fun lineChanged(value: LyricLine?) {
        runtime?.let { it.lineChanged(value); return }
        if (value != null) line = value
    }

    @Synchronized fun lyricsChanged(value: List<LyricLine>) {
        runtime?.let { it.lyricsChanged(value); return }
        lyrics = value
        if (line !in value) line = null
    }

    private fun clearPending() {
        track = null
        mediaItem = null
        status = null
        playing = null
        position = null
        line = null
        lyrics = null
    }
}
