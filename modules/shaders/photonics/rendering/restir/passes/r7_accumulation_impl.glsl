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
    if (!sample_history_is_valid(accumulator))
        accumulator = SampleHistory(
            vec4(0.0f),
            vec4(0.0f),
            vec4(0.0f)
        );

    bool combined_frame_complete = false;
#if defined PH_ENABLE_RESTIR_GI
    vec4 current_gi_state = texelFetch(
        restir_gi_current_state,
        frag_tex_coord,
        0
    );
    bool current_gi_state_finite = !any(isnan(current_gi_state))
        && !any(isinf(current_gi_state));
    bool current_gi_evaluated = current_gi_state_finite
        && current_gi_state.r >= 0.5f;
    bool current_gi_finite = current_gi_state_finite
        && current_gi_state.g >= 0.5f;
    // A finite zero-radiance trace is still a complete current GI sample.
    // Positive radiance is diagnostic information, not a validity predicate.
    // r6 always writes the current direct contribution for an in-world
    // fragment. In combined mode the frame is complete only when r3 also
    // produced a finite current GI batch, including a zero-radiance batch.
    combined_frame_complete = current_gi_evaluated && current_gi_finite;
#endif
#if !defined PH_ENABLE_RESTIR_GI
    // Without GI, r6 is the authoritative current direct-light evaluation.
    // A zero result is still meaningful (for example, a fully occluded light)
    // and must be allowed to darken history normally.
    combined_frame_complete = true;
#endif
    bool has_reprojected_history = sample_history_is_valid(accumulator);

    // A valid surface can briefly have no current GI proposal while the voxel
    // layout is uploading or while a newly exposed camera region is filling.
    // Recover one previous sample only after its stored GI path has been
    // validated against the current tree. When the tree is unavailable, keep
    // the old conservative outside-the-edit-region fallback for the short
    // publication window. In either case this is presentation continuity:
    // the incomplete current-evaluation state keeps the recovered sample from
    // being promoted to the current scene epoch until fresh transport arrives.
#if defined PH_ENABLE_RESTIR_GI
    if (!has_reprojected_history
            && !combined_frame_complete) {
        bool can_recover_history = false;
        ivec2 previous_texel;
        if (sample_history_reproject_nearest_texel(previous_texel)) {
            if (ph_world_ready != 0) {
                IndirectReservoir previous_indirect = indirect_reservoir_empty();
                can_recover_history =
                    indirect_reservoir_load_previous(
                        previous_indirect,
                        previous_texel
                    )
                    && indirect_reservoir_has_sample(previous_indirect)
                    && indirect_reservoir_classify_reused_path(
                        previous_indirect,
                        frag_rt_pos
                    ) == indirect_path_validation_valid;
            } else {
                can_recover_history = ph_world_settled == 0
                    && !ph_restir_scene_change_affects_receiver_for_recovery(
                        frag_rt_pos
                    );
            }
        }

        if (can_recover_history) {
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
                has_reprojected_history = sample_history_is_valid(accumulator);
            }
        }
    }
#endif

    // r6 uses alpha=1 as the per-frame sample-count seed. That alpha is not
    // validity by itself: the explicit r3 state above decides whether the GI
    // trace completed against a usable tree. Combining r6 output while that
    // state is missing would average an upload-time zero into valid history
    // and make the dark patch persist. Keep previous history untouched until
    // a current transport sample or a geometrically recovered sample exists.
    if (combined_frame_complete) {
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
    // Do not promote a frame without a complete current transport evaluation.
    // This covers both an empty retry marker and a valid old sample displayed
    // for presentation continuity after a scene revision. A current
    // evaluated zero result reaches the normal combine path above and is the
    // only zero-radiance result allowed to establish the new epoch.
    if (!combined_frame_complete)
        gi_history_epoch_frag_out = uint(max(ph_scene_revision - 1, 0));
#endif

    // Validity diagnostics are captured in the private framebuffer by the
    // adjacent r7 validity passes. Never replace production lighting here:
    // doing so makes the diagnostic palette itself look like a GI failure and
    // also changes the denoiser input.
}
