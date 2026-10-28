// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import java.awt.GraphicsConfiguration
import java.awt.Transparency
import java.awt.image.ColorModel
import java.awt.image.DirectColorModel

/** AWT visual metadata is evidence about its peer, not the GL framebuffer or compositor output. */
internal object AwtGraphicsDiagnostics {
    fun read(configuration: GraphicsConfiguration?): Map<String, Any?> {
        if (configuration == null) return mapOf("available" to false)
        return mapOf(
            "available" to true,
            "configurationClass" to configuration.javaClass.name,
            // X11GraphicsConfig includes the visual ID here without access to JDK internals.
            "configuration" to configuration.toString(),
            "translucencyCapable" to configuration.isTranslucencyCapable,
            "defaultColorModel" to colorModel(configuration.colorModel),
            // This API can return generic ARGB even when the peer's default visual is opaque.
            "translucentColorModel" to colorModel(configuration.getColorModel(Transparency.TRANSLUCENT)),
            "evidence" to "awt_configuration_only_glx_unverified"
        )
    }

    private fun colorModel(model: ColorModel?): Map<String, Any?> {
        if (model == null) return mapOf("available" to false)
        return buildMap {
            put("available", true)
            put("class", model.javaClass.name)
            put("pixelBits", model.pixelSize)
            put("componentBits", model.componentSize.toList())
            put("hasAlpha", model.hasAlpha())
            put("alphaPremultiplied", model.isAlphaPremultiplied)
            put("transparency", model.transparency)
            put("transferType", model.transferType)
            if (model is DirectColorModel) {
                put("redMask", mask(model.redMask))
                put("greenMask", mask(model.greenMask))
                put("blueMask", mask(model.blueMask))
                put("alphaMask", mask(model.alphaMask))
            }
        }
    }

    private fun mask(value: Int) = "0x" + Integer.toUnsignedString(value, 16).padStart(8, '0')
}
