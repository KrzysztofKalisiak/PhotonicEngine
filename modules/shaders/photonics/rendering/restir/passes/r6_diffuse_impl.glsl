#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"

#ifdef PH_DISABLE_RESTIR_VISIBILITY
#undef PH_DISABLE_RESTIR_VISIBILITY
#endif

#include "/photonics/rendering/restir/restir.glsl"
#include "/photonics/modifiers/restir_gi_modifier.glsl"

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
layout(location = DIRECT_HISTORY_STATE_0) out vec2 di_history_state;
layout(location = RESTIR_EXTERNAL_LIGHTING_OUT) out vec4 external_lighting;
#endif

#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC
layout(location = RESTIR_SOURCE_HISTORY_OUT) out vec3 source_history_lighting;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

#if defined PH_ENABLE_BLOCKLIGHT
    di_history_state = vec2(0.0f);
    external_lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);
#endif
#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC
    source_history_lighting = vec3(0.0f);
#endif

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_ENABLE_RESTIR_GI
    IndirectReservoir indirect_reservoir = indirect_reservoir_empty();
    indirect_reservoir_load(indirect_reservoir, frag_tex_coord);

    lighting.rgb = indirect_reservoir_get_final_color(indirect_reservoir);

#ifndef PH_RESTIR_GI_MODIFIER_DISABLED
    modify_restir_gi(lighting.rgb);
#endif

    indirect_reservoir_encode(indirect_reservoir, gi_reservoir_0, gi_reservoir_1);
#endif

#if defined PH_ENABLE_BLOCKLIGHT
    uint receiver_token = frag_data_sublevel_token(_frag_data);
#ifdef PH_RESTIR_SOFT_SHADOWS
    int local_light_count = 0;
    int local_visible_light_count = 0;
    if (receiver_token != 0u) {
        vec3 local_direct_lighting = vec3(0.0f);
        FragMotion receiver_motion;
        frag_motion_load(receiver_motion, frag_tex_coord);
        vec3 receiver_grid_pos;
        bool receiver_grid_pos_valid = ph_sable_recover_current_grid_position(
            frag_data_sublevel_slot(_frag_data),
            receiver_token,
            receiver_motion.previous_player_pos,
            receiver_grid_pos
        );
        int priority_count = clamp(
            ph_priority_light_count,
            0,
            light_list_size
        );
        for (int light_index = 0; light_index < priority_count; light_index++) {
            DirectSample local_sample = DirectSample(light_index);
            if (!direct_sample_matches_receiver_domain(
                    local_sample,
                    receiver_token
            )) continue;
            local_light_count++;

            vec3 local_sample_color;
            bool sample_visible;
            if (receiver_grid_pos_valid) {
                sample_visible = direct_sample_get_final_unweighted_color_at_sable_grid(
                    local_sample,
                    frag_rt_pos,
                    frag_geo_normal,
                    frag_tex_normal,
                    receiver_grid_pos,
                    local_sample_color
                );
            } else {
                sample_visible = direct_sample_get_final_unweighted_color(
                    local_sample,
                    frag_rt_pos,
                    frag_geo_normal,
                    frag_tex_normal,
                    local_sample_color
                );
            }
            if (sample_visible) {
                local_direct_lighting += local_sample_color;
                local_visible_light_count++;
            }
        }
        // Area-light samples still need temporal accumulation and SVGF.
        lighting.rgb += local_direct_lighting;
    }
#endif

    DirectReservoir direct_reservoir = direct_reservoir_empty();
    direct_reservoir_load(direct_reservoir, frag_tex_coord);
    if (direct_reservoir_has_sample(direct_reservoir)
            && direct_sample_matches_receiver_domain(
                direct_reservoir.smple,
                receiver_token
            )) direct_reservoir = direct_reservoir_empty();

#if defined PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC
    vec3 direct_unshadowed_lighting = direct_reservoir_get_unshadowed_color(
        direct_reservoir,
        frag_rt_pos,
        frag_geo_normal,
        frag_tex_normal
    );
    float direct_proposal_metadata = direct_sample_encode_proposal_metadata(
        direct_reservoir.smple
    );
#endif
    vec3 direct_lighting = direct_reservoir_get_final_color(
        direct_reservoir,
        frag_rt_pos,
        frag_geo_normal,
        frag_tex_normal
    );
    if (direct_reservoir_has_sample(direct_reservoir)
            && direct_sample_uses_external_history(
                direct_reservoir.smple,
                receiver_token
            ))
        external_lighting.rgb = direct_lighting;
    else
        lighting.rgb += direct_lighting;

    direct_reservoir_encode(direct_reservoir, di_reservoir_0);
    direct_history_encode(direct_reservoir, di_history_state);
    if (receiver_token != 0u) {
#ifdef PH_RESTIR_SOFT_SHADOWS
        int local_visibility_signature = 0;
        di_history_state.y = float(
            min(local_light_count, 4095) * 4096
                + local_visibility_signature
        );
#else
        // Exact local hard shadows have no temporal state. Do not let the
        // stochastic external-reservoir confidence become a false signature.
        di_history_state.y = 0.0f;
#endif
    }
#if defined PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC
    float unshadowed_luminance = direct_sample_weight(
        max(direct_unshadowed_lighting, vec3(0.0f))
    );
    float visible_luminance = direct_sample_weight(
        max(direct_lighting, vec3(0.0f))
    );
    if (isnan(unshadowed_luminance) || isinf(unshadowed_luminance))
        unshadowed_luminance = 0.0f;
    if (isnan(visible_luminance) || isinf(visible_luminance))
        visible_luminance = 0.0f;
    source_history_lighting = vec3(
        log2(1.0f + unshadowed_luminance),
        log2(1.0f + visible_luminance),
        direct_proposal_metadata
    );
#endif
#endif

#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC && !defined PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC
    source_history_lighting = lighting.rgb;
#if defined PH_ENABLE_BLOCKLIGHT
    source_history_lighting += external_lighting.rgb;
#endif
#endif
}
