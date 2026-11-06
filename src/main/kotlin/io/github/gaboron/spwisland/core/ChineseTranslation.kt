// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

/** Selects display text from the host's merged, untagged secondary lyric lines. */
internal object ChineseTranslation {
    fun select(source: String?): String? {
        val lines = source?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)
            ?.distinct()?.toList().orEmpty()
        // A single untagged legacy translation has no language evidence to reinterpret.
        if (lines.size <= 1) return lines.firstOrNull()
        return lines.firstOrNull(::isChineseCandidate)
    }

    private fun isChineseCandidate(text: String): Boolean {
        var hasHan = false
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            when (Character.UnicodeScript.of(point)) {
                Character.UnicodeScript.HAN -> hasHan = true
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL -> return false
                else -> Unit
            }
            offset += Character.charCount(point)
        }
        // Workshop callbacks do not carry translation languages. Source order resolves
        // multiple Chinese candidates; kana-free Japanese cannot be distinguished here.
        return hasHan
    }
}
