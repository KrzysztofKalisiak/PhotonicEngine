#version 430

#if defined PH_RESTIR_SPLIT_GI
#define PH_RESTIR_DIRECT_PASS
#endif

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    DirectReservoir reservoir = direct_reservoir_empty();
    float sample_weight = 0.0f;
    uint receiver_token = frag_data_sublevel_token(_frag_data);

    if (light_list_size > 0) {
        DirectProposalLayout proposal_layout = direct_build_proposal_layout();
        int priority_count = proposal_layout.priority_count;
        int priority_offset = priority_count > 0
            ? ph_rand_next_int(frag_rnd_state, 0, priority_count)
            : 0;
        bool uses_rank_strata = direct_layout_uses_rank_strata(
            proposal_layout
        );
        int camera_count = uses_rank_strata
            ? 0
            : direct_camera_prefix_count();
        float camera_phase = camera_count > 0
            ? ph_rand_next_float(frag_rnd_state)
            : 0.0f;
        int tail_count = uses_rank_strata
            ? 0
            : proposal_layout.ordinary_count - camera_count;
        float tail_phase = tail_count > 0
            ? ph_rand_next_float(frag_rnd_state)
            : 0.0f;
        vec4 rank_phases_0_3 = vec4(0.0f);
        float rank_phase_4 = 0.0f;
        if (uses_rank_strata) {
            rank_phases_0_3 = vec4(
                ph_rand_next_float(frag_rnd_state),
                ph_rand_next_float(frag_rnd_state),
                ph_rand_next_float(frag_rnd_state),
                ph_rand_next_float(frag_rnd_state)
            );
            rank_phase_4 = ph_rand_next_float(frag_rnd_state);
        }
        for (int i = 0; i < PH_RESTIR_INITIAL_SAMPLES; i++) {
            float proposal_probability;
            DirectSample smple = direct_sample_stratified(
                i,
                priority_offset,
                camera_phase,
                tail_phase,
                proposal_layout,
                rank_phases_0_3,
                rank_phase_4,
                proposal_probability
            );
            // A Sable receiver evaluates lights from its own rigid motion
            // domain directly in r6. Keeping them out of this reservoir makes
            // its estimator external-only instead of partitioning one random
            // all-light representative after selection.
            float target_weight = 0.0f;
            if (!direct_sample_matches_receiver_domain(smple, receiver_token)) {
                target_weight = direct_sample_get_weight(
                    smple,
                    frag_rt_pos,
                    frag_geo_normal,
                    frag_tex_normal
                );
            }
            float resampling_weight = proposal_probability > 0.0f
                ? target_weight / proposal_probability
                : 0.0f;

            if (direct_reservoir_update(reservoir, smple, resampling_weight, 1.0f))
                sample_weight = target_weight;
        }
    }

    direct_reservoir_finalize_weight(reservoir, sample_weight);
    // Validation consumes the reservoir selected by this pass. Keeping it
    // here avoids a full-screen read/write pass without changing the ray test
    // or the reservoir seen by temporal reuse.
    direct_reservoir_validate_visiblity(
        reservoir,
        frag_rt_pos,
        frag_geo_normal
    );
    direct_reservoir_encode(reservoir, di_reservoir_0);
}
