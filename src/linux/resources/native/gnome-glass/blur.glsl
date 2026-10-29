// SPDX-License-Identifier: GPL-3.0-only
uniform vec2 stepSize;
uniform int prefilterMode;

void main() {
    vec2 uv = cogl_tex_coord0_in.xy;
    if (prefilterMode == 1) {
        // Each pyramid level filters a 2x2 footprint before reducing either dimension.
        vec2 delta = stepSize * 0.5;
        vec4 color = texture2D(cogl_sampler0, uv + vec2(-delta.x, -delta.y));
        color += texture2D(cogl_sampler0, uv + vec2(delta.x, -delta.y));
        color += texture2D(cogl_sampler0, uv + vec2(-delta.x, delta.y));
        color += texture2D(cogl_sampler0, uv + delta);
        cogl_color_out = color * 0.25;
    } else {
        // Sigma 2, radius 6. Each bilinear lookup combines two adjacent kernel taps.
        // stepSize is exactly one source texel; the pyramid supplies the wider logical radius.
        vec4 color = texture2D(cogl_sampler0, uv) * 0.199675627;
        color += texture2D(cogl_sampler0, uv + stepSize * 1.407333400) * 0.297322513;
        color += texture2D(cogl_sampler0, uv - stepSize * 1.407333400) * 0.297322513;
        color += texture2D(cogl_sampler0, uv + stepSize * 3.294214972) * 0.091848343;
        color += texture2D(cogl_sampler0, uv - stepSize * 3.294214972) * 0.091848343;
        color += texture2D(cogl_sampler0, uv + stepSize * 5.201813222) * 0.010991331;
        color += texture2D(cogl_sampler0, uv - stepSize * 5.201813222) * 0.010991331;
        cogl_color_out = color;
    }
}
