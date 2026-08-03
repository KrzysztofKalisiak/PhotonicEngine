#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC && !defined PH_RESTIR_SOURCE_HISTORY_COMPOSE_PASS
//ph_required: uniform sampler2D restir_source_history_diagnostic;

vec3 sample_photonics_direct(vec2 tex_coord) {
    return texture(restir_source_history_diagnostic, tex_coord).rgb;
}

float ph_sample_photonics_source_variance(vec2 tex_coord) {
    return 0.0f;
}

vec3 sample_photonics_handheld(vec2 tex_coord) {
    return vec3(0.0f);
}
#else

#if !defined PH_RESTIR_SPLIT_GI || defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_lighting;
#endif
#if defined PH_TEMPORAL_UPSCALER && !defined PH_TEMPORAL_UPSCALER_SOURCE_PASS
//ph_required: uniform sampler2D photonics_temporal_lighting;
#endif
#if defined PH_TEMPORAL_UPSCALER_SPLIT_SCREEN && !defined PH_TEMPORAL_UPSCALER_SOURCE_PASS
//ph_required: uniform sampler2D photonics_temporal_source;
//ph_required: uniform sampler2D photonics_temporal_diagnostic;
#endif
#if PH_RESTIR_DENOISER_PASSES != 0
//ph_required: uniform sampler2D denoise_result;
#endif
#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_external_lighting;
#endif

#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_local_lighting;
#endif

#if defined PH_ENABLE_HANDHELD_LIGHT && !defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC
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

#if defined PH_TEMPORAL_UPSCALER_SPLIT_SCREEN && !defined PH_TEMPORAL_UPSCALER_SOURCE_PASS
ivec2 ph_temporal_diagnostic_texel(ivec2 image_size, vec2 tex_coord) {
    return clamp(
        ivec2(tex_coord * vec2(image_size)),
        ivec2(0),
        image_size - ivec2(1)
    );
}

vec3 ph_temporal_diagnostic_state(vec4 diagnostic) {
    uint code = uint(floor(max(diagnostic.a, 0.0f) + 0.5f));
    uint mode = code & 7u;
    uint limiter_bin = (code >> 3u) & 15u;
    uint confidence_bin = (code >> 7u) & 7u;
    bool limiter_changed = (code & (1u << 10u)) != 0u;

    if (mode == 0u)
        return vec3(0.0f);
    if (limiter_changed)
        return vec3(1.0f);

    vec3 state_color;
    if (mode == 1u)
        state_color = vec3(0.05f, 0.20f, 1.00f);
    else if (mode == 2u)
        state_color = vec3(1.00f, 0.05f, 0.02f);
    else if (mode == 3u)
        state_color = vec3(1.00f, 0.35f, 0.02f);
    else if (mode == 4u)
        state_color = vec3(1.00f, 0.90f, 0.02f);
    else if (mode == 6u)
        state_color = vec3(0.02f, 0.95f, 1.00f);
    else
        state_color = vec3(0.05f, 1.00f, 0.15f);

    float confidence = float(confidence_bin) / 7.0f;
    state_color *= mix(0.20f, 1.0f, confidence);

    float limiter_strength = float(limiter_bin) / 15.0f;
    return mix(
        state_color,
        vec3(1.0f, 0.0f, 1.0f),
        0.80f * limiter_strength
    );
}

bool ph_temporal_diagnostic_is_marker(vec2 tex_coord) {
    vec2 output_size = vec2(textureSize(photonics_temporal_lighting, 0));
    vec2 half_size = floor(output_size * 0.5f);
    bool right = tex_coord.x >= 0.5f;
    bool top = tex_coord.y >= 0.5f;
    vec2 pixel = tex_coord * output_size;
    vec2 quadrant_origin = vec2(
        right ? half_size.x : 0.0f,
        top ? half_size.y : 0.0f
    );
    vec2 quadrant_size = vec2(
        right ? output_size.x - half_size.x : half_size.x,
        top ? output_size.y - half_size.y : half_size.y
    );
    vec2 local_pixel = pixel - quadrant_origin;

    return local_pixel.x >= 3.0f
        && local_pixel.x < 9.0f
        && local_pixel.y >= quadrant_size.y - 9.0f
        && local_pixel.y < quadrant_size.y - 3.0f;
}

