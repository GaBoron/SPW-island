// SPDX-License-Identifier: GPL-3.0-only
// Optical profile adapted from IslandGlassShaders.kt (Copyright 2025 Kyant, Apache-2.0).
// See NOTICE and licenses/Apache-2.0.txt in the plugin source.
#version 140
uniform sampler2D background, blurred, contourMap, clientMask;
uniform vec4 sampleRect;
uniform vec2 clientSize;
uniform float maskAlpha, revealOpacity, collapseScale;
in vec2 texcoord0;
out vec4 fragColor;
void main() {
    vec2 uv = texcoord0;
    vec2 p = sampleRect.xy + vec2(uv.x, 1.0 - uv.y) * sampleRect.zw;
    vec2 clientUV = vec2(p.x / clientSize.x, 1.0 - p.y / clientSize.y);
    vec4 field = texture(contourMap, clientUV);
    float depth = max((0.5 - field.r) * 128.0, 0.0);
    vec2 normal = field.gb * 2.0 - 1.0;
    normal /= max(length(normal), 0.0001);
    float band = max(20.0 * collapseScale, 0.001);
    float t = clamp(depth / band, 0.0, 1.0);
    float rim = 1.0 - t;
    float displacement = 52.0 * collapseScale * rim * rim * (0.5 + 0.5 * rim);
    vec2 textureNormal = vec2(normal.x, -normal.y);
    vec2 lens = uv - textureNormal * displacement / sampleRect.zw;
    vec2 separation = textureNormal * 1.8 * collapseScale *
        (1.0 - smoothstep(0.0, 1.0, t)) / sampleRect.zw;
    vec3 sharp = vec3(texture(background, lens - separation).r,
                      texture(background, lens).g, texture(background, lens + separation).b);
    vec3 soft = texture(blurred, lens).rgb;
    vec3 color = mix(sharp, soft, smoothstep(0.0, max(28.0 * collapseScale, 0.001), depth));
    float light = dot(normal, normalize(vec2(-1.0, -1.0)));
    float highlight = abs(light) * mix(0.15, 1.0, smoothstep(-0.1, 0.1, light));
    color += vec3(0.5 * highlight * exp(-depth / 0.8));
    float coverage = clamp(texture(clientMask, clientUV).a / max(maskAlpha, 0.0001), 0.0, 1.0);
    float alpha = min(field.a, coverage) * revealOpacity;
    fragColor = vec4(color * alpha, alpha);
}
