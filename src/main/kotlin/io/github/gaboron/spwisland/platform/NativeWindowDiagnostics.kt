// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT
import com.sun.jna.platform.win32.WinUser.WS_MINIMIZE
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/** OS state describes the native window, not the compositor's final pixels. */
internal object NativeWindowDiagnostics {
    fun read(window: Window): Map<String, Any?> {
        if (!Platform.isWindows()) return mapOf("nativeVisibility" to "unknown", "compositorVisibility" to "unknown")
        val hwnd = HWND(Native.getWindowPointer(window))
        val user = User32.INSTANCE
        val exists = user.IsWindow(hwnd)
        val placement = WINDOWPLACEMENT()
        val placementKnown = exists && user.GetWindowPlacement(hwnd, placement).booleanValue()
        val extendedStyle = if (exists) user.GetWindowLong(hwnd, -20) else 0
        val ownBounds = RECT()
        val boundsKnown = exists && user.GetWindowRect(hwnd, ownBounds)
        val aboveClasses = mutableListOf<String>()
        val overlappingAbove = mutableListOf<Map<String, Any?>>()
        var inspected = 0
        var truncated = false
        if (boundsKnown) {
            var above = user.GetWindow(hwnd, DWORD(3)) // GW_HWNDPREV: preceding windows in Z order.
            while (above != null && inspected < 64) {
                inspected++
                val bounds = RECT()
                if (user.GetWindow(above, DWORD(4)) != hwnd && user.IsWindowVisible(above) &&
                    user.GetWindowRect(above, bounds) && bounds.left < ownBounds.right && bounds.right > ownBounds.left &&
                    bounds.top < ownBounds.bottom && bounds.bottom > ownBounds.top) {
                    val name = CharArray(128)
                    user.GetClassName(above, name, name.size)
                    val className = Native.toString(name)
                    val topmost = user.GetWindowLong(above, -20) and 8 != 0
                    val pid = IntByReference()
                    user.GetWindowThreadProcessId(above, pid)
                    overlappingAbove += mapOf("class" to className, "topmost" to topmost,
                        "sameProcess" to (pid.value.toLong() == ProcessHandle.current().pid()),
                        "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                    if (topmost) aboveClasses += className
                }
                above = user.GetWindow(above, DWORD(3))
            }
            truncated = above != null
        }
        val foreground = user.GetForegroundWindow()
        val foregroundClass = foreground?.let {
            val name = CharArray(128)
            user.GetClassName(it, name, name.size)
            Native.toString(name)
        }
        val foregroundBounds = RECT()
        val foregroundBoundsKnown = foreground != null && user.GetWindowRect(foreground, foregroundBounds)
        return mapOf("nativeExists" to exists, "nativeVisible" to (exists && user.IsWindowVisible(hwnd)),
            "minimized" to if (exists) user.GetWindowLong(hwnd, -16) and WS_MINIMIZE != 0 else "unknown",
            "showCommand" to if (placementKnown) placement.showCmd else null,
            "nativeTopmost" to (exists && extendedStyle and 8 != 0),
            "nativeStyle" to mapOf("layered" to (extendedStyle and 0x80000 != 0),
                "clickThrough" to (extendedStyle and 0x20 != 0)),
            "nativeBounds" to if (boundsKnown) listOf(ownBounds.left, ownBounds.top, ownBounds.right, ownBounds.bottom) else null,
            "foregroundClass" to foregroundClass,
            "foregroundBounds" to if (foregroundBoundsKnown)
                listOf(foregroundBounds.left, foregroundBounds.top, foregroundBounds.right, foregroundBounds.bottom) else null,
            "overlappingTopmostAbove" to aboveClasses,
            "overlappingWindowsAbove" to overlappingAbove,
            "zOrderSample" to mapOf("inspected" to inspected, "truncated" to truncated,
                "region" to "native_backing_rect", "evidence" to "z_order_only_compositor_unverified"),
            "compositorVisibility" to "unknown")
    }
}
