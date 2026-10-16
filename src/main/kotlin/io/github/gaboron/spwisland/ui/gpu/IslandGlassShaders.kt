/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified 2026-10-15 for SPW Island. Source: Kyant0/AndroidLiquidGlass,
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, internal/Shaders.kt.
 * Local changes include shape SDF/normal adapters, numeric guards and back-light
 * attenuation of the upstream Lens/dispersion/DefaultHighlight algorithms.
 * 2026-10-16: added auxiliary surface tint and center/edge blur blending.
 */
package io.github.gaboron.spwisland.ui.gpu

/** Backdrop's optical programs; coordinates and fixed material values are logical pixels. */
internal object IslandGlassShaders {
    const val CENTER_BLUR_SIGMA = 6f
    const val EDGE_BLUR_SIGMA = 2f
    const val LENS_HEIGHT = 14f
    const val LENS_AMOUNT = 36f
    const val DISTANCE_RANGE = 16f
    const val CAPTURE_PADDING = 32

    // Geometry adapter only. Both textures come from the existing animated silhouette.
    private const val SHAPE = """
        uniform shader contour;
        uniform shader coverage;
        uniform float2 fieldScale;
        uniform float2 fieldOrigin;

        float sdIsland(float2 coord) {
            float2 position = coord * fieldScale - fieldOrigin;
            float distance = float(contour.eval(position).r) * 16.0;
            return coverage.eval(position).a >= 0.5 ? -distance : distance;
        }

        float2 gradSdIsland(float2 coord) {
            float2 grad = float2(
                sdIsland(coord + float2(0.5, 0.0)) - sdIsland(coord - float2(0.5, 0.0)),
                sdIsland(coord + float2(0.0, 0.5)) - sdIsland(coord - float2(0.0, 0.5)));
            float magnitude = length(grad);
            return magnitude > 0.0001 ? grad / magnitude : float2(0.0, -1.0);
        }
    """

    // Upstream refraction/dispersion with local geometry and center/edge blur blending.
    const val LENS = """
        uniform shader content;
        uniform shader edgeContent;
        uniform float refractionHeight;
        uniform float refractionAmount;
        uniform float depthEffect;
        uniform float chromaticAberration;
        uniform float2 center;
        uniform float2 halfSize;
        $SHAPE

        float circleMap(float x) {
            return 1.0 - sqrt(max(0.0, 1.0 - x * x));
        }

        half4 main(float2 coord) {
            float2 centeredCoord = coord - center;
            float sd = sdIsland(coord);
            if (-sd >= refractionHeight) {
                return content.eval(coord);
            }
            sd = min(sd, 0.0);

            float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
            float2 radial = centeredCoord / max(length(centeredCoord), 0.0001);
            float2 grad = normalize(gradSdIsland(coord) + depthEffect * radial);
            float2 refractedCoord = coord + d * grad;
            float dispersionIntensity = chromaticAberration * clamp(
                centeredCoord.x * centeredCoord.y / (halfSize.x * halfSize.y), -1.0, 1.0);
            float2 dispersedCoord = d * grad * dispersionIntensity;

            half4 color = half4(0.0);
            half4 red = edgeContent.eval(refractedCoord + dispersedCoord);
            color.r += red.r / 3.5;
            color.a += red.a / 7.0;
            half4 orange = edgeContent.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
            color.r += orange.r / 3.5;
            color.g += orange.g / 7.0;
            color.a += orange.a / 7.0;
            half4 yellow = edgeContent.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
            color.r += yellow.r / 3.5;
            color.g += yellow.g / 3.5;
            color.a += yellow.a / 7.0;
            half4 green = edgeContent.eval(refractedCoord);
            color.g += green.g / 3.5;
            color.a += green.a / 7.0;
            half4 cyan = edgeContent.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
            color.g += cyan.g / 3.5;
            color.b += cyan.b / 3.0;
            color.a += cyan.a / 7.0;
            half4 blue = edgeContent.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
            color.b += blue.b / 3.0;
            color.a += blue.a / 7.0;
            half4 purple = edgeContent.eval(refractedCoord - dispersedCoord);
            color.r += purple.r / 7.0;
            color.b += purple.b / 3.0;
            color.a += purple.a / 7.0;
            half centerBlend = half(smoothstep(0.0, refractionHeight, -sd));
            return mix(color, content.eval(coord), centerBlend);
        }
    """

    // DefaultHighlightShaderString. The requested upper-left lighting keeps the
    // upstream abs(dot)/falloff, but attenuates the opposite side of the light.
    const val HIGHLIGHT = """
        layout(color) uniform half4 color;
        uniform float angle;
        uniform float falloff;
        $SHAPE

        half4 main(float2 coord) {
            float2 grad = gradSdIsland(coord);
            float2 normal = float2(cos(angle), sin(angle));
            float d = dot(grad, normal);
            float intensity = pow(abs(d), falloff);
            intensity *= mix(0.15, 1.0, smoothstep(-0.1, 0.1, d));
            return color * intensity;
        }
    """

    // A local geometry adapter, independent of the optical and color programs.
    const val SEGMENT_DISTANCE = """
        uniform float2 start;
        uniform float2 end;
        half4 main(float2 coord) {
            float2 edge = end - start;
            float t = clamp(dot(coord - start, edge) / max(dot(edge, edge), 0.000001), 0.0, 1.0);
            half distance = half(clamp(length(coord - start - t * edge) / 16.0, 0.0, 1.0));
            return half4(distance, distance, distance, 1.0);
        }
    """

    // Explicit coordinates preserve the physical-size PictureShader cache tile.
    const val DESKTOP_COORDINATES = """
        uniform shader image;
        uniform float2 deviceScale;
        uniform float2 captureOffset;
        half4 main(float2 coord) {
            return image.eval(coord * deviceScale + captureOffset);
        }
    """

    // The visible surface and the lyric contrast sampler share the same final colors.
    const val MATERIAL = """
        uniform shader lens;
        uniform float surfaceAlpha;
        uniform float3 surfaceTint;
        uniform float progressX;
        uniform float progressShade;
        half4 main(float2 coord) {
            half4 base = lens.eval(coord);
            half gray = coord.x < progressX ? half(progressShade) : half(0.0);
            half alpha = half(surfaceAlpha);
            return half4((half3(surfaceTint) + half3(gray)) * alpha, alpha) + base * (1.0 - alpha);
        }
    """

    // Auxiliary windows have a fixed circular-corner rectangle, so no sampled contour is needed.
    // The animated island keeps its original path-based distance field.
    private const val ROUND_RECT_SHAPE = """
        uniform float cornerRadius;
        float sdIsland(float2 coord) {
            float2 q = abs(coord - center) - halfSize + cornerRadius;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerRadius;
        }
        float2 gradSdIsland(float2 coord) {
            float2 q = abs(coord - center) - halfSize + cornerRadius;
            float2 outer = max(q, 0.0);
            float magnitude = length(outer);
            float2 normal = magnitude > 0.0001 ? outer / magnitude :
                (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
            return normal * sign(coord - center);
        }
    """
    val ROUND_RECT_LENS = LENS.replace(SHAPE, ROUND_RECT_SHAPE)
    val ROUND_RECT_HIGHLIGHT = "uniform float2 center; uniform float2 halfSize;\n" +
        HIGHLIGHT.replace(SHAPE, ROUND_RECT_SHAPE)
}
