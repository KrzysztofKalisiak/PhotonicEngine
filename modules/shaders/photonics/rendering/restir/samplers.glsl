//ph_required: uniform sampler2D restir_lighting;
#if PH_RESTIR_DENOISER_PASSES != 0
//ph_required: uniform sampler2D denoise_result;
#endif
#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_external_lighting;
#endif

#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_local_lighting;
#endif

#if defined PH_ENABLE_HANDHELD_LIGHT
//ph_required: uniform sampler2D other_handheld;
#endif

#if defined PH_RESTIR_SPLIT_GI
//ph_required: uniform sampler2D ph_frag_data0;
//ph_required: uniform sampler2D ph_frag_data1;
//ph_required: uniform sampler2D ph_gi_frag_data0;
//ph_required: uniform sampler2D ph_gi_frag_data1;
#if PH_RESTIR_GI_DENOISER_PASSES != 0
//ph_required: uniform sampler2D restir_gi_denoise_result;
#else
//ph_required: uniform sampler2D restir_gi_lighting;
#endif

#include "/photonics/utility/normal_encoding.glsl"

float ph_unpack_snorm_16(uint packed_component) {
    int signed_component = int(packed_component & 0xffffu);
    if (signed_component >= 32768)
        signed_component -= 65536;

    return clamp(float(signed_component) / 32767.0f, -1.0f, 1.0f);
}

vec2 ph_unpack_snorm_2x16(uint packed_value) {
    return vec2(
        ph_unpack_snorm_16(packed_value),
        ph_unpack_snorm_16(packed_value >> 16u)
    );
}

bool ph_gi_upsample_matches(
    vec4 center_data0,
    uvec4 center_data1,
    vec4 sample_data0,
    uvec4 sample_data1
) {
    const uint in_world_bit = 1u;
    const uint hand_bit = 1u << 2;
    const uint sublevel_token_mask = 0xffff00u;
    const uint receiver_identity_mask = in_world_bit
        | hand_bit
        | sublevel_token_mask;

    if ((center_data1.w & in_world_bit) == 0u
            || (sample_data1.w & in_world_bit) == 0u
            || (center_data1.w & receiver_identity_mask)
                != (sample_data1.w & receiver_identity_mask))
        return false;

    vec3 center_normal = ph_decode_normal(
        ph_unpack_snorm_2x16(center_data1.y)
    );
    vec3 sample_normal = ph_decode_normal(
        ph_unpack_snorm_2x16(sample_data1.y)
    );
    if (dot(center_normal, sample_normal) < 0.9f)
        return false;

    vec3 position_delta = sample_data0.xyz - center_data0.xyz;
    if (dot(position_delta, position_delta) > 4.0f)
        return false;

    float plane_distance = max(
        abs(dot(position_delta, center_normal)),
        abs(dot(position_delta, sample_normal))
    );
    return plane_distance <= 0.15f;
}

vec3 ph_gi_radiance_at(ivec2 texel) {
#if PH_RESTIR_GI_DENOISER_PASSES != 0
    return texelFetch(restir_gi_denoise_result, texel, 0).rgb;
#else
    vec4 lighting = texelFetch(restir_gi_lighting, texel, 0);
    return lighting.rgb / max(lighting.a, 1.0f);
#endif
}

vec3 ph_gi_nearest_matching_radiance(
    vec4 center_data0,
    uvec4 center_data1,
    vec2 gi_position,
    ivec2 gi_size,
    out bool found
) {
    ivec2 nearest_texel = ivec2(floor(gi_position + vec2(0.5f)));
    float best_distance_sq = 1e30f;
    vec3 best_radiance = vec3(0.0f);
    found = false;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 texel = clamp(
                nearest_texel + ivec2(x, y),
                ivec2(0),
                gi_size - ivec2(1)
            );
            vec4 sample_data0 = texelFetch(ph_gi_frag_data0, texel, 0);
            uvec4 sample_data1 = floatBitsToUint(
                texelFetch(ph_gi_frag_data1, texel, 0)
            );
            if (!ph_gi_upsample_matches(
                    center_data0,
                    center_data1,
                    sample_data0,
                    sample_data1
            )) continue;

            vec2 texel_delta = vec2(texel) - gi_position;
            float distance_sq = dot(texel_delta, texel_delta);
            if (distance_sq >= best_distance_sq)
                continue;

            best_distance_sq = distance_sq;
            best_radiance = ph_gi_radiance_at(texel);
            found = true;
        }
    }

    return best_radiance;
}

