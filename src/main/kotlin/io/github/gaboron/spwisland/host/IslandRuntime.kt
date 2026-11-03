// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.PlaybackTimeline
import io.github.gaboron.spwisland.core.SpectrumMode
import io.github.gaboron.spwisland.core.IslandBackgroundStyle
import io.github.gaboron.spwisland.core.performance
import io.github.gaboron.spwisland.core.PerformanceProfile
import io.github.gaboron.spwisland.diagnostics.DiagnosticLatency
import io.github.gaboron.spwisland.ui.*
import io.github.gaboron.spwisland.platform.ProcessSpectrum
import io.github.gaboron.spwisland.platform.LinuxProcessSpectrum
import io.github.gaboron.spwisland.platform.SpectrumSource
import io.github.gaboron.spwisland.ui.ComposeFontPickerWindow
import com.sun.jna.Platform
import io.github.gaboron.spwisland.remote.LinuxIslandProcess
import io.github.gaboron.spwisland.remote.LinuxUiLifecycle
import javax.swing.SwingUtilities
import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.platform.LinuxHelper
import java.util.concurrent.atomic.AtomicBoolean

class IslandRuntime : AutoCloseable {
    val timeline = PlaybackTimeline()
    @Volatile private var diagnosticProfile = PerformanceProfile.STANDARD
    private val positionLatency = DiagnosticLatency("callback.position")
    private val lineLatency = DiagnosticLatency("callback.line")
    private val metadata = TrackMetadataLoader(timeline, performance = { settings.read().performance })
    fun trackChanged(track: io.github.gaboron.spwisland.core.Track) {
        IslandLog.event("playback", "track.changed")
        metadata.load(track)
    }
    fun stateChanged(value: io.github.gaboron.spwisland.core.PlaybackStatus) {
        timeline.stateChanged(value)
        metadata.recoverMissingTrack()
    }
    fun playingChanged(value: Boolean) {
        timeline.playingChanged(value)
        metadata.recoverMissingTrack()
    }
    fun positionChanged(value: Long) {
        val started = System.nanoTime()
        try {
            timeline.positionChanged(value)
            metadata.recoverMissingTrack()
        } finally { positionLatency.record(System.nanoTime() - started, 0, diagnosticProfile) }
    }
    fun lineChanged(line: io.github.gaboron.spwisland.core.LyricLine?) {
        val started = System.nanoTime()
        try { timeline.lineChanged(line) }
        finally { lineLatency.record(System.nanoTime() - started, 0, diagnosticProfile) }
    }
    fun lyricsChanged(lines: List<io.github.gaboron.spwisland.core.LyricLine>) {
        IslandLog.event("playback", "lyrics.document_changed", mapOf("lines" to lines.size))
        timeline.lyricsChanged(lines)
    }
    private val ui: IslandUiLifecycle? = if (Platform.isLinux()) null else IslandUiLifecycle(::createWindow, ::report)
    private val linuxWindow = if (Platform.isLinux()) LinuxUiLifecycle({
        LinuxIslandProcess(timeline, settings, playbackActions(), ::report, spectrum,
            hostPlayerForeground = { playerVisibility?.hidden == true })
    }, ::report) else null
    private val spectrum: SpectrumSource = if (Platform.isLinux()) LinuxProcessSpectrum()
        else ProcessSpectrum(::notifySpectrumFallback)
    @Volatile private var closed = false
    @Volatile private var playerVisibility: HostPlayerVisibility? = null
    private val desktopSetupRunning = AtomicBoolean()
    private val glassRefreshStarted = AtomicBoolean()
    @Volatile private var desktopSetupThread: Thread? = null
    private val settings = IslandLog.timed("runtime.settings.construct") {
        HostSettings(WorkshopApi.manager.createConfigManager()) {
            updateSpectrumMode()
            if (!closed) ui?.reload()
        }
    }
    private val fontPicker = if (Platform.isWindows()) ComposeFontPickerWindow({ selection ->
        safely {
            settings.setFont(selection.family, selection.weight)
        }
    }, ::report) else null
    private val shortcut = IslandShortcut { safely { settings.set("enabled", !settings.read().enabled) } }
    private val volume = HostVolumeControl(::report)
    fun start() {
        playerVisibility = HostPlayerVisibility({ settings.read() }) { if (!closed) ui?.reload() }
            .also { it.refresh() }
        IslandLog.timed("runtime.spectrum.start") { updateSpectrumMode() }
        IslandLog.timed("runtime.shortcut.register") { shortcut.start() }
        IslandLog.timed("runtime.metadata.enqueue") { metadata.start() }
        IslandLog.timed("runtime.ui.enqueue") {
            if (Platform.isLinux()) {
                linuxWindow?.start()
            } else ui?.start()
        }
    }
    private fun createWindow(): IslandWindow = IslandWindow(timeline, settings, playbackActions(), ::report,
        spectrum::levels, spectrum::usesSyntheticFallback, { spectrum.status }, active = { !closed },
        hostPlayerForeground = { playerVisibility?.hidden == true })

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
        if (closed) return
        val current = settings.read()
        playerVisibility?.refresh()
        diagnosticProfile = current.performance
        IslandLog.cadence(current.performance.diagnosticSampleIntervalNs, current.performance.performanceReportIntervalNs)
        spectrum.setEnabled(current.enabled && current.performance.spectrumMode == SpectrumMode.LIVE &&
            current.sideContent.showsSpectrum && (!Platform.isLinux() || timeline.snapshot().playing))
        // Startup-only synchronization follows the same disabled/low-performance gate as rendering.
        if (!closed && Platform.isLinux() && current.enabled && current.performance.renderLiquidGlass &&
            current.backgroundStyle == IslandBackgroundStyle.LIQUID_GLASS &&
            System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').any {
                it.equals("GNOME", true) || it.equals("KDE", true) && System.getenv("XDG_SESSION_TYPE") == "wayland"
            } &&
            glassRefreshStarted.compareAndSet(false, true)) {
            if (desktopSetupRunning.compareAndSet(false, true)) {
                IslandLog.thread("SPW Island compositor renderer sync") {
                    try {
                        if (!closed) safely("compositor.renderer.sync") {
                            val message = LinuxHelper.refreshCompositorGlass()
                            if (!closed && message != null)
                                WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
                        }
                    } finally { desktopSetupThread = null; desktopSetupRunning.set(false) }
                }.also { desktopSetupThread = it }.start()
            } else {
                glassRefreshStarted.set(false)
            }
        }
    }
    fun recover() = safely("window.recover") {
        settings.set("auto_hide_on_hover", false); settings.set("click_through", false)
        settings.set("hide_spw_player_foreground", false)
        settings.set("enabled", true); settings.resetPosition()
        IslandLog.event("window", "recovery.settings_applied", mapOf(
            "source" to "spw_settings", "presentationVerified" to false))
    }
    fun installGnomePointer() = installGnomeSupport("pointer")
    fun installGnomeGlass() = installGnomeSupport("glass")

    private fun installGnomeSupport(kind: String) {
        if (closed) return
        if (!Platform.isLinux()) {
            WorkshopApi.ui.toast("此安装入口仅用于 Linux 桌面。", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        if (!desktopSetupRunning.compareAndSet(false, true)) {
            WorkshopApi.ui.toast("桌面支持正在安装，请稍候。", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        IslandLog.thread("SPW Island desktop setup") {
            try {
                safely("desktop.install.$kind") {
                    if (closed) return@safely
                    val kdeSetup = kind == "glass" && System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
                        .split(':').any { it.equals("KDE", true) }
                    val progress = when {
                        kdeSetup -> "正在安装 KDE 液态玻璃支持，请稍候。"
                        kind == "glass" -> "正在安装 GNOME 液态玻璃支持，请稍候。"
                        else -> "正在安装 GNOME 悬停隐藏支持，请稍候。"
                    }
                    WorkshopApi.ui.toast(progress, WorkshopApi.Ui.ToastType.Warning)
                    val message = if (kind == "glass") LinuxHelper.installCompositorGlass() else LinuxHelper.installGnomeSupport(kind)
                    if (!closed) WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
                }
            } finally {
                desktopSetupThread = null
                desktopSetupRunning.set(false)
                if (!closed) updateSpectrumMode()
            }
        }.also { desktopSetupThread = it }.start()
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
        if (closed) return
        val operation = IslandLog.operation("runtime", name)
        try { block(); operation.close() }
        catch (error: Exception) { operation.failed(error); report(error) }
    }
    private fun report(error: Throwable) {
        IslandLog.failure("runtime", "operation.failed", error)
        if (closed) return
        IslandLog.attempt("IslandRuntime", "report.attempt1") { WorkshopApi.ui.toast(error.message ?: "词岛操作失败", WorkshopApi.Ui.ToastType.Error) }
    }
    private fun notifySpectrumFallback(message: String) {
        if (closed) return
        IslandLog.attempt("IslandRuntime", "notifySpectrumFallback.attempt2") { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }
    override fun close() {
        if (closed) return
        closed = true
        desktopSetupThread?.interrupt()
        positionLatency.close(); lineLatency.close()
        ui?.close()
        listOfNotNull(playerVisibility, shortcut, volume, linuxWindow, fontPicker, metadata, settings, spectrum).forEach { resource ->
            try { IslandLog.timed("runtime.close.${resource.javaClass.simpleName}") { resource.close() } } catch (error: Throwable) {
                IslandLog.failure("IslandRuntime", "caught.error", error); report(error) }
        }
    }
}
