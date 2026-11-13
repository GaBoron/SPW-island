// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32.WinUser.WinEventProc
import com.sun.jna.platform.win32.WinUser.WS_MINIMIZE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import io.github.gaboron.spwisland.core.PerformanceProfile
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only shell observation on a dedicated Win32 message loop, independent of island visibility. */
internal class WindowsSystemUi(private val changed: () -> Unit) : AutoCloseable {
    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(window: HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    @Volatile private var enabled = false
    @Volatile private var closed = false
    @Volatile private var threadId = 0
    @Volatile private var ready = false
    @Volatile private var reason: String? = null
    private var thread: Thread? = null
    private var nextCheck = 0L
    private val queued = AtomicBoolean()
    private val calibrating = AtomicBoolean()
    private val user = User32.INSTANCE
    private val dwm = Native.load("dwmapi", Dwm::class.java)
    private val hooks = mutableListOf<HANDLE>()
    private var switching = false
    private var eventsInstalled = false
    private var protectedWindow: HWND? = null
    private val callback = WinEventProc { _, eventValue, window, objectId, childId, _, _ ->
        val event = eventValue.toInt()
        if (enabled && !closed) {
            if (event == SWITCH_START) {
                switching = true
                publish("system_switcher")
            }
            if (event == SWITCH_END) switching = false
            if (event < OBJECT_DESTROY) requestCheck()
            else if (objectId.toInt() == 0 && childId.toInt() == 0 && window != null &&
                (window == protectedWindow || user.GetAncestor(window, 2) == window && overlayCandidate(window))) {
                requestCheck()
            }
        }
    }

    val hiddenReason: String?
        get() = if (!enabled || closed) null else if (!ready) "system_ui_pending" else reason

    /** EDT call: no native enumeration here. Calibration uses the centralized performance budget. */
    fun update(active: Boolean, profile: PerformanceProfile) {
        if (closed) return
        val toggled = enabled != active
        enabled = active
        if (toggled) {
            ready = false
            reason = null
            nextCheck = 0
        }
        if (active && thread == null) {
            thread = IslandLog.thread("SPW Island system UI") { observe() }.apply { isDaemon = true; start() }
        }
        val now = System.nanoTime()
        if (toggled || active && now >= nextCheck) {
            nextCheck = now + profile.visibilityCheckIntervalMs * 1_000_000
            calibrating.set(true)
            requestCheck()
        }
    }

    private fun requestCheck() {
        val id = threadId
        if (id != 0 && !closed && queued.compareAndSet(false, true)) {
            if (user.PostThreadMessage(id, CHECK, WPARAM(0), LPARAM(0)) == 0) queued.set(false)
        }
    }

    private fun observe() {
        val message = MSG()
        try {
            // Create the queue before exposing the id. GetMessage pumps out-of-context WinEvents.
            user.PeekMessage(message, null, 0, 0, 0)
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            requestCheck()
            while (!closed) {
                val result = user.GetMessage(message, null, 0, 0)
                if (result <= 0) break
                if (message.message == CHECK) {
                    queued.set(false)
                    inspect(calibrating.getAndSet(false))
                } else {
                    user.TranslateMessage(message)
                    user.DispatchMessage(message)
                }
            }
        } catch (error: Throwable) {
            IslandLog.failure("window", "system_ui.observer_failed", error)
        } finally {
            releaseHooks()
            threadId = 0
            publish(null)
        }
    }

    private fun inspect(calibration: Boolean) {
        if (!enabled) {
            releaseHooks()
            switching = false
            publish(null)
            return
        }
        if (!eventsInstalled) {
            eventsInstalled = true
            // Keep callback and handles alive until disabled/closed. No injected DLL or keyboard hook.
            for ((first, last) in listOf(FOREGROUND to FOREGROUND, SWITCH_START to SWITCH_END,
                OBJECT_DESTROY to OBJECT_HIDE)) {
                val hook = user.SetWinEventHook(first, last, null, callback, 0, 0, 2) // SKIPOWNPROCESS
                if (hook != null && Pointer.nativeValue(hook.pointer) != 0L) hooks += hook
                else IslandLog.warn("window", "system_ui.hook_unavailable", mapOf("event" to first))
            }
        }
        try {
            val processes = mutableMapOf<Int, String?>()
            val foreground = user.GetForegroundWindow()
            var found = foreground?.takeIf(::visible)?.let { classify(it, true, processes) }
            protectedWindow = foreground.takeIf { found != null }
            if (found == null) user.EnumWindows({ window, _ ->
                if (visible(window)) {
                    found = classify(window, false, processes)
                    if (found != null) protectedWindow = window
                }
                found == null
            }, null)
            // Recover from a missed SWITCHEND using actual visible surfaces.
            if (calibration && found == null) switching = false
            publish(found ?: if (switching) "system_switcher" else null)
        } catch (error: Exception) {
            IslandLog.failure("window", "system_ui.sample_failed", error)
            switching = false
            protectedWindow = null
            publish(null)
        }
    }

    private fun visible(window: HWND): Boolean {
        if (!user.IsWindowVisible(window) || user.GetWindowLong(window, -16) and WS_MINIMIZE != 0) return false
        val cloaked = IntByReference()
        if (dwm.DwmGetWindowAttribute(window, 14, cloaked, 4) == 0 && cloaked.value != 0) return false
        val bounds = RECT()
        return user.GetWindowRect(window, bounds) && bounds.right > bounds.left && bounds.bottom > bounds.top
    }

    private fun classify(window: HWND, foreground: Boolean, processes: MutableMap<Int, String?>): String? {
        val type = className(window)
        if (type.isEmpty() || type in ordinaryWindows) return null
        // Most window events belong to ordinary applications. Do not query their process images.
        if (!foreground && !overlayCandidate(window, type)) return null
        val pid = IntByReference()
        if (user.GetWindowThreadProcessId(window, pid) == 0 || pid.value == 0) return null
        val process = processes.getOrPut(pid.value) {
            // A window can disappear during enumeration, or deny process-image access.
            runCatching {
                ProcessHandle.of(Integer.toUnsignedLong(pid.value)).flatMap { it.info().command() }
                    .map { Path.of(it).fileName.toString().lowercase(Locale.ROOT) }.orElse(null)
            }.getOrNull()
        }
        return when {
            process == "screenclippinghost.exe" -> "system_capture"
            process == "snippingtool.exe" && (foreground || type == "Microsoft-Windows-SnipperToolbar" || topmost(window)) -> "system_capture"
            foreground && process in shellProcesses -> "system_shell"
            process == "explorer.exe" && type in switchClasses -> "system_switcher"
            else -> null
        }
    }

    // Recording controls stay visible without owning focus and can use a different window class.
    // Restrict the extra candidates by native style, then verify the owning capture process.
    private fun topmost(window: HWND): Boolean = user.GetWindowLong(window, -20) and 0x00000008 != 0

    private fun overlayCandidate(window: HWND, type: String = className(window)): Boolean =
        type in overlayClasses || topmost(window)

    private fun className(window: HWND): String {
        val name = CharArray(256)
        return if (user.GetClassName(window, name, name.size) == 0) "" else Native.toString(name)
    }

    private fun publish(value: String?) {
        val notify = !ready || reason != value
        reason = value
        ready = true
        if (notify && !closed) {
            IslandLog.state("window", "system_ui", value ?: "inactive")
            changed()
        }
    }

    private fun releaseHooks() {
        hooks.forEach { user.UnhookWinEvent(it) }
        hooks.clear()
        eventsInstalled = false
    }

    override fun close() {
        closed = true
        enabled = false
        val id = threadId
        if (id != 0) user.PostThreadMessage(id, 0x12, WPARAM(0), LPARAM(0)) // WM_QUIT
    }

    private companion object {
        const val CHECK = 0x8001
        const val FOREGROUND = 0x0003
        const val SWITCH_START = 0x0014
        const val SWITCH_END = 0x0015
        const val OBJECT_DESTROY = 0x8001
        const val OBJECT_HIDE = 0x8003
        val ordinaryWindows = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "CabinetWClass")
        val switchClasses = setOf("MultitaskingViewFrame", "TaskSwitcherWnd", "XamlExplorerHostIslandWindow")
        val overlayClasses = switchClasses + setOf("Microsoft-Windows-SnipperToolbar", "ScreenClippingHost",
            "Windows.UI.Core.CoreWindow", "WinUIDesktopWin32WindowClass")
        val shellProcesses = setOf("startmenuexperiencehost.exe", "searchhost.exe", "searchapp.exe",
            "searchui.exe", "shellexperiencehost.exe", "shellhost.exe")
    }
}
