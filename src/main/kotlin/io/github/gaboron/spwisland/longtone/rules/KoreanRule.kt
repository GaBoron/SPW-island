// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone.rules

import io.github.gaboron.spwisland.longtone.LongToneRule
import io.github.gaboron.spwisland.longtone.LongToneRuleContext

/** NFC composes valid Jamo sequences into Hangul syllable blocks before this check. */
class KoreanRule : LongToneRule {
    override val id = "korean"
    override val priority = 100

    override fun matches(context: LongToneRuleContext): Boolean =
        context.scripts == setOf(Character.UnicodeScript.HANGUL)

    override fun isLongTone(context: LongToneRuleContext): Boolean {
        val text = context.textWithoutEdgePunctuation
        return context.durationMs >= 1000 && text.codePointCount(0, text.length) == 1 &&
            text.codePointAt(0) in 0xAC00..0xD7A3
    }
}
