// SPDX-License-Identifier: GPL-3.0-only
#version 140
uniform mat4 modelViewProjectionMatrix;
in vec4 vertex;
in vec2 texCoord;
out vec2 texcoord0;
void main() {
    texcoord0 = texCoord;
    gl_Position = modelViewProjectionMatrix * vertex;
}
