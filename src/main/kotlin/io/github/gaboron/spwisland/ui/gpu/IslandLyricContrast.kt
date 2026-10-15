// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui.gpu

import org.jetbrains.skia.*
import kotlin.math.abs

/** One GPU contrast decision per line, applied through the classic lyric stroke paint. */
internal class IslandLyricContrast : AutoCloseable {
    private var background: Shader? = null
    private var inverseBase: Matrix33? = null
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private var sampleEffect: RuntimeEffect? = null
    private var sampleBuilder: RuntimeShaderBuilder? = null
    private var context: DirectContext? = null
    private var surfaceContext: DirectContext? = null
    private var samples: Surface? = null
    private var decision: Surface? = null
    private val paint = Paint().apply { blendMode = BlendMode.SRC }
    private val sampling = FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
    private var lineShader: Shader? = null
    private var unavailable = false

    val active: Boolean get() = background != null && inverseBase != null && context != null && !unavailable

    /** The caller retains ownership of the background shader for this frame. */
    fun begin(background: Shader?, baseMatrix: Matrix33, context: DirectContext?) {
        this.background = background
        this.context = context
        inverseBase = if (background != null) inverse(baseMatrix) else null
        if (!active || surfaceContext !== context) releaseSurfaces()
    }

    fun prepareLine(canvas: Canvas, left: Float, right: Float, ascent: Float, descent: Float, color: Int) {
        lineShader?.close(); lineShader = null
        val backdrop = background ?: return
        val base = inverseBase ?: return
        val current = context ?: return
        if (unavailable) return
        try {
            val sampled = samples ?: checkNotNull(Surface.makeRenderTarget(current, true,
                ImageInfo(5, 3, ColorType.RGBA_8888, ColorAlphaType.PREMUL))).also {
                samples = it; surfaceContext = current
            }
            val output = decision ?: checkNotNull(Surface.makeRenderTarget(current, true,
                ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL))).also { decision = it }
            val sampler = sampleBuilder ?: run {
                val compiled = sampleEffect ?: RuntimeEffect.makeForShader(SAMPLE).also { sampleEffect = it }
                RuntimeShaderBuilder(compiled).also { sampleBuilder = it }
            }
            val program = builder ?: run {
                val compiled = effect ?: RuntimeEffect.makeForShader(PROGRAM).also { effect = it }
                RuntimeShaderBuilder(compiled).also { builder = it }
            }
            val transform = base.makeConcat(canvas.localToDeviceAsMatrix33).mat
            sampler.uniform("backgroundX", transform[0], transform[1], transform[2])
            sampler.uniform("backgroundY", transform[3], transform[4], transform[5])
            sampler.uniform("backgroundW", transform[6], transform[7], transform[8])
            sampler.child("background", backdrop)
            sampler.uniform("lineBounds", left, -ascent, right, descent)
            program.uniform("lyricColor", (color ushr 16 and 255) / 255f,
                (color ushr 8 and 255) / 255f, (color and 255) / 255f)
            // Explicit GPU passes: one optical evaluation per sample, then one line decision.
            // ImageShader keeps glyph animation from rerasterizing a PictureShader at new scales.
            sampler.makeShader().use { shader ->
                paint.shader = shader
                sampled.canvas.drawPaint(paint)
            }
            sampled.makeImageSnapshot().use { image ->
                image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling).use { shader ->
                    program.child("background", shader)
                    program.makeShader().use { result ->
                        paint.shader = result
                        output.canvas.drawPaint(paint)
                    }
                }
            }
            output.makeImageSnapshot().use {
                lineShader = it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling)
            }
        } catch (error: Throwable) {
            unavailable = true
            builder?.close(); builder = null
            effect?.close(); effect = null
            sampleBuilder?.close(); sampleBuilder = null
            sampleEffect?.close(); sampleEffect = null
            releaseSurfaces()
            System.err.println("[SPW Island] 歌词背景对比度检测不可用，恢复原描边：${error.message}")
        } finally { paint.shader = null }
    }

    fun draw(canvas: Canvas, path: Path, classicStroke: Paint): Boolean {
        val shader = lineShader ?: return false
        classicStroke.shader = shader
        try { canvas.drawPath(path, classicStroke) } finally { classicStroke.shader = null }
        return true
    }

    override fun close() {
        background = null; inverseBase = null
        context = null; releaseSurfaces()
        paint.close()
        builder?.close(); builder = null
        effect?.close(); effect = null
        sampleBuilder?.close(); sampleBuilder = null
        sampleEffect?.close(); sampleEffect = null
    }

    private fun releaseSurfaces() {
        lineShader?.close(); lineShader = null
        samples?.close(); samples = null
        decision?.close(); decision = null; surfaceContext = null
    }

    private fun inverse(matrix: Matrix33): Matrix33? {
        val m = matrix.mat
        val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (!determinant.isFinite() || abs(determinant) < .00000001f) return null
        val d = 1f / determinant
        return Matrix33(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d,
            (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d,
            (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d,
            (m[0] * m[4] - m[1] * m[3]) * d
        )
    }

    private companion object {
        const val SAMPLE = """
            uniform shader background;
            uniform float3 backgroundX;
            uniform float3 backgroundY;
            uniform float3 backgroundW;
            uniform float4 lineBounds;
            half4 main(float2 coord) {
                float2 p = mix(lineBounds.xy, lineBounds.zw, coord / float2(5.0, 3.0));
                float3 point = float3(p, 1.0);
                float w = dot(backgroundW, point);
                if (abs(w) < 0.000001) return half4(0.0);
                return background.eval(float2(dot(backgroundX, point), dot(backgroundY, point)) / w);
            }
        """

        // WCAG relative luminance in sRGB; anti-aliasing remains the path rasterizer's job.
        const val PROGRAM = """
            uniform shader background;
            uniform float3 lyricColor;

            float luminance(float3 color) {
                color = clamp(color, 0.0, 1.0);
                float3 linearRgb = mix(color / 12.92,
                    pow((color + 0.055) / 1.055, float3(2.4)), step(float3(0.04045), color));
                return dot(linearRgb, float3(0.2126, 0.7152, 0.0722));
            }

            float contrast(float a, float b) {
                return (max(a, b) + 0.05) / (min(a, b) + 0.05);
            }

            half4 main(float2 coord) {
                float mainLight = luminance(lyricColor);
                float dimLight = luminance(float3(126.0, 129.0, 138.0) / 255.0);
                float subLight = luminance(float3(177.0, 182.0, 195.0) / 255.0);
                float minimum = 100.0;
                for (int y = 0; y < 3; ++y) {
                    for (int x = 0; x < 5; ++x) {
                        half4 behind = background.eval(float2(float(x) + 0.5, float(y) + 0.5));
                        float light = luminance(float3(behind.rgb) / max(float(behind.a), 0.0001));
                        minimum = min(minimum, min(contrast(mainLight, light),
                            min(contrast(dimLight, light), contrast(subLight, light))));
                    }
                }
                return half4(0.0, 0.0, 0.0, minimum < 4.5 ? 1.0 : 0.0);
            }
        """
    }
}
