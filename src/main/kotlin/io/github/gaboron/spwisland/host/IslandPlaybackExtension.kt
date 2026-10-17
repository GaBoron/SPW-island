// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.gaboron.spwisland.core.*

/** Registered through META-INF/extensions.idx; never supplies or replaces the host's lyrics. */
class IslandPlaybackExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        IslandPlugin.playback.trackChanged(mediaItem.toTrack())
        return null
    }
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        IslandPlugin.playback.stateChanged(when (state) {
            PlaybackExtensionPoint.State.Idle -> PlaybackStatus.IDLE
            PlaybackExtensionPoint.State.Buffering -> PlaybackStatus.BUFFERING
            PlaybackExtensionPoint.State.Ready -> PlaybackStatus.READY
            PlaybackExtensionPoint.State.Ended -> PlaybackStatus.ENDED
        })
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) { IslandPlugin.playback.playingChanged(isPlaying) }
    override fun onPositionUpdated(position: Long) { IslandPlugin.playback.positionChanged(position) }
    override fun onSeekTo(position: Long) { IslandPlugin.playback.seek(position) }
    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        IslandPlugin.playback.lineChanged(lyricsLine?.toLyricLine())
    }
    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        IslandPlugin.playback.lyricsChanged(lyricsLines.map { it.toLyricLine() })
    }
}

private fun PlaybackExtensionPoint.LyricsLine.toLyricLine() =
    LyricLine(startTime, endTime, pureMainText, pureSubText,
        lyricsCells.map { Word(it.startTime, it.endTime, it.text) })
