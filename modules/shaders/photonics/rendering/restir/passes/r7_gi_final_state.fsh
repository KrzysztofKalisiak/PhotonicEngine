#version 430

#define PH_LIGHTING_PASS

#if defined PH_RESTIR_SPLIT_GI
#define PH_RESTIR_GI_PASS
#endif

// This pass reads the current r3 state and the post-reuse reservoir, then
// writes only the standalone publication token. It must not expose its target
// as a sampler through the shared include.
#define PH_RESTIR_GI_FINAL_STATE_CAPTURE_PASS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = 0) out vec4 gi_final_state_out;

void main() {
    gi_final_state_out = vec4(0.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

    vec4 current_state = texelFetch(
        restir_gi_current_state,
        frag_tex_coord,
        0
    );
    bool current_state_finite = !any(isnan(current_state))
        && !any(isinf(current_state));
    int state = current_state_finite
        ? int(max(current_state.a, 0.0f) + 0.5f)
        : 0;

    IndirectReservoir final_reservoir = indirect_reservoir_empty();
    bool final_loaded = indirect_reservoir_load(
        final_reservoir,
        frag_tex_coord
    );
    bool final_finite = final_loaded
        && indirect_reservoir_has_batch(final_reservoir);
    bool final_positive = final_finite
        && indirect_reservoir_has_usable_sample(final_reservoir);

    if (final_finite)
        state |= PH_RESTIR_GI_STATE_FINAL_FINITE;
    if (final_positive)
        state |= PH_RESTIR_GI_STATE_FINAL_POSITIVE;

    bool current_evaluated = (state & PH_RESTIR_GI_STATE_EVALUATED) != 0;
    bool current_finite = (state & PH_RESTIR_GI_STATE_CURRENT_FINITE) != 0;
    gi_final_state_out = vec4(
        current_evaluated ? 1.0f : 0.0f,
        current_finite ? 1.0f : 0.0f,
        final_positive ? 1.0f : 0.0f,
        float(state)
    );
}
