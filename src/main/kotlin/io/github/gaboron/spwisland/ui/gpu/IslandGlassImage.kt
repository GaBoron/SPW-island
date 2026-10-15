// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.platform.WindowsDesktopBackdrop
import org.jetbrains.skia.*

/** Backdrop's Vibrancy -> Blur chain, uploaded only for a new real desktop crop. */
internal class IslandGlassImage : AutoCloseable {
    private var source: WindowsDesktopBackdrop.Pixels? = null
    private var scale = 0f to 0f
    private var image: Image? = null
    private var filtered: Shader? = null
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private var mappingKey: Any? = null
    var shader: Shader? = null
        private set
    var generation = 0L
        private set

    fun update(sample: WindowsDesktopBackdrop.Sample) {
        if (source !== sample.pixels || scale != (sample.scaleX to sample.scaleY)) upload(sample)
        val pixels = sample.pixels
        val offsetX = sample.islandX - pixels.x
        val offsetY = sample.islandY - pixels.y
        val nextKey = listOf(pixels, sample.scaleX, sample.scaleY, offsetX, offsetY)
        if (mappingKey == nextKey) return
        val program = builder ?: run {
            val compiled = RuntimeEffect.makeForShader(IslandGlassShaders.DESKTOP_COORDINATES)
            effect = compiled
            RuntimeShaderBuilder(compiled).also { builder = it }
        }
        program.child("image", checkNotNull(filtered))
        program.uniform("deviceScale", sample.scaleX, sample.scaleY)
        program.uniform("captureOffset", offsetX, offsetY)
        val next = program.makeShader()
        shader?.close(); shader = next; mappingKey = nextKey; generation++
    }

    private fun upload(sample: WindowsDesktopBackdrop.Sample) {
        val pixels = sample.pixels
        val nextScale = sample.scaleX to sample.scaleY
        val owned = if (pixels.texture == null) Image.makeRaster(ImageInfo(pixels.width, pixels.height,
            ColorType.BGRA_8888, ColorAlphaType.OPAQUE), checkNotNull(pixels.data), pixels.width * 4) else null
        val nextImage = pixels.texture ?: checkNotNull(owned)
        val nextShader = try {
            PictureRecorder().use { recorder ->
                val canvas = recorder.beginRecording(Rect.makeWH(pixels.width.toFloat(), pixels.height.toFloat()))
                Paint().use { paint ->
                    // Backdrop effects/ColorFilter.kt: saturation 1.5, contrast 1, brightness 0.
                    ColorFilter.makeMatrix(ColorMatrix(
                        1.3935f, -.3575f, -.036f, 0f, 0f,
                        -.1065f, 1.1425f, -.036f, 0f, 0f,
                        -.1065f, -.3575f, 1.464f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )).use { color ->
                        ImageFilter.makeColorFilter(color, null, null).use { vibrancy ->
                            ImageFilter.makeBlur(2f * sample.scaleX, 2f * sample.scaleY,
                                FilterTileMode.CLAMP, vibrancy).use { blur ->
                                paint.imageFilter = blur
                                canvas.drawImage(nextImage, 0f, 0f, paint)
                            }
                        }
                    }
                }
                recorder.finishRecordingAsPicture().use {
                    it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, FilterMode.LINEAR)
                }
            }
        } catch (error: Throwable) { owned?.close(); throw error }
        filtered?.close(); image?.close()
        filtered = nextShader; image = owned; source = pixels; scale = nextScale; mappingKey = null
    }

    override fun close() {
        shader?.close(); shader = null
        filtered?.close(); filtered = null
        image?.close(); image = null; source = null
        builder?.close(); builder = null
        effect?.close(); effect = null; mappingKey = null
    }
}
