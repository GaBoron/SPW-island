// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Platform
import org.jetbrains.skiko.Library

/** Select only this plugin's native library while loading; renderer selection stays per window. */
internal object SkikoRuntime {
    fun load() {
        if (!Platform.isWindows()) { Library.load(); return }
        val directory = WindowsNativeResources.skikoDirectory()
        val previous = System.getProperty("skiko.library.path")
        try {
            System.setProperty("skiko.library.path", directory.toString())
            Library.load()
        } finally {
            if (previous == null) System.clearProperty("skiko.library.path")
            else System.setProperty("skiko.library.path", previous)
        }
    }
}
