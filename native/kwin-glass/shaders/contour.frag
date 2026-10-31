// SPDX-License-Identifier: GPL-3.0-only
#version 140
uniform vec2 points[256];
uniform int pointCount;
uniform vec2 clientSize;
in vec2 texcoord0;
out vec4 fragColor;
void main() {
    vec2 p = vec2(texcoord0.x, 1.0 - texcoord0.y) * clientSize;
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
    fragColor = vec4(0.5 + 0.5 * clamp(distance / 64.0, -1.0, 1.0),
                     normal * 0.5 + 0.5, clamp(0.5 - distance, 0.0, 1.0));
}
