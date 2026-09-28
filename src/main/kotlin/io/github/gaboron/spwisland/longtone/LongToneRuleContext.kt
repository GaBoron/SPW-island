// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone

import io.github.gaboron.spwisland.core.Word
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/** Shared Unicode facts about one timing cell. Language-specific interpretation belongs in rules. */
class LongToneRuleContext(val rawText: String, val durationMs: Long) {
    constructor(word: Word) : this(word.text, (word.endMs - word.startMs).coerceAtLeast(0))

    val normalizedText: String = Normalizer.normalize(rawText, Normalizer.Form.NFC)
    val codePointCount: Int = normalizedText.codePointCount(0, normalizedText.length)
    val graphemeCount: Int = BreakIterator.getCharacterInstance(Locale.ROOT).run {
        setText(normalizedText)
        var count = 0
        while (next() != BreakIterator.DONE) count++
        count
    }
    val letterCount: Int = normalizedText.codePoints().filter { Character.isLetter(it) }.count().toInt()
    val hasWhitespace: Boolean = normalizedText.codePoints().anyMatch { Character.isWhitespace(it) }
    val punctuationCount: Int = normalizedText.codePoints().filter { isPunctuation(it) }.count().toInt()
    val scripts: Set<Character.UnicodeScript> = normalizedText.codePoints()
        .filter { Character.isLetter(it) }
        .mapToObj { Character.UnicodeScript.of(it) }
        .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }
        .toList().toSet()

    /** Trim punctuation and whitespace around a cell without changing its internal content. */
    val textWithoutEdgePunctuation: String = normalizedText.trimCodePoints { cp ->
        Character.isWhitespace(cp) || isPunctuation(cp)
    }

    private fun String.trimCodePoints(predicate: (Int) -> Boolean): String {
        var start = 0
        var end = length
        while (start < end) {
            val cp = codePointAt(start)
            if (!predicate(cp)) break
            start += Character.charCount(cp)
        }
        while (start < end) {
            val cp = codePointBefore(end)
            if (!predicate(cp)) break
            end -= Character.charCount(cp)
        }
        return substring(start, end)
    }

    private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }
}
