// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone.rules

import io.github.gaboron.spwisland.longtone.LongToneRule
import io.github.gaboron.spwisland.longtone.LongToneRuleContext
import java.util.Locale

/** A short English token needs progressively more time as its estimated syllable count grows. */
class EnglishRule : LongToneRule {
    override val id = "english"
    override val priority = 200

    private val token = Regex("[a-z]+(?:['’][a-z]+)*['’]?")
    private val vowels = Regex("[aeiouy]+")
    private val contractions = mapOf("i'm" to 1200L, "can't" to 1200L, "don't" to 1200L,
        "you're" to 1200L, "we're" to 1200L)

    override fun matches(context: LongToneRuleContext): Boolean =
        context.scripts == setOf(Character.UnicodeScript.LATIN)

    override fun isLongTone(context: LongToneRuleContext): Boolean {
        val text = context.normalizedText.trim { it in " \t\n\r.,!?;:…\"“”()[]{}" }
            .lowercase(Locale.ROOT).replace('’', '\'')
        if (text.any { it.isWhitespace() }) return false
        if (!token.matches(text)) return false
        contractions[text]?.let { return context.durationMs >= it }
        if ('\'' in text.dropLast(1)) return false
        val bare = text.trimEnd('\'')
        // A long ordinary word can contain several syllables inside one timing cell.
        if (bare.length !in 2..7) return false
        var syllables = vowels.findAll(bare).count()
        if (bare.endsWith('e') && syllables > 1 && !bare.endsWith("le")) syllables--
        if (bare.endsWith("ed") && bare.length > 3 && bare[bare.length - 3] !in "td" && syllables > 1) syllables--
        if (bare.endsWith("es") && bare.length > 3 && bare[bare.length - 3] !in "sxz" &&
            !bare.endsWith("ches") && !bare.endsWith("shes") && syllables > 1) syllables--
        if (text.endsWith("in'") && bare.length > 3) syllables = maxOf(syllables, 2)
        if (syllables !in 1..3) return false
        val threshold = when (syllables) { 1 -> 1000L; 2 -> 1600L; else -> 2400L }
        return context.durationMs >= threshold + if (text.endsWith('\'')) 200L else 0L
    }
}
