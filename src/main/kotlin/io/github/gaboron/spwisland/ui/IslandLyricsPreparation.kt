// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.*
import java.awt.Font
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Prepares a few imminent lines without waiting on the presentation thread or warming an entire song. */
internal class IslandLyricsPreparation(private val layouts: IslandLyricsLayout.Cache,
                                      private val report: (Throwable) -> Unit) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "island-lyrics-preparation").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    private var track: Track? = null
    private var line: LyricLine? = null
    private var lyrics: List<LyricLine>? = null
    private var settings: IslandSettings? = null
    private var coverRgb: Int? = null
    private var position = 0L
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile var preparedHeight = 0
        private set

    fun update(snapshot: PlaybackSnapshot, nextSettings: IslandSettings) {
        if (closed) return
        val jumped = abs(snapshot.positionMs - position) > 3_000
        position = snapshot.positionMs
        if (!jumped && track == snapshot.track && line == snapshot.line && lyrics == snapshot.lyrics &&
            settings == nextSettings && coverRgb == snapshot.metadata.coverRgb) return
        track = snapshot.track; line = snapshot.line; lyrics = snapshot.lyrics
        settings = nextSettings; coverRgb = snapshot.metadata.coverRgb
        val request = ++generation
        try {
            worker.execute {
                if (closed || request != generation) return@execute
                runCatching { prepare(snapshot, nextSettings, request) }.onFailure(report)
            }
        } catch (_: RejectedExecutionException) { /* Closing the panel cancels optional preparation. */ }
    }

    private fun prepare(snapshot: PlaybackSnapshot, settings: IslandSettings, request: Long) {
        val infoFont = SystemUiFont.derive(Font.PLAIN, 12f)
        LyricTypography.shape(trackLabel(snapshot.track), infoFont)
        val reached = maxOf(snapshot.positionMs, snapshot.line?.startMs ?: 0L)
        val lookAhead = if (settings.performance.animateLayout) 3 else 1
        val upcoming = snapshot.lyrics.asSequence().filter { it.text.isNotBlank() && it.startMs > reached }
            .sortedWith(compareBy<LyricLine> { it.startMs }.thenBy { it.endMs })
            .take(lookAhead).toList()
        val outline = IslandPalette.from(settings, snapshot.metadata.coverRgb).lyricOutline
        for (sample in listOf(snapshot) + upcoming.map { snapshot.copy(line = it, positionMs = it.startMs) }) {
            if (closed || request != generation) return
            val layout = layouts.forSnapshot(sample, settings)
            preparedHeight = maxOf(preparedHeight, layout.preferredHeight)
            for (block in layout.blocks) {
                if (closed || request != generation) return
                if (outline) {
                    block.shapedMain.outline
                    block.shapedSub?.outline
                }
                if (settings.karaoke && settings.performance.detailedKaraoke && block.timedWords.isNotEmpty())
                    WordGeometry.ready(block.shapedMain, block.main, block.timedWords.map { it.text })
            }
        }
    }

    override fun close() {
        closed = true; generation++; worker.shutdownNow()
    }

    companion object {
        fun trackLabel(track: Track?): String = listOfNotNull(track?.title, track?.artist)
            .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "在 SPW 中播放音乐" }
    }
}
