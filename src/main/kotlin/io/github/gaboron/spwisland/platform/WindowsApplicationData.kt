// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinError
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path

/** Resolve the physical AppData location so CLR activation can see executables written by an MSIX host. */
internal object WindowsApplicationData {
    fun local(): Path {
        val ole = Ole32.INSTANCE
        val initialized = ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
        // An existing COM apartment is also sufficient; never balance someone else's initialization.
        check(initialized >= 0 || initialized == WinError.RPC_E_CHANGED_MODE) {
            "无法初始化 Windows 本地应用数据查询（${initialized.toUInt().toString(16)}）"
        }
        try {
            val result = PointerByReference()
            try {
                // RETURN_FILTER_REDIRECTION_TARGET returns LocalCache\Local for a virtualized desktop host.
                // DONT_VERIFY also permits the first launch before that directory exists.
                val status = Shell32.INSTANCE.SHGetKnownFolderPath(KnownFolders.FOLDERID_LocalAppData,
                    0x00040000 or 0x00004000, null, result).toInt()
                check(status >= 0) { "无法查询 Windows 本地应用数据目录（${status.toUInt().toString(16)}）" }
                val value = checkNotNull(result.value).getWideString(0)
                check(value.isNotBlank()) { "Windows 返回了空的本地应用数据目录" }
                return Path.of(value).toAbsolutePath().normalize()
            } finally {
                result.value?.let(ole::CoTaskMemFree)
            }
        } finally {
            if (initialized >= 0) ole.CoUninitialize()
        }
    }
}
