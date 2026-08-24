//ph_required: uniform float near, far;

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#include "/photonics/rendering/restir/svgf.glsl"

layout(location = 0) out vec4 denoise_out;

vec3 ph_accumulated_lighting(ivec2 texel) {
    vec3 result = ph_restir_sanitize_radiance(
        texelFetch(restir_lighting, texel, 0).rgb
    );
#if defined PH_ENABLE_BLOCKLIGHT
    result += ph_restir_sanitize_radiance(
        texelFetch(restir_external_lighting, texel, 0).rgb
    );
#endif
    return ph_restir_sanitize_radiance(result);
}

bool ph_matches_denoise_receiver(
    ivec2 texel,
    bool center_has_direct_sample,
    int center_direct_light,
    bool center_direct_visible,
    float center_local_signature
) {
    FragData frag;
    frag_data_load(frag, texel);

    if (!frag_data_is_in_world(frag))
        return false;

    if (frag_data_is_hand(frag) != frag_is_hand
            || frag_data_sublevel_token(frag) != frag_data_sublevel_token(_frag_data))
        return false;

    vec3 center_normal = frag_data_geo_normal(_frag_data);
    vec3 sample_normal = frag_data_geo_normal(frag);
    if (dot(center_normal, sample_normal) < 0.95f)
        return false;

    vec3 position_delta = frag_data_player_pos(frag)
        - frag_data_player_pos(_frag_data);
    float plane_distance = max(
        abs(dot(position_delta, center_normal)),
        abs(dot(position_delta, sample_normal))
    );
    if (plane_distance > 0.075f)
        return false;

#if defined PH_ENABLE_BLOCKLIGHT
    if (frag_data_sublevel_token(_frag_data) != 0u) {
        vec2 sample_state;
        direct_history_load(sample_state, texel);
        if (any(isnan(sample_state)) || any(isinf(sample_state))
                || abs(sample_state.y - center_local_signature) > 0.5f)
            return false;
    } else if (center_has_direct_sample) {
        DirectReservoir sample_reservoir = direct_reservoir_empty();
        if (direct_reservoir_load(sample_reservoir, texel)
                && direct_reservoir_has_sample(sample_reservoir)
                && sample_reservoir.smple.light_index == center_direct_light) {
            vec2 sample_state;
            bool sample_visible = direct_history_load(sample_state, texel);
            if (sample_visible != center_direct_visible)
                return false;
        }
    }
#endif

    return true;
}

void main() {
    setup_frag_data(0);

    ivec2 max_texel = textureSize(restir_lighting, 0) - ivec2(1);

    // Firefly rejection
    vec3 center = ph_accumulated_lighting(frag_tex_coord);
    denoise_out = vec4(center, 10.0f);
    if (!frag_is_in_world) return;

#if defined PH_RESTIR_GI_VALIDITY_DIAGNOSTIC || defined PH_RESTIR_GI_VALIDITY_CHANNELS_DIAGNOSTIC
    // The r7 output is already a flat validity map. Do not blur its state
    // colors through SVGF, otherwise neighboring validity classes become
    // indistinguishable during the diagnostic run.
    denoise_out.a = 0.0f;
    return;
#endif

    if (!ph_restir_accumulation_is_valid(frag_tex_coord)) {
        // Do not let an unresolved r7 pixel enter the spatial filter. Its
        // zero RGB is a retry marker, not a measured black lighting value.
        denoise_out = vec4(0.0f);
        return;
    }

    bool center_has_direct_sample = false;
    int center_direct_light = -1;
    bool center_direct_visible = false;
    float center_local_signature = 0.0f;
#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir center_reservoir = direct_reservoir_empty();
    center_has_direct_sample = direct_reservoir_load(center_reservoir, frag_tex_coord)
        && direct_reservoir_has_sample(center_reservoir);
    vec2 center_state;
    center_direct_visible = direct_history_load(center_state, frag_tex_coord);
    center_local_signature = center_state.y;
    if (center_has_direct_sample) {
        center_direct_light = center_reservoir.smple.light_index;
    }
#endif

    vec3 maxNeighbour = vec3(0.0f);
    bool hasNeighbour = false;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) continue;

            ivec2 pos = clamp(frag_tex_coord + ivec2(x, y), ivec2(0), max_texel);
            if (!ph_restir_accumulation_is_valid(pos))
                continue;
            if (!ph_matches_denoise_receiver(
                    pos,
                    center_has_direct_sample,
                    center_direct_light,
                    center_direct_visible,
                    center_local_signature
            )) continue;

            vec3 color = ph_accumulated_lighting(pos);
            maxNeighbour = max(maxNeighbour, color);
            hasNeighbour = true;
        }
    }

    if (hasNeighbour)
        denoise_out.rgb = min(center, maxNeighbour);

    if (frag_is_hand) return;

    denoise_out.a = 0.0f;
    float weight_sum = 0.0f;

    for (int i = 0; i < 9; i++) {
        ivec2 p = clamp(frag_tex_coord + offset[i], ivec2(0), max_texel);
        if (!ph_matches_denoise_receiver(
                p,
                center_has_direct_sample,
                center_direct_light,
                center_direct_visible,
                center_local_signature
        )) continue;

        float variance = ph_restir_sanitize_variance(
            texelFetch(restir_lighting_variance, p, 0).z
        );
        float kernel_weight = kernel[i];

        denoise_out.a += variance * kernel_weight;
        weight_sum += kernel_weight;
    }

    if (weight_sum > 0.0f)
        denoise_out.a = ph_restir_sanitize_variance(
            denoise_out.a / weight_sum
        );
}
