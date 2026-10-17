// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Shared identity for the window and native tray; retain the existing artwork until replaced. */
internal object ApplicationIdentity {
    const val NAME = "灵动词岛 for SPW"
    val icon: BufferedImage by lazy {
        ApplicationIdentity::class.java.getResourceAsStream("/icons/application.png")?.use(ImageIO::read)
            ?: BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                try {
                    g.scale(2.0, 2.0)
                    g.color = Color(12, 15, 21); g.fillRoundRect(1, 5, 30, 22, 18, 18)
                    g.color = Color(132, 216, 188)
                    for (i in 0..3) g.fillRoundRect(7 + i * 5, 10 + (i % 2) * 3, 3, 12 - (i % 2) * 6, 2, 2)
                } finally { g.dispose() }
            }
    }
    fun exportIcon(): Path = Files.createTempFile("spw-island-icon-", ".png").also {
        ImageIO.write(icon, "png", it.toFile())
        it.toFile().deleteOnExit()
    }
}
