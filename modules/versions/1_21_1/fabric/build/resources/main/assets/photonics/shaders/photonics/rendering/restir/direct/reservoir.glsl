#include "/photonics/rendering/restir/direct/sample.glsl"
#include "/photonics/rendering/frag/sable_motion.glsl"

#define DIRECT_RESERVOIR_0 2
#define DIRECT_HISTORY_STATE_0 3

//ph_required: uniform sampler2D restir_direct_reservoirs0;
//ph_required: uniform sampler2D prev_restir_direct_reservoirs0;
//ph_required: uniform sampler2D restir_direct_state;
//ph_required: uniform sampler2D prev_restir_direct_state;

const float max_direct_temporal_samples = 20.0f * PH_RESTIR_INITIAL_SAMPLES;
const float max_direct_reservoir_samples = 128.0f;
const int PH_DIRECT_VISIBILITY_ITERATIONS = 100;

struct DirectReservoir {
    DirectSample smple;

    float weight;
    float total_samples;
};

bool direct_reservoir_is_nan(DirectReservoir reservoir) {
    return isnan(reservoir.weight) || isinf(reservoir.weight) ||
        isnan(reservoir.total_samples) || isinf(reservoir.total_samples);
}

DirectReservoir direct_reservoir_empty() {
    return DirectReservoir(
        direct_sample_empty(),
        0.0f,
        0.0f
    );
}

bool direct_reservoir_is_empty(DirectReservoir reservoir) {
    return direct_sample_is_empty(reservoir.smple);
}

bool direct_reservoir_has_sample(DirectReservoir reservoir) {
    return !direct_reservoir_is_empty(reservoir)
        && reservoir.total_samples > 0.0f
        && !direct_reservoir_is_nan(reservoir);
}

bool direct_reservoir_has_batch(DirectReservoir reservoir) {
    return reservoir.total_samples > 0.0f
        && !direct_reservoir_is_nan(reservoir);
}

// Zero is the explicit final-visibility rejection marker. Positive weights,
// including very dim valid samples, remain eligible for reuse.
bool direct_reservoir_is_reusable(DirectReservoir reservoir) {
    return direct_reservoir_has_sample(reservoir) && reservoir.weight > 0.0f;
}

bool direct_history_state_is_visible(vec2 state) {
    return !any(isnan(state)) && !any(isinf(state)) && state.x >= 0.5f;
}

bool direct_history_load(out vec2 state, ivec2 tex_coord) {
    state = texelFetch(restir_direct_state, tex_coord, 0).rg;
    return direct_history_state_is_visible(state);
}

bool direct_history_load_previous(out vec2 state, ivec2 tex_coord) {
    state = texelFetch(prev_restir_direct_state, tex_coord, 0).rg;
    return direct_history_state_is_visible(state);
}

void direct_history_encode(DirectReservoir reservoir, out vec2 state) {
    if (!direct_reservoir_is_reusable(reservoir)) {
        state = vec2(0.0f);
        return;
    }

    // Keep a compact confidence value beside the final visibility bit. The
    // current policy only consumes the bit, but the confidence is useful when
    // expanding the reactive policy without adding another history target.
    state = vec2(
        1.0f,
        min(reservoir.total_samples / max_direct_temporal_samples, 1.0f)
    );
}

bool direct_reservoir_update(
    inout DirectReservoir reservoir,
    DirectSample smple,
    float weight,
    float samples
) {
    reservoir.total_samples += samples;
    if (weight <= 0.0f || isnan(weight) || isinf(weight))
        return false;

    reservoir.weight += weight;

    float required_rng = weight / reservoir.weight;
    if (ph_rand_next_float(frag_rnd_state) < required_rng) {
        reservoir.smple = smple;
        return true;
    }

    return false;
}

