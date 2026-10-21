// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.google.gson.Gson
import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.diagnostics.IslandLog

/** Restrict context to our immutable settings model, never the host config document. */
internal object SettingsDiagnostics {
    private val gson = Gson()
    fun changed(before: IslandSettings?, after: IslandSettings, source: String, attempts: Int = 1) {
        val previous = before?.let { gson.toJsonTree(it).asJsonObject }
        val changes = gson.toJsonTree(after).asJsonObject.entrySet().filter { previous?.get(it.key) != it.value }
            .associate { it.key to "${previous?.get(it.key)} -> ${it.value}" }
        if (changes.isNotEmpty()) IslandLog.event("settings", "settings.changed",
            mapOf("source" to source, "attempts" to attempts, "changes" to changes))
    }
}
