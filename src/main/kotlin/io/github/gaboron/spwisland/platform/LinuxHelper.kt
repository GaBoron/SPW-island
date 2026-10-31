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
import java.net.URL
import io.github.gaboron.spwisland.diagnostics.DiagnosticProcesses
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import com.google.gson.JsonParser

/** Python desktop bridges use system libraries directly, without extra Python packages. */
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

    fun startTray(name: String, icon: Path, width: Int, height: Int, pixels: String): Process =
        start("tray", name, icon.toString(), extract("island-dbus-tray.py").toString(),
            width.toString(), height.toString(), pixels)

    fun startGlass(): Process = startScript("island-glass.py", extract("island-pointer.py").toString())

    /** Read-only and bounded; run only when the user exports diagnostics, never in a render loop. */
    fun gnomeDiagnostics(): String? {
        if (!System.getProperty("os.name").startsWith("Linux") ||
            System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':').none { it.equals("GNOME", true) }) return null
        val process = startScript("gnome-diagnostics.py", extract("gnome-crash-reports.py").toString(),
            extract("gnome-core-location.py").toString())
        val result = FutureTask { process.inputStream.readNBytes(196_609) }
        IslandLog.thread("SPW Island GNOME diagnostic output") { result.run() }.start()
        try {
            check(process.waitFor(24, TimeUnit.SECONDS)) { "GNOME 诊断采集超时" }
            val bytes = result.get(2, TimeUnit.SECONDS)
            check(process.exitValue() == 0 && bytes.size <= 196_608) { "GNOME 诊断采集未完成" }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            result.cancel(true)
        }
    }

    fun installGnomeSupport(kind: String): String = runGnomeSetup(kind, false)

    private val kde: Boolean get() = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
        .split(':').any { it.equals("KDE", true) }

    fun installCompositorGlass(): String = if (kde) runKwinSetup(false)["message"].asString
        else installGnomeSupport("glass")

    fun refreshGnomeGlass(): String? {
        val result = JsonParser.parseString(runGnomeSetup("glass", true)).asJsonObject
        IslandLog.event("linux.glass", "renderer.synced", mapOf(
            "installed" to result["installed"]?.asBoolean, "ready" to result["ready"]?.asBoolean,
            "changed" to result["changed"]?.asBoolean))
        return result["message"]?.asString?.takeIf {
            it.isNotBlank() && result["installed"]?.asBoolean == true && result["ready"]?.asBoolean != true &&
                result["changed"]?.asBoolean == true
        }
    }

    fun refreshCompositorGlass(): String? {
        if (!kde) return refreshGnomeGlass()
        val result = runKwinSetup(true)
        IslandLog.event("linux.glass", "kwin.renderer.synced", mapOf(
            "installed" to result["installed"]?.asBoolean, "ready" to result["ready"]?.asBoolean,
            "changed" to result["changed"]?.asBoolean))
        return result["message"]?.asString?.takeIf {
            it.isNotBlank() && result["ready"]?.asBoolean != true
        }
    }

    private fun runKwinSetup(refresh: Boolean): com.google.gson.JsonObject {
        val archive = extract("spw-island-kwin-glass.zip")
        val arguments = listOf(archive.toString(), extract("island-pointer.py").toString()) +
            if (refresh) listOf("--refresh-existing") else emptyList()
        val process = startScript("kwin-glass-support.py", *arguments.toTypedArray())
        val output = FutureTask { process.inputStream.readNBytes(65_537) }
        IslandLog.thread("SPW Island KWin setup output") { output.run() }.start()
        try {
            check(process.waitFor(if (refresh) 30 else 630, TimeUnit.SECONDS)) { "KWin 配套组件安装超时。" }
            val bytes = output.get(2, TimeUnit.SECONDS)
            check(bytes.size <= 65_536) { "KWin 安装输出超出限制。" }
            val result = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            if (process.exitValue() != 0) result["detail"]?.asString?.let { detail ->
                IslandLog.warn("linux.glass", "kwin.setup.failed", mapOf("detail" to detail.takeLast(5000)))
            }
            check(process.exitValue() == 0) { result["error"]?.asString ?: "KWin 配套组件安装失败。" }
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            output.cancel(true)
        }
    }

    private fun runGnomeSetup(kind: String, refresh: Boolean): String {
        require(kind == "pointer" || kind == "glass")
        val archive = extract("spw-island-$kind@gaboron.github.io.shell-extension.zip")
        val arguments = listOf(kind, archive.toString()) + if (refresh) listOf("--refresh-existing") else emptyList()
        val process = startScript("install-gnome-support.py", *arguments.toTypedArray())
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("GNOME 支持安装超时，请重试。")
        }
        val message = process.inputStream.bufferedReader().readText().trim()
        check(process.exitValue() == 0) {
            if (refresh) runCatching { JsonParser.parseString(message).asJsonObject["error"]?.asString }
                .getOrNull() ?: "GNOME 支持自动同步失败"
            else message.ifBlank { "GNOME 支持安装失败" }
        }
        return message
    }

    fun startScript(name: String, vararg arguments: String): Process {
        val builder = ProcessBuilder(listOf("/usr/bin/python3", extract(name).toString()) + arguments).apply {
            // GTK menus and AWT use the same X11/XWayland coordinate and popup-grab space.
            environment()["GDK_BACKEND"] = "x11"
            environment()["SPW_ISLAND_PY_DIAGNOSTICS"] = extract("island-diagnostics.py").toString()
        }
        return DiagnosticProcesses.start(builder, "linux.${if (arguments.firstOrNull() == "jvm") "ui" else name.substringBeforeLast('.')}")
    }

    internal fun uiClasspath(): String {
        val classes = listOf(LinuxHelper::class.java, kotlin.Unit::class.java, Native::class.java, X11::class.java,
            SkiaLayer::class.java, Job::class.java)
        val own = classpathEntry(LinuxHelper::class.java)
        val paths = mutableListOf(own.toString())
        // Prefer the plugin's bundled versions to similarly named libraries already loaded by SPW.
        val libraries = own.parent.resolve("lib")
        if (Files.isDirectory(libraries)) Files.list(libraries).use { jars ->
            jars.filter { it.fileName.toString().endsWith(".jar") }.sorted().forEach { paths += it.toString() }
        }
        // JNA / JNA Platform come from SPW, including in this independent UI JVM.
        paths += classes.map { classpathEntry(it).toString() }
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

    private fun classpathEntry(type: Class<*>): Path {
        val location = type.protectionDomain?.codeSource?.location
        if (location?.protocol == "file") return Path.of(location.toURI())

        // Host class loaders may omit CodeSource locations while still exposing class resources.
        val name = type.name.replace('.', '/') + ".class"
        val resource = type.getResource("/$name")
        return resourceClasspath(resource, name) ?: error(
            "Cannot locate Linux UI dependency ${type.name} " +
                "(source=${location?.protocol ?: "none"}, resource=${resource?.protocol ?: "none"})"
        )
    }

    private fun resourceClasspath(resource: URL?, name: String): Path? {
        return when (resource?.protocol) {
            "jar" -> (resource.openConnection() as JarURLConnection).jarFileURL
                .takeIf { it.protocol == "file" }?.let { Path.of(it.toURI()) }
            "file" -> {
                var root = Path.of(resource.toURI())
                repeat(name.count { it == '/' } + 1) { root = root.parent ?: return null }
                root
            }
            else -> null
        }
    }

    fun startUi(javaHome: String = System.getProperty("java.home")): Process {
        return start("jvm", javaHome, uiClasspath(),
            "io/github/gaboron/spwisland/remote/LinuxIslandMain", ApplicationIdentity.NAME)
    }
}