bool direct_reservoir_merge(
    inout DirectReservoir result,
    DirectReservoir other,
    inout float sample_weight
) {
    if (!direct_reservoir_has_sample(other))
        return false;

    // A Sable receiver's reservoir estimates only lights outside its own
    // motion domain. Reject stale representatives produced while that pixel
    // was unclassified or belonged to another receiver; their all-light
    // normalization cannot be converted into an external-only batch.
    if (direct_sample_matches_receiver_domain(
            other.smple,
            frag_data_sublevel_token(_frag_data)
    )) return false;

    // A visibility-rejected reservoir represents a valid zero-contribution
    // batch. Its M must remain in the estimator denominator; dropping it lets
    // an older visible representative survive at excessive brightness.
    if (!direct_reservoir_is_reusable(other)) {
        result.total_samples += other.total_samples;
        return false;
    }

    float other_sample_weight = direct_sample_get_weight(
        other.smple,
        frag_rt_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal
    );
    if (other_sample_weight <= 0.0f || isnan(other_sample_weight) || isinf(other_sample_weight))
        return false;

    float other_weight = other_sample_weight * other.weight * other.total_samples;
    if (direct_reservoir_update(result, other.smple, other_weight, other.total_samples)) {
        sample_weight = other_sample_weight;
        return true;
    }

    return false;
}

bool direct_reservoir_merge_current_batch(
    inout DirectReservoir result,
    DirectReservoir current,
    inout float sample_weight
) {
    if (direct_reservoir_has_sample(current))
        return direct_reservoir_merge(result, current, sample_weight);

    // The fresh same-pixel proposal batch can legitimately contain only
    // zero-target candidates after the receiver-domain partition. Its M still
    // belongs in this frame's estimator denominator. Identity-less batches
    // are deliberately not transferred from temporal or spatial neighbors.
    if (direct_reservoir_has_batch(current))
        result.total_samples += current.total_samples;
    return false;
}

void direct_reservoir_clamp_samples(inout DirectReservoir reservoir) {
    if (reservoir.total_samples <= max_direct_reservoir_samples) return;

    reservoir.weight *= max_direct_reservoir_samples / reservoir.total_samples;
    reservoir.total_samples = max_direct_reservoir_samples;
}

void direct_reservoir_validate_visiblity(inout DirectReservoir reservoir, vec3 sample_pos, vec3 geo_normal) {
    if (direct_sample_is_empty(reservoir.smple)) return;

    Light light = direct_sample_get_light(reservoir.smple);

    if (!ph_sable_same_sublevel_light_visible(
            frag_data_sublevel_slot(_frag_data),
            frag_data_sublevel_token(_frag_data),
            sample_pos + world_offset,
            geo_normal,
            direct_sample_get_temporal_domain(reservoir.smple),
            light.position + world_offset
    )) {
        reservoir.weight = 0.0f;
        return;
    }

    vec3 to_light = light.position - sample_pos;

    vec3 unused0;
    float unused1;

    if (!trace_light_vis(sample_pos, geo_normal, to_light, light.position, PH_DIRECT_VISIBILITY_ITERATIONS, unused0, unused1))
        reservoir.weight = 0.0f;
}

void direct_reservoir_finalize_weight(
    inout DirectReservoir reservoir,
    float sample_weight
) {
    if (sample_weight <= 0.0f || isnan(sample_weight) || isinf(sample_weight) ||
        reservoir.total_samples <= 0.0f || direct_reservoir_is_nan(reservoir)) {
        if (reservoir.total_samples > 0.0f
                && reservoir.weight == 0.0f
                && !isnan(reservoir.total_samples)
                && !isinf(reservoir.total_samples)) {
            reservoir.smple = direct_sample_empty();
            return;
        }
        reservoir = direct_reservoir_empty();
        return;
    }

    float final_weight = (1.0f / sample_weight) * (reservoir.weight / reservoir.total_samples);
    if (isnan(final_weight) || isinf(final_weight)) {
        reservoir = direct_reservoir_empty();
        return;
    }

    reservoir.weight = final_weight;
}

bool direct_color_is_finite(vec3 color) {
    return !any(isnan(color)) && !any(isinf(color));
}

vec3 direct_reservoir_get_unshadowed_color(
    DirectReservoir reservoir,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (direct_sample_is_empty(reservoir.smple))
        return vec3(0.0f);

    Light light = direct_sample_get_light(reservoir.smple);
    return direct_sample_get_color(reservoir.smple, light, sample_pos, geo_normal, tex_normal) * reservoir.weight;
}

