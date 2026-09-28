// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone

import io.github.gaboron.spwisland.core.Word
import io.github.gaboron.spwisland.longtone.rules.EnglishRule
import io.github.gaboron.spwisland.longtone.rules.HanRule
import io.github.gaboron.spwisland.longtone.rules.JapaneseRule
import io.github.gaboron.spwisland.longtone.rules.KoreanRule

/** Select exactly one highest-priority matching rule; an ambiguous tie is a safe rejection. */
class LongToneRuleRegistry(rules: List<LongToneRule> = listOf(
    HanRule(), JapaneseRule(), KoreanRule(), EnglishRule()
)) {
    private val rules = rules.toList()

    fun withRule(rule: LongToneRule): LongToneRuleRegistry = LongToneRuleRegistry(rules + rule)

    fun isLongTone(word: Word): Boolean = isLongTone(LongToneRuleContext(word))

    fun isLongTone(context: LongToneRuleContext): Boolean {
        val matching = rules.filter { it.matches(context) }
        val highest = matching.maxOfOrNull { it.priority } ?: return false
        val selected = matching.filter { it.priority == highest }.singleOrNull() ?: return false
        return selected.isLongTone(context)
    }
}
