#define FRAG_USE_PLAYER_POS
#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
#endif

#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

const float ph_spatial_max_receiver_distance_sq = 0.5625f;
const float ph_spatial_max_plane_distance = 0.05f;
const float ph_spatial_min_normal_alignment = 0.99f;
const int ph_indirect_spatial_candidate_attempts = 8;
// The 1.21.1 property layer already clamps this to one. Keep the shader-side
// ray budget explicit so a pack override cannot multiply GI validation cost.
const int ph_indirect_spatial_validation_budget = 1;

bool ph_spatial_is_finite(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

ivec2 ph_spatial_next_sample_texel(
    inout uint rnd_state,
    float reuse_radius
) {
    float angle = 6.28318530718f * ph_rand_next_float(rnd_state);
    float sample_radius = max(
        1.0f,
        sqrt(ph_rand_next_float(rnd_state)) * reuse_radius
    );
    ivec2 sample_offset = ivec2(round(
        vec2(cos(angle), sin(angle)) * sample_radius
    ));
    return frag_tex_coord + sample_offset;
}

bool ph_spatial_sample_texel_is_valid(
    ivec2 sample_texel,
    ivec2 texture_size
) {
    return !all(equal(sample_texel, frag_tex_coord))
        && !any(lessThan(sample_texel, ivec2(0)))
        && !any(greaterThanEqual(sample_texel, texture_size));
}

int ph_spatial_continuity_lane(ivec2 texture_size) {
#ifdef PH_TEMPORAL_UPSCALER_SOURCE_VALIDATION_LANES
    float normalized_x = (float(frag_tex_coord.x) + 0.5f)
        / float(max(texture_size.x, 1));
    return clamp(int(floor(normalized_x * 4.0f)), 0, 3);
#else
    return 3;
#endif
}

bool ph_spatial_current_receiver_can_reuse(bool continuous_spatial) {
    if (frag_is_hand
            || (!continuous_spatial && frag_is_bad_angle)
            || !ph_spatial_is_finite(frag_geo_normal)
            || !ph_spatial_is_finite(frag_player_pos))
        return false;

    int receiver_slot = frag_data_sublevel_slot(_frag_data);
    uint receiver_token = frag_data_sublevel_token(_frag_data);
    if (receiver_token == 0u)
        return receiver_slot < 0;

    // Spatial candidates are all from the current frame. Rigid world motion
    // cannot invalidate two receivers carrying the same live Sable identity;
    // the token, normal and surface-plane checks below still reject unrelated
    // sublevels and geometry boundaries.
    return receiver_slot >= 0
        && receiver_slot < ph_sable_sublevel_count
        && receiver_token == ph_sable_identity_token(receiver_slot);
}

bool ph_spatial_receiver_matches(
    FragData sample_frag,
    bool continuous_spatial
) {
    bool sample_bad_angle = frag_data_is_bad_angle(sample_frag);
    if (!frag_data_is_in_world(sample_frag)
            || frag_data_is_hand(sample_frag)
            || (!continuous_spatial && sample_bad_angle))
        return false;

    if (frag_data_sublevel_slot(sample_frag)
                != frag_data_sublevel_slot(_frag_data)
            || frag_data_sublevel_token(sample_frag)
                != frag_data_sublevel_token(_frag_data))
        return false;

    vec3 sample_normal = frag_data_geo_normal(sample_frag);
    vec3 sample_position = frag_data_player_pos(sample_frag);
    if (!ph_spatial_is_finite(sample_normal)
            || !ph_spatial_is_finite(sample_position))
        return false;

    if (dot(sample_normal, frag_geo_normal) < ph_spatial_min_normal_alignment)
        return false;

    vec3 position_delta = sample_position - frag_player_pos;
    float plane_distance = max(
        abs(dot(position_delta, frag_geo_normal)),
        abs(dot(position_delta, sample_normal))
    );
    if (!continuous_spatial) {
        if (dot(position_delta, position_delta)
                >= ph_spatial_max_receiver_distance_sq)
            return false;

        return plane_distance <= ph_spatial_max_plane_distance;
    }

    // A bounded screen-space neighbor can be many blocks away laterally on a
    // grazing plane. Keep receiver-plane validation, including RGBA16F
    // position precision, but do not impose the invalid lateral cutoff.
    return plane_distance <= ph_restir_precision_plane_tolerance(
        ph_spatial_max_plane_distance,
        sample_position,
        frag_player_pos,
        sample_normal,
        frag_geo_normal
    );
}

#if defined PH_ENABLE_BLOCKLIGHT
#if PH_RESTIR_SPATIAL_REUSE_SAMPLES > 0
//ph_required: uniform sampler2D restir_direct_spatial_input;

void ph_spatial_direct_reservoir_load(
    out DirectReservoir reservoir,
    ivec2 texel
) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(restir_direct_spatial_input, texel, 0).rgb
    );
    if (direct_reservoir_is_nan(reservoir))
        reservoir = direct_reservoir_empty();
}

