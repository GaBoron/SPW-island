// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import io.github.gaboron.spwisland.platform.IslandLog

class IslandPlugin(context: PluginContext) : SpwPlugin(context) {
    override fun start() {
        IslandLog.info("plugin", "start requested")
        if (runtime != null) return
        val created = IslandRuntime()
        runtime = created
        try {
            created.start()
            playback.attach(created)
            IslandLog.info("plugin", "start complete")
        } catch (error: Throwable) {
            IslandLog.error("plugin", "start failed", error)
            playback.detach(created)
            runtime = null
            created.close()
            throw error
        }
    }
    override fun stop() {
        IslandLog.info("plugin", "stop requested")
        val old = runtime ?: return
        playback.detach(old)
        runtime = null
        old.close()
        IslandLog.info("plugin", "stop complete")
        IslandLog.flush()
    }
    override fun update() = stop()
    override fun delete() = stop()

    companion object {
        // The single bridge required by PF4J's independently constructed extension instance.
        @Volatile private var runtime: IslandRuntime? = null
        internal val playback = PlaybackCallbackBridge()
        @JvmStatic @JvmName("recover") fun recover() { runtime?.recover() }
        @JvmStatic @JvmName("about") fun about() { runtime?.about() }
        @JvmStatic @JvmName("chooseFont") fun chooseFont() { runtime?.chooseFont() }
        @JvmStatic @JvmName("resetSettings") fun resetSettings() { runtime?.resetSettings() }
        @JvmStatic @JvmName("installGnomePointer") fun installGnomePointer() { runtime?.installGnomePointer() }
        @JvmStatic @JvmName("openSource") fun openSource() { runtime?.openSource() }
        @JvmStatic @JvmName("openLogs") fun openLogs() { runtime?.openLogs() }
    }
}
