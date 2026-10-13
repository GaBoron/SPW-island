// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Shape
import java.awt.geom.Rectangle2D
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import io.github.gaboron.spwisland.core.Word

/** Bounded background preparation keeps outline intersections off the animation thread. */
object WordGeometry {
    data class Cluster(val bounds: Rectangle2D, val shape: Shape)
    data class Cell(val bounds: Rectangle2D, val clusters: List<Cluster>)
    private data class Key(val shaped: ShapedText, val words: List<Word>)
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "island-word-geometry").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val cache = object : LinkedHashMap<Key, CompletableFuture<List<Cell>>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CompletableFuture<List<Cell>>>) = size > 8
    }

    @Synchronized fun ready(shaped: ShapedText, text: String, words: List<Word>): List<Cell>? {
        val key = Key(shaped, words)
        val future = cache[key] ?: try {
            CompletableFuture.supplyAsync({ prepare(shaped, text, words) }, worker).also { cache[key] = it }
        } catch (_: java.util.concurrent.RejectedExecutionException) { return null }
        return if (future.isCompletedExceptionally) null else future.getNow(null)
    }

    internal fun prepare(shaped: ShapedText, text: String, words: List<Word>): List<Cell> {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var offset = 0
        return words.map { word ->
            val end = (offset + word.text.length).coerceAtMost(text.length)
            val bounds = shaped.layout.getLogicalHighlightShape(offset, end).bounds2D
            val clusters = mutableListOf<Cluster>()
            while (offset < end) {
                val next = minOf(breaks.following(offset).takeUnless { it == BreakIterator.DONE } ?: end, end)
                clusters += Cluster(shaped.layout.getLogicalHighlightShape(offset, next).bounds2D, shaped.glyph(offset, next))
                offset = next
            }
            Cell(bounds, clusters)
        }
    }
}
