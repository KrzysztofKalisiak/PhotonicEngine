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
    ph_restir_sanitize_history(smple);

    SampleHistory accumulator;

    if (!ph_restir_history_split_radiance_bypass()
            && ph_restir_gi_history_epoch_matches(frag_tex_coord, false))
        sample_history_reproject(accumulator);
    else
        accumulator = SampleHistory(vec4(0.0f), vec4(0.0f), vec4(0.0f));
    ph_restir_sanitize_history(accumulator);

    float current_energy = ph_luminance(
        max(smple.lighting.rgb, vec3(0.0f))
            + max(smple.external_lighting.rgb, vec3(0.0f))
    );
    bool has_current_energy = !isnan(current_energy)
        && !isinf(current_energy)
        && current_energy > 0.000001f;

    // A GI reservoir can be valid even when its contribution is exactly zero
    // (for example, a ray terminated on an opaque surface with no emitted
    // radiance). During tree publication the same zero value can also mean
    // that the current voxel query has not produced a usable result yet. The
    // current batch is eligible once the published tree is ready; the separate
    // settled bit includes an intentional delay for diagnostics and must not
    // blank newly exposed receivers during that delay.
    bool has_current_gi_batch = false;
#if defined PH_ENABLE_RESTIR_GI
    IndirectReservoir current_indirect = indirect_reservoir_empty();
    bool current_indirect_loaded = indirect_reservoir_load(
        current_indirect,
        frag_tex_coord
    );
    has_current_gi_batch = current_indirect_loaded
        && indirect_reservoir_has_batch(current_indirect)
        && ph_world_ready != 0;
#endif
    bool has_current_transport = has_current_energy
        || has_current_gi_batch;
#if !defined PH_ENABLE_RESTIR_GI
    // Without GI, r6 is the authoritative current direct-light evaluation.
    // A zero result is still meaningful (for example, a fully occluded light)
    // and must be allowed to darken history normally.
    has_current_transport = true;
#endif
    bool has_reprojected_history = max(
            accumulator.lighting.a,
            accumulator.external_lighting.a
        ) > 0.0f;
    bool history_retry_required = !has_reprojected_history
        && !has_current_transport;

    // A valid surface can briefly have no current GI proposal while the voxel
    // layout is uploading or while a newly exposed camera region is filling.
    // Recover one geometrically matched previous sample only while the tree is
    // still publishing. The candidate already passed the surface, sublevel,
    // finite-value, and regional epoch checks; once the tree is settled, a
    // missing current batch must remain a retry rather than import stale light.
#if defined PH_ENABLE_RESTIR_GI
    if (!has_reprojected_history
            && !has_current_transport
            && ph_world_settled == 0
            && !ph_restir_scene_change_affects_receiver_for_recovery(
                frag_rt_pos
            )) {
        SampleHistory recovered_history;
        if (sample_history_reproject_nearest_history(recovered_history)) {
            accumulator = recovered_history;
            ph_restir_sanitize_history(accumulator);
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

    // r6 uses alpha=1 as the per-frame sample-count seed. That alpha is not
    // validity by itself: during an unsettled upload r6 can contain a finite
    // zero-radiance proposal with no usable transport. Combining it here
    // would average black into valid history and make the dark patch persist.
    // Keep the previous history untouched until a current transport sample or
    // a geometrically recovered history sample is available.
    if (has_current_transport) {
        sample_history_combine_lighting(accumulator, smple);
        ph_restir_sanitize_history(accumulator);

#if PH_RESTIR_DENOISER_PASSES != 0
        sample_history_combine_moment(accumulator, smple);
        sample_history_compute_variance(accumulator, smple);
#endif
    }

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

#if defined PH_RESTIR_GI_VALIDITY_DIAGNOSTIC
    // Keep this diagnostic in the existing lighting attachment so it does not
    // change the combined-GI framebuffer layout or alter pass scheduling.
    // RGB bits: red=history, green=current transport, blue=world unsettled.
    vec3 validity_color = vec3(
        has_reprojected_history ? 1.0f : 0.0f,
        has_current_transport ? 1.0f : 0.0f,
        ph_world_settled == 0 ? 1.0f : 0.0f
    );
    lighting_frag_out = vec4(validity_color, 1.0f);
    lighting_variance_frag_out = vec4(0.0f);
#if defined PH_ENABLE_BLOCKLIGHT
    external_lighting_frag_out = vec4(0.0f);
#endif
#endif
}
