#version 300 es
// Full-screen quad for Edendale's video enhancement passes (F.1). The texture
// coordinate follows the quad, so every pass keeps the frame's orientation.
in vec4 aFramePosition;
out vec2 vTexCoord;

void main() {
    gl_Position = aFramePosition;
    vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
