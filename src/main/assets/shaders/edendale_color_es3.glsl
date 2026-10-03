#version 300 es
// Picture adjustments (F.2): a port of Apple's ColorAdjustment.metal, in its
// order — brightness, contrast about mid-gray, gamma, saturation with Rec. 709
// luma, a hue turn about the gray axis — then a clamp to 0…1.
precision highp float;
uniform sampler2D uTexSampler;
uniform float uBrightness;
uniform float uContrast;
uniform float uGamma;
uniform float uSaturation;
uniform float uHue;
out vec4 outColor;

vec3 applyHueRotation(vec3 rgb, float hueDegrees) {
    if (abs(hueDegrees) < 1e-3) {
        return rgb;
    }
    float angle = hueDegrees * (3.14159265358979323846 / 180.0);
    float cosA = cos(angle);
    float sinA = sin(angle);
    vec3 axis = vec3(0.57735026919);
    return rgb * cosA + cross(axis, rgb) * sinA + axis * dot(axis, rgb) * (1.0 - cosA);
}

void main() {
    vec4 pixel = texelFetch(uTexSampler, ivec2(gl_FragCoord.xy), 0);
    vec3 rgb = pixel.rgb;
    rgb *= uBrightness;
    rgb = (rgb - 0.5) * uContrast + 0.5;
    if (uGamma > 0.01 && abs(uGamma - 1.0) > 1e-3) {
        rgb = pow(max(rgb, vec3(0.0)), vec3(1.0 / uGamma));
    }
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(luma), rgb, uSaturation);
    rgb = applyHueRotation(rgb, uHue);
    outColor = vec4(clamp(rgb, 0.0, 1.0), pixel.a);
}
