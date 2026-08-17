#define FRAG_USE_RT_POS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#if defined PH_RESTIR_VALIDITY_FINAL_PASS
// This pass is deliberately separate from r7's production outputs. It reads
// the current-frame capture and records the post-r7 history state without
// changing any ReSTIR attachment.
//ph_required: uniform sampler2D restir_gi_validity_current;
layout(location = 1) out vec4 validity_final_out;
#else
layout(location = 0) out vec4 validity_current_out;
#endif

void main() {
#if defined PH_RESTIR_VALIDITY_FINAL_PASS
    validity_final_out = vec4(0.0f);
#else
    validity_current_out = vec4(0.0f);
#endif

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_RESTIR_VALIDITY_FINAL_PASS
    vec4 current = texelFetch(restir_gi_validity_current, frag_tex_coord, 0);
    vec4 accumulated = texelFetch(restir_lighting, frag_tex_coord, 0);
    bool finite_history = !any(isnan(accumulated))
        && !any(isinf(accumulated));
    bool history_accepted = finite_history && accumulated.a > 0.0f;

    // R=post-r7 history accepted, G=current direct evidence,
    // B=current GI batch, A=current total energy evidence.
    validity_final_out = vec4(
        history_accepted ? 1.0f : 0.0f,
        current.r,
        current.g,
        current.b
    );
#else
    vec3 current_lighting = texelFetch(restir_lighting, frag_tex_coord, 0).rgb;
#if defined PH_ENABLE_BLOCKLIGHT
    current_lighting += texelFetch(
        restir_external_lighting,
        frag_tex_coord,
        0
    ).rgb;
#endif

    bool finite_lighting = !any(isnan(current_lighting))
        && !any(isinf(current_lighting));
    float current_energy = ph_luminance(max(current_lighting, vec3(0.0f)));
    bool has_current_energy = finite_lighting && current_energy > 0.000001f;
    bool has_current_direct = has_current_energy;
    bool has_current_gi = false;

#if defined PH_ENABLE_RESTIR_GI
    IndirectReservoir indirect_reservoir = indirect_reservoir_empty();
    bool indirect_loaded = indirect_reservoir_load(
        indirect_reservoir,
        frag_tex_coord
    );
    has_current_gi = indirect_loaded
        && indirect_reservoir_has_batch(indirect_reservoir)
        && ph_world_ready != 0;

    // Remove the current GI estimate before deciding whether the remaining
    // energy is direct. This intentionally reports transport evidence rather
    // than only a reservoir candidate, so exact local direct lighting is not
    // falsely shown as absent.
    vec3 gi_lighting = indirect_reservoir_get_final_color(indirect_reservoir);
    float direct_energy = ph_luminance(max(
        current_lighting - gi_lighting,
        vec3(0.0f)
    ));
    has_current_direct = finite_lighting && direct_energy > 0.000001f;
#endif

#if defined PH_ENABLE_BLOCKLIGHT
    // Preserve a valid, zero-radiance direct proposal as diagnostic evidence.
    // This mirrors r6's domain check without modifying the reservoir.
    DirectReservoir direct_reservoir = direct_reservoir_empty();
    direct_reservoir_load(direct_reservoir, frag_tex_coord);
    uint receiver_token = frag_data_sublevel_token(_frag_data);
    if (direct_reservoir_has_sample(direct_reservoir)
            && direct_sample_matches_receiver_domain(
                direct_reservoir.smple,
                receiver_token
            )) {
        direct_reservoir = direct_reservoir_empty();
    }
    has_current_direct = has_current_direct
        || direct_reservoir_has_sample(direct_reservoir);
#endif

    // R=current direct evidence, G=current GI batch, B=current energy,
    // A=published-tree readiness. These values are never stored in a
    // production history channel.
    validity_current_out = vec4(
        has_current_direct ? 1.0f : 0.0f,
        has_current_gi ? 1.0f : 0.0f,
        has_current_energy ? 1.0f : 0.0f,
        ph_world_ready != 0 ? 1.0f : 0.0f
    );
#endif
}