bool ph_spatial_direct_light_matches_receiver(DirectReservoir reservoir) {
    int light_index = reservoir.smple.light_index;
    if (light_index < 0 || light_index >= light_list_size)
        return false;

    uint receiver_token = frag_data_sublevel_token(_frag_data);
    if (receiver_token == 0u) {
        // Receiver matching remains world-only. Any current-generation light
        // can therefore be reused here, including moving external Sable lights.
        return true;
    }

    // Same-domain lights are evaluated exactly in r6. Every reservoir built
    // for this receiver therefore estimates the same external-only target.
    return !direct_sample_matches_receiver_domain(
        reservoir.smple,
        receiver_token
    );
}

bool ph_spatial_direct_reservoir_merge(
    inout DirectReservoir result,
    DirectReservoir other,
    inout float sample_weight
) {
    if (direct_sample_matches_receiver_domain(
            other.smple,
            frag_data_sublevel_token(_frag_data)
    )) return false;

    float effective_samples = min(
        other.total_samples,
        float(PH_RESTIR_INITIAL_SAMPLES)
    );
    if (effective_samples <= 0.0f) return false;

    other.total_samples = effective_samples;
    direct_reservoir_validate_visiblity(
        other,
        frag_rt_pos,
        frag_geo_normal
    );

    // This is now a zero-contribution batch at the current receiver, so its
    // effective M belongs in this receiver's denominator exactly once.
    if (!direct_reservoir_is_reusable(other)) {
        result.total_samples += effective_samples;
        return false;
    }

    float current_target = direct_sample_get_weight(
        other.smple,
        frag_rt_pos,
        frag_geo_normal,
        frag_tex_normal
    );
    if (current_target <= 0.0f
            || isnan(current_target)
            || isinf(current_target)) {
        result.total_samples += effective_samples;
        return false;
    }

    float candidate_weight = current_target
        * other.weight
        * effective_samples;
    if (candidate_weight <= 0.0f
            || isnan(candidate_weight)
            || isinf(candidate_weight)) {
        result.total_samples += effective_samples;
        return false;
    }

    bool selected = direct_reservoir_update(
        result,
        other.smple,
        candidate_weight,
        effective_samples
    );
    if (selected) sample_weight = current_target;
    return selected;
}
#endif
#endif

#if defined PH_ENABLE_RESTIR_GI
#if PH_RESTIR_SPATIAL_REUSE_SAMPLES > 0
//ph_required: uniform sampler2D restir_indirect_spatial_input0;
//ph_required: uniform usampler2D restir_indirect_spatial_input1;

void ph_spatial_indirect_reservoir_load(
    out IndirectReservoir reservoir,
    ivec2 texel
) {
    indirect_reservoir_decode(
        reservoir,
        texelFetch(restir_indirect_spatial_input0, texel, 0),
        texelFetch(restir_indirect_spatial_input1, texel, 0).rgb
    );
    if (!indirect_reservoir_is_finite(reservoir))
        reservoir = indirect_reservoir_empty();
}

