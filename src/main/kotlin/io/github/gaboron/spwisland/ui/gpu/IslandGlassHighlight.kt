// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import org.jetbrains.skia.*
import kotlin.math.ceil
import kotlin.math.min

/** Backdrop Highlight.Default: clipped blurred stroke in an independent PLUS layer. */
internal class IslandGlassHighlight : AutoCloseable {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private var shader: Shader? = null
    private var key: Any? = null
    private val stroke = Paint().apply {
        isAntiAlias = true; mode = PaintMode.STROKE; strokeJoin = PaintStrokeJoin.ROUND
        color = Color.WHITE; setAlphaf(.5f)
    }
    private val composite = Paint().apply { blendMode = BlendMode.PLUS }
    private val blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, .25f, true)

    init { stroke.maskFilter = blur }

    fun draw(canvas: Canvas, path: Path, contour: IslandGlassContour, scale: Float) {
        val bounds = path.bounds
        val nextKey = contour.generation to scale
        if (key != nextKey) {
            val program = builder ?: run {
                val compiled = RuntimeEffect.makeForShader(IslandGlassShaders.HIGHLIGHT)
                effect = compiled
                RuntimeShaderBuilder(compiled).also { builder = it }
            }
            program.child("contour", checkNotNull(contour.shader))
            program.child("coverage", checkNotNull(contour.coverage))
            program.uniform("fieldScale", contour.scaleX, contour.scaleY)
            program.uniform("fieldOrigin", contour.originX, contour.originY)
            program.uniform("color", 1f, 1f, 1f, 1f)
            program.uniform("angle", -2.3561945f) // Upper left, per the island's lighting requirement.
            program.uniform("falloff", 1f)
            val next = program.makeShader()
            try { stroke.shader = next } catch (error: Throwable) { next.close(); throw error }
            shader?.close(); shader = next
            // The reference rounds a half-dp width up to whole physical pixels.
            stroke.strokeWidth = ceil(min(.5f * scale, min(bounds.width, bounds.height) * scale / 2f)) * 2f / scale
            key = nextKey
        }
        val save = canvas.saveLayer(Rect.makeLTRB(bounds.left - 1f, bounds.top - 1f,
            bounds.right + 1f, bounds.bottom + 1f), composite)
        try {
            canvas.clipPath(path, ClipMode.INTERSECT, true)
            canvas.drawPath(path, stroke)
        } finally { canvas.restoreToCount(save) }
    }

    override fun close() {
        stroke.close(); composite.close(); blur.close()
        shader?.close(); shader = null
        builder?.close(); builder = null
        effect?.close(); effect = null; key = null
    }
}
