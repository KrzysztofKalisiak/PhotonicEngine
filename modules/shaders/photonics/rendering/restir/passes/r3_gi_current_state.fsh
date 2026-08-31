#version 430

#define PH_LIGHTING_PASS

#if defined PH_RESTIR_SPLIT_GI
#define PH_RESTIR_GI_PASS
#endif

// This pass writes to a standalone, non-history target. Do not declare the
// target itself as a sampler through the shared history include.
#define PH_RESTIR_GI_STATE_CAPTURE_PASS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = 0) out vec4 gi_current_state_out;

void main() {
    gi_current_state_out = vec4(0.0f);

    setup_frag_data(0);
    bool gi_evaluated = frag_is_in_world && ph_world_ready != 0;
    if (!gi_evaluated) return;

    IndirectReservoir current = indirect_reservoir_empty();
    bool loaded = indirect_reservoir_load(current, frag_tex_coord);
    // r3 encodes failed/non-finite traces as an empty reservoir. A finite
    // decoded struct is therefore insufficient: require the batch count that
    // r3 writes after the trace. A zero-radiance trace still has one sample.
    bool finite = loaded && indirect_reservoir_has_batch(current);
    bool positive = finite && indirect_reservoir_has_usable_sample(current);

    int state = 0;
    if (gi_evaluated) state |= PH_RESTIR_GI_STATE_EVALUATED;
    if (finite) state |= PH_RESTIR_GI_STATE_CURRENT_FINITE;
    // A non-empty tree is not enough to publish GI. The compiler exposes
    // settled only after the current layout has quiesced and all queued work
    // has been uploaded.
    if (gi_evaluated && finite && ph_world_settled != 0)
        state |= PH_RESTIR_GI_STATE_PUBLISHED;

    // R=evaluated, G=finite current result, B=positive contribution, A=state
    // bits. The final-state pass adds post-reuse reservoir bits later.
    gi_current_state_out = vec4(
        gi_evaluated ? 1.0f : 0.0f,
        finite ? 1.0f : 0.0f,
        positive ? 1.0f : 0.0f,
        float(state)
    );
}
