// SPDX-License-Identifier: GPL-3.0-only
uniform vec2 stepSize;

void main() {
    vec2 uv = cogl_tex_coord0_in.xy;
    vec4 color = texture2D(cogl_sampler0, uv) * 0.227027;
    color += texture2D(cogl_sampler0, uv + stepSize * 1.384615) * 0.316216;
    color += texture2D(cogl_sampler0, uv - stepSize * 1.384615) * 0.316216;
    color += texture2D(cogl_sampler0, uv + stepSize * 3.230769) * 0.070270;
    color += texture2D(cogl_sampler0, uv - stepSize * 3.230769) * 0.070270;
    cogl_color_out = color;
}
