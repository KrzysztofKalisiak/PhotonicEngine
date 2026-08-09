#version 430

#define PH_TEMPORAL_UPSCALER_SOURCE_PASS

#if !defined PH_RESTIR_SPLIT_GI
//ph_required: uniform sampler2D ph_frag_data0;
#endif

#include "/photonics/rendering/restir/samplers.glsl"

layout(location = 0) out vec4 source_lighting_out;

void main() {
    vec2 source_size = vec2(textureSize(ph_frag_data0, 0));
    vec2 tex_coord = gl_FragCoord.xy / source_size;

    vec3 radiance = sample_photonics_direct(tex_coord);
    float variance = ph_sample_photonics_source_variance(tex_coord);

    if (any(isnan(radiance)) || any(isinf(radiance)))
        radiance = vec3(0.0f);
    if (isnan(variance) || isinf(variance))
        variance = 1.0f;

    source_lighting_out = vec4(
        clamp(radiance, vec3(0.0f), vec3(65504.0f)),
        clamp(variance, 0.0f, 65504.0f)
    );
}
