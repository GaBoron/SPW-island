// SPDX-License-Identifier: GPL-3.0-only
#version 140
uniform sampler2D source;
uniform vec2 stepSize;
uniform int prefilterMode;
in vec2 texcoord0;
out vec4 fragColor;
void main() {
    vec2 uv = texcoord0;
    if (prefilterMode == 1) {
        vec2 delta = stepSize * 0.5;
        vec4 color = texture(source, uv + vec2(-delta.x, -delta.y));
        color += texture(source, uv + vec2(delta.x, -delta.y));
        color += texture(source, uv + vec2(-delta.x, delta.y));
        color += texture(source, uv + delta);
        fragColor = color * 0.25;
    } else {
        // Continuous paired Gaussian taps, after bounded 2x prefilter reductions.
        vec4 color = texture(source, uv) * 0.199675627;
        color += texture(source, uv + stepSize * 1.407333400) * 0.297322513;
        color += texture(source, uv - stepSize * 1.407333400) * 0.297322513;
        color += texture(source, uv + stepSize * 3.294214972) * 0.091848343;
        color += texture(source, uv - stepSize * 3.294214972) * 0.091848343;
        color += texture(source, uv + stepSize * 5.201813222) * 0.010991331;
        color += texture(source, uv - stepSize * 5.201813222) * 0.010991331;
        fragColor = color;
    }
}
