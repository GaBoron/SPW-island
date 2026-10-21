// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.ui.*
import java.io.*
import javax.swing.SwingUtilities
import io.github.gaboron.spwisland.diagnostics.IslandLog
import io.github.gaboron.spwisland.diagnostics.DiagnosticPulse

/** Entry point loaded with the host's bundled JVM, even when jpackage omits bin/java. */
object LinuxIslandMain {
    @JvmStatic fun main(args: Array<String>) {
        IslandLog.start("ui")
        val startup = IslandLog.operation("window", "remote.create")
        val incoming = DiagnosticPulse("ipc", "host_updates").apply { expected = true }
        var messages = 0L
        val output = ObjectOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out))).apply { flush() }
        // Libraries must not write text into the binary command pipe.
        System.setOut(System.err)
        var nextRequest = 0L
        fun send(action: String, vararg arguments: String): Long = synchronized(output) {
            val requestId = ++nextRequest
            if (action !in setOf("frames", "volume", "set_volume")) IslandLog.event("ipc", "command.sent", mapOf("action" to action, "request" to requestId))
            output.writeObject(IslandCommand(action, arguments.toList(), requestId)); output.reset(); output.flush()
            requestId
        }
        val timeline = RemotePlayback()
        val spectrum = RemoteSpectrum()
        val volume = RemoteVolumeControl { action, value -> send(action, value.toString()) }
        val store = RemoteSettingsStore { action, arguments -> send(action, *arguments.toTypedArray()) }
        var window: IslandWindow? = null
        var lastHealth = 0L
        try {
            ObjectInputStream(BufferedInputStream(System.`in`)).use { input ->
                while (true) {
                    val message = input.readObject()
                    incoming.beat(++messages)
                    if (message is IslandSpectrum) { spectrum.accept(message); continue }
                    if (message is IslandVolumeResult) {
                        SwingUtilities.invokeAndWait { IslandLog.guard("LinuxIslandMain", "invokeAndWait.callback_failed") { volume.accept(message) } }
                        continue
                    }
                    val state = message as IslandState
                    state.settings?.let {
                        IslandLog.cadence(it.performance.diagnosticSampleIntervalNs)
                        incoming.timeoutNs = it.performance.diagnosticStallTimeoutNs
                    }
                    SwingUtilities.invokeAndWait {
                        IslandLog.guard("LinuxIslandMain", "invokeAndWait.callback_failed") {
                            timeline.accept(state)
                            val settingsChanged = store.accept(state)
                            if (window == null) {
                                window = IslandWindow(timeline, store, object : PlaybackActions {
                                override fun previous() { send("previous") }
                                override fun toggle() { send("toggle") }
                                override fun next() { send("next") }
                                override fun seek(positionMs: Long) = timeline.seek(positionMs) { send("seek", it.toString()) }
                                override fun adjustVolume(delta: Double, completed: (Double?) -> Unit) = volume.adjust(delta, completed)
                                override fun setVolume(value: Double, completed: (Double?) -> Unit) = volume.set(value, completed)
                                }, { error ->
                                    IslandLog.failure("ui", "operation.failed", error)
                                    send("error", error.javaClass.name)
                                }, spectrum::levels, spectrum::fallback, spectrum::status)
                                startup.close()
                            }
                            else if (settingsChanged) window.reload()
                            if (state.about) window.about()
                            val now = System.nanoTime()
                            if (now - lastHealth >= 1_000_000_000L) {
                                send("frames", window.presentedFrames.toString())
                                lastHealth = now
                            }
                        }
                    }
                }
            }
        } catch (_: EOFException) {
            // The host unloaded the plugin or exited. No orphan overlay.
            IslandLog.event("ipc", "host.eof")
        } catch (error: Throwable) {
            startup.failed(error)
            IslandLog.failure("ipc", "reader.failed", error)
            throw error
        } finally {
            incoming.close()
            IslandLog.attempt("ui", "shutdown.failed") { SwingUtilities.invokeAndWait { IslandLog.guard("LinuxIslandMain", "invokeAndWait.callback_failed") { window?.close() } } }
            startup.close()
            IslandLog.flush()
            IslandLog.stop()
        }
    }
}
