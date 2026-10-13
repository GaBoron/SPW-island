// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone

import io.github.gaboron.spwisland.core.Word
import java.util.WeakHashMap

/** Font-independent timing-cell analysis shared by preparation and drawing in one panel. */
class LongToneAnalysis(private val rules: LongToneRuleRegistry = LongToneRuleRegistry()) {
    private val flags = WeakHashMap<List<Word>, BooleanArray>()
    private val empty = BooleanArray(0)

    fun forWords(words: List<Word>): BooleanArray {
        if (words.isEmpty()) return empty
        synchronized(flags) { flags[words]?.let { return it } }
        val analyzed = BooleanArray(words.size) { rules.isLongTone(words[it]) }
        return synchronized(flags) { flags.getOrPut(words) { analyzed } }
    }

    fun clear() = synchronized(flags) { flags.clear() }
}
