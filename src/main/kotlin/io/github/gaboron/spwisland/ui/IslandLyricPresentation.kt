// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

/** Shared row motion for native GPU drawing and the compatibility painter. */
internal object IslandLyricPresentation {
    data class Row(val layout: IslandLyricsLayout.Row, val previous: Boolean,
                   val offset: Double, val opacity: Float, val scale: Double)

    fun rows(current: IslandLyricsLayout, previous: IslandLyricsLayout?, height: Float,
             transition: Double, fontSize: Int): List<Row> {
        val progress = if (transition >= 1) 1.0 else AmllMotion.line(transition * .65)
        val outgoingAlpha = (1 - transition * 3).coerceIn(0.0, 1.0).toFloat()
        val currentRows = current.rows(height)
        val previousRows = previous?.rows(height).orEmpty()
        val currentLines = currentRows.map { it.block.line }
        return buildList {
            if (outgoingAlpha > 0) previousRows.filter { it.block.line !in currentLines }.forEach {
                add(Row(it, true, -fontSize * progress, outgoingAlpha, 1 - .04 * progress))
            }
            currentRows.forEach { row ->
                val old = previousRows.firstOrNull { it.block.line == row.block.line }
                if (old != null) {
                    fun lerp(from: Float, to: Float) = (from + (to - from) * progress).toFloat()
                    add(Row(row.copy(mainBaseline = lerp(old.mainBaseline, row.mainBaseline),
                        subBaseline = lerp(old.subBaseline, row.subBaseline)), false, 0.0, 1f, 1.0))
                } else add(Row(row, false, fontSize * (1 - progress),
                    (transition * 3).coerceIn(0.0, 1.0).toFloat(), .96 + .04 * progress))
            }
        }
    }
}
