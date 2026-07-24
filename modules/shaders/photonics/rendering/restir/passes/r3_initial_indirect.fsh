#version 430

#define FRAG_USE_PLAYER_POS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#include "/photonics/rendering/indirect_lighting.glsl"

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;

bool ph_has_reusable_world_indirect_history() {
    if (frag_is_hand || frag_data_sublevel_token(_frag_data) != 0u)
        return false;

    vec3 previous_player_pos;
    vec3 expected_previous_normal;
    uint sublevel_token;
    vec2 uv = ph_reproject_frag_data(
        _frag_data,
        frag_tex_coord,
        false,
        get_taa_jitter(),
        previous_player_pos,
        expected_previous_normal,
        sublevel_token
    ).xy;

    if (any(lessThan(uv, vec2(0.0f))) || any(greaterThanEqual(uv, vec2(1.0f))))
        return false;

    ivec2 previous_size = textureSize(prev_ph_frag_data0, 0);
    ivec2 prev_texel = ivec2(uv * vec2(previous_size));

    FragData prev_frag;
    frag_data_load_previous(prev_frag, prev_texel);

    if (!frag_data_is_in_world(prev_frag)
            || frag_data_is_hand(prev_frag)
            || frag_data_sublevel_token(prev_frag) != sublevel_token)
        return false;

    if (!frag_is_bad_angle) {
        vec3 d = frag_data_player_pos(prev_frag) - previous_player_pos;
        if (dot(d, d) >= PH_HISTORY_POSITION_ERROR_SQ)
            return false;
    }

    if (dot(frag_data_geo_normal(prev_frag), expected_previous_normal) < 0.99f)
        return false;

    IndirectReservoir previous_reservoir = indirect_reservoir_empty();
    return indirect_reservoir_load_previous(previous_reservoir, prev_texel)
        && indirect_reservoir_has_sample(previous_reservoir);
}

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    IndirectReservoir reservoir = indirect_reservoir_empty();
    if (ph_world_ready == 0) {
        indirect_reservoir_encode(
            reservoir,
            gi_reservoir_0,
            gi_reservoir_1
        );
        return;
    }

    // Stable world receivers already have a validated temporal proposal. Trace
    // alternating pixels so disocclusions, hand geometry, and Sable receivers
    // still receive a fresh GI path every frame.
    bool deferred_to_history = ((frag_tex_coord.x + frag_tex_coord.y + frameCounter) & 1) != 0
        && ph_has_reusable_world_indirect_history();
    if (deferred_to_history) {
        indirect_reservoir_encode(
            reservoir,
            gi_reservoir_0,
            gi_reservoir_1
        );
        return;
    }

    uint initial_rnd_state = frag_rnd_state;
    uint trace_rnd_state = initial_rnd_state;

    vec3 indirect_result = vec3(0.0f);
    vec3 hit_normal;
    vec3 hit_position;

    sample_indirect(
        indirect_result,
        frag_rt_pos,
        frag_geo_normal,
        trace_rnd_state,

        hit_position,
        hit_normal
    );

    indirect_sample_set_hit_normal(reservoir.smple, hit_normal);
    indirect_sample_set_hit_point(
        reservoir.smple,
        hit_position,
        frag_rt_pos,
        frag_geo_normal,
        initial_rnd_state
    );

    // Use the serialized finite hit point for both world and sky samples so
    // normal-map compensation matches the point reused in later frames.
    vec3 stored_hit_position = indirect_sample_get_hit_point(reservoir.smple);
    indirect_result *= indirect_normal_factor(
        _frag_data,
        stored_hit_position
    );
    indirect_sample_set_color(reservoir.smple, indirect_result);

    reservoir.weight = max(ph_luminance(reservoir.smple.color), 0.0f);
    reservoir.total_samples = 1.0f;

    indirect_reservoir_finalize_weight(reservoir, reservoir.weight);
    indirect_reservoir_encode(reservoir, gi_reservoir_0, gi_reservoir_1);
}
