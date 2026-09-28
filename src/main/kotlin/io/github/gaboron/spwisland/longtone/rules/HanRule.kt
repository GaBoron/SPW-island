// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone.rules

import io.github.gaboron.spwisland.longtone.LongToneRule
import io.github.gaboron.spwisland.longtone.LongToneRuleContext

/** Pure Han cells have no trustworthy way to identify an internal sustained syllable. */
class HanRule : LongToneRule {
    override val id = "han"
    override val priority = 100

    override fun matches(context: LongToneRuleContext): Boolean =
        context.scripts == setOf(Character.UnicodeScript.HAN)

    override fun isLongTone(context: LongToneRuleContext): Boolean {
        val text = context.textWithoutEdgePunctuation
        return context.durationMs >= 1000 && text.codePointCount(0, text.length) == 1 &&
            text.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
    }
}
