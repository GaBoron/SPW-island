// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.*
import javax.swing.JDialog

/** Independent from the overlay; replace complete antialiased alpha frames on both platforms. */
internal class BufferedAboutWindow(configuration: GraphicsConfiguration?) :
    JDialog(null as Window?, "关于与状态", Dialog.ModalityType.MODELESS, configuration) {
    private val frame = WindowAlphaFrame(26.0)
    override fun update(graphics: Graphics) = paint(graphics)
    override fun paint(graphics: Graphics) {
        if ((contentPane as? LiquidGlassSurface)?.gpuDrawing == true) super.paint(graphics)
        else frame.paint(graphics, width, height) { super.paint(it) }
    }
}
