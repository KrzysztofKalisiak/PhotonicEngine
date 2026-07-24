#version 430

#if !defined PH_ENABLE_RESTIR_GI && defined PH_ENABLE_GI && defined PH_RESTIR_COMBINED_GI
#define PH_ENABLE_RESTIR_GI
#endif

#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_direct_reservoirs0;

layout(location = 0) out vec3 spatial_reservoir_out;
#define INDIRECT_SPATIAL_INPUT_0 1
#define INDIRECT_SPATIAL_INPUT_1 2
#else
#define INDIRECT_SPATIAL_INPUT_0 0
#define INDIRECT_SPATIAL_INPUT_1 1
#endif

#if defined PH_ENABLE_RESTIR_GI
//ph_required: uniform sampler2D restir_indirect_reservoirs0;
//ph_required: uniform usampler2D restir_indirect_reservoirs1;

layout(location = INDIRECT_SPATIAL_INPUT_0) out vec4 indirect_spatial_reservoir0_out;
layout(location = INDIRECT_SPATIAL_INPUT_1) out uvec3 indirect_spatial_reservoir1_out;
#endif

void main() {
#if defined PH_ENABLE_BLOCKLIGHT
    spatial_reservoir_out = texelFetch(
        restir_direct_reservoirs0,
        ivec2(gl_FragCoord.xy),
        0
    ).rgb;
#endif

#if defined PH_ENABLE_RESTIR_GI
    ivec2 texel = ivec2(gl_FragCoord.xy);
    indirect_spatial_reservoir0_out = texelFetch(
        restir_indirect_reservoirs0,
        texel,
        0
    );
    indirect_spatial_reservoir1_out = texelFetch(
        restir_indirect_reservoirs1,
        texel,
        0
    ).rgb;
#endif
}
