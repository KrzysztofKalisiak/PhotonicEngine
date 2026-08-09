#define FRAG_USE_PLAYER_POS
#define FRAG_USE_GEO_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting_frag_out;
layout(location = RESTIR_LIGHTING_VARIANCE_OUT) out vec4 lighting_variance_frag_out;
#if defined PH_ENABLE_BLOCKLIGHT
layout(location = RESTIR_EXTERNAL_LIGHTING_OUT) out vec4 external_lighting_frag_out;
#endif
#if defined PH_ENABLE_RESTIR_GI
layout(location = RESTIR_GI_HISTORY_EPOCH_OUT) out uint gi_history_epoch_frag_out;
#endif

void main() {
    lighting_frag_out = vec4(0.0f);
    lighting_variance_frag_out = vec4(0.0f);
#if defined PH_ENABLE_BLOCKLIGHT
    external_lighting_frag_out = vec4(0.0f);
#endif
#if defined PH_ENABLE_RESTIR_GI
    // Radiance history follows physical section content, not compiler
    // publication/layout revisions caused by chunk streaming.
    gi_history_epoch_frag_out = uint(max(ph_scene_revision, 0));
#endif

    setup_frag_data(0);
    if (!frag_is_in_world) return;

    SampleHistory smple;
    sample_history_load(smple);

    SampleHistory accumulator;

    if (ph_restir_gi_history_epoch_matches(frag_tex_coord))
        sample_history_reproject(accumulator);
    else
        accumulator = SampleHistory(vec4(0.0f), vec4(0.0f), vec4(0.0f));
    sample_history_combine_lighting(accumulator, smple);

#if PH_RESTIR_DENOISER_PASSES != 0
    sample_history_combine_moment(accumulator, smple);
    sample_history_compute_variance(accumulator, smple);
#endif

    lighting_frag_out = accumulator.lighting;
    lighting_variance_frag_out = accumulator.variance;
#if defined PH_ENABLE_BLOCKLIGHT
    external_lighting_frag_out = accumulator.external_lighting;
#endif
}