bool ph_spatial_indirect_reservoir_merge(
    inout IndirectReservoir result,
    IndirectReservoir other,
    FragData source_frag,
    inout float sample_weight
) {
    float effective_samples = min(
        other.total_samples,
        max_indirect_reservoir_samples
    );
    if (effective_samples <= 0.0f
            || isnan(effective_samples)
            || isinf(effective_samples))
        return false;

    // An empty neighboring history has no representative sample that can be
    // re-evaluated at this receiver, so it must not darken the local batch.
    if (!indirect_reservoir_has_sample(other))
        return false;

    float shift = indirect_sample_compute_shift(
        other.smple,
        _frag_data,
        source_frag
    );
    if (shift <= 0.0f
            || shift >= 1.2f
            || isnan(shift)
            || isinf(shift))
        return false;

    uint path_validation = indirect_reservoir_classify_reused_path(
        other,
        frag_rt_pos
    );
    if (path_validation
            == indirect_path_validation_blocked_current_receiver) {
        // The neighbor represents a valid zero-target batch at this receiver.
        // Account its clamped M once without making it selectable.
        indirect_reservoir_add_batch_samples(
            result,
            effective_samples
        );
        return false;
    }
    if (path_validation != indirect_path_validation_valid)
        return false;

    other.total_samples = effective_samples;
    return indirect_reservoir_merge(
        result,
        other,
        shift,
        sample_weight
    );
}
#endif
#endif

