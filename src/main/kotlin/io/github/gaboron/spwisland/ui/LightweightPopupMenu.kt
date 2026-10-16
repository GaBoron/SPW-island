// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import io.github.gaboron.spwisland.platform.GlobalMenuDismisser
import io.github.gaboron.spwisland.core.IslandSettings
import io.github.gaboron.spwisland.core.IslandBackgroundStyle
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder

/** Reusable in-process popup: no helper process, polling thread, or per-open window allocation. */
internal class LightweightPopupMenu(private val owner: Window, private val report: (Throwable) -> Unit,
                                     private val settings: () -> IslandSettings) : AutoCloseable {
    private data class LayoutKey(val kind: PopupMenuKind, val id: Int, val label: String)
    private data class Palette(val background: Color, val text: Color, val muted: Color,
                               val accent: Color, val hover: Color, val border: Color)

    private val surface = MenuSurface()
    private val alphaFrame = WindowAlphaFrame(32.0)
    private val window = object : JWindow(owner) {
        override fun update(graphics: Graphics) = paint(graphics)
        override fun paint(graphics: Graphics) {
            if (Platform.isWindows() && !surface.gpuDrawing)
                alphaFrame.paint(graphics, width, height) { super.paint(it) }
            else super.paint(graphics)
        }
    }.apply {
        // POPUP windows are not reliably focusable on Linux, preventing outside-click dismissal.
        type = if (Platform.isWindows()) Window.Type.POPUP else Window.Type.UTILITY
        background = if (Platform.isWindows()) Color(0, 0, 0, 0) else Color(0x20, 0x20, 0x20)
        isAlwaysOnTop = true
        focusableWindowState = true
        isAutoRequestFocus = true
        rootPane.isOpaque = false
        layeredPane.isOpaque = false
        contentPane = surface
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) = dismiss()
        })
        rootPane.registerKeyboardAction({ dismiss() },
            KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)
    }
    private val toggleMarks = mutableMapOf<Int, JLabel>()
    private val buttons = mutableListOf<JButton>()
    private val dismisser = GlobalMenuDismisser(::dismiss)
    private var layoutKey: List<LayoutKey> = emptyList()
    private var dark: Boolean? = null
    private var glass: Boolean? = null
    private var command: ((Int) -> Unit)? = null

    fun prewarm(entries: List<PopupMenuEntry>, useDark: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        update(entries, useDark)
    }

    fun show(entries: List<PopupMenuEntry>, useDark: Boolean, anchor: Point, selected: (Int) -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        command = selected
        update(entries, useDark)
        val work = workArea(anchor)
        val x = anchor.x.coerceIn(work.x, (work.x + work.width - window.width).coerceAtLeast(work.x))
        val below = anchor.y + window.height
        val y = (if (below <= work.y + work.height) anchor.y else anchor.y - window.height)
            .coerceIn(work.y, (work.y + work.height - window.height).coerceAtLeast(work.y))
        window.setLocation(x, y)
        if (!dismisser.arm(window)) report(IllegalStateException("无法监听菜单外部点击"))
        surface.showWhenReady {
            window.isVisible = true
            window.toFront()
            if (Platform.isWindows()) runCatching {
                User32.INSTANCE.SetForegroundWindow(HWND(Native.getWindowPointer(window)))
            }.onFailure(report)
            window.requestFocus()
            EventQueue.invokeLater { if (window.isVisible) window.requestFocus() }
        }
    }

    private fun update(entries: List<PopupMenuEntry>, useDark: Boolean) {
        val nextKey = entries.map { LayoutKey(it.kind, it.id, it.label) }
        val useGlass = usesGlass()
        if (dark != useDark || glass != useGlass || layoutKey != nextKey) {
            dark = useDark
            glass = useGlass
            layoutKey = nextKey
            rebuild(entries, palette(useDark))
            window.pack()
            applyWindowShape()
        } else {
            entries.filter { it.kind == PopupMenuKind.TOGGLE }.forEach {
                toggleMarks[it.id]?.text = if (it.selected) "✓" else ""
            }
        }
        surface.invalidateGpuContent()
    }

    private fun rebuild(entries: List<PopupMenuEntry>, colors: Palette) {
        surface.removeAll()
        surface.colors = colors
        toggleMarks.clear()
        buttons.clear()
        entries.forEach { entry ->
            val component = when (entry.kind) {
                PopupMenuKind.TITLE -> label(entry.label, colors.muted, Font.BOLD, 11.5f, 27, 32, 8, 4)
                PopupMenuKind.NOTE -> label(entry.label, colors.muted, Font.PLAIN, 11.5f, 29, 32, 5, 7)
                PopupMenuKind.SEPARATOR -> separator(colors)
                PopupMenuKind.TOGGLE, PopupMenuKind.ACTION -> button(entry, colors)
            }
            component.alignmentX = Component.LEFT_ALIGNMENT
            surface.add(component)
        }
        surface.revalidate()
    }

    private fun label(text: String, color: Color, style: Int, size: Float,
                      height: Int, left: Int, top: Int, bottom: Int) = JLabel(text).apply {
        foreground = color
        font = SystemUiFont.derive(style, size)
        border = EmptyBorder(top, left, bottom, 10)
        preferredSize = Dimension(236, height)
        maximumSize = preferredSize
    }

    private fun separator(colors: Palette) = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = EmptyBorder(6, 32, 6, 10)
        add(object : JComponent() {
            override fun paintComponent(graphics: Graphics) {
                graphics.color = colors.border
                graphics.fillRect(0, 0, width, 1)
            }
        }, BorderLayout.CENTER)
        preferredSize = Dimension(236, 13)
        maximumSize = preferredSize
    }

    private fun button(entry: PopupMenuEntry, colors: Palette): JButton {
        val button = MenuButton(colors.hover).apply {
            layout = BorderLayout()
            border = EmptyBorder(0, 10, 0, 12)
            preferredSize = Dimension(236, 32)
            maximumSize = preferredSize
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            isRolloverEnabled = true
        }
        val mark = JLabel(if (entry.kind == PopupMenuKind.TOGGLE && entry.selected) "✓" else "").apply {
            foreground = colors.accent
            font = SystemUiFont.derive(Font.PLAIN, 14f)
            preferredSize = Dimension(22, 32)
        }
        val text = JLabel(entry.label).apply {
            foreground = colors.text
            font = SystemUiFont.derive(Font.PLAIN, 13f)
        }
        button.add(mark, BorderLayout.WEST)
        button.add(text, BorderLayout.CENTER)
        button.addActionListener { choose(entry.id) }
        if (entry.kind == PopupMenuKind.TOGGLE) toggleMarks[entry.id] = mark
        buttons += button
        return button
    }

    private fun choose(id: Int) {
        val callback = command
        dismiss()
        callback?.invoke(id)
    }

    private fun dismiss() {
        dismisser.disarm()
        surface.cancelShow()
        command = null
        if (!window.isVisible) return
        window.isVisible = false
    }

    private fun workArea(anchor: Point): Rectangle {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val configuration = environment.screenDevices.asSequence()
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(anchor) }
            ?: environment.defaultScreenDevice.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    }

    private fun usesGlass() = settings().let {
        Platform.isWindows() && it.backgroundStyle == IslandBackgroundStyle.LIQUID_GLASS && !it.lowPerformance
    }

    private fun palette(useDark: Boolean) = if (useDark) Palette(
        Color(0x20, 0x20, 0x20), Color(0xF5, 0xF5, 0xF5), if (usesGlass()) Color(0xC6, 0xC6, 0xC6) else Color(0x9D, 0x9D, 0x9D),
        Color(0x60, 0xCD, 0xFF), Color(0x4A, 0x4A, 0x4A, 150), Color(0xFF, 0xFF, 0xFF, 42)
    ) else Palette(
        Color(0xF3, 0xF3, 0xF3), Color(0x1B, 0x1B, 0x1B), if (usesGlass()) Color(0x44, 0x44, 0x44) else Color(0x5D, 0x5D, 0x5D),
        Color(0x00, 0x67, 0xC0), Color(0xE9, 0xE9, 0xE9, 138), Color(0x45, 0x45, 0x45, 28)
    )

    private fun applyWindowShape() {
        if (Platform.isWindows()) return
        window.background = surface.colors.background
        runCatching {
            window.shape = RoundRectangle2D.Double(
                0.0, 0.0, window.width.toDouble(), window.height.toDouble(), 32.0, 32.0)
        }
    }

    override fun close() {
        dismisser.close()
        surface.cancelShow()
        command = null
        window.dispose()
    }

    private inner class MenuSurface : LiquidGlassSurface(settings, report, 32.0) {
        var colors = palette(false)
            set(value) { field = value; background = value.background; surfaceBorder = value.border }
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(6, 6, 6, 6)
            background = colors.background; surfaceBorder = colors.border
        }
    }

    private class MenuButton(private val hover: Color) : JButton() {
        override fun paintComponent(graphics: Graphics) {
            if (model.isRollover || isFocusOwner) {
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = hover
                g.fillRoundRect(0, 0, width, height, 14, 14)
                g.dispose()
            }
            super.paintComponent(graphics)
        }
    }

}
