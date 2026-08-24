#version 430

#define FRAG_USE_RT_POS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    IndirectReservoir reservoir = indirect_reservoir_empty();
    indirect_reservoir_load(reservoir, frag_tex_coord);
    if (indirect_reservoir_has_sample(reservoir)) {
        uint path_validation = indirect_reservoir_classify_reused_path(
            reservoir,
            frag_rt_pos
        );
        if (path_validation
                == indirect_path_validation_blocked_current_receiver) {
            indirect_reservoir_reject(reservoir);
        } else if (path_validation != indirect_path_validation_valid) {
            reservoir = indirect_reservoir_empty();
        }
    } else if (indirect_reservoir_has_batch(reservoir)) {
        // Keep a finite zero-radiance batch's represented M while removing
        // unusable representative state.
        reservoir.smple = indirect_sample_empty();
        reservoir.weight = 0.0f;
    } else {
        reservoir = indirect_reservoir_empty();
    }
    indirect_reservoir_clamp_samples(reservoir);

    indirect_reservoir_encode(
        reservoir,
        gi_reservoir_0,
        gi_reservoir_1
    );
}
