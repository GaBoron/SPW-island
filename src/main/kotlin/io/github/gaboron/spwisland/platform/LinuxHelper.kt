// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.ui.ApplicationIdentity
import com.sun.jna.Native
import com.sun.jna.platform.unix.X11
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skiko.SkiaLayer
import kotlinx.coroutines.Job
import java.net.JarURLConnection

/** Only Python's standard library is required; GTK is loaded directly for native menus. */
internal object LinuxHelper {
    private val scripts = mutableMapOf<String, Path>()
    @Synchronized private fun extract(name: String): Path = scripts.getOrPut(name) {
        val path = Files.createTempFile("spw-island-linux-", ".${name.substringAfterLast('.')}")
        LinuxHelper::class.java.getResourceAsStream("/native/$name").use { input ->
            checkNotNull(input) { "Linux helper $name is missing from the plugin" }
            Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        path.toFile().deleteOnExit()
        path
    }

    fun start(vararg arguments: String): Process = startScript("island-linux.py", *arguments)

    fun installGnomePointer(): String {
        val archive = extract("spw-island-pointer@gaboron.github.io.shell-extension.zip")
        val process = startScript("install-gnome-pointer.py", archive.toString())
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("GNOME 指针扩展安装超时")
        }
        val message = process.inputStream.bufferedReader().readText().trim()
        check(process.exitValue() == 0) { message.ifBlank { "GNOME 指针扩展安装失败" } }
        return message
    }

    fun startScript(name: String, vararg arguments: String): Process = ProcessBuilder(
        listOf("/usr/bin/python3", extract(name).toString()) + arguments
    ).redirectError(ProcessBuilder.Redirect.INHERIT).apply {
        // GTK menus and AWT use the same X11/XWayland coordinate and popup-grab space.
        environment()["GDK_BACKEND"] = "x11"
    }.start()

    internal fun uiClasspath(): String {
        val classes = listOf(LinuxHelper::class.java, kotlin.Unit::class.java, Native::class.java, X11::class.java,
            SkiaLayer::class.java, Job::class.java)
        val own = Path.of(LinuxHelper::class.java.protectionDomain.codeSource.location.toURI())
        val paths = mutableListOf(own.toString())
        // Prefer the plugin's bundled versions to similarly named libraries already loaded by SPW.
        val libraries = own.parent.resolve("lib")
        if (Files.isDirectory(libraries)) Files.list(libraries).use { jars ->
            jars.filter { it.fileName.toString().endsWith(".jar") }.sorted().forEach { paths += it.toString() }
        }
        paths += classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        // Gradle keeps classes/resources separate; an installed plugin contains both in classes/.
        LinuxHelper::class.java.getResource("/native/island-linux.py")?.takeIf { it.protocol == "file" }?.let {
            paths += Path.of(it.toURI()).parent.parent.toString()
        }
        // The native runtime is a separate JAR; resolve its resource without loading Skiko in the host.
        SkiaLayer::class.java.getResource("/libskiko-linux-x64.so")?.takeIf { it.protocol == "jar" }?.let {
            paths += Path.of((it.openConnection() as JarURLConnection).jarFileURL.toURI()).toString()
        }
        return paths.distinct().joinToString(java.io.File.pathSeparator)
    }

    fun startUi(javaHome: String = System.getProperty("java.home")): Process {
        return start("jvm", javaHome, uiClasspath(),
            "io/github/gaboron/spwisland/remote/LinuxIslandMain", ApplicationIdentity.NAME)
    }
}
