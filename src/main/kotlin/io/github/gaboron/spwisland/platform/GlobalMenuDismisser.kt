// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.diagnostics.IslandLog

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.HHOOK
import com.sun.jna.platform.win32.WinUser.LowLevelMouseProc
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32.WinUser.MSLLHOOKSTRUCT
import java.awt.Window
import java.awt.AWTEvent
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/** Watches presses while a popup is preparing or visible, without consuming the original click. */
internal class GlobalMenuDismisser(private val onOutsidePress: () -> Unit) : AutoCloseable {
    private class HookSession {
        lateinit var callback: LowLevelMouseProc
        @Volatile var hook: HHOOK? = null
        @Volatile var threadId = 0
    }

    private val user32 = if (Platform.isWindows()) User32.INSTANCE else null
    private val kernel32 = if (Platform.isWindows()) Kernel32.INSTANCE else null
    @Volatile private var activeSession: HookSession? = null
    private var awtListener: AWTEventListener? = null
    private var x11: X11MenuDismisser? = null

    fun arm(window: Window): Boolean {
        disarm()
        if (!Platform.isWindows()) {
            val listener = AWTEventListener { event ->
                IslandLog.guard("GlobalMenuDismisser", "AWTEventListener.callback_failed") {
                    if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
                        val point = MouseInfo.getPointerInfo()?.location
                        if (point != null && !window.bounds.contains(point)) onOutsidePress()
                    }
                }
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
            awtListener = listener
            if (Platform.isLinux() && Toolkit.getDefaultToolkit().javaClass.name.contains("XToolkit")) {
                x11 = IslandLog.attempt("GlobalMenuDismisser", "arm.attempt1") { X11MenuDismisser(window, onOutsidePress) }.getOrNull()
                return x11 != null
            }
            return false
        }
        val user32 = user32 ?: return false
        val kernel32 = kernel32 ?: return true
        if (!window.isDisplayable) return false
        val pointer = Native.getWindowPointer(window) ?: return false
        if (Pointer.nativeValue(pointer) == 0L) return false
        val rect = com.sun.jna.platform.win32.WinDef.RECT()
        if (!user32.GetWindowRect(HWND(pointer), rect)) return false

        val session = HookSession()
        session.callback = object : LowLevelMouseProc {
            override fun callback(nCode: Int, wParam: WPARAM, info: MSLLHOOKSTRUCT): LRESULT {
                IslandLog.guard("GlobalMenuDismisser", "callback.callback_failed") {
                    if (nCode >= 0 && activeSession === session && wParam.toInt() in PRESS_MESSAGES &&
                        (!window.isVisible || info.pt.x < rect.left || info.pt.x >= rect.right ||
                            info.pt.y < rect.top || info.pt.y >= rect.bottom)
                    ) {
                        SwingUtilities.invokeLater {
                            IslandLog.guard("GlobalMenuDismisser", "invokeLater.callback_failed") { if (activeSession === session) onOutsidePress() }
                        }
                    }
                    return user32.CallNextHookEx(null, nCode, wParam, LPARAM(Pointer.nativeValue(info.pointer)))
                }
            }
        }
        activeSession = session

        val ready = CountDownLatch(1)
        val installed = AtomicInteger(-1)
        Thread({
            val message = MSG()
            user32.PeekMessage(message, null, 0, 0, PM_NOREMOVE)
            session.threadId = kernel32.GetCurrentThreadId()
            val hook = user32.SetWindowsHookEx(WH_MOUSE_LL, session.callback, kernel32.GetModuleHandle(null), 0)
            if (hook == null) {
                IslandLog.warn("menu", "outside_click_hook.unavailable", mapOf("win32Error" to Native.getLastError()))
                installed.set(0)
                ready.countDown()
                return@Thread
            }
            session.hook = hook
            if (activeSession !== session) {
                user32.UnhookWindowsHookEx(hook)
                session.hook = null
                installed.set(0)
                ready.countDown()
                return@Thread
            }
            installed.set(1)
            ready.countDown()
            try {
                while (true) {
                    val result = user32.GetMessage(message, null, 0, 0)
                    if (result <= 0) break
                    user32.TranslateMessage(message)
                    user32.DispatchMessage(message)
                }
            } finally {
                session.hook?.let(user32::UnhookWindowsHookEx)
                session.hook = null
            }
        }, "spw-menu-hook-pump").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("GlobalMenuDismisser") }.start()

        val installedSuccessfully = try {
            ready.await(2, TimeUnit.SECONDS) && installed.get() == 1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!installedSuccessfully) {
            IslandLog.warn("menu", "outside_click_hook.not_ready")
            disarm()
        }
        return installedSuccessfully
    }

    fun disarm() {
        x11?.close()
        x11 = null
        awtListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        awtListener = null
        val session = activeSession ?: return
        activeSession = null
        val user32 = user32 ?: return
        session.hook?.let(user32::UnhookWindowsHookEx)
        session.hook = null
        if (session.threadId != 0) user32.PostThreadMessage(session.threadId, WM_QUIT, WPARAM(0), LPARAM(0))
    }

    override fun close() = disarm()

    private companion object {
        const val WH_MOUSE_LL = 14
        const val WM_LBUTTONDOWN = 0x0201
        const val WM_RBUTTONDOWN = 0x0204
        const val WM_MBUTTONDOWN = 0x0207
        const val WM_QUIT = 0x0012
        const val PM_NOREMOVE = 0
        val PRESS_MESSAGES = setOf(WM_LBUTTONDOWN, WM_RBUTTONDOWN, WM_MBUTTONDOWN)
    }
}
