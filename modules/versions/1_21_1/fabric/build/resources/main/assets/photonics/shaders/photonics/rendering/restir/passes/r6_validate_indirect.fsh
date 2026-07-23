#version 430

#define FRAG_USE_RT_POS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out vec4 gi_reservoir_1;
layout(location = INDIRECT_RESERVOIR_2) out vec4 gi_reservoir_2;

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    IndirectReservoir reservoir = indirect_reservoir_empty();
    indirect_reservoir_load(reservoir, frag_tex_coord);
    indirect_reservoir_validate_visibility(reservoir, frag_rt_pos);
    indirect_reservoir_clamp_samples(reservoir);

    indirect_reservoir_encode(
        reservoir,
        gi_reservoir_0,
        gi_reservoir_1,
        gi_reservoir_2
    );
}
