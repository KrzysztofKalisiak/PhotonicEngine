// v55 diagnostic: isolate the current ReSTIR estimate from temporal
// accumulation while keeping baseline and exact Sable-local controls.
#define PH_RESTIR_STREAM_SPLIT_DIAGNOSTIC

//ph_required: uniform sampler2D restir_lighting;
#if PH_RESTIR_DENOISER_PASSES != 0
//ph_required: uniform sampler2D denoise_result;
#endif
#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_external_lighting;
//ph_required: uniform sampler2D restir_current_lighting;
#endif

#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_local_lighting;
#endif

#if defined PH_ENABLE_HANDHELD_LIGHT
//ph_required: uniform sampler2D other_handheld;
#endif

vec3 sample_photonics_direct(vec2 tex_coord) {
    #if PH_RESTIR_DENOISER_PASSES != 0
    vec3 result = texture(denoise_result, tex_coord).rgb;
    vec3 accumulated_result = texture(restir_lighting, tex_coord).rgb;
    #if defined PH_ENABLE_BLOCKLIGHT
    accumulated_result += texture(restir_external_lighting, tex_coord).rgb;
    #endif
    #else
    vec4 lighting = texture(restir_lighting, tex_coord);
    vec3 result = lighting.rgb / max(lighting.a, 1.0f);
    #if defined PH_ENABLE_BLOCKLIGHT
    vec4 external_lighting = texture(restir_external_lighting, tex_coord);
    result += external_lighting.rgb / max(external_lighting.a, 1.0f);
    #endif
    vec3 accumulated_result = result;
    #endif

    #if defined PH_ENABLE_BLOCKLIGHT
    vec3 current_result = texture(restir_current_lighting, tex_coord).rgb;
    vec3 local_lighting = texture(restir_local_lighting, tex_coord).rgb;

    #ifdef PH_RESTIR_STREAM_SPLIT_DIAGNOSTIC
    if (tex_coord.x < 0.25f)
        return vec3(0.0f);
    if (tex_coord.x < 0.5f)
        return current_result;
    if (tex_coord.x < 0.75f)
        return accumulated_result;
    return local_lighting;
    #else
    result += local_lighting;
    #endif
    #endif
    return result;
}

vec3 sample_photonics_handheld(vec2 tex_coord) {
#if defined PH_ENABLE_HANDHELD_LIGHT
    return texture(other_handheld, tex_coord).rgb;
#else
    return vec3(0.0f);
#endif
}