vec3 ph_sample_temporal_diagnostic(vec2 tex_coord) {
    bool right = tex_coord.x >= 0.5f;
    bool top = tex_coord.y >= 0.5f;

    if (ph_temporal_diagnostic_is_marker(tex_coord)) {
        if (top && !right)
            return vec3(0.0f, 1.0f, 1.0f);
        if (top)
            return vec3(0.10f, 0.35f, 1.0f);
        if (!right)
            return vec3(1.0f, 0.85f, 0.0f);
        return vec3(1.0f);
    }

    // Keep the original full-screen UV in every quadrant. Only the selected
    // signal changes, so it stays aligned with shaderpack geometry and albedo.
    if (top && !right) {
        ivec2 source_texel = ph_temporal_diagnostic_texel(
            textureSize(photonics_temporal_source, 0),
            tex_coord
        );
        return texelFetch(photonics_temporal_source, source_texel, 0).rgb;
    }

    ivec2 diagnostic_texel = ph_temporal_diagnostic_texel(
        textureSize(photonics_temporal_diagnostic, 0),
        tex_coord
    );
    vec4 diagnostic = texelFetch(
        photonics_temporal_diagnostic,
        diagnostic_texel,
        0
    );
    if (top)
        return diagnostic.rgb;
    if (!right)
        return ph_temporal_diagnostic_state(diagnostic);

    return texture(photonics_temporal_lighting, tex_coord).rgb;
}
#endif

#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC && defined PH_RESTIR_SOURCE_HISTORY_COMPOSE_PASS
#include "/photonics/uniforms.glsl"

bool ph_restir_source_history_is_marker(vec2 tex_coord) {
    vec2 output_size = vec2(textureSize(restir_lighting, 0));
    vec2 half_size = floor(output_size * 0.5f);
    bool right = tex_coord.x >= 0.5f;
    bool top = tex_coord.y >= 0.5f;
    vec2 pixel = tex_coord * output_size;
    vec2 quadrant_origin = vec2(
        right ? half_size.x : 0.0f,
        top ? half_size.y : 0.0f
    );
    vec2 quadrant_size = vec2(
        right ? output_size.x - half_size.x : half_size.x,
        top ? output_size.y - half_size.y : half_size.y
    );
    vec2 local_pixel = pixel - quadrant_origin;

    return local_pixel.x >= 3.0f
        && local_pixel.x < 9.0f
        && local_pixel.y >= quadrant_size.y - 9.0f
        && local_pixel.y < quadrant_size.y - 3.0f;
}

vec3 ph_sample_accumulated_direct(vec2 tex_coord) {
    vec3 result = texture(restir_lighting, tex_coord).rgb;
    result += texture(restir_external_lighting, tex_coord).rgb;
    return result;
}

vec3 ph_sample_denoised_direct(vec2 tex_coord) {
#if PH_RESTIR_DENOISER_PASSES != 0
    return texture(denoise_result, tex_coord).rgb;
#else
    return ph_sample_accumulated_direct(tex_coord);
#endif
}

#if defined PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC
float ph_decode_restir_estimator_luminance(float encoded) {
    if (isnan(encoded) || isinf(encoded)) return 0.0f;
    return min(exp2(clamp(encoded, 0.0f, 16.0f)) - 1.0f, 65504.0f);
}

void ph_decode_restir_proposal_metadata(
    float encoded,
    out int stratum,
    out float expansion
) {
    if (isnan(encoded) || isinf(encoded) || encoded <= 0.0f) {
        stratum = 0;
        expansion = 0.0f;
        return;
    }

    stratum = int(floor(encoded + 0.0001f));
    float encoded_expansion = clamp(encoded - float(stratum), 0.0f, 0.999f);
    expansion = exp2(encoded_expansion * 16.0f) - 1.0f;
}

vec3 ph_restir_proposal_stratum_color(int stratum) {
    if (stratum == 1) return vec3(0.0f, 1.0f, 1.0f);
    if (stratum == 2) return vec3(0.2f, 1.0f, 0.2f);
    if (stratum == 3) return vec3(1.0f, 0.85f, 0.0f);
    if (stratum == 4) return vec3(1.0f, 0.25f, 0.05f);
    if (stratum == 5) return vec3(0.15f, 0.35f, 1.0f);
    if (stratum == 6) return vec3(1.0f, 0.0f, 1.0f);
    return vec3(0.0f);
}

bool ph_restir_estimator_revision_marker(vec2 tex_coord, out vec3 color) {
    vec2 output_size = vec2(textureSize(restir_lighting, 0));
    vec2 pixel = tex_coord * output_size;
    float marker_width = 36.0f;
    float marker_start = floor(0.5f * (output_size.x - marker_width));
    if (pixel.x < marker_start || pixel.x >= marker_start + marker_width
            || pixel.y < output_size.y - 9.0f
            || pixel.y >= output_size.y - 3.0f) {
        color = vec3(0.0f);
        return false;
    }

    int marker_index = clamp(int((pixel.x - marker_start) / 6.0f), 0, 5);
    if (marker_index == 0) {
        color = ph_world_ready != 0
            ? vec3(0.0f, 2.0f, 0.0f)
            : vec3(2.0f, 0.0f, 0.0f);
        return true;
    }

    int revision_bit = (ph_world_revision_slot >> (marker_index - 1)) & 1;
    color = revision_bit != 0 ? vec3(2.0f) : vec3(0.03f);
    return true;
}
#endif

