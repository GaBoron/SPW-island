// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.diagnostics.IslandLog

/** Registered through META-INF/extensions.idx; never supplies or replaces the host's lyrics. */
class IslandPlaybackExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        callback("load_lyrics") { IslandPlugin.playback.trackChanged(mediaItem.toTrack()) }
        return null
    }
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        IslandLog.state("playback", "state", state.toString())
        callback("state") { IslandPlugin.playback.stateChanged(when (state) {
            PlaybackExtensionPoint.State.Idle -> PlaybackStatus.IDLE
            PlaybackExtensionPoint.State.Buffering -> PlaybackStatus.BUFFERING
            PlaybackExtensionPoint.State.Ready -> PlaybackStatus.READY
            PlaybackExtensionPoint.State.Ended -> PlaybackStatus.ENDED
        }) }
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        IslandLog.state("playback", "playing", isPlaying.toString())
        callback("playing") { IslandPlugin.playback.playingChanged(isPlaying) }
    }
    override fun onPositionUpdated(position: Long) { callback("position") { IslandPlugin.playback.positionChanged(position) } }
    override fun onSeekTo(position: Long) {
        IslandLog.event("playback", "seek.callback", mapOf("positionMs" to position))
        callback("seek") { IslandPlugin.playback.seek(position) }
    }
    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        callback("lyric_line") { IslandPlugin.playback.lineChanged(lyricsLine?.toLyricLine()) }
    }
    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        callback("lyric_document") { IslandPlugin.playback.lyricsChanged(lyricsLines.map { it.toLyricLine() }) }
    }
    private inline fun callback(name: String, action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            IslandLog.failure("playback", "$name.callback_failed", error); throw error
        }
    }
}

private fun PlaybackExtensionPoint.LyricsLine.toLyricLine() =
    LyricLine(startTime, endTime, pureMainText, pureSubText,
        lyricsCells.map { Word(it.startTime, it.endTime, it.text) })
