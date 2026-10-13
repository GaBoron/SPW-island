// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Platform
import org.jetbrains.skiko.Library
import java.nio.file.Path

/** Select only this plugin's native library while loading; renderer selection stays per window. */
internal object SkikoRuntime {
    fun load() {
        if (!Platform.isWindows()) { Library.load(); return }
        val resource = checkNotNull(javaClass.getResource("/native/compose/skiko-windows-x64.dll")) {
            "词岛 GPU 渲染资源缺失"
        }
        check(resource.protocol == "file") { "GPU 渲染资源未展开为文件" }
        val previous = System.getProperty("skiko.library.path")
        try {
            System.setProperty("skiko.library.path", Path.of(resource.toURI()).parent.toString())
            Library.load()
        } finally {
            if (previous == null) System.clearProperty("skiko.library.path")
            else System.setProperty("skiko.library.path", previous)
        }
    }
}
