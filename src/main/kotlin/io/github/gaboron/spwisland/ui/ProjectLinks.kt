// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import java.awt.Desktop
import java.net.URI
import java.util.Properties

object ProjectLinks {
    val sponsor: URI = URI("https://afdian.com/a/gaboron")
    val source: URI by lazy {
        val properties = Properties()
        checkNotNull(javaClass.getResourceAsStream("/project.properties")).use { properties.load(it) }
        URI(properties.getProperty("source.url"))
    }
    fun openSource() = open(source)
    fun openSponsor() = open(sponsor)

    private fun open(uri: URI) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "无法打开浏览器，请访问：$uri"
        }
        Desktop.getDesktop().browse(uri)
    }
}
