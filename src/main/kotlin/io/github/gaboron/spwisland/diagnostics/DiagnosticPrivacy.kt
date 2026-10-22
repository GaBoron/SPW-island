// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.io.PrintWriter
import java.io.StringWriter

/** Only explicitly supplied context is logged; never serialize playback or host objects. */
internal object DiagnosticPrivacy {
    private val home = System.getProperty("user.home", "").takeIf { it.isNotEmpty() }
    private val credential = Regex("""(?i)(authorization|password|token|secret|api[_-]?key)(["']?\s*[:=]\s*["']?|\s+)[^\s,;"']+""")
    private val bearer = Regex("(?i)bearer\\s+[^\\s,;]+|(?:gh[pousr]_|github_pat_|sk-proj-)[A-Za-z0-9_]+")
    private val windowsPath = Regex("[A-Za-z]:[\\\\/][^\\r\\n\"<>|]*")
    private val unixPath = Regex("(?<![\\w:])/(?!/)[^\\s\"<>)]*")

    fun text(value: Any?): String {
        val raw = value?.toString().orEmpty()
        var text = if (raw.length > 32_768) raw.take(24_576) + "\n<middle truncated>\n" + raw.takeLast(8192) else raw
        // Keep absolute paths recognizable until their complete suffix has been removed.
        text = unixPath.replace(windowsPath.replace(text, "<path>"), "<path>")
        if (home != null) text = text.replace(home, "<home>", ignoreCase = true)
        return credential.replace(bearer.replace(text, "<credential>")) {
            "${it.groupValues[1]}${it.groupValues[2]}<redacted>"
        }
    }

    fun stack(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        .toString().let(::text)

    fun thread(thread: Thread): Map<String, Any?> = try {
        mapOf("lastProgressThread" to thread.name, "threadState" to thread.state,
            "threadStack" to thread.stackTrace.take(64).joinToString("\n"))
    } catch (error: Exception) { mapOf("threadStack" to "unavailable: ${error.javaClass.name}") }
}
