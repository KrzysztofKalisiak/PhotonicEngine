#version 430

//ph_required: uniform sampler2D restir_lighting;
#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_external_lighting;
#endif

layout(location = 0) out vec4 current_lighting;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    current_lighting = texelFetch(restir_lighting, texel, 0);
#if defined PH_ENABLE_BLOCKLIGHT
    current_lighting.rgb += texelFetch(
        restir_external_lighting,
        texel,
        0
    ).rgb;
#endif
    current_lighting.a = 1.0f;
}
