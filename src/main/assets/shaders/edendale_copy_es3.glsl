#version 300 es
// A filtered copy: the identity at the same size, a bilinear resize otherwise.
precision highp float;
uniform sampler2D uTexSampler;
in vec2 vTexCoord;
out vec4 outColor;

void main() {
    outColor = texture(uTexSampler, vTexCoord);
}
