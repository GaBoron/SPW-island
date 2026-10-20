// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import java.awt.Window

/** Windows-only capabilities. All access is from the Swing event thread. */
class WindowsOverlay {
    companion object { private const val ERROR_INVALID_WINDOW_HANDLE = 1400 }
    private val user32 = if (Platform.isWindows()) User32.INSTANCE else null
    val supportsClickThrough: Boolean get() = user32 != null
    val supportsStableTranslucentCanvas: Boolean get() = user32 != null
    private fun handle(window: Window) = HWND(Native.getWindowPointer(window))
    private fun existingHandle(window: Window, api: User32): HWND? {
        val pointer = Native.getWindowPointer(window) ?: return null
        if (Pointer.nativeValue(pointer) == 0L) return null
        val hwnd = HWND(pointer)
        return hwnd.takeIf { api.IsWindow(it) }
    }

    /** Null means the native peer is missing, not that it is non-topmost. */
    fun isActuallyTopmost(window: Window): Boolean? {
        val api = user32 ?: return null
        val hwnd = existingHandle(window, api) ?: return null
        return api.GetWindowLong(hwnd, -20) and 0x00000008 != 0 // WS_EX_TOPMOST
    }

    fun isNativeVisible(window: Window): Boolean? {
        val api = user32 ?: return null
        val hwnd = existingHandle(window, api) ?: return null
        return api.IsWindowVisible(hwnd)
    }

    fun isMinimized(window: Window): Boolean? {
        val api = user32 ?: return null
        val hwnd = existingHandle(window, api) ?: return null
        return api.IsIconic(hwnd)
    }

    fun describeWindow(window: Window): String {
        val api = user32 ?: return "native API unavailable"
        val hwnd = existingHandle(window, api) ?: return "native peer missing; awtVisible=${window.isVisible}"
        val flags = api.GetWindowLong(hwnd, -20)
        return "handle=${Native.getWindowPointer(window)}; nativeVisible=${api.IsWindowVisible(hwnd)}; " +
            "minimized=${api.IsIconic(hwnd)}; topmost=${flags and 0x00000008 != 0}; " +
            "layered=${flags and 0x00080000 != 0}; transparent=${flags and 0x00000020 != 0}"
    }

    fun reinforceTopmost(window: Window) {
        val api = user32 ?: return
        if (!window.isDisplayable || !window.isVisible) return
        val hwnd = existingHandle(window, api) ?: return
        // Reassert the top of the topmost band without moving, resizing or taking keyboard focus.
        val flags = 0x0001 or 0x0002 or 0x0010 or 0x0200 // NOSIZE | NOMOVE | NOACTIVATE | NOOWNERZORDER
        Native.setLastError(0)
        if (api.SetWindowPos(hwnd, HWND(Pointer.createConstant(-1)), 0, 0, 0, 0, flags)) return
        val error = Native.getLastError()
        // AWT may replace a native peer around a visibility transition. Reacquire it on the next tick.
        if (error == ERROR_INVALID_WINDOW_HANDLE) return
        error("无法维持词岛置顶：$error")
    }

    fun clickThrough(window: Window, enabled: Boolean) {
        val api = user32 ?: return
        if (!window.isDisplayable) return
        val hwnd = handle(window)
        val old = api.GetWindowLong(hwnd, -20) // GWL_EXSTYLE
        val flags = 0x20 or 0x80000 // WS_EX_TRANSPARENT | WS_EX_LAYERED
        // Keep LAYERED: AWT uses it for per-pixel transparency even when unlocked.
        val next = if (enabled) old or flags else old and 0x20.inv()
        if (next != old) {
            Native.setLastError(0)
            val result = api.SetWindowLong(hwnd, -20, next)
            check(result != 0 || Native.getLastError() == 0) { "无法更新词岛鼠标穿透：${Native.getLastError()}" }
        }
    }

    fun foregroundIsFullscreen(window: Window): Boolean {
        val api = user32 ?: return false
        if (!window.isDisplayable) return false
        val foreground = api.GetForegroundWindow() ?: return false
        if (foreground == handle(window)) return false
        val className = CharArray(256)
        api.GetClassName(foreground, className, className.size)
        if (Native.toString(className) in setOf("Progman", "WorkerW", "Shell_TrayWnd")) return false
        val monitor = api.MonitorFromWindow(foreground, 2) ?: return false
        // Use native monitor coordinates on both sides, avoiding mixed-DPI coordinate comparisons.
        if (monitor != api.MonitorFromWindow(handle(window), 2)) return false
        val info = MONITORINFO()
        if (!api.GetMonitorInfo(monitor, info).booleanValue()) return false
        val rect = RECT()
        if (!api.GetWindowRect(foreground, rect)) return false
        val screen = info.rcMonitor
        return rect.left <= screen.left && rect.top <= screen.top &&
            rect.right >= screen.right && rect.bottom >= screen.bottom
    }
}