vec3 ph_sample_restir_source_history_diagnostic(vec2 tex_coord) {
#if defined PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC
    vec3 marker_color;
    if (ph_restir_estimator_revision_marker(tex_coord, marker_color))
        return marker_color;

    vec3 estimator_signals = texture(restir_local_lighting, tex_coord).rgb;
    float unshadowed = ph_decode_restir_estimator_luminance(estimator_signals.r);
    float visible = ph_decode_restir_estimator_luminance(estimator_signals.g);
    float rejected = max(unshadowed - visible, 0.0f);
    float visibility_ratio = unshadowed > 0.000001f
        ? clamp(visible / unshadowed, 0.0f, 1.0f)
        : 0.0f;
    float unshadowed_signal = clamp(estimator_signals.r / 8.0f, 0.0f, 1.0f);
    float visible_signal = clamp(estimator_signals.g / 8.0f, 0.0f, 1.0f);

    if (visible > unshadowed + max(0.0001f, unshadowed * 0.001f))
        return vec3(2.0f, 0.0f, 2.0f);

#if defined PH_RESTIR_DIRECT_ESTIMATOR_RANK_DIAGNOSTIC
    int proposal_stratum;
    float proposal_expansion;
    ph_decode_restir_proposal_metadata(
        estimator_signals.b,
        proposal_stratum,
        proposal_expansion
    );
    float expansion_signal = clamp(
        log2(1.0f + proposal_expansion) / 12.0f,
        0.0f,
        1.0f
    );
    float strength = (0.15f + 0.85f * unshadowed_signal)
        * (0.4f + 0.6f * expansion_signal);
    return ph_restir_proposal_stratum_color(proposal_stratum) * strength;
#else
    float rejected_signal = clamp(log2(1.0f + rejected) / 8.0f, 0.0f, 1.0f);
    return vec3(
        rejected_signal,
        visible_signal,
        visibility_ratio * unshadowed_signal
    );
#endif
#else
    bool right = tex_coord.x >= 0.5f;
    bool top = tex_coord.y >= 0.5f;

    if (ph_restir_source_history_is_marker(tex_coord)) {
        if (top && !right)
            return vec3(0.0f, 1.0f, 1.0f);
        if (top)
            return vec3(0.10f, 0.35f, 1.0f);
        if (!right)
            return vec3(1.0f, 0.85f, 0.0f);
        return vec3(1.0f, 0.0f, 1.0f);
    }

    // Keep full-screen UVs so every signal remains aligned with the shader
    // pack's geometry and albedo in its own quadrant.
    if (top && !right)
        return texture(restir_local_lighting, tex_coord).rgb;
    if (top)
        return ph_sample_accumulated_direct(tex_coord);
    if (!right)
        return ph_sample_denoised_direct(tex_coord);
    return ph_sample_split_gi(tex_coord);
#endif
}
#endif

vec3 sample_photonics_direct(vec2 tex_coord) {
#if defined PH_TEMPORAL_UPSCALER && !defined PH_TEMPORAL_UPSCALER_SOURCE_PASS
    #if defined PH_TEMPORAL_UPSCALER_SPLIT_SCREEN
    return ph_sample_temporal_diagnostic(tex_coord);
    #else
    return texture(photonics_temporal_lighting, tex_coord).rgb;
    #endif
#else
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
#endif
}

float ph_sample_photonics_source_variance(vec2 tex_coord) {
#if defined PH_TEMPORAL_UPSCALER && !defined PH_TEMPORAL_UPSCALER_SOURCE_PASS
    return 0.0f;
#else
    float variance = 0.0f;
#if defined PH_RESTIR_SPLIT_GI
    #if defined PH_ENABLE_BLOCKLIGHT
        #if PH_RESTIR_DENOISER_PASSES != 0
        variance = max(texture(denoise_result, tex_coord).a, 0.0f);
        #else
        variance = 1.0f / max(texture(restir_lighting, tex_coord).a, 1.0f);
        #endif
    #endif

    #if PH_RESTIR_GI_DENOISER_PASSES != 0
    variance += max(texture(restir_gi_denoise_result, tex_coord).a, 0.0f);
    #else
    variance += 1.0f / max(texture(restir_gi_lighting, tex_coord).a, 1.0f);
    #endif
#else
    #if PH_RESTIR_DENOISER_PASSES != 0
    variance = max(texture(denoise_result, tex_coord).a, 0.0f);
    #else
    variance = 1.0f / max(texture(restir_lighting, tex_coord).a, 1.0f);
    #endif
#endif
    return variance;
#endif
}

vec3 sample_photonics_handheld(vec2 tex_coord) {
#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC
    return vec3(0.0f);
#elif defined PH_ENABLE_HANDHELD_LIGHT
    return texture(other_handheld, tex_coord).rgb;
#else
    return vec3(0.0f);
#endif
}

#endif
