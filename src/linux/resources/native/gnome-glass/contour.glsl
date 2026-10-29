// SPDX-License-Identifier: GPL-3.0-only
uniform vec2 points[256];
uniform int pointCount;
uniform vec4 fieldRect;

// A cached field of the existing animated Path, independent of the optical pass.
void main() {
    vec2 p = fieldRect.xy + cogl_tex_coord0_in.xy * fieldRect.zw;
    float nearest = 1.0e12;
    vec2 closest = vec2(0.0);
    bool inside = false;
    vec2 previous = points[pointCount - 1];
    for (int i = 0; i < 256; i++) {
        if (i >= pointCount) break;
        vec2 current = points[i];
        vec2 edge = current - previous;
        float t = clamp(dot(p - previous, edge) / max(dot(edge, edge), 0.000001), 0.0, 1.0);
        vec2 delta = p - previous - edge * t;
        float squared = dot(delta, delta);
        if (squared < nearest) { nearest = squared; closest = delta; }
        if ((previous.y > p.y) != (current.y > p.y)) {
            float crossing = previous.x + (p.y - previous.y) * edge.x / edge.y;
            if (p.x < crossing) inside = !inside;
        }
        previous = current;
    }
    float distance = sqrt(nearest) * (inside ? -1.0 : 1.0);
    vec2 normal = nearest > 0.000001 ? normalize(closest) * (inside ? -1.0 : 1.0) : vec2(0.0, -1.0);
    cogl_color_out = vec4(0.5 + 0.5 * clamp(distance / 64.0, -1.0, 1.0),
                          normal * 0.5 + 0.5, clamp(0.5 - distance, 0.0, 1.0));
}
