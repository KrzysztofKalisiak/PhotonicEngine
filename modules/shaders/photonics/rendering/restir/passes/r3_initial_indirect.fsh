#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#include "/photonics/rendering/indirect_lighting.glsl"

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;

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
