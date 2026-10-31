// SPDX-License-Identifier: GPL-3.0-only
#version 140
uniform mat4 modelViewProjectionMatrix;
in vec4 position;
in vec2 texcoord;
out vec2 texcoord0;
void main() {
    texcoord0 = texcoord;
    gl_Position = modelViewProjectionMatrix * position;
}
