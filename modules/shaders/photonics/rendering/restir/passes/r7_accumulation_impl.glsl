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

    if (!ph_restir_history_split_radiance_bypass()
            && ph_restir_gi_history_epoch_matches(frag_tex_coord))
        sample_history_reproject(accumulator);
    else
        accumulator = SampleHistory(vec4(0.0f), vec4(0.0f), vec4(0.0f));

    float current_energy = ph_luminance(
        max(smple.lighting.rgb, vec3(0.0f))
            + max(smple.external_lighting.rgb, vec3(0.0f))
    );
    bool has_current_energy = !isnan(current_energy)
        && !isinf(current_energy)
        && current_energy > 0.000001f;
    bool has_reprojected_history = max(
            accumulator.lighting.a,
            accumulator.external_lighting.a
        ) > 0.0f;
    bool history_retry_required = !has_reprojected_history
        && !has_current_energy;

    // During a layout upload a valid surface can briefly have no current GI
    // proposal. Recover one geometrically matched previous sample only when
    // the receiver is outside the changed scene region; the normal temporal
    // combine then drains it instead of pinning stale radiance forever.
#if defined PH_ENABLE_RESTIR_GI
    if (!has_reprojected_history
            && !has_current_energy
            && ph_world_settled == 0
            && !ph_restir_scene_change_affects_receiver(frag_rt_pos)) {
        SampleHistory recovered_history;
        if (sample_history_reproject_nearest_history(recovered_history)) {
            accumulator = recovered_history;
            accumulator.lighting.a = min(accumulator.lighting.a, 1.0f);
            accumulator.external_lighting.a = min(
                accumulator.external_lighting.a,
                1.0f
            );
            accumulator.variance.w = min(accumulator.variance.w, 1.0f);
            has_reprojected_history = max(
                    accumulator.lighting.a,
                    accumulator.external_lighting.a
                ) > 0.0f;
            history_retry_required = false;
        }
    }
#endif

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
#if defined PH_ENABLE_RESTIR_GI
    // Do not mark an empty post-revision frame as a valid current epoch. That
    // would turn a transient zero proposal into reusable black history and
    // prevent the affected receiver from retrying once the tree is usable.
    if (history_retry_required)
        gi_history_epoch_frag_out = uint(max(ph_scene_revision - 1, 0));
#endif
}
