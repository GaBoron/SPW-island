// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import io.github.gaboron.spwisland.diagnostics.IslandLog

class IslandPlugin(context: PluginContext) : SpwPlugin(context) {
    private fun startDiagnostics() {
        val started = System.nanoTime()
        IslandLog.start("host", mapOf("pluginVersion" to pluginContext.pluginVersion,
            "spwVersion" to pluginContext.spwVersion, "spwChannel" to pluginContext.spwChannel))
        IslandLog.duration("plugin.diagnostics", started)
    }
    init {
        val started = System.nanoTime()
        startDiagnostics()
        IslandLog.duration("plugin.init", started)
    }
    override fun start() {
        if (runtime != null) return
        val started = System.nanoTime()
        startDiagnostics()
        val operation = IslandLog.operation("lifecycle", "plugin.start")
        var created: IslandRuntime? = null
        try {
            val next = IslandLog.timed("runtime.construct") { IslandRuntime() }
            created = next
            runtime = next
            next.start()
            IslandLog.timed("runtime.playback.attach") { playback.attach(next) }
            operation.close()
        } catch (error: Throwable) {
            IslandLog.failure("IslandPlugin", "caught.error", error);
            operation.failed(error)
            created?.let(playback::detach)
            runtime = null
            IslandLog.attempt("lifecycle", "plugin.start_cleanup_failed") { created?.close() }
            IslandLog.attempt("lifecycle", "diagnostics.start_cleanup_failed") { IslandLog.stop() }
            throw error
        } finally {
            IslandLog.duration("plugin.start", started)
        }
    }
    override fun stop() {
        val old = runtime ?: run { IslandLog.stop(); return }
        playback.detach(old)
        runtime = null
        val operation = IslandLog.operation("lifecycle", "plugin.stop")
        try { old.close(); operation.close() }
        catch (error: Throwable) {
            IslandLog.failure("IslandPlugin", "caught.error", error); operation.failed(error); throw error }
        finally { IslandLog.stop() }
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
        @JvmStatic @JvmName("installGnomeGlass") fun installGnomeGlass() { runtime?.installGnomeGlass() }
        @JvmStatic @JvmName("openSource") fun openSource() { runtime?.openSource() }
        @JvmStatic @JvmName("openLogs") fun openLogs() { HostDiagnosticActions.openLogs() }
        @JvmStatic @JvmName("exportDiagnostics") fun exportDiagnostics() { HostDiagnosticActions.export() }
    }
}
