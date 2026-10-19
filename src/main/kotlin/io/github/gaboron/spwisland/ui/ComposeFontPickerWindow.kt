// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import androidx.compose.ui.awt.ComposeWindow
import io.github.gaboron.spwisland.core.IslandSettings
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import io.github.gaboron.spwisland.platform.SkikoRuntime
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Loads the plugin's Skiko DLL rather than the different one bundled with SPW. */
internal class ComposeFontPickerWindow(
    private val apply: (FontPickerSelection) -> Unit,
    private val report: (Throwable) -> Unit
) : AutoCloseable {
    private var window: ComposeWindow? = null
    private val closed = AtomicBoolean()

    fun show(current: IslandSettings) {
        if (closed.get()) return
        SwingUtilities.invokeLater {
            if (closed.get()) return@invokeLater
            window?.let { it.isVisible = true; it.toFront(); return@invokeLater }
            try {
                SkikoRuntime.load()
                val frame = ComposeWindow()
                frame.title = "选择字体 · 动态歌词岛"
                frame.iconImage = ApplicationIdentity.icon
                frame.minimumSize = Dimension(960, 680)
                frame.setSize(1160, 740)
                frame.setLocationRelativeTo(null)
                frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                frame.setContent {
                    FontPickerContent(current, onCancel = { frame.dispose() }, onApply = {
                        apply(it)
                        frame.dispose()
                    })
                }
                frame.addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent?) {
                        if (window === frame) window = null
                    }
                })
                window = frame
                frame.isVisible = true
            } catch (error: Throwable) {
                window?.dispose()
                window = null
                report(IllegalStateException("字体窗口启动失败", error))
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) SwingUtilities.invokeLater { window?.dispose(); window = null }
    }
}
