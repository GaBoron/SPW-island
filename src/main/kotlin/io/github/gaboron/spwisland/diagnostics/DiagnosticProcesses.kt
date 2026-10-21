// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

/** stderr is drained independently of protocol stdout, including Python/JNI and native failures. */
internal object DiagnosticProcesses {
    fun start(builder: ProcessBuilder, module: String): Process {
        builder.environment()["SPW_ISLAND_LOG_SESSION"] = IslandLog.session
        builder.environment()["SPW_ISLAND_LOG_DIRECTORY"] = IslandLog.directory.toString()
        val process = try { builder.redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.PIPE).start() }
        catch (error: Throwable) { IslandLog.failure(module, "process.start_failed", error); throw error }
        IslandLog.event(module, "process.started", mapOf("childPid" to process.pid()))
        IslandLog.thread("SPW Island $module stderr") {
            IslandLog.attempt(module, "process.stderr_failed") {
                process.errorStream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    val pending = StringBuilder()
                    while (true) {
                        val size = reader.read(buffer)
                        if (size < 0) break
                        for (index in 0 until size) {
                            val char = buffer[index]
                            if (char == '\n' || pending.length >= 8192) {
                                if (pending.isNotEmpty()) IslandLog.warn(module, "process.stderr",
                                    mapOf("childPid" to process.pid(), "message" to pending.toString()))
                                pending.setLength(0)
                            }
                            if (char != '\n' && char != '\r') pending.append(char)
                        }
                    }
                    if (pending.isNotEmpty()) IslandLog.warn(module, "process.stderr",
                        mapOf("childPid" to process.pid(), "message" to pending.toString()))
                }
            }
            val code = process.waitFor()
            IslandLog.event(module, "process.exited", mapOf("childPid" to process.pid(), "exitCode" to code))
        }.start()
        return process
    }
}
