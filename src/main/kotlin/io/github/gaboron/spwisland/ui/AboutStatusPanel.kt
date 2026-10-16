// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel

/** One prominent display state, then compact grouped facts; failure reasons remain next to their source. */
internal class AboutStatusPanel(private val textColor: Color, private val muted: Color,
                                 private val warning: Color, private val naturalWidth: Int) : JPanel(null), WidthAware {
    private var entries: List<IslandStatusEntry> = emptyList()
    private var groups: List<Pair<JLabel, List<StatusRow>>> = emptyList()

    init { isOpaque = false }

    fun update(values: List<IslandStatusEntry>) {
        if (entries == values) return
        val structureChanged = entries.map { it.title to it.group } != values.map { it.title to it.group }
        entries = values
        if (structureChanged) {
            removeAll()
            groups = IslandStatusGroup.entries.mapNotNull { group ->
                val items = values.filter { it.group == group }
                if (items.isEmpty()) return@mapNotNull null
                val heading = JLabel(group.title).apply {
                    font = SystemUiFont.derive(Font.BOLD, 12f); foreground = muted
                }
                add(heading)
                heading to items.map { entry -> StatusRow(entry, group == IslandStatusGroup.PLAYBACK &&
                    entry == items.first()).also(::add) }
            }
        } else {
            groups.flatMap { it.second }.forEach { row -> values.firstOrNull { it.title == row.title }?.let(row::update) }
        }
        revalidate(); repaint()
    }

    private fun arrange(width: Int, layout: Boolean): Int {
        var y = 0
        for ((index, group) in groups.withIndex()) {
            val (heading, rows) = group
            if (index > 0) y += 22
            val headingHeight = heading.preferredSize.height
            if (layout) heading.setBounds(0, y, width, headingHeight)
            y += headingHeight + 12
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) y += 12
                val height = row.heightFor(width)
                if (layout) row.setBounds(0, y, width, height)
                y += height
            }
        }
        return y
    }

    override fun heightFor(width: Int) = arrange(width.coerceAtLeast(1), false)
    override fun getPreferredSize() = Dimension(naturalWidth, heightFor(width.takeIf { it > 0 } ?: naturalWidth))
    override fun doLayout() { arrange(width.coerceAtLeast(1), true) }

    private inner class StatusRow(value: IslandStatusEntry, private val prominent: Boolean) : JPanel(null), WidthAware {
        var title = value.title
            private set
        private val label = JLabel(value.title).apply {
            font = SystemUiFont.derive(Font.PLAIN, 12f); foreground = muted
        }
        private val summary = WrappingText("", textColor, naturalWidth - 88, if (prominent) 18f else 13f, bold = prominent)
        private val detail = WrappingText("", muted, naturalWidth - 88, 11.5f)
        init { isOpaque = false; add(label); add(summary); add(detail); update(value) }

        fun update(value: IslandStatusEntry) {
            title = value.title; label.text = title
            if (summary.text != value.summary) summary.text = value.summary
            summary.foreground = if (value.warning) warning else textColor
            // Routine explanations stay secondary; warnings always retain the complete failure reason.
            if (detail.text != value.detail) detail.text = value.detail
            detail.foreground = if (value.warning) warning else muted
        }

        override fun heightFor(width: Int): Int {
            val available = (width - 88).coerceAtLeast(1)
            return maxOf(label.preferredSize.height, summary.heightFor(available) +
                if (detail.text.isEmpty()) 0 else 4 + detail.heightFor(available))
        }
        override fun doLayout() {
            val available = (width - 88).coerceAtLeast(1)
            val summaryHeight = summary.heightFor(available)
            label.setBounds(0, if (prominent) 5 else 1, 80, label.preferredSize.height)
            summary.setBounds(88, 0, available, summaryHeight)
            detail.setBounds(88, summaryHeight + 4, available, detail.heightFor(available))
        }
    }
}
