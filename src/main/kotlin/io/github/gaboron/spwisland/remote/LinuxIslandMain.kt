// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.ui.*
import java.io.*
import javax.swing.SwingUtilities

/** Entry point loaded with the host's bundled JVM, even when jpackage omits bin/java. */
object LinuxIslandMain {
    @JvmStatic fun main(args: Array<String>) {
        val output = ObjectOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out))).apply { flush() }
        // Libraries must not write text into the binary command pipe.
        System.setOut(System.err)
        var nextRequest = 0L
        fun send(action: String, vararg arguments: String): Long = synchronized(output) {
            val requestId = ++nextRequest
            output.writeObject(IslandCommand(action, arguments.toList(), requestId)); output.reset(); output.flush()
            requestId
        }
        val timeline = RemotePlayback()
        val spectrum = RemoteSpectrum()
        val store = RemoteSettingsStore { action, arguments -> send(action, *arguments.toTypedArray()) }
        var window: IslandWindow? = null
        var lastHealth = 0L
        try {
            ObjectInputStream(BufferedInputStream(System.`in`)).use { input ->
                while (true) {
                    val message = input.readObject()
                    if (message is IslandSpectrum) { spectrum.accept(message); continue }
                    val state = message as IslandState
                    SwingUtilities.invokeAndWait {
                        timeline.accept(state)
                        val settingsChanged = store.accept(state)
                        if (window == null) window = IslandWindow(timeline, store, object : PlaybackActions {
                            override fun previous() { send("previous") }
                            override fun toggle() { send("toggle") }
                            override fun next() { send("next") }
                            override fun seek(positionMs: Long) = timeline.seek(positionMs) { send("seek", it.toString()) }
                        }, { send("error", it.toString()) }, spectrum::levels, spectrum::fallback, spectrum::status)
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
        } catch (_: EOFException) {
            // The host unloaded the plugin or exited. No orphan overlay.
        } finally {
            SwingUtilities.invokeAndWait { window?.close() }
        }
    }
}
