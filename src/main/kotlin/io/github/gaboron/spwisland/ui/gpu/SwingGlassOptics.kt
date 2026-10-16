// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import io.github.gaboron.spwisland.platform.WindowsDesktopBackdrop
import org.jetbrains.skia.*
import kotlin.math.ceil

/** Fixed-window geometry, with the desktop image and all optical work kept in the GPU scene. */
internal class SwingGlassOptics : AutoCloseable {
    private val background = IslandGlassImage()
    private val lensEffect = RuntimeEffect.makeForShader(IslandGlassShaders.ROUND_RECT_LENS)
    private val lens = RuntimeShaderBuilder(lensEffect)
    private val materialEffect = RuntimeEffect.makeForShader(IslandGlassShaders.MATERIAL)
    private val material = RuntimeShaderBuilder(materialEffect)
    private val highlightEffect = RuntimeEffect.makeForShader(IslandGlassShaders.ROUND_RECT_HIGHLIGHT)
    private val highlight = RuntimeShaderBuilder(highlightEffect)
    private val fill = Paint().apply { isAntiAlias = true }
    private val edge = Paint().apply {
        isAntiAlias = true; mode = PaintMode.STROKE; blendMode = BlendMode.PLUS
    }
    private val blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, .25f, true)
    private var lensShader: Shader? = null
    private var materialShader: Shader? = null
    private var highlightShader: Shader? = null
    private var key: Any? = null
    private var scale = 1f

    init { edge.maskFilter = blur }

    fun draw(canvas: Canvas, path: Path, sample: WindowsDesktopBackdrop.Sample?, arc: Double, tint: Int): Boolean {
        if (sample != null) { background.update(sample); scale = sample.scaleY }
        if (background.shader == null) return false
        val bounds = path.bounds
        val nextKey = listOf(background.generation, bounds, arc, tint, scale)
        if (key != nextKey) {
            val cx = (bounds.left + bounds.right) / 2f
            val cy = (bounds.top + bounds.bottom) / 2f
            val halfW = bounds.width / 2f
            val halfH = bounds.height / 2f
            val radius = minOf(arc.toFloat() / 2f, halfW, halfH)
            lens.child("content", checkNotNull(background.shader))
            lens.child("edgeContent", checkNotNull(background.edgeShader))
            lens.uniform("center", cx, cy); lens.uniform("halfSize", halfW, halfH)
            lens.uniform("cornerRadius", radius)
            lens.uniform("refractionHeight", IslandGlassShaders.LENS_HEIGHT)
            lens.uniform("refractionAmount", -IslandGlassShaders.LENS_AMOUNT)
            lens.uniform("depthEffect", 0f)
            lens.uniform("chromaticAberration", .2f / IslandGlassShaders.LENS_AMOUNT)
            val nextLens = lens.makeShader()
            lensShader?.close(); lensShader = nextLens
            material.child("lens", nextLens)
            // Keep small text readable even over the brightest/darkest possible desktop crop.
            material.uniform("surfaceAlpha", .82f)
            material.uniform("surfaceTint", (tint ushr 16 and 255) / 255f,
                (tint ushr 8 and 255) / 255f, (tint and 255) / 255f)
            material.uniform("progressX", 0f); material.uniform("progressShade", 0f)
            val nextMaterial = material.makeShader()
            fill.shader = nextMaterial
            materialShader?.close(); materialShader = nextMaterial
            highlight.uniform("center", cx, cy); highlight.uniform("halfSize", halfW, halfH)
            highlight.uniform("cornerRadius", radius)
            highlight.uniform("color", 1f, 1f, 1f, .5f)
            highlight.uniform("angle", -2.3561945f); highlight.uniform("falloff", 1f)
            val nextHighlight = highlight.makeShader()
            edge.shader = nextHighlight
            highlightShader?.close(); highlightShader = nextHighlight
            edge.strokeWidth = ceil(.5f * scale) * 2f / scale
            key = nextKey
        }
        // A crop crossing a monitor edge uses the image shader's CLAMP sampling while capture catches up.
        canvas.drawPath(path, fill)
        canvas.scoped {
            canvas.clipPath(path, ClipMode.INTERSECT, true)
            canvas.drawPath(path, edge)
        }
        return true
    }

    override fun close() {
        fill.close(); edge.close(); blur.close()
        materialShader?.close(); lensShader?.close(); highlightShader?.close()
        material.close(); lens.close(); highlight.close()
        materialEffect.close(); lensEffect.close(); highlightEffect.close()
        background.close()
    }
}
