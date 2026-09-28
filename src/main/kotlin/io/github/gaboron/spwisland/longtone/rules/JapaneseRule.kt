// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone.rules

import io.github.gaboron.spwisland.longtone.LongToneRule
import io.github.gaboron.spwisland.longtone.LongToneRuleContext

/** Kana evidence identifies Japanese; only one clear mora or contracted kana can be emphasized. */
class JapaneseRule : LongToneRule {
    override val id = "japanese"
    override val priority = 200

    private val kana = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    private val allowed = kana + Character.UnicodeScript.HAN
    private val smallKana = "ゃゅょぁぃぅぇぉャュョァィゥェォ".codePoints().toArray().toSet()
    private val standaloneExcluded = smallKana + setOf('っ'.code, 'ッ'.code, 'ん'.code, 'ン'.code)

    override fun matches(context: LongToneRuleContext): Boolean =
        context.scripts.any { it in kana } && context.scripts.all { it in allowed }

    override fun isLongTone(context: LongToneRuleContext): Boolean {
        if (context.durationMs < 1000) return false
        val points = context.textWithoutEdgePunctuation.codePoints().toArray()
        if (points.isEmpty() || points.any { Character.UnicodeScript.of(it) !in kana }) return false
        if (points.size == 1) return points[0] !in standaloneExcluded
        return points.size == 2 && points[0] !in standaloneExcluded &&
            points[1] in smallKana &&
            Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.of(points[1])
    }
}
