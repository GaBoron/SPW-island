// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.platform.WindowsDesktopBackdrop
import org.jetbrains.skia.*
import java.awt.Shape
import kotlin.math.roundToInt

/** Composes the ported Backdrop material; content remains in the original scene. */
internal class IslandLiquidGlass : AutoCloseable {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private val contour = IslandGlassContour()
    private val background = IslandGlassImage()
    private var highlight: IslandGlassHighlight? = null
    private var shader: Shader? = null
    private var paint: Paint? = null
    private var materialEffect: RuntimeEffect? = null
    private var materialBuilder: RuntimeShaderBuilder? = null
    private var materialKey: Any? = null
    var materialShader: Shader? = null
        private set
    private var shaderKey: Any? = null
    private var deviceScale = 1f
    var failure: String? = null
        private set

    fun draw(canvas: Canvas, path: Path, shape: Shape, geometry: Any, nominalSize: Float,
             sample: WindowsDesktopBackdrop.Sample, opacity: Int, progress: Float,
             progressOpacity: Float, width: Int, surfaceColor: Int = 0xff000000.toInt()): Boolean {
        if (failure != null) return false
        val pixels = sample.pixels
        val bounds = path.bounds
        if (sample.islandX + bounds.left * sample.scaleX < pixels.x - 1 ||
            sample.islandY + bounds.top * sample.scaleY < pixels.y - 1 ||
            sample.islandX + bounds.right * sample.scaleX > pixels.x + pixels.width + 1 ||
            sample.islandY + bounds.bottom * sample.scaleY > pixels.y + pixels.height + 1) return false
        return try {
            val program = builder ?: run {
                val compiled = RuntimeEffect.makeForShader(IslandGlassShaders.LENS)
                effect = compiled
                RuntimeShaderBuilder(compiled).also { builder = it }
            }
            contour.update(path, shape, geometry, sample.scaleX, sample.scaleY)
            background.update(sample)
            val nextKey = listOf(contour.generation, background.generation, nominalSize)
            val target = paint ?: Paint().apply { isAntiAlias = true }.also { paint = it }
            if (shaderKey != nextKey) {
                program.child("contour", checkNotNull(contour.shader))
                program.child("coverage", checkNotNull(contour.coverage))
                program.uniform("fieldScale", sample.scaleX, sample.scaleY)
                program.uniform("fieldOrigin", contour.originX, contour.originY)
                program.child("content", checkNotNull(background.shader))
                program.child("edgeContent", checkNotNull(background.edgeShader))
                program.uniform("center", (bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
                program.uniform("halfSize", (bounds.width / 2f).coerceAtLeast(.001f),
                    (bounds.height / 2f).coerceAtLeast(.001f))
                // Shared optics scale down only while the existing shape collapses.
                val collapseScale = (minOf(bounds.width, bounds.height) / nominalSize.coerceAtLeast(.001f))
                    .coerceIn(.0001f, 1f)
                program.uniform("refractionHeight", IslandGlassShaders.LENS_HEIGHT * collapseScale)
                program.uniform("refractionAmount", -IslandGlassShaders.LENS_AMOUNT * collapseScale)
                program.uniform("depthEffect", 0f)
                // Keep upstream's seven samples, but respect the requested faint dispersion.
                program.uniform("chromaticAberration", .2f / IslandGlassShaders.LENS_AMOUNT)
                val next = program.makeShader()
                try { target.shader = next } catch (error: Throwable) { next.close(); throw error }
                shader?.close(); shader = next; shaderKey = nextKey
            }
            deviceScale = sample.scaleY
            target.shader = material(opacity, progress, progressOpacity, width, surfaceColor)
            canvas.drawPath(path, target)
            true
        } catch (error: Throwable) { disable(error); false }
    }

    private fun material(opacity: Int, progress: Float, progressOpacity: Float, width: Int, surfaceColor: Int): Shader {
        val nextKey = listOf(shaderKey, opacity, progress, progressOpacity, width, surfaceColor)
        if (materialKey == nextKey) return checkNotNull(materialShader)
        val program = materialBuilder ?: run {
            val compiled = RuntimeEffect.makeForShader(IslandGlassShaders.MATERIAL)
            materialEffect = compiled
            RuntimeShaderBuilder(compiled).also { materialBuilder = it }
        }
        program.child("lens", checkNotNull(shader))
        program.uniform("surfaceAlpha", (.5f * opacity / 100f * 255f).roundToInt().coerceIn(0, 255) / 255f)
        program.uniform("surfaceTint", (surfaceColor ushr 16 and 255) / 255f,
            (surfaceColor ushr 8 and 255) / 255f, (surfaceColor and 255) / 255f)
        program.uniform("progressX", width * progress)
        program.uniform("progressShade", (25.5f * progressOpacity).roundToInt().coerceIn(0, 255) / 255f)
        val next = program.makeShader()
        materialShader?.close(); materialShader = next; materialKey = nextKey
        return next
    }

    fun drawHighlight(canvas: Canvas, path: Path) {
        if (failure != null) return
        try {
            val renderer = highlight ?: IslandGlassHighlight().also { highlight = it }
            renderer.draw(canvas, path, contour, deviceScale)
        } catch (error: Throwable) { disable(error) }
    }

    private fun disable(error: Throwable) {
        failure = error.message ?: error.javaClass.simpleName
        close()
        System.err.println("[SPW Island] 液态玻璃光学绘制失败，恢复经典背景：$failure")
    }

    override fun close() {
        paint?.close(); paint = null
        materialShader?.close(); materialShader = null
        materialBuilder?.close(); materialBuilder = null
        materialEffect?.close(); materialEffect = null; materialKey = null
        shader?.close(); shader = null
        highlight?.close(); highlight = null
        contour.close(); background.close()
        builder?.close(); builder = null
        effect?.close(); effect = null; shaderKey = null
    }
}
