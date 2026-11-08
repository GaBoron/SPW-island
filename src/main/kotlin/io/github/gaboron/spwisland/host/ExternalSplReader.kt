package io.github.gaboron.spwisland.host

/** Reads the bounded enhanced-LRC timeline emitted by SPW Lyrics, without inventing word timing. */
internal object ExternalSplReader {
    private val rowTime = Regex("^\\[(\\d+):(\\d{2})(?:[.](\\d{1,3}))?]")
    private val endTime = Regex("\\[(\\d+):(\\d{2})(?:[.](\\d{1,3}))?]$")
    private val wordTime = Regex("<(\\d+):(\\d{2})(?:[.](\\d{1,3}))?>")

    fun read(content: String, durationMs: Long): List<ExternalLyricLine> {
        if (content.isBlank() || content.length > 4_000_000) return emptyList()
        val rows = content.lineSequence().take(20_000).mapNotNull { text ->
            val start = rowTime.find(text) ?: return@mapNotNull null
            val body = text.substring(start.range.last + 1)
            val end = endTime.find(body)
            Row(time(start), if (end == null) body else body.substring(0, end.range.first), end?.let(::time))
        }.toList()
        if (rows.isEmpty()) return listOf(ExternalLyricLine(0, durationMs.coerceAtLeast(1),
            content.trim(), "", emptyList()))
        val grouped = rows.groupBy { it.start }.entries.sortedBy { it.key }
        return grouped.mapIndexed { index, (start, group) ->
            val main = group.first()
            val nextStart = grouped.getOrNull(index + 1)?.key
            val end = (main.end ?: nextStart ?: durationMs.takeIf { it > start } ?: start + 5_000).coerceAtLeast(start)
            val words = words(main.body, start, end)
            ExternalLyricLine(start, end, wordTime.replace(main.body, ""),
                group.drop(1).joinToString("\n") { wordTime.replace(it.body, "") }, words)
        }
    }

    private fun words(body: String, start: Long, end: Long): List<ExternalLyricWord> {
        val markers = wordTime.findAll(body).toList()
        if (markers.isEmpty()) return emptyList()
        val result = mutableListOf<ExternalLyricWord>()
        var cursor = 0
        var wordStart = start
        for (marker in markers) {
            val next = time(marker).coerceIn(start, end)
            val text = body.substring(cursor, marker.range.first)
            if (text.isNotEmpty()) result += ExternalLyricWord(wordStart, next.coerceAtLeast(wordStart), text)
            cursor = marker.range.last + 1
            wordStart = next
        }
        val tail = body.substring(cursor)
        if (tail.isNotEmpty()) result += ExternalLyricWord(wordStart, end.coerceAtLeast(wordStart), tail)
        return result
    }

    private fun time(match: MatchResult): Long =
        (match.groupValues[1].toLongOrNull() ?: 0).coerceAtMost(1_000_000) * 60_000 +
            (match.groupValues[2].toLongOrNull() ?: 0) * 1_000 +
            match.groupValues[3].padEnd(3, '0').toLongOrNull().let { it ?: 0 }

    private data class Row(val start: Long, val body: String, val end: Long?)
}

internal data class ExternalLyricLine(val startMs: Long, val endMs: Long, val text: String,
    val translation: String, val words: List<ExternalLyricWord>)
internal data class ExternalLyricWord(val startMs: Long, val endMs: Long, val text: String)