void main() {
    setup_frag_data(31);
    if (!frag_is_in_world) {
#if defined PH_ENABLE_BLOCKLIGHT
        direct_reservoir_encode(direct_reservoir_empty(), di_reservoir_0);
#endif
#if defined PH_ENABLE_RESTIR_GI
        IndirectReservoir empty_indirect = indirect_reservoir_empty();
        indirect_reservoir_encode(
            empty_indirect,
            gi_reservoir_0,
            gi_reservoir_1
        );
#endif
        return;
    }

#if defined PH_ENABLE_BLOCKLIGHT
    float direct_sample_weight = 0.0f;
    DirectReservoir direct_result = direct_reservoir_empty();
    DirectReservoir temp_direct = direct_reservoir_empty();
    DirectReservoir direct_fallback = direct_reservoir_empty();

#if PH_RESTIR_SPATIAL_REUSE_SAMPLES > 0
    ph_spatial_direct_reservoir_load(temp_direct, frag_tex_coord);
#else
    direct_reservoir_load(temp_direct, frag_tex_coord);
#endif
    if (direct_reservoir_has_sample(temp_direct)) {
        direct_fallback = temp_direct;
        direct_fallback.weight = 0.0f;
    }
    direct_reservoir_merge_current_batch(
        direct_result,
        temp_direct,
        direct_sample_weight
    );
#endif


#if defined PH_ENABLE_RESTIR_GI
    float indirect_sample_weight = 0.0f;
    IndirectReservoir indirect_result = indirect_reservoir_empty();
    IndirectReservoir temp_indirect = indirect_reservoir_empty();

    indirect_reservoir_load(temp_indirect, frag_tex_coord);
    indirect_reservoir_merge_current_batch(
        indirect_result,
        temp_indirect,
        1.0f,
        indirect_sample_weight
    );
#endif

    const float reuse_radius = PH_RESTIR_SPATIAL_REUSE_RADIUS * PH_ACTIVE_RENDER_SCALE;
    const int reuse_samples = PH_RESTIR_SPATIAL_REUSE_SAMPLES;
    const int indirect_reuse_samples = min(
        reuse_samples,
        ph_indirect_spatial_validation_budget
    );
    ivec2 spatial_texture_size = textureSize(ph_frag_data0, 0);
    int spatial_continuity_lane = ph_spatial_continuity_lane(
        spatial_texture_size
    );
    bool continuous_direct_spatial = spatial_continuity_lane >= 2;
#if defined PH_ENABLE_BLOCKLIGHT
    bool direct_spatial_receiver_can_reuse =
        ph_spatial_current_receiver_can_reuse(continuous_direct_spatial);
#endif
#if defined PH_ENABLE_RESTIR_GI
    bool indirect_spatial_receiver_can_reuse =
        ph_spatial_current_receiver_can_reuse(false)
        && frag_data_sublevel_token(_frag_data) == 0u;
#endif

#if defined PH_ENABLE_BLOCKLIGHT
    for (int i = 0; i < reuse_samples; i++) {
        if (!direct_spatial_receiver_can_reuse || reuse_radius <= 0.0f)
            break;

        ivec2 sample_texel = ph_spatial_next_sample_texel(
            frag_rnd_state,
            reuse_radius
        );
        if (!ph_spatial_sample_texel_is_valid(
                sample_texel,
                spatial_texture_size
        ))
            continue;

        FragData sample_frag;
        frag_data_load(sample_frag, sample_texel);
        if (!ph_spatial_receiver_matches(
                sample_frag,
                continuous_direct_spatial
        )) continue;

#if PH_RESTIR_SPATIAL_REUSE_SAMPLES > 0
        ph_spatial_direct_reservoir_load(temp_direct, sample_texel);
        if (direct_reservoir_is_reusable(temp_direct)
                && ph_spatial_direct_light_matches_receiver(temp_direct)) {
            ph_spatial_direct_reservoir_merge(
                direct_result,
                temp_direct,
                direct_sample_weight
            );
        }
#endif
    }
#endif

#if defined PH_ENABLE_RESTIR_GI
#if PH_RESTIR_SPATIAL_REUSE_SAMPLES > 0
    if (indirect_spatial_receiver_can_reuse && reuse_radius > 0.0f) {
        uint indirect_spatial_rnd_state = ph_new_rand_state(
            gl_FragCoord.xy,
            frameCounter,
            1171
        );

        for (int i = 0; i < indirect_reuse_samples; i++) {
            bool candidate_found = false;
            ivec2 sample_texel = frag_tex_coord;
            FragData sample_frag;

            // Search only on receiver geometry. Reservoir contents are not
            // inspected until after selection, so bright samples receive no
            // extra proposal probability.
            for (int attempt = 0;
                    attempt < ph_indirect_spatial_candidate_attempts;
                    attempt++) {
                sample_texel = ph_spatial_next_sample_texel(
                    indirect_spatial_rnd_state,
                    reuse_radius
                );
                if (!ph_spatial_sample_texel_is_valid(
                        sample_texel,
                        spatial_texture_size
                ))
                    continue;

                frag_data_load(sample_frag, sample_texel);
                if (!ph_spatial_receiver_matches(sample_frag, false))
                    continue;

                candidate_found = true;
                break;
            }

            if (!candidate_found)
                continue;

            ph_spatial_indirect_reservoir_load(
                temp_indirect,
                sample_texel
            );
            ph_spatial_indirect_reservoir_merge(
                indirect_result,
                temp_indirect,
                sample_frag,
                indirect_sample_weight
            );
        }
    }
#endif
#endif

#if defined PH_ENABLE_BLOCKLIGHT
    if (direct_reservoir_is_reusable(direct_result)) {
        // Temporal reuse re-evaluates the representative light and visibility
        // at the current receiver, so retaining more of that reservoir does
        // not trail old radiance. Sable receivers need the full temporal cap
        // because their final external-light accumulation is intentionally
        // short while moving. World receivers keep the upstream spatial cap.
        float output_sample_cap = frag_data_sublevel_token(_frag_data) != 0u
            ? max_direct_temporal_samples
            : max_direct_reservoir_samples;
        direct_reservoir_clamp_samples_to(
            direct_result,
            output_sample_cap
        );
        direct_reservoir_finalize_weight(direct_result, direct_sample_weight);
    }
    if (!direct_reservoir_is_reusable(direct_result)
            && direct_reservoir_has_sample(direct_fallback)) {
        direct_result = direct_fallback;
        direct_reservoir_clamp_samples(direct_result);
    }

    direct_reservoir_encode(direct_result, di_reservoir_0);
#endif


#if defined PH_ENABLE_RESTIR_GI
    indirect_reservoir_clamp_samples(indirect_result);

    indirect_reservoir_finalize_weight(indirect_result, indirect_sample_weight);
    indirect_reservoir_clamp_samples(indirect_result);
    indirect_reservoir_encode(indirect_result, gi_reservoir_0, gi_reservoir_1);
#endif
}
