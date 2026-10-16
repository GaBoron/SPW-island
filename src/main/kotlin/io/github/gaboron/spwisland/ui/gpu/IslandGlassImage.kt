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
    private var filteredEdge: Shader? = null
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private var mappingKey: Any? = null
    var shader: Shader? = null
        private set
    var edgeShader: Shader? = null
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
        val nextEdge = try {
            program.child("image", checkNotNull(filteredEdge))
            program.makeShader()
        } catch (error: Throwable) { next.close(); throw error }
        edgeShader?.close(); edgeShader = nextEdge
        shader?.close(); shader = next; mappingKey = nextKey; generation++
    }

    private fun upload(sample: WindowsDesktopBackdrop.Sample) {
        val pixels = sample.pixels
        val nextScale = sample.scaleX to sample.scaleY
        val owned = if (pixels.texture == null) Image.makeRaster(ImageInfo(pixels.width, pixels.height,
            ColorType.BGRA_8888, ColorAlphaType.OPAQUE), checkNotNull(pixels.data), pixels.width * 4) else null
        val nextImage = pixels.texture ?: checkNotNull(owned)
        val nextShader = try {
            filter(nextImage, sample, IslandGlassShaders.CENTER_BLUR_SIGMA)
        } catch (error: Throwable) { owned?.close(); throw error }
        val nextEdge = try {
            filter(nextImage, sample, IslandGlassShaders.EDGE_BLUR_SIGMA)
        } catch (error: Throwable) { nextShader.close(); owned?.close(); throw error }
        filtered?.close(); filteredEdge?.close(); image?.close()
        filtered = nextShader; filteredEdge = nextEdge; image = owned
        source = pixels; scale = nextScale; mappingKey = null
    }

    private fun filter(image: Image, sample: WindowsDesktopBackdrop.Sample, sigma: Float): Shader =
        PictureRecorder().use { recorder ->
            val pixels = sample.pixels
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
                        ImageFilter.makeBlur(sigma * sample.scaleX, sigma * sample.scaleY,
                            FilterTileMode.CLAMP, vibrancy).use { blur ->
                            paint.imageFilter = blur
                            canvas.drawImage(image, 0f, 0f, paint)
                        }
                    }
                }
            }
            recorder.finishRecordingAsPicture().use {
                it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, FilterMode.LINEAR)
            }
        }

    override fun close() {
        shader?.close(); shader = null
        edgeShader?.close(); edgeShader = null
        filtered?.close(); filtered = null
        filteredEdge?.close(); filteredEdge = null
        image?.close(); image = null; source = null
        builder?.close(); builder = null
        effect?.close(); effect = null; mappingKey = null
    }
}