bool direct_sample_get_final_unweighted_color(
    DirectSample smple,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    out vec3 result
) {
    result = vec3(0.0f);
    if (direct_sample_is_empty(smple))
        return false;

    Light light = direct_sample_get_light(smple);
    vec3 sampled_color = direct_sample_get_color(
        smple,
        light,
        sample_pos,
        geo_normal,
        tex_normal
    );
    if (!direct_color_is_finite(sampled_color)
            || direct_sample_weight(sampled_color) <= 0.0f)
        return false;

#ifdef PH_DISABLE_RESTIR_VISIBILITY
    result = sampled_color;
    return true;
#else
    vec3 trace_position = light.position;
#ifdef PH_RESTIR_SOFT_SHADOWS
    ph_rand_sample_position(frag_rnd_state, trace_position, sample_pos);
#endif

    if (!ph_sable_same_sublevel_light_visible(
            frag_data_sublevel_slot(_frag_data),
            frag_data_sublevel_token(_frag_data),
            sample_pos + world_offset,
            geo_normal,
            direct_sample_get_temporal_domain(smple),
            light.position + world_offset
    )) return false;

    vec3 to_light = trace_position - sample_pos;
    vec3 tint_color;
    float light_transmittance;
    if (!trace_light_vis(
            sample_pos,
            geo_normal,
            to_light,
            trace_position,
            PH_DIRECT_VISIBILITY_ITERATIONS,
            tint_color,
            light_transmittance
    )) return false;

    result = sampled_color * tint_color * light_transmittance;
    if (!direct_color_is_finite(result)) {
        result = vec3(0.0f);
        return false;
    }
    return true;
#endif
}

vec3 direct_reservoir_get_final_color(
    inout DirectReservoir reservoir,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (direct_sample_is_empty(reservoir.smple))
        return vec3(0.0f);

    vec3 final_color;
    if (!direct_sample_get_final_unweighted_color(
            reservoir.smple,
            sample_pos,
            geo_normal,
            tex_normal,
            final_color
    )) {
        reservoir.weight = 0.0f;
        return vec3(0.0f);
    }

    vec3 result = final_color * reservoir.weight;
    if (!direct_color_is_finite(result)) {
        reservoir = direct_reservoir_empty();
        return vec3(0.0f);
    }
    return result;
}

void direct_reservoir_encode(DirectReservoir reservoir, out vec3 data0) {
    if (direct_reservoir_is_nan(reservoir))
        reservoir = direct_reservoir_empty();

    data0[0] = float(reservoir.smple.light_index);
    data0[1] = max(reservoir.weight, 0.0f);
    data0[2] = reservoir.total_samples;
}

void direct_reservoir_decode(out DirectReservoir reservoir, vec3 data0) {
    if (any(isnan(data0)) || any(isinf(data0))) {
        reservoir = direct_reservoir_empty();
        return;
    }

    reservoir.smple.light_index = int(data0[0]);
    reservoir.weight            = data0[1];
    reservoir.total_samples     = data0[2];
}

bool direct_reservoir_load(out DirectReservoir reservoir, ivec2 tex_coord) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(restir_direct_reservoirs0, tex_coord, 0).rgb
    );

    if (direct_reservoir_is_nan(reservoir)) {
        reservoir = direct_reservoir_empty();
        return false;
    }

    return true;
}

bool direct_reservoir_load_flipped(out DirectReservoir reservoir, ivec2 tex_coord) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(prev_restir_direct_reservoirs0, tex_coord, 0).rgb
    );

    if (direct_reservoir_is_nan(reservoir)) {
        reservoir = direct_reservoir_empty();
        return false;
    }

    return true;
}

bool direct_reservoir_load_previous(out DirectReservoir reservoir, ivec2 tex_coord) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(prev_restir_direct_reservoirs0, tex_coord, 0).rgb
    );

    if (direct_reservoir_is_nan(reservoir) || !direct_sample_reproject(reservoir.smple)) {
        reservoir = direct_reservoir_empty();
        return false;
    }

    return true;
}
