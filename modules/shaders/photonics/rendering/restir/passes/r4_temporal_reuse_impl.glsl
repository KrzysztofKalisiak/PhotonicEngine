#define FRAG_USE_PLAYER_POS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
#endif

#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    // REPROJECTION
    vec3 previous_player_pos;
    vec3 expected_previous_normal;
    uint sublevel_token;
    vec2 uv = ph_reproject_frag_data(
        _frag_data,
        frag_tex_coord,
        frag_is_hand,
        get_taa_jitter(),
        previous_player_pos,
        expected_previous_normal,
        sublevel_token
    ).xy;

    if (any(lessThan(uv, vec2(0.0f))) || any(greaterThanEqual(uv, vec2(1.0f))))
        discard;

    ivec2 previous_size = textureSize(prev_ph_frag_data0, 0);
    ivec2 prev_texel = ivec2(uv * vec2(previous_size));

    FragData prev_frag;
    frag_data_load_previous(prev_frag, prev_texel);

    if (!frag_data_is_in_world(prev_frag)) discard;
    if (frag_data_sublevel_token(prev_frag) != sublevel_token) discard;

    if (!frag_is_bad_angle) {
        vec3 projected_player_pos = frag_data_player_pos(prev_frag);
        vec3 d = projected_player_pos - previous_player_pos;
        if (dot(d, d) >= PH_HISTORY_POSITION_ERROR_SQ) discard;
    }

    vec3 n = frag_data_geo_normal(prev_frag);
    if (dot(n, expected_previous_normal) < 0.99f) discard;

#if defined PH_ENABLE_BLOCKLIGHT
    // DIRECT TEMPORAL REUSE

    float direct_sample_weight = 0.0f;
    DirectReservoir direct_result = direct_reservoir_empty();
    DirectReservoir temp_direct = direct_reservoir_empty();
    DirectReservoir direct_fallback = direct_reservoir_empty();

    // load freshly sampled reservoir
    direct_reservoir_load(temp_direct, frag_tex_coord);
    if (direct_reservoir_has_sample(temp_direct)) {
        direct_fallback = temp_direct;
        direct_fallback.weight = 0.0f;
    }
    direct_reservoir_merge_current_batch(
        direct_result,
        temp_direct,
        direct_sample_weight
    );

    // Only reuse a reservoir that survived the final visibility check in the
    // previous frame. A zero-weight reservoir retains identity for the
    // accumulation pass, but contributes no energy here.
    vec2 direct_history_state;
    if (direct_history_load_previous(direct_history_state, prev_texel)
            && direct_reservoir_load_previous(temp_direct, prev_texel)) {
        direct_reservoir_validate_visiblity(temp_direct, frag_rt_pos, frag_geo_normal);
        if (direct_reservoir_is_reusable(temp_direct)) {
            temp_direct.total_samples = min(max_direct_temporal_samples, temp_direct.total_samples);
            direct_reservoir_merge(direct_result, temp_direct, direct_sample_weight);
        }
    }

    // Preserve the current rejected sample when no visible candidate won. Its
    // identity lets accumulation distinguish a real visibility transition
    // from ordinary stochastic representative-light churn.
    if (direct_reservoir_is_reusable(direct_result))
        direct_reservoir_finalize_weight(direct_result, direct_sample_weight);
    if (!direct_reservoir_is_reusable(direct_result)
            && direct_reservoir_has_sample(direct_fallback)) {
        direct_result = direct_fallback;
        direct_reservoir_clamp_samples(direct_result);
    }

    direct_reservoir_encode(direct_result, di_reservoir_0);

#endif

#if defined PH_ENABLE_RESTIR_GI
    // INDIRECT TEMPORAL REUSE

    float indirect_sample_weight = 0.0f;
    IndirectReservoir indirect_result = indirect_reservoir_empty();
    IndirectReservoir temp_indirect = indirect_reservoir_empty();

    // Indirect samples do not yet encode a Sable hit domain or local-space hit
    // point. Reuse world history only; moving receivers keep their current
    // frame proposal until that representation exists.
    if (sublevel_token == 0u
            && !frag_is_hand
            && !frag_data_is_hand(prev_frag)
            && indirect_reservoir_load_previous(temp_indirect, prev_texel)) {
        temp_indirect.total_samples = min(max_indirect_temporal_samples, temp_indirect.total_samples);

        // Previous fragment positions are camera-relative. Convert the source
        // receiver into the current camera space without changing the local
        // direct-light reprojection contract.
        FragData shift_source_frag = prev_frag;
        shift_source_frag.data0.xyz -= cameraPosition - previousCameraPosition;

        float shift = indirect_sample_compute_shift(
            temp_indirect.smple,
            _frag_data,
            shift_source_frag
        );
        if (shift > 0.0f && shift < 1.2f) {
            indirect_reservoir_merge(
                indirect_result,
                temp_indirect,
                shift,
                indirect_sample_weight
            );
        }
    }

    indirect_reservoir_load(temp_indirect, frag_tex_coord);
    indirect_reservoir_merge_current_batch(
        indirect_result,
        temp_indirect,
        1.0f,
        indirect_sample_weight
    );

    // write resulting reservoir
    indirect_reservoir_finalize_weight(indirect_result, indirect_sample_weight);
    indirect_reservoir_encode(indirect_result, gi_reservoir_0, gi_reservoir_1);
#endif
}
