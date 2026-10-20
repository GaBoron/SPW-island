// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.platform.LinuxHelper
import io.github.gaboron.spwisland.platform.IslandLog
import io.github.gaboron.spwisland.platform.SpectrumSource
import io.github.gaboron.spwisland.ui.PlaybackActions
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The host's Skiko swapBuffers can block its EDT when minimized; never render on that EDT. */
internal class LinuxIslandProcess(private val timeline: PlaybackSource, private val settings: SettingsStore,
                         private val actions: PlaybackActions, private val report: (Throwable) -> Unit,
                         private val spectrum: SpectrumSource,
                         javaHome: String = System.getProperty("java.home")) : AutoCloseable {
    private val process = LinuxHelper.startUi(javaHome)
    @Volatile var presentedFrames: Long = 0
        private set
    private val output = ObjectOutputStream(BufferedOutputStream(process.outputStream)).apply { flush() }
    private val sender = Executors.newSingleThreadScheduledExecutor { Thread(it, "SPW Island IPC").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val showAbout = AtomicBoolean()
    private val acknowledgedCommand = AtomicLong()
    private val volumeResults = ConcurrentLinkedQueue<IslandVolumeResult>()
    private var previousSettings: IslandSettings? = null
    private var previousMetadata: TrackMetadata? = null
    private var previousLyrics: List<LyricLine>? = null
    private var nextState = 0L
    private var audioEnabled = false
    private var lastAudioStatus = ""
    private var lastFrameDiagnosticNs = 0L
    private var previousDiagnosticFrames = -1L

    init {
        IslandLog.info("linux-host", "UI child started; pid=${process.pid()}")
        sender.scheduleWithFixedDelay({
            if (!closed.get()) try {
                // Read the watermark BEFORE the state: never acknowledge a command with its old snapshot.
                val acknowledged = acknowledgedCommand.get()
                val snapshot = timeline.snapshot()
                val currentSettings = settings.read()
                val now = System.nanoTime()
                while (true) output.writeObject(volumeResults.poll() ?: break)
                if (now >= nextState) {
                    spectrum.setEnabled(currentSettings.enabled && snapshot.playing &&
                        currentSettings.sideContent.showsSpectrum && currentSettings.performance.spectrumMode == SpectrumMode.LIVE)
                    val state = IslandState(snapshot.copy(metadata = TrackMetadata(), lyrics = emptyList()),
                        currentSettings.takeIf { it != previousSettings },
                        snapshot.metadata.takeIf { it != previousMetadata },
                        snapshot.lyrics.takeIf { it != previousLyrics }, showAbout.getAndSet(false), acknowledged)
                    output.writeObject(state)
                    previousSettings = currentSettings
                    previousMetadata = snapshot.metadata
                    previousLyrics = snapshot.lyrics
                    // Maintain approximately 10 Hz state without raising lyric/cover traffic to audio cadence.
                    nextState = if (nextState == 0L || now - nextState > 100_000_000L)
                        now + 100_000_000L else nextState + 100_000_000L
                }
                val live = currentSettings.enabled && snapshot.playing && currentSettings.sideContent.showsSpectrum &&
                    currentSettings.performance.spectrumMode == SpectrumMode.LIVE
                if (live || live != audioEnabled || spectrum.status != lastAudioStatus) {
                    output.writeObject(IslandSpectrum(if (live) spectrum.levels() else FloatArray(4),
                        live && spectrum.usesSyntheticFallback(), spectrum.status))
                    lastAudioStatus = spectrum.status
                    audioEnabled = live
                }
                output.reset() // Bound the stream's object table over long listening sessions.
                output.flush()
            } catch (error: Exception) {
                if (!closed.get()) { close(); report(IllegalStateException("Linux 词岛进程已断开", error)) }
            }
        }, 0, 33, TimeUnit.MILLISECONDS)
        Thread({
            try {
                ObjectInputStream(BufferedInputStream(process.inputStream)).use { input ->
                    while (!closed.get()) dispatch(input.readObject() as IslandCommand)
                }
            } catch (error: Exception) {
                if (!closed.get()) { close(); report(IllegalStateException("Linux 词岛进程已退出", error)) }
            }
        }, "SPW Island commands").apply { isDaemon = true }.start()
    }

    private fun dispatch(command: IslandCommand) {
        val a = command.arguments
        try {
            when (command.action) {
                "frames" -> {
                    presentedFrames = a.single().toLong()
                    val now = System.nanoTime()
                    if (now - lastFrameDiagnosticNs >= TimeUnit.SECONDS.toNanos(5)) {
                        IslandLog.info("linux-host.health", "childPid=${process.pid()}; presentedFrames=$presentedFrames; " +
                            "alive=${process.isAlive}")
                        if (previousDiagnosticFrames >= 0 && presentedFrames == previousDiagnosticFrames &&
                            settings.read().enabled && (!settings.read().hidePaused || timeline.snapshot().playing)) {
                            IslandLog.anomaly("linux-host.frames", "child reports no new presented frames")
                        } else IslandLog.recovered("linux-host.frames", "child frames progressing or intentionally hidden")
                        previousDiagnosticFrames = presentedFrames
                        lastFrameDiagnosticNs = now
                    }
                }
                "previous" -> actions.previous()
                "toggle" -> actions.toggle()
                "next" -> actions.next()
                "seek" -> actions.seek(a.single().toLong())
                "volume", "set_volume" -> {
                    val completed: (Double?) -> Unit = { value ->
                        if (!closed.get()) volumeResults.add(IslandVolumeResult(command.requestId, value))
                    }
                    val value = a.single().toDouble()
                    if (command.action == "volume") actions.adjustVolume(value, completed)
                    else actions.setVolume(value, completed)
                }
                "set" -> settings.set(a[0], if (a[1] == "boolean") a[2].toBooleanStrict() else a[2])
                "position" -> settings.savePosition(a[0], a[1].toInt(), a[2].toInt(),
                    requireNotNull(IslandAnchor.fromStorage(a[3])))
                "reset" -> settings.resetPosition()
                "reset_all" -> settings.resetAll()
                "error" -> report(IllegalStateException(a.single()))
            }
        } catch (error: Exception) { report(error) }
        finally { acknowledgedCommand.set(command.requestId) }
    }

    fun about() { showAbout.set(true) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        IslandLog.info("linux-host", "closing UI child; pid=${process.pid()}; lastFrames=$presentedFrames")
        spectrum.setEnabled(false)
        sender.shutdownNow()
        // Killing the process also closes a blocked pipe writer; do not wait for the host EDT.
        process.destroy()
        Thread({
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            runCatching { output.close() }
        }, "SPW Island cleanup").apply { isDaemon = true }.start()
    }
}
