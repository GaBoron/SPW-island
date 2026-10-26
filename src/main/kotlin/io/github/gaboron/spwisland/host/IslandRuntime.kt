// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.PlaybackTimeline
import io.github.gaboron.spwisland.core.SpectrumMode
import io.github.gaboron.spwisland.core.performance
import io.github.gaboron.spwisland.ui.*
import io.github.gaboron.spwisland.platform.ProcessSpectrum
import io.github.gaboron.spwisland.platform.LinuxProcessSpectrum
import io.github.gaboron.spwisland.platform.SpectrumSource
import io.github.gaboron.spwisland.ui.ComposeFontPickerWindow
import com.sun.jna.Platform
import io.github.gaboron.spwisland.remote.LinuxIslandProcess
import javax.swing.SwingUtilities
import io.github.gaboron.spwisland.diagnostics.IslandLog

class IslandRuntime : AutoCloseable {
    val timeline = PlaybackTimeline()
    private val metadata = TrackMetadataLoader(timeline)
    fun trackChanged(track: io.github.gaboron.spwisland.core.Track) {
        IslandLog.event("playback", "track.changed")
        metadata.load(track)
    }
    fun lineChanged(line: io.github.gaboron.spwisland.core.LyricLine?) = timeline.lineChanged(line)
    fun lyricsChanged(lines: List<io.github.gaboron.spwisland.core.LyricLine>) {
        IslandLog.event("playback", "lyrics.document_changed", mapOf("lines" to lines.size))
        timeline.lyricsChanged(lines)
    }
    private val ui: IslandUiLifecycle? = if (Platform.isLinux()) null else IslandUiLifecycle(::createWindow, ::report)
    private var linuxWindow: LinuxIslandProcess? = null
    private val spectrum: SpectrumSource = if (Platform.isLinux()) LinuxProcessSpectrum()
        else ProcessSpectrum(::notifySpectrumFallback)
    @Volatile private var closed = false
    private val settings = HostSettings(WorkshopApi.manager.createConfigManager()) {
        updateSpectrumMode()
        if (!closed) ui?.reload()
    }
    private val fontPicker = if (Platform.isWindows()) ComposeFontPickerWindow({ selection ->
        safely {
            settings.setFont(selection.family, selection.weight)
        }
    }, ::report) else null
    private val shortcut = IslandShortcut { safely { settings.set("enabled", !settings.read().enabled) } }
    private val volume = HostVolumeControl(::report)
    fun start() {
        updateSpectrumMode()
        shortcut.start()
        metadata.start()
        if (Platform.isLinux()) {
            linuxWindow = LinuxIslandProcess(timeline, settings, playbackActions(), ::report, spectrum)
        } else ui?.start()
    }
    private fun createWindow(): IslandWindow = IslandWindow(timeline, settings, playbackActions(), ::report,
        spectrum::levels, spectrum::usesSyntheticFallback, { spectrum.status })

    private fun playbackActions(): PlaybackActions = object : PlaybackActions {
        override fun previous() = safely("playback.previous") { WorkshopApi.playback.previous() }
        override fun toggle() = safely("playback.toggle") {
            if (timeline.snapshot().playing) WorkshopApi.playback.pause() else WorkshopApi.playback.play()
        }
        override fun next() = safely("playback.next") { WorkshopApi.playback.next() }
        override fun seek(positionMs: Long) = safely("playback.seek") {
            WorkshopApi.playback.seekTo(positionMs)
            timeline.seek(positionMs)
        }
        override fun adjustVolume(delta: Double, completed: (Double?) -> Unit) = volume.adjust(delta, completed)
        override fun setVolume(value: Double, completed: (Double?) -> Unit) = volume.set(value, completed)
    }
    private fun updateSpectrumMode() {
        val current = settings.read()
        IslandLog.cadence(current.performance.diagnosticSampleIntervalNs)
        spectrum.setEnabled(current.enabled && current.performance.spectrumMode == SpectrumMode.LIVE &&
            current.sideContent.showsSpectrum && (!Platform.isLinux() || timeline.snapshot().playing))
    }
    fun recover() = safely("window.recover") {
        settings.set("auto_hide_on_hover", false); settings.set("click_through", false)
        settings.set("enabled", true); settings.resetPosition()
        IslandLog.event("window", "recovery.settings_applied", mapOf(
            "source" to "spw_settings", "presentationVerified" to false))
    }
    fun installGnomePointer() {
        if (!Platform.isLinux() || closed) return
        Thread({ safely {
            val message = io.github.gaboron.spwisland.platform.LinuxHelper.installGnomePointer()
            if (!closed) WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
        } }, "SPW Island GNOME setup").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("IslandRuntime") }.start()
    }
    fun resetSettings() = safely { settings.resetAll() }
    fun about() {
        if (closed) return
        linuxWindow?.about() ?: ui?.about()
    }
    fun chooseFont() {
        if (!closed) fontPicker?.show(settings.read())
    }

    fun openSource() { SwingUtilities.invokeLater { IslandLog.guard("IslandRuntime", "invokeLater.callback_failed") { if (!closed) safely { ProjectLinks.openSource() } } } }
    private fun safely(name: String = "operation", block: () -> Unit) {
        val operation = IslandLog.operation("runtime", name)
        try { block(); operation.close() }
        catch (error: Exception) { operation.failed(error); report(error) }
    }
    private fun report(error: Throwable) {
        IslandLog.failure("runtime", "operation.failed", error)
        IslandLog.attempt("IslandRuntime", "report.attempt1") { WorkshopApi.ui.toast(error.message ?: "词岛操作失败", WorkshopApi.Ui.ToastType.Error) }
    }
    private fun notifySpectrumFallback(message: String) {
        IslandLog.attempt("IslandRuntime", "notifySpectrumFallback.attempt2") { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }
    override fun close() {
        if (closed) return
        closed = true
        ui?.close()
        val remote = linuxWindow
        linuxWindow = null
        listOfNotNull(shortcut, volume, remote, fontPicker, metadata, settings, spectrum).forEach { resource ->
            try { resource.close() } catch (error: Throwable) {
                IslandLog.failure("IslandRuntime", "caught.error", error); report(error) }
        }
    }
}
