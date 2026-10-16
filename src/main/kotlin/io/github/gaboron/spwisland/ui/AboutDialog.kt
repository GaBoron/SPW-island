// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.platform.SystemTheme
import io.github.gaboron.spwisland.core.IslandBackgroundStyle
import io.github.gaboron.spwisland.core.IslandSettings
import com.sun.jna.Platform
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI

/** A compact attribution card and live, structured diagnostics; all work stays on the EDT. */
internal class AboutDialog(private val owner: Window, private val report: (Throwable) -> Unit,
                           private val status: () -> List<IslandStatusEntry>,
                           private val settings: () -> IslandSettings) : AutoCloseable {
    companion object {
        private const val DIALOG_WIDTH = 560
        private const val ROW_WIDTH = DIALOG_WIDTH - 86
        private fun measuredHeight(component: Component, width: Int): Int = when {
            component is WidthAware -> component.heightFor(width.coerceAtLeast(1))
            component is Container && component.layout is VerticalStack ->
                (component.layout as VerticalStack).heightFor(component, width)
            else -> component.preferredSize.height
        }
    }
    private data class Palette(val background: Color, val card: Color, val text: Color, val muted: Color,
                               val accent: Color, val hover: Color, val border: Color, val warning: Color)
    private var window: JDialog? = null
    private var dark: Boolean? = null
    private var glass = false
    private var displayedStatus: List<IslandStatusEntry> = emptyList()
    private var statusPanel: AboutStatusPanel? = null
    private var colors: Palette? = null
    private val refreshTimer = Timer(1000) { refreshStatus() }
    val isVisible: Boolean get() = window?.isVisible == true

    fun show() {
        check(SwingUtilities.isEventDispatchThread())
        val useDark = !SystemTheme.isLight()
        val useGlass = usesGlass()
        if (window == null || dark != useDark || glass != useGlass) {
            window?.dispose()
            dark = useDark
            glass = useGlass
            displayedStatus = status()
            colors = palette(useDark, useGlass)
            window = createWindow(colors!!)
        } else refreshStatus()
        window?.let { dialog ->
            fitToWorkArea(dialog)
            (dialog.contentPane as LiquidGlassSurface).showWhenReady {
                if (window === dialog) {
                    dialog.isVisible = true
                    dialog.toFront()
                    dialog.requestFocus()
                    refreshTimer.start()
                }
            }
        }
    }

    private fun refreshStatus() {
        if (window == null) return
        if (dark != !SystemTheme.isLight() || glass != usesGlass()) { show(); return }
        val current = status()
        if (current == displayedStatus) return
        displayedStatus = current
        statusPanel?.update(current)
        statusPanel?.revalidate()
        statusPanel?.repaint()
        SwingUtilities.invokeLater { (window?.contentPane as? LiquidGlassSurface)?.invalidateGpuContent() }
    }

    private fun createWindow(colors: Palette): JDialog = BufferedAboutWindow(owner.graphicsConfiguration).apply {
        setIconImage(ApplicationIdentity.icon)
        isUndecorated = true
        isAlwaysOnTop = true
        background = Color(0, 0, 0, 0)
        font = SystemUiFont.derive(Font.PLAIN, 12f)
        rootPane.isOpaque = false
        layeredPane.isOpaque = false
        defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        contentPane = surface(colors, this)
        fun configure(component: Component) {
            component.font?.let { component.font = SystemUiFont.derive(it.style, it.size2D) }
            if (component is JComponent) component.isDoubleBuffered = false
            if (component is Container) component.components.forEach(::configure)
        }
        configure(rootPane)
        rootPane.registerKeyboardAction({ isVisible = false }, KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW)
        addComponentListener(object : ComponentAdapter() {
            override fun componentHidden(event: ComponentEvent) {
                if (window === event.component) refreshTimer.stop()
            }
        })
        pack()
    }

    private fun surface(colors: Palette, dialog: JDialog): JPanel {
        val runtime = AboutCard(colors).apply {
            layout = VerticalStack(ROW_WIDTH + 36)
            border = EmptyBorder(16, 18, 16, 18)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 22)
                add(label("运行状态", colors.text, 14f, true), BorderLayout.WEST)
                add(label(IslandRuntimeStatus.environment, colors.muted, 11f), BorderLayout.EAST)
            })
            add(Box.createVerticalStrut(20))
            statusPanel = AboutStatusPanel(colors.text, colors.muted, colors.warning, ROW_WIDTH).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                update(displayedStatus)
            }
            add(statusPanel)
        }
        val credits = AboutCard(colors).apply {
            layout = BorderLayout(0, 10)
            border = EmptyBorder(16, 18, 16, 18)
            add(label("版权与许可", colors.text, 13f, true), BorderLayout.NORTH)
            add(WrappingText("视觉原创：Lyricify / WXRIW（XY Wang）· CC BY-SA 4.0\n" +
                "歌词动画：AMLL contributors / Steve-xmh · AGPL-3.0-only\n" +
                "其他程序：GPL-3.0-only · SPW API：Apache-2.0", colors.muted, ROW_WIDTH, 11.5f), BorderLayout.CENTER)
            add(WrappingText("本插件独立改编；完整许可、署名与源码随安装包提供。", colors.muted, ROW_WIDTH, 11f), BorderLayout.SOUTH)
        }
        val body = ScrollableBody().apply {
            isOpaque = false
            layout = VerticalStack(DIALOG_WIDTH - 2, 14)
            border = EmptyBorder(0, 24, 4, 24)
            runtime.alignmentX = Component.LEFT_ALIGNMENT
            credits.alignmentX = Component.LEFT_ALIGNMENT
            add(runtime)
            add(credits)
        }
        return AboutSurface(colors, settings, report).apply {
            layout = BorderLayout()
            border = EmptyBorder(1, 1, 1, 1)
            val header = header(colors, dialog)
            add(header, BorderLayout.NORTH)
            add(JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
                isOpaque = false
                viewport.isOpaque = false
                border = null
                viewportBorder = null
                verticalScrollBar.unitIncrement = 18
                verticalScrollBar.apply {
                    isOpaque = false
                    preferredSize = Dimension(10, 0)
                    setUI(object : BasicScrollBarUI() {
                        override fun createDecreaseButton(orientation: Int) = scrollSpacer()
                        override fun createIncreaseButton(orientation: Int) = scrollSpacer()
                        override fun paintTrack(g: Graphics, component: JComponent, bounds: Rectangle) = Unit
                        override fun paintThumb(graphics: Graphics, component: JComponent, bounds: Rectangle) {
                            if (bounds.isEmpty || !component.isEnabled) return
                            val g = graphics.create() as Graphics2D
                            try {
                                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                g.color = Color(colors.muted.red, colors.muted.green, colors.muted.blue,
                                    if (isThumbRollover) 160 else 90)
                                g.fillRoundRect(bounds.x + 2, bounds.y + 2, (bounds.width - 4).coerceAtLeast(1),
                                    (bounds.height - 4).coerceAtLeast(1), 6, 6)
                            } finally { g.dispose() }
                        }
                        private fun scrollSpacer() = JButton().apply {
                            preferredSize = Dimension(0, 0)
                            minimumSize = preferredSize
                            maximumSize = preferredSize
                            isFocusable = false
                        }
                    })
                }
            }, BorderLayout.CENTER)
            add(footer(colors, dialog), BorderLayout.SOUTH)
            installDrag(header, dialog)
        }
    }

    private fun header(colors: Palette, dialog: JDialog) = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = EmptyBorder(22, 24, 22, 22)
        add(IslandMark(colors), BorderLayout.WEST)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(1, 14, 0, 0)
            add(label(ApplicationIdentity.NAME, colors.text, 20f, true))
            add(Box.createVerticalStrut(4))
            add(label("独立歌词显示插件 · 关于与状态", colors.muted, 12f))
        }, BorderLayout.CENTER)
        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = EmptyBorder(0, 12, 0, 0)
            add(button("关闭", colors, icon = true).apply { addActionListener { dialog.isVisible = false } })
        }, BorderLayout.EAST)
    }

    private fun label(text: String, color: Color, size: Float, bold: Boolean = false) = JLabel(text).apply {
        foreground = color
        font = SystemUiFont.derive(if (bold) Font.BOLD else Font.PLAIN, size)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun footer(colors: Palette, dialog: JDialog) = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = EmptyBorder(18, 24, 20, 24)
        add(button("项目源码", colors).apply {
            addActionListener { runCatching(ProjectLinks::openSource).onFailure(report) }
        }, BorderLayout.WEST)
        add(button("关闭", colors, primary = true).apply {
            addActionListener { dialog.isVisible = false }
            dialog.rootPane.defaultButton = this
        }, BorderLayout.EAST)
    }

    private fun button(text: String, colors: Palette, primary: Boolean = false, icon: Boolean = false): DialogButton {
        val transparent = Color(0, 0, 0, 0)
        return DialogButton(text, DialogButtonColors(
            when { icon -> transparent; primary -> colors.accent; else -> colors.card },
            if (primary) colors.accent.brighter() else colors.hover,
            if (primary) colors.accent.darker() else colors.hover.darker(),
            if (primary) colors.background else colors.text,
            if (primary || icon) transparent else colors.border, colors.accent),
            Dimension(if (icon) 34 else if (primary) 80 else 104, if (icon) 34 else 36), icon)
    }

    private fun fitToWorkArea(dialog: JDialog) {
        dialog.pack()
        val configuration = owner.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        val work = Rectangle(bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
        val width = dialog.width.coerceAtMost((work.width - 32).coerceAtLeast(1))
        val height = dialog.height.coerceAtMost((work.height - 32).coerceAtLeast(1))
        dialog.setSize(width, height)
        dialog.validate()
        if (!Platform.isWindows())
            dialog.shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 26.0, 26.0)
        dialog.setLocation(work.x + (work.width - width) / 2, work.y + (work.height - height) / 2)
    }

    private fun installDrag(component: Container, dialog: JDialog) {
        val drag = object : MouseAdapter() {
            private var pointer: Point? = null
            private var origin: Point? = null
            override fun mousePressed(event: MouseEvent) { pointer = event.locationOnScreen; origin = dialog.location }
            override fun mouseDragged(event: MouseEvent) {
                val start = pointer ?: return
                val location = origin ?: return
                val current = event.locationOnScreen
                dialog.setLocation(location.x + current.x - start.x, location.y + current.y - start.y)
            }
        }
        fun attach(target: Component) {
            if (target is AbstractButton) return
            target.addMouseListener(drag); target.addMouseMotionListener(drag)
            if (target is Container) target.components.forEach(::attach)
        }
        attach(component)
    }

    private fun usesGlass(): Boolean = settings().let {
        Platform.isWindows() && it.backgroundStyle == IslandBackgroundStyle.LIQUID_GLASS && !it.lowPerformance
    }

    private fun palette(useDark: Boolean, glass: Boolean): Palette {
        val base = if (useDark) Palette(
            Color(0x20, 0x20, 0x20), Color(0x2B, 0x2B, 0x2B), Color(0xF5, 0xF5, 0xF5), Color(0xAE, 0xAE, 0xAE),
            Color(0x60, 0xCD, 0xFF), Color(0x40, 0x40, 0x40), Color(255, 255, 255, 32), Color(0xF2, 0xBE, 0x6A)
        ) else Palette(
            Color(0xF3, 0xF3, 0xF3), Color.WHITE, Color(0x1B, 0x1B, 0x1B), Color(0x60, 0x60, 0x60),
            Color(0x00, 0x67, 0xC0), Color(0xE0, 0xE0, 0xE0), Color(0, 0, 0, 28), Color(0x91, 0x5A, 0x00)
        )
        return if (glass) base.copy(
            card = if (useDark) Color(255, 255, 255, 14) else Color(255, 255, 255, 48),
            muted = if (useDark) Color(0xC6, 0xC6, 0xC6) else Color(0x44, 0x44, 0x44),
            warning = if (useDark) base.warning else Color(0x70, 0x41, 0x00))
            else base
    }

    override fun close() {
        refreshTimer.stop()
        window?.dispose()
        window = null
        statusPanel = null
    }

    private class AboutSurface(colors: Palette, settings: () -> IslandSettings, report: (Throwable) -> Unit) :
        LiquidGlassSurface(settings, report, 26.0) {
        init { background = colors.background; surfaceBorder = colors.border }
        override fun getPreferredSize() = Dimension(DIALOG_WIDTH, super.getPreferredSize().height)
    }
    private class AboutCard(private val colors: Palette) : JPanel(), WidthAware {
        init { isOpaque = false }
        override fun heightFor(width: Int): Int {
            val manager = layout
            if (manager is VerticalStack) return manager.heightFor(this, width)
            val borderLayout = manager as BorderLayout
            val contentWidth = width - insets.left - insets.right
            val children = listOf(BorderLayout.NORTH, BorderLayout.CENTER, BorderLayout.SOUTH)
                .mapNotNull { borderLayout.getLayoutComponent(it) }
            return insets.top + insets.bottom + children.sumOf { measuredHeight(it, contentWidth) } +
                borderLayout.vgap * (children.size - 1).coerceAtLeast(0)
        }
        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()
            return Dimension(natural.width, heightFor(width.takeIf { it > 0 } ?: natural.width))
        }
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = colors.card; g.fillRoundRect(0, 0, width, height, 16, 16)
                g.color = colors.border; g.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
            } finally { g.dispose() }
            super.paintComponent(graphics)
        }
    }
    private class VerticalStack(private val naturalWidth: Int, private val gap: Int = 0) : LayoutManager {
        override fun addLayoutComponent(name: String?, component: Component) = Unit
        override fun removeLayoutComponent(component: Component) = Unit
        override fun minimumLayoutSize(parent: Container) = Dimension(0, preferredLayoutSize(parent).height)
        override fun preferredLayoutSize(parent: Container): Dimension {
            val viewportWidth = (parent.parent as? JViewport)?.width?.takeIf { it > 0 }
            val width = viewportWidth ?: parent.width.takeIf { it > 0 } ?: naturalWidth
            return Dimension(naturalWidth, heightFor(parent, width))
        }
        fun heightFor(parent: Container, width: Int): Int {
            val children = parent.components.filter { it.isVisible }
            val contentWidth = width - parent.insets.left - parent.insets.right
            return parent.insets.top + parent.insets.bottom +
                children.sumOf { measuredHeight(it, contentWidth) } + gap * (children.size - 1).coerceAtLeast(0)
        }
        override fun layoutContainer(parent: Container) {
            val contentWidth = (parent.width - parent.insets.left - parent.insets.right).coerceAtLeast(1)
            var y = parent.insets.top
            parent.components.filter { it.isVisible }.forEach { child ->
                val height = measuredHeight(child, contentWidth)
                child.setBounds(parent.insets.left, y, contentWidth, height)
                y += height + gap
            }
        }
    }

    private class ScrollableBody : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize() = Dimension(DIALOG_WIDTH - 2, preferredSize.height)
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 18
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - 36).coerceAtLeast(18)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private class IslandMark(private val colors: Palette) : JComponent() {
        init { preferredSize = Dimension(44, 44); minimumSize = preferredSize }
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = colors.text; g.fillRoundRect(0, 5, 44, 34, 28, 28)
                g.color = colors.background
                intArrayOf(11, 17, 23, 29).forEachIndexed { index, x ->
                    val bar = if (index % 2 == 0) 14 else 9
                    g.fillRoundRect(x, 22 - bar / 2, 4, bar, 4, 4)
                }
            } finally { g.dispose() }
        }
    }
}
