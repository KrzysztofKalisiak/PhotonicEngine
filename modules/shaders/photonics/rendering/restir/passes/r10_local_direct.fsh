#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"

#ifdef PH_DISABLE_RESTIR_VISIBILITY
#undef PH_DISABLE_RESTIR_VISIBILITY
#endif

#include "/photonics/rendering/restir/restir.glsl"

layout(location = 0) out vec3 local_lighting;

void main() {
    local_lighting = vec3(0.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

    uint receiver_token = frag_data_sublevel_token(_frag_data);
    if (receiver_token == 0u) return;

    int receiver_slot = frag_data_sublevel_slot(_frag_data);
    FragMotion receiver_motion;
    frag_motion_load(receiver_motion, frag_tex_coord);
    vec3 receiver_grid_pos;
    bool receiver_grid_pos_valid = ph_sable_recover_current_grid_position(
        receiver_slot,
        receiver_token,
        receiver_motion.previous_player_pos,
        receiver_grid_pos
    );

    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    for (int light_index = 0; light_index < priority_count; light_index++) {
        DirectSample local_sample = DirectSample(light_index);
        if (!direct_sample_matches_receiver_domain(local_sample, receiver_token))
            continue;

        vec3 local_sample_color;
        bool sample_visible;
        if (receiver_grid_pos_valid) {
            sample_visible = direct_sample_get_final_unweighted_color_at_sable_grid(
                local_sample,
                frag_rt_pos,
                frag_geo_normal,
                frag_is_hand ? frag_geo_normal : frag_tex_normal,
                receiver_grid_pos,
                local_sample_color
            );
        } else {
            sample_visible = direct_sample_get_final_unweighted_color(
                local_sample,
                frag_rt_pos,
                frag_geo_normal,
                frag_is_hand ? frag_geo_normal : frag_tex_normal,
                local_sample_color
            );
        }
        if (sample_visible)
            local_lighting += local_sample_color;
    }
}
