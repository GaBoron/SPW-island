// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.longtone

/** A rule decides eligibility only; the renderer owns every animation detail. */
interface LongToneRule {
    val id: String
    val priority: Int
    fun matches(context: LongToneRuleContext): Boolean
    fun isLongTone(context: LongToneRuleContext): Boolean
}
