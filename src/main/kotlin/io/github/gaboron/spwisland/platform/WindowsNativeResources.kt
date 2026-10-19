// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import java.nio.file.Files
import java.nio.file.Path

/** Loaded DLLs and running helpers must not pin files in the replaceable plugin directory. */
internal object WindowsNativeResources {
    private val directory by lazy { Files.createTempDirectory("spw-island-${ProcessHandle.current().pid()}-") }
    private val extracted = mutableMapOf<String, Path>()

    @Synchronized fun extract(resource: String): Path = extracted.getOrPut(resource) {
        val target = directory.resolve(resource.substringAfterLast('/'))
        check(!Files.exists(target)) { "原生资源文件名重复：$resource" }
        WindowsNativeResources::class.java.getResourceAsStream(resource).use { input ->
            checkNotNull(input) { "插件中缺少原生资源：$resource" }
            Files.copy(input, target)
        }
        target
    }

    fun skikoDirectory(): Path {
        val library = extract("/native/compose/skiko-windows-x64.dll")
        extract("/native/compose/icudtl.dat")
        return library.parent
    }
}
