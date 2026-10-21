// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT
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
        val ownBounds = RECT()
        val aboveClasses = mutableListOf<String>()
        if (exists && user.GetWindowRect(hwnd, ownBounds)) {
            var above = user.GetWindow(hwnd, DWORD(3)) // GW_HWNDPREV: preceding windows in Z order.
            var inspected = 0
            while (above != null && inspected++ < 64) {
                val bounds = RECT()
                if (user.GetWindow(above, DWORD(4)) != hwnd && user.IsWindowVisible(above) && user.GetWindowLong(above, -20) and 8 != 0 &&
                    user.GetWindowRect(above, bounds) && bounds.left < ownBounds.right && bounds.right > ownBounds.left &&
                    bounds.top < ownBounds.bottom && bounds.bottom > ownBounds.top) {
                    val name = CharArray(128)
                    user.GetClassName(above, name, name.size)
                    aboveClasses += Native.toString(name)
                }
                above = user.GetWindow(above, DWORD(3))
            }
        }
        return mapOf("nativeExists" to exists, "nativeVisible" to (exists && user.IsWindowVisible(hwnd)),
            "minimized" to if (placementKnown) placement.showCmd == 2 else "unknown",
            "nativeTopmost" to (exists && user.GetWindowLong(hwnd, -20) and 8 != 0),
            "overlappingTopmostAbove" to aboveClasses,
            "compositorVisibility" to "unknown")
    }
}