vec3 ph_sample_split_gi(vec2 tex_coord) {
    ivec2 direct_size = textureSize(ph_frag_data0, 0);
    ivec2 direct_texel = clamp(
        ivec2(tex_coord * vec2(direct_size)),
        ivec2(0),
        direct_size - ivec2(1)
    );
    vec4 center_data0 = texelFetch(ph_frag_data0, direct_texel, 0);
    uvec4 center_data1 = floatBitsToUint(
        texelFetch(ph_frag_data1, direct_texel, 0)
    );

    ivec2 gi_size = textureSize(ph_gi_frag_data0, 0);
    vec2 gi_position = tex_coord * vec2(gi_size) - 0.5f;
    ivec2 gi_base = ivec2(floor(gi_position));
    vec2 gi_fraction = fract(gi_position);

    vec3 result = vec3(0.0f);
    float weight_sum = 0.0f;
    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 offset = ivec2(x, y);
            ivec2 texel = clamp(
                gi_base + offset,
                ivec2(0),
                gi_size - ivec2(1)
            );
            vec4 sample_data0 = texelFetch(ph_gi_frag_data0, texel, 0);
            uvec4 sample_data1 = floatBitsToUint(
                texelFetch(ph_gi_frag_data1, texel, 0)
            );
            if (!ph_gi_upsample_matches(
                    center_data0,
                    center_data1,
                    sample_data0,
                    sample_data1
            )) continue;

            vec2 axis_weight = mix(
                vec2(1.0f) - gi_fraction,
                gi_fraction,
                vec2(offset)
            );
            float weight = axis_weight.x * axis_weight.y;
            result += ph_gi_radiance_at(texel) * weight;
            weight_sum += weight;
        }
    }

    // A lone corner tap can have an arbitrarily small bilinear weight. As the
    // camera moves, normalizing that tap or dropping to zero makes low-rate GI
    // pulse at silhouettes. Search only those unsupported pixels for the
    // nearest receiver-compatible GI sample; the common path remains four taps.
    if (weight_sum > 0.01f)
        return result / weight_sum;

    bool fallback_found;
    vec3 fallback = ph_gi_nearest_matching_radiance(
        center_data0,
        center_data1,
        gi_position,
        gi_size,
        fallback_found
    );
    return fallback_found ? fallback : vec3(0.0f);
}
#endif

vec3 sample_photonics_direct(vec2 tex_coord) {
#if defined PH_RESTIR_SPLIT_GI
    vec3 result = vec3(0.0f);
    #if defined PH_ENABLE_BLOCKLIGHT
    #if PH_RESTIR_DENOISER_PASSES != 0
    result = texture(denoise_result, tex_coord).rgb;
    #else
    vec4 lighting = texture(restir_lighting, tex_coord);
    result = lighting.rgb / max(lighting.a, 1.0f);
    vec4 external_lighting = texture(restir_external_lighting, tex_coord);
    result += external_lighting.rgb / max(external_lighting.a, 1.0f);
    #endif
    #endif
    result += ph_sample_split_gi(tex_coord);
#else
    #if PH_RESTIR_DENOISER_PASSES != 0
    vec3 result = texture(denoise_result, tex_coord).rgb;
    #else
    vec4 lighting = texture(restir_lighting, tex_coord);
    vec3 result = lighting.rgb / max(lighting.a, 1.0f);
    #if defined PH_ENABLE_BLOCKLIGHT
    vec4 external_lighting = texture(restir_external_lighting, tex_coord);
    result += external_lighting.rgb / max(external_lighting.a, 1.0f);
    #endif
    #endif
#endif

    #if defined PH_ENABLE_BLOCKLIGHT
    result += texture(restir_local_lighting, tex_coord).rgb;
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
