// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.diagnostics.DiagnosticExecutors

import java.awt.Shape
import java.awt.geom.Rectangle2D
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import io.github.gaboron.spwisland.core.Word

/** Bounded background preparation keeps outline intersections off the animation thread. */
object WordGeometry {
    data class Cluster(val bounds: Rectangle2D, val shape: Shape)
    data class Cell(val bounds: Rectangle2D, val clusters: List<Cluster>)
    private data class Key(val shaped: ShapedText, val words: List<Word>)
    private val worker = DiagnosticExecutors.pool(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "island-word-geometry").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("WordGeometry") } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val cache = object : LinkedHashMap<Key, CompletableFuture<List<Cell>?>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CompletableFuture<List<Cell>?>>) = size > 8
    }

    @Synchronized fun ready(shaped: ShapedText, text: String, words: List<Word>): List<Cell>? {
        val key = Key(shaped, words)
        val future = cache[key] ?: try {
            CompletableFuture.supplyAsync({ prepare(shaped, text, words).also {
                if (it == null) IslandLog.warn("lyrics", "geometry.limited",
                    mapOf("characters" to text.length, "words" to words.size))
            } }, worker).whenComplete { _, error ->
                if (error != null) IslandLog.failure("lyrics", "geometry.failed", error)
            }.also { cache[key] = it }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            IslandLog.warn("lyrics", "geometry.queue_full"); return null
        }
        if (future.isCompletedExceptionally) return null
        // The worker can fail after the check above; optional geometry must still fall back on the draw thread.
        return try { future.getNow(null) }
        catch (_: CompletionException) { null }
        catch (_: CancellationException) { null }
    }

    internal fun prepare(shaped: ShapedText, text: String, words: List<Word>): List<Cell>? {
        // Optional motion has a fixed budget; timed highlighting remains available through the painter's fallback.
        if (text.length > 2_048 || words.size > 512) return null
        val outline = LyricGlyphOutline.from(shaped.outline) ?: return null
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var offset = 0
        var count = 0
        return words.map { word ->
            val end = (offset + word.text.length).coerceAtMost(text.length)
            val bounds = shaped.layout.getLogicalHighlightShape(offset, end).bounds2D
            val clusters = mutableListOf<Cluster>()
            while (offset < end) {
                if (++count > 512 || Thread.currentThread().isInterrupted) return null
                val next = minOf(breaks.following(offset).takeUnless { it == BreakIterator.DONE } ?: end, end)
                val clusterBounds = shaped.layout.getLogicalHighlightShape(offset, next).bounds2D
                val minX = if (offset == 0) minOf(clusterBounds.x, shaped.left.toDouble()) else clusterBounds.x
                val maxX = if (next == shaped.layout.characterCount) maxOf(clusterBounds.maxX, shaped.right.toDouble()) else clusterBounds.maxX
                val clip = Rectangle2D.Double(minX, shaped.top.toDouble() - 1, maxX - minX, shaped.height.toDouble() + 2)
                clusters += Cluster(clusterBounds, outline.clipped(clip) ?: return null)
                offset = next
            }
            Cell(bounds, clusters)
        }
    }
}
