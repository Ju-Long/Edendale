#version 300 es
// Temporal denoise (F.5): a port of Apple's TemporalDenoise.metal. Blends the
// current frame with the history where they barely differ; motion (a
// difference beyond the threshold band) keeps the current frame, so edges
// don't ghost.
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uHistorySampler;
uniform float uStrength;
uniform float uMotionThreshold;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 current = texelFetch(uTexSampler, p, 0);
    vec4 history = texelFetch(uHistorySampler, p, 0);
    if (uStrength <= 0.0 || uMotionThreshold <= 0.0) {
        outColor = current;
        return;
    }
    vec3 diff = abs(current.rgb - history.rgb);
    float lumaDiff = dot(diff, vec3(0.299, 0.587, 0.114));
    float maxDiff = max(max(diff.r, diff.g), diff.b);
    float metric = (lumaDiff + maxDiff) * 0.5;
    float motion = smoothstep(uMotionThreshold * 0.5, uMotionThreshold * 1.5, metric);
    float maxHistoryBlend = 0.85 * clamp(uStrength, 0.0, 1.0);
    float historyWeight = (1.0 - motion) * maxHistoryBlend;
    outColor = vec4(mix(current.rgb, history.rgb, historyWeight), current.a);
}
