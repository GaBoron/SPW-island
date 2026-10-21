// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.diagnostics.IslandLog

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path

/** Checks the helper's requested CLR without letting the managed EXE host show its own error dialog. */
internal object DotNetFrameworkRuntime {
    private const val RUNTIME_INFO_REQUEST_AMD64 = 0x04
    private const val RUNTIME_INFO_DONT_RETURN_DIRECTORY = 0x10
    private const val RUNTIME_INFO_DONT_RETURN_VERSION = 0x20
    private const val RUNTIME_INFO_DONT_SHOW_ERROR_DIALOG = 0x40

    private interface Mscoree : StdCallLibrary {
        fun GetRequestedRuntimeInfo(
            executable: WString,
            version: WString?,
            configurationFile: WString?,
            startupFlags: Int,
            runtimeInfoFlags: Int,
            directory: Pointer?,
            directoryLength: Int,
            requiredDirectoryLength: IntByReference?,
            runtimeVersion: Pointer?,
            runtimeVersionLength: Int,
            requiredRuntimeVersionLength: IntByReference?
        ): Int
    }

    fun canRun(executable: Path): Boolean = IslandLog.attempt("DotNetFrameworkRuntime", "canRun.attempt1") {
        val result = Native.load("mscoree", Mscoree::class.java).GetRequestedRuntimeInfo(
            WString(executable.toString()), WString("v4.0.30319"), null, 0,
            RUNTIME_INFO_REQUEST_AMD64 or
                RUNTIME_INFO_DONT_RETURN_DIRECTORY or
                RUNTIME_INFO_DONT_RETURN_VERSION or
                RUNTIME_INFO_DONT_SHOW_ERROR_DIALOG,
            null, 0, null, null, 0, null
        )
        if (result != 0) IslandLog.warn("native", "clr.unavailable", mapOf("hresult" to result.toUInt().toString(16)))
        result == 0
    }.getOrDefault(false)
}
