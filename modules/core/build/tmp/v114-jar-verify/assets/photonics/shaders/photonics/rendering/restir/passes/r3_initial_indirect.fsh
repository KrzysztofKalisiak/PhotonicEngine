#version 430

#if defined PH_RESTIR_SPLIT_GI
#define PH_RESTIR_GI_PASS
#endif

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#include "/photonics/rendering/indirect_lighting.glsl"

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;

void main() {
    setup_frag_data(0);
    IndirectReservoir reservoir = indirect_reservoir_empty();
    if (!frag_is_in_world || ph_world_ready == 0) {
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
    uint path_hash;

    int gi_bounce_limit = PH_MAX_GI_BOUNCES;
#if defined PH_RESTIR_GI_TRANSPORT_LANES
    int gi_transport_lane = min(int(frag_tex_coord.x * 4.0f), 3);
    if ((gi_transport_lane & 1) != 0)
        gi_bounce_limit += 1;
#endif

    sample_indirect(
        indirect_result,
        frag_rt_pos,
        frag_geo_normal,
        trace_rnd_state,
        gi_bounce_limit,

        hit_position,
        hit_normal,
        path_hash
    );

    indirect_sample_set_hit_point(
        reservoir.smple,
        hit_position,
        frag_rt_pos,
        frag_geo_normal,
        initial_rnd_state
    );
    indirect_sample_set_hit_metadata(
        reservoir.smple,
        hit_normal,
        path_hash
    );

    indirect_sample_set_color(reservoir.smple, indirect_result);

    reservoir.weight = max(ph_luminance(reservoir.smple.color), 0.0f);
    reservoir.total_samples = 1.0f;

    indirect_reservoir_finalize_weight(reservoir, reservoir.weight);
    indirect_reservoir_encode(reservoir, gi_reservoir_0, gi_reservoir_1);
}
