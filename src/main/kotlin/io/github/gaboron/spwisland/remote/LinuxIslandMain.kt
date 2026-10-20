// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.ui.*
import io.github.gaboron.spwisland.platform.IslandLog
import java.io.*
import javax.swing.SwingUtilities

/** Entry point loaded with the host's bundled JVM, even when jpackage omits bin/java. */
object LinuxIslandMain {
    @JvmStatic fun main(args: Array<String>) {
        val output = ObjectOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out))).apply { flush() }
        // Libraries must not write text into the binary command pipe.
        System.setOut(System.err)
        IslandLog.info("linux-ui", "UI process started; desktop=${System.getenv("XDG_CURRENT_DESKTOP")}; " +
            "session=${System.getenv("XDG_SESSION_TYPE")}; java=${System.getProperty("java.version")}")
        var nextRequest = 0L
        fun send(action: String, vararg arguments: String): Long = synchronized(output) {
            val requestId = ++nextRequest
            output.writeObject(IslandCommand(action, arguments.toList(), requestId)); output.reset(); output.flush()
            requestId
        }
        val timeline = RemotePlayback()
        val spectrum = RemoteSpectrum()
        val volume = RemoteVolumeControl { action, value -> send(action, value.toString()) }
        val store = RemoteSettingsStore { action, arguments -> send(action, *arguments.toTypedArray()) }
        var window: IslandWindow? = null
        var lastHealth = 0L
        var lastDiagnostics = 0L
        var previousPaintCount = -1L
        try {
            ObjectInputStream(BufferedInputStream(System.`in`)).use { input ->
                while (true) {
                    val message = input.readObject()
                    if (message is IslandSpectrum) { spectrum.accept(message); continue }
                    if (message is IslandVolumeResult) {
                        SwingUtilities.invokeAndWait { volume.accept(message) }
                        continue
                    }
                    val state = message as IslandState
                    SwingUtilities.invokeAndWait {
                        timeline.accept(state)
                        val settingsChanged = store.accept(state)
                        if (window == null) {
                            IslandLog.info("linux-ui", "creating overlay")
                            window = IslandWindow(timeline, store, object : PlaybackActions {
                            override fun previous() { send("previous") }
                            override fun toggle() { send("toggle") }
                            override fun next() { send("next") }
                            override fun seek(positionMs: Long) = timeline.seek(positionMs) { send("seek", it.toString()) }
                            override fun adjustVolume(delta: Double, completed: (Double?) -> Unit) = volume.adjust(delta, completed)
                            override fun setVolume(value: Double, completed: (Double?) -> Unit) = volume.set(value, completed)
                        }, { error ->
                            IslandLog.error("linux-ui", "overlay reported failure", error)
                            send("error", error.toString())
                        }, spectrum::levels, spectrum::fallback, spectrum::status)
                            IslandLog.info("linux-ui", "overlay constructed")
                        } else if (settingsChanged) window.reload()
                        if (state.about) window.about()
                        val now = System.nanoTime()
                        if (now - lastHealth >= 1_000_000_000L) {
                            send("frames", window.presentedFrames.toString())
                            lastHealth = now
                        }
                        if (now - lastDiagnostics >= 5_000_000_000L) {
                            val frames = window.presentedFrames
                            IslandLog.info("linux-ui.health", "frames=$frames; configuredEnabled=${store.read().enabled}")
                            val current = store.read()
                            if (previousPaintCount >= 0 && frames == previousPaintCount && current.enabled &&
                                (!current.hidePaused || timeline.snapshot().playing)) {
                                IslandLog.anomaly("linux-ui.renderStalled", "no frame progress in 5 seconds")
                            } else IslandLog.recovered("linux-ui.renderStalled", "frames advanced or hidden by policy")
                            previousPaintCount = frames
                            lastDiagnostics = now
                        }
                    }
                }
            }
        } catch (_: EOFException) {
            IslandLog.info("linux-ui", "host pipe closed")
            // The host unloaded the plugin or exited. No orphan overlay.
        } finally {
            IslandLog.info("linux-ui", "disposing overlay and exiting")
            SwingUtilities.invokeAndWait { window?.close() }
            IslandLog.flush()
        }
    }
}
