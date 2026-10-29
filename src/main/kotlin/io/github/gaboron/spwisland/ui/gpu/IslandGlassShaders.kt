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
 * Local changes include shape SDF/normal adapters, layered blur, normal-aligned
 * RGB dispersion, bounded lens falloff, auxiliary tint and back-light attenuation.
 */
package io.github.gaboron.spwisland.ui.gpu

/** Backdrop's optical programs; coordinates and fixed material values are logical pixels. */
internal object IslandGlassShaders {
    const val CENTER_BLUR_SIGMA = 10f
    const val TRANSITION_BLUR_SIGMA = 4f
    const val EDGE_BLUR_SIGMA = .8f
    const val BLUR_TRANSITION_HEIGHT = 28f
    const val LENS_HEIGHT = 20f
    const val LENS_AMOUNT = 52f
    const val CHROMATIC_SPREAD = 3.6f // Total distance between the red and blue samples.
    const val DISTANCE_RANGE = 32f
    // Every blur layer follows the lens coordinates, so reserve the combined optical footprint.
    const val CAPTURE_PADDING = LENS_AMOUNT + CHROMATIC_SPREAD / 2f + CENTER_BLUR_SIGMA * 3f + 2f

    // Geometry adapter only. Both textures come from the existing animated silhouette.
    private const val SHAPE = """
        uniform shader contour;
        uniform shader coverage;
        uniform float2 fieldScale;
        uniform float2 fieldOrigin;

        float sdIsland(float2 coord) {
            float2 position = coord * fieldScale - fieldOrigin;
            float distance = float(contour.eval(position).r) * $DISTANCE_RANGE;
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

    // Separate the sharp refracted rim from the soft center; wavelengths follow the local normal.
    const val LENS = """
        uniform shader content;
        uniform shader transitionContent;
        uniform shader edgeContent;
        uniform float blurTransitionHeight;
        uniform float refractionHeight;
        uniform float refractionAmount;
        uniform float chromaticSpread;
        $SHAPE

        half4 main(float2 coord) {
            float depth = max(-sdIsland(coord), 0.0);
            float band = max(refractionHeight, 0.0001);
            float transition = max(blurTransitionHeight, band);
            if (depth >= transition) {
                return content.eval(coord);
            }
            float t = clamp(depth / band, 0.0, 1.0);
            float rim = 1.0 - t;
            // A finite slope at the outer rim avoids the circular profile's singular derivative.
            float d = rim * rim * (0.5 + 0.5 * rim) * refractionAmount;
            float2 grad = gradSdIsland(coord);
            float2 refractedCoord = coord + d * grad;
            // Separation depends on rim depth, never x*y or the signed displacement.
            // It stays present on both center axes and fades smoothly toward the interior.
            float2 dispersedCoord = grad * (0.5 * chromaticSpread * (1.0 - smoothstep(0.0, 1.0, t)));
            half4 red = edgeContent.eval(refractedCoord - dispersedCoord);
            half4 green = edgeContent.eval(refractedCoord);
            half4 blue = edgeContent.eval(refractedCoord + dispersedCoord);
            half4 color = half4(red.r, green.g, blue.b, green.a);
            // Overlapping blur ramps bridge the clear rim and soft center beyond the lens band.
            // All layers share the same refraction coordinates to avoid a double-image boundary.
            float blurT = clamp(depth / transition, 0.0, 1.0);
            half middleBlend = half(smoothstep(0.0, 0.6, blurT));
            half centerBlend = half(smoothstep(0.25, 1.0, blurT));
            half4 middle = transitionContent.eval(refractedCoord);
            half4 center = content.eval(refractedCoord);
            return mix(mix(color, middle, middleBlend), center, centerBlend);
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
            half distance = half(clamp(length(coord - start - t * edge) / $DISTANCE_RANGE, 0.0, 1.0));
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
        uniform float2 center;
        uniform float2 halfSize;
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
    val ROUND_RECT_HIGHLIGHT = HIGHLIGHT.replace(SHAPE, ROUND_RECT_SHAPE)
}
