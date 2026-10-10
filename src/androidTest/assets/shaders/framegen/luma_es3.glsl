#version 300 es
// Rec. 709 luma into a one-channel target, so the motion search reads one
// byte per sample (the LUMA_INPUT variant of motion_coarse_es3.glsl).
precision highp float;
uniform sampler2D uTexSampler;
in vec2 vTexCoord;
out vec4 outLuma;

void main() {
    outLuma = vec4(dot(texture(uTexSampler, vTexCoord).rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.0, 1.0);
}
