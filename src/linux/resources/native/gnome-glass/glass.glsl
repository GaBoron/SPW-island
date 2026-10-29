// SPDX-License-Identifier: GPL-3.0-only
// Optical profile adapted from IslandGlassShaders.kt (Copyright 2025 Kyant,
// Apache-2.0); see NOTICE and licenses/Apache-2.0.txt in the plugin source.
uniform vec4 sampleRect;
uniform vec4 fieldRect;
uniform float revealOpacity;
uniform float collapseScale;
uniform vec2 clientSize;
uniform float maskAlpha;

void main() {
    vec2 uv = cogl_tex_coord0_in.xy;
    vec2 p = sampleRect.xy + uv * sampleRect.zw;
    vec2 fieldUV = (p - fieldRect.xy) / fieldRect.zw;
    vec4 field = texture2D(cogl_sampler2, fieldUV);
    float depth = max((0.5 - field.r) * 128.0, 0.0);
    vec2 normal = field.gb * 2.0 - 1.0;
    normal /= max(length(normal), 0.0001);
    float band = max(20.0 * collapseScale, 0.001);
    float t = clamp(depth / band, 0.0, 1.0);
    float rim = 1.0 - t;
    float displacement = 52.0 * collapseScale * rim * rim * (0.5 + 0.5 * rim);
    vec2 lens = uv - normal * displacement / sampleRect.zw;
    vec2 separation = normal * 1.8 * collapseScale * (1.0 - smoothstep(0.0, 1.0, t)) / sampleRect.zw;
    vec3 sharp = vec3(texture2D(cogl_sampler0, lens - separation).r,
                      texture2D(cogl_sampler0, lens).g,
                      texture2D(cogl_sampler0, lens + separation).b);
    vec3 soft = texture2D(cogl_sampler1, lens).rgb;
    float blurMix = smoothstep(0.0, max(28.0 * collapseScale, 0.001), depth);
    vec3 color = mix(sharp, soft, blurMix);
    // Light follows the actual contour, including the notch attachment and its crest.
    float light = dot(normal, normalize(vec2(-1.0, -1.0)));
    float highlight = abs(light) * mix(0.15, 1.0, smoothstep(-0.1, 0.1, light));
    color += vec3(0.5 * highlight * exp(-depth / 0.8));
    // The client surface supplies the final coverage, so a delayed geometry message
    // cannot draw glass outside the currently presented animated silhouette.
    float clientCoverage = clamp(texture2D(cogl_sampler3, p / clientSize).a / max(maskAlpha, 0.0001), 0.0, 1.0);
    float alpha = min(field.a, clientCoverage) * revealOpacity;
    cogl_color_out = vec4(color * alpha, alpha) * cogl_color_in;
}
