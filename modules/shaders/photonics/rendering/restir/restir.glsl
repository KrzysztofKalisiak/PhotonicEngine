#ifndef PH_SHARED_INCLUDE
#define PH_SHARED_INCLUDE

#define MINIMUM_RESERVOIR_WEIGHT 0.000001f

#include "/photonics/utility/color.glsl"

#include "/photonics/rendering/restir/direct/reservoir.glsl"
#include "/photonics/rendering/restir/indirect/reservoir.glsl"

#include "/photonics/utility/projection.glsl"
#include "/photonics/utility/normal_encoding.glsl"
#include "/photonics/rendering/frag/frag_motion.glsl"

#define RESTIR_LIGHTING_OUT 0
#define RESTIR_LIGHTING_VARIANCE_OUT 1
#if defined PH_ENABLE_BLOCKLIGHT
#if defined PH_ENABLE_RESTIR_GI
#define RESTIR_EXTERNAL_LIGHTING_OUT 6
#else
#define RESTIR_EXTERNAL_LIGHTING_OUT 4
#endif
#endif

#if defined PH_RESTIR_SOURCE_HISTORY_DIAGNOSTIC
#if defined PH_ENABLE_BLOCKLIGHT
#define RESTIR_SOURCE_HISTORY_OUT 5
#else
#define RESTIR_SOURCE_HISTORY_OUT 4
#endif
#endif

#if defined PH_ENABLE_RESTIR_GI
#if defined PH_RESTIR_GI_PASS
#define RESTIR_GI_HISTORY_EPOCH_OUT 4
#elif defined PH_ENABLE_BLOCKLIGHT
#define RESTIR_GI_HISTORY_EPOCH_OUT 7
#else
#define RESTIR_GI_HISTORY_EPOCH_OUT 4
#endif
#endif

//ph_required: uniform sampler2D restir_lighting;
//ph_required: uniform sampler2D restir_lighting_variance;

//ph_required: uniform sampler2D prev_restir_lighting;
//ph_required: uniform sampler2D prev_restir_lighting_variance;

#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform sampler2D restir_external_lighting;
//ph_required: uniform sampler2D prev_restir_external_lighting;
#endif

struct SampleHistory {
    vec4 lighting;
    vec4 external_lighting;
    vec4 variance;
};

const float INVALID_SAMPLE_COMPONENT = -999.0f;
const SampleHistory INVALID_HISTORY = SampleHistory(
    vec4(INVALID_SAMPLE_COMPONENT),
    vec4(INVALID_SAMPLE_COMPONENT),
    vec4(INVALID_SAMPLE_COMPONENT)
);
const float PH_HISTORY_POSITION_ERROR_SQ = 0.3f;
const float PH_HISTORY_BASE_PLANE_DISTANCE = 1.0f / 32.0f;
const float PH_HISTORY_MAX_PRECISION_PLANE_DISTANCE = 0.5f;
const float PH_HISTORY_HALF_MIN_NORMAL = 1.0f / 16384.0f;

#if defined PH_ENABLE_RESTIR_GI
bool ph_restir_gi_history_texel_available(ivec2 texel) {
    ivec2 history_size = textureSize(prev_restir_gi_history_epoch, 0);
    return !any(lessThan(texel, ivec2(0)))
        && !any(greaterThanEqual(texel, history_size));
}

bool ph_restir_gi_history_epoch_matches(ivec2 texel) {
    // A streamed voxel tree can invalidate the receiver and every indirect
    // path at once. Do not reuse the previous GI frame while that tree is in
    // flight; path validation alone cannot repair already accumulated
    // radiance from the old layout.
    if (ph_world_settled == 0)
        return false;

    if (!ph_restir_gi_history_texel_available(texel))
        return false;

    return texelFetch(prev_restir_gi_history_epoch, texel, 0).r
            == uint(ph_scene_revision);
}
#else
bool ph_restir_gi_history_epoch_matches(ivec2 texel) {
    return true;
}
#endif

int ph_restir_continuity_lane(ivec2 texture_size) {
#ifdef PH_TEMPORAL_UPSCALER_SOURCE_VALIDATION_LANES
    float normalized_x = (float(frag_tex_coord.x) + 0.5f)
        / float(max(texture_size.x, 1));
    return clamp(int(floor(normalized_x * 4.0f)), 0, 3);
#else
    // Production v113 uses continuous history and direct spatial reuse.
    return 3;
#endif
}

bool ph_restir_use_continuous_history(ivec2 texture_size) {
    int lane = ph_restir_continuity_lane(texture_size);
    return lane == 1 || lane == 3;
}

float ph_restir_half_component_rounding_bound(float stored_component) {
    float magnitude = max(
        abs(stored_component),
        PH_HISTORY_HALF_MIN_NORMAL
    );
    uint exponent_bits = (
        floatBitsToUint(magnitude) >> 23u
    ) & 0xffu;
    int exponent = int(exponent_bits) - 127;
    return exp2(float(exponent - 11));
}

vec3 ph_restir_half_position_rounding_bound(vec3 stored_position) {
    return vec3(
        ph_restir_half_component_rounding_bound(stored_position.x),
        ph_restir_half_component_rounding_bound(stored_position.y),
        ph_restir_half_component_rounding_bound(stored_position.z)
    );
}

float ph_restir_precision_plane_tolerance(
    float base_tolerance,
    vec3 stored_position,
    vec3 expected_position,
    vec3 stored_normal,
    vec3 expected_normal
) {
    // Both positions originate in RGBA16F fragment attachments. Account for
    // the worst projected round-to-nearest error from both endpoints.
    vec3 component_error = ph_restir_half_position_rounding_bound(
        stored_position
    ) + ph_restir_half_position_rounding_bound(expected_position);
    float stored_normal_error = dot(abs(stored_normal), component_error);
    float expected_normal_error = dot(abs(expected_normal), component_error);
    return min(
        PH_HISTORY_MAX_PRECISION_PLANE_DISTANCE,
        base_tolerance + max(stored_normal_error, expected_normal_error)
    );
}

bool ph_restir_history_surface_matches(
    FragData previous_frag,
    vec3 expected_previous_position,
    vec3 expected_previous_normal,
    bool continuous_history,
    float legacy_position_error_sq
) {
    vec3 previous_normal = frag_data_geo_normal(previous_frag);
    if (dot(previous_normal, expected_previous_normal) < 0.99f)
        return false;

    vec3 previous_position = frag_data_player_pos(previous_frag);
    vec3 position_delta = previous_position - expected_previous_position;
    if (!continuous_history) {
        // Preserve the upstream rule in diagnostic lanes 1 and 3.
        return frag_is_bad_angle
            || dot(position_delta, position_delta)
                < legacy_position_error_sq;
    }

    float plane_distance = max(
        abs(dot(position_delta, previous_normal)),
        abs(dot(position_delta, expected_previous_normal))
    );
    return plane_distance <= ph_restir_precision_plane_tolerance(
        PH_HISTORY_BASE_PLANE_DISTANCE,
        previous_position,
        expected_previous_position,
        previous_normal,
        expected_previous_normal
    );
}

bool sample_history_is_valid(SampleHistory history) {
    return history.lighting.x != INVALID_SAMPLE_COMPONENT;
}

void sample_history_load(out SampleHistory smple) {
    smple.lighting = texelFetch(restir_lighting, frag_tex_coord, 0);
#if defined PH_ENABLE_BLOCKLIGHT
    smple.external_lighting = texelFetch(restir_external_lighting, frag_tex_coord, 0);
#else
    smple.external_lighting = vec4(0.0f, 0.0f, 0.0f, smple.lighting.a);
#endif
    smple.variance = vec4(0f);
}

SampleHistory sample_history_mix(SampleHistory s1, SampleHistory s2, float a) {
    if (!sample_history_is_valid(s1)) {
        a = 1f;
    } else if (!sample_history_is_valid(s2)) {
        a = 0f;
    } else if (!sample_history_is_valid(s1) && !sample_history_is_valid(s2)) {
        return INVALID_HISTORY;
    }

    return SampleHistory(
        mix(s1.lighting, s2.lighting, a),
        mix(s1.external_lighting, s2.external_lighting, a),
        mix(s1.variance, s2.variance, a)
    );
}

SampleHistory sample_history_reproject_single(
    ivec2 texel,
    vec3 previous_player_pos,
    vec3 expected_previous_normal,
    uint sublevel_token,
    float distance_factor
) {
    ivec2 history_size = textureSize(prev_restir_lighting, 0);
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, history_size)))
        return INVALID_HISTORY;

#if defined PH_ENABLE_RESTIR_GI
    if (!ph_restir_gi_history_epoch_matches(texel))
        return INVALID_HISTORY;
#endif

    FragData prev_frag;
    frag_data_load_previous(prev_frag, texel);

    if (!frag_data_is_in_world(prev_frag)) return INVALID_HISTORY;
    if (frag_data_sublevel_token(prev_frag) != sublevel_token) return INVALID_HISTORY;

    bool continuous_history = ph_restir_use_continuous_history(
        history_size
    );
    if (!ph_restir_history_surface_matches(
            prev_frag,
            previous_player_pos,
            expected_previous_normal,
            continuous_history,
            distance_factor
    )) return INVALID_HISTORY;

    vec4 lighting = texelFetch(prev_restir_lighting, ivec2(texel), 0);
    if (any(isnan(lighting)) || any(isinf(lighting))) return INVALID_HISTORY;

#if defined PH_ENABLE_BLOCKLIGHT
    vec4 external_lighting = texelFetch(prev_restir_external_lighting, ivec2(texel), 0);
    if (any(isnan(external_lighting)) || any(isinf(external_lighting))) return INVALID_HISTORY;
#else
    vec4 external_lighting = vec4(0.0f, 0.0f, 0.0f, lighting.a);
#endif

    vec4 variance = texelFetch(prev_restir_lighting_variance, ivec2(texel), 0);
    if (any(isnan(variance)) || any(isinf(variance))) return INVALID_HISTORY;

    return SampleHistory(lighting, external_lighting, variance);
}

SampleHistory sample_history_reproject_mixed(
    vec2 center,
    vec3 previous_player_pos,
    vec3 expected_previous_normal,
    uint sublevel_token,
    float distance_factor
) {
    ivec2 icenter = ivec2(center);

    SampleHistory c_00 = sample_history_reproject_single(icenter + ivec2(0, 0), previous_player_pos, expected_previous_normal, sublevel_token, distance_factor);
    SampleHistory c_10 = sample_history_reproject_single(icenter + ivec2(1, 0), previous_player_pos, expected_previous_normal, sublevel_token, distance_factor);
    SampleHistory c_01 = sample_history_reproject_single(icenter + ivec2(0, 1), previous_player_pos, expected_previous_normal, sublevel_token, distance_factor);
    SampleHistory c_11 = sample_history_reproject_single(icenter + ivec2(1, 1), previous_player_pos, expected_previous_normal, sublevel_token, distance_factor);

    SampleHistory result = sample_history_mix(
        sample_history_mix(c_00, c_10, fract(center.x)),
        sample_history_mix(c_01, c_11, fract(center.x)),
        fract(center.y)
    );

    if (!sample_history_is_valid(result))
        return SampleHistory(vec4(0.0f), vec4(0.0f), vec4(0.0f));

    return result;
}

void sample_history_reproject(out SampleHistory smple) {
    vec3 previous_player_pos;
    vec3 expected_previous_normal;
    uint sublevel_token;

    vec2 center = (ph_reproject_frag_data(
        _frag_data,
        frag_tex_coord,
        frag_is_hand,
        get_taa_jitter(),
        previous_player_pos,
        expected_previous_normal,
        sublevel_token
    ).xy * vec2(textureSize(prev_restir_lighting, 0))) - 0.5f;

    const float distance_factor = PH_HISTORY_POSITION_ERROR_SQ;

    smple = sample_history_reproject_mixed(
        center,
        previous_player_pos,
        expected_previous_normal,
        sublevel_token,
        distance_factor
    );
}

bool sample_history_reproject_nearest_texel(out ivec2 texel) {
    vec3 previous_player_pos;
    vec3 expected_previous_normal;
    uint sublevel_token;

    vec2 uv = ph_reproject_frag_data(
        _frag_data,
        frag_tex_coord,
        frag_is_hand,
        get_taa_jitter(),
        previous_player_pos,
        expected_previous_normal,
        sublevel_token
    ).xy;

    if (any(lessThan(uv, vec2(0.0f))) || any(greaterThanEqual(uv, vec2(1.0f))))
        return false;

    ivec2 history_size = textureSize(prev_restir_lighting, 0);
    texel = ivec2(uv * vec2(history_size));
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, history_size)))
        return false;

    FragData prev_frag;
    frag_data_load_previous(prev_frag, texel);

    if (!frag_data_is_in_world(prev_frag)) return false;
    if (frag_data_sublevel_token(prev_frag) != sublevel_token) return false;

    return ph_restir_history_surface_matches(
        prev_frag,
        previous_player_pos,
        expected_previous_normal,
        ph_restir_use_continuous_history(history_size),
        PH_HISTORY_POSITION_ERROR_SQ
    );
}

const int DIRECT_HISTORY_UNKNOWN = 0;
const int DIRECT_HISTORY_MISMATCH = 1;
const int DIRECT_HISTORY_VERIFIED = 2;
const float PH_RELATIVE_LIGHT_MAX_TRAIL_BLOCKS = 0.15f;
const float PH_RELATIVE_LIGHT_MIN_HISTORY_FRAMES = 2.0f;
const float PH_RELATIVE_LIGHT_FALLBACK_HISTORY_FRAMES = 4.0f;
const float PH_SABLE_AMBIGUOUS_HISTORY_FRAMES = 8.0f;

bool sample_history_light_relative_step(
    int light_index,
    int receiver_slot,
    uint receiver_token,
    out float relative_step
) {
    vec4 previous_position = light_list_get_previous_position(light_index);
    if (previous_position.w < 0.5f) {
        relative_step = 0.0f;
        return false;
    }

    Light light = light_list_get(light_index);
    if (receiver_token == 0u) {
        relative_step = length(light.position - previous_position.xyz);
    } else {
        relative_step = ph_sable_receiver_relative_light_step(
            receiver_slot,
            receiver_token,
            light.position - rt_camera_position,
            previous_position.xyz - (previousCameraPosition - world_offset)
        );
    }

    return !isnan(relative_step) && !isinf(relative_step);
}

int sample_history_direct_provenance(
    bool external_stream,
    out bool involves_moving_light,
    out bool same_sublevel_light,
    out bool sable_representative_mismatch,
    out float relative_light_step,
    out bool relative_light_step_valid
) {
    involves_moving_light = false;
    same_sublevel_light = false;
    sable_representative_mismatch = false;
    relative_light_step = 0.0f;
    relative_light_step_valid = false;

#if defined PH_ENABLE_BLOCKLIGHT
    int receiver_slot = frag_data_sublevel_slot(_frag_data);
    uint receiver_token = frag_data_sublevel_token(_frag_data);

    DirectReservoir current_reservoir = direct_reservoir_empty();
    bool current_loaded = direct_reservoir_load(
        current_reservoir,
        frag_tex_coord
    );
    vec2 current_state;
    bool current_visible = direct_history_load(current_state, frag_tex_coord);
    bool current_has_sample = current_loaded
        && direct_reservoir_has_sample(current_reservoir);
    if (current_has_sample) {
        bool current_is_external = direct_sample_uses_external_history(
            current_reservoir.smple,
            receiver_token
        );
        current_has_sample = current_is_external == external_stream;
    }

    DirectReservoir previous_reservoir = direct_reservoir_empty();
    bool previous_loaded = false;
    bool previous_visible = false;
    bool previous_state_loaded = false;
    vec2 previous_state = vec2(0.0f);
    ivec2 previous_texel;
    if (sample_history_reproject_nearest_texel(previous_texel)) {
        previous_loaded = direct_reservoir_load_previous(
            previous_reservoir,
            previous_texel
        );
        previous_visible = direct_history_load_previous(
            previous_state,
            previous_texel
        );
        previous_state_loaded = true;
    }
    bool previous_has_sample = previous_loaded
        && direct_reservoir_has_sample(previous_reservoir);
    if (previous_has_sample) {
        bool previous_is_external = direct_sample_uses_external_history(
            previous_reservoir.smple,
            receiver_token
        );
        previous_has_sample = previous_is_external == external_stream;
    }

    if (receiver_token != 0u && !external_stream
            && previous_state_loaded
            && (any(isnan(current_state)) || any(isinf(current_state))
                || any(isnan(previous_state)) || any(isinf(previous_state))
                || abs(current_state.y - previous_state.y) > 0.5f))
        return DIRECT_HISTORY_MISMATCH;

    if (current_has_sample)
        involves_moving_light = current_reservoir.smple.light_index
            < ph_moving_light_count;
    if (previous_has_sample)
        involves_moving_light = involves_moving_light
            || previous_reservoir.smple.light_index < ph_moving_light_count;

    if (current_has_sample) {
        relative_light_step_valid = sample_history_light_relative_step(
            current_reservoir.smple.light_index,
            receiver_slot,
            receiver_token,
            relative_light_step
        );
    } else if (previous_has_sample) {
        relative_light_step_valid = sample_history_light_relative_step(
            previous_reservoir.smple.light_index,
            receiver_slot,
            receiver_token,
            relative_light_step
        );
    }

    // A world receiver needs the most reactive of the two representatives so
    // a moving footprint cannot survive a stochastic representative switch.
    // A Sable receiver instead follows the current representative's motion
    // domain; mixing two unrelated domains here caused co-moving histories to
    // collapse to the two-frame floor whenever ReSTIR changed representatives.
    if (receiver_token == 0u && current_has_sample && previous_has_sample
            && previous_reservoir.smple.light_index
                != current_reservoir.smple.light_index) {
        float previous_step;
        bool previous_step_valid = sample_history_light_relative_step(
            previous_reservoir.smple.light_index,
            receiver_slot,
            receiver_token,
            previous_step
        );
        relative_light_step_valid = relative_light_step_valid
            && previous_step_valid;
        if (relative_light_step_valid)
            relative_light_step = max(relative_light_step, previous_step);
    }

    if (receiver_token != 0u) {
        bool current_same_sublevel = false;
        bool previous_same_sublevel = false;
        if (current_has_sample) {
            current_same_sublevel = direct_sample_get_temporal_domain(
                current_reservoir.smple
            ) == int(receiver_token);
        }
        if (previous_has_sample) {
            previous_same_sublevel = direct_sample_get_temporal_domain(
                previous_reservoir.smple
            ) == int(receiver_token);
        }

        same_sublevel_light = !external_stream
            && current_has_sample
            && previous_has_sample
            && current_same_sublevel
            && previous_same_sublevel;
        if (external_stream) {
            sable_representative_mismatch = !current_has_sample
                || !previous_has_sample
                || current_reservoir.smple.light_index
                    != previous_reservoir.smple.light_index;
        }
    }

    // A reservoir stores one stochastic representative of total direct light.
    // Different representatives are normal and do not prove discontinuity.
    if (!current_has_sample || !previous_has_sample
            || current_reservoir.smple.light_index
                != previous_reservoir.smple.light_index)
        return DIRECT_HISTORY_UNKNOWN;

    // Visibility is comparable only for the same remapped light.
    if (current_visible != previous_visible)
        return DIRECT_HISTORY_MISMATCH;
    if (!current_visible)
        return DIRECT_HISTORY_UNKNOWN;

    return DIRECT_HISTORY_VERIFIED;
#else
    return DIRECT_HISTORY_UNKNOWN;
#endif
}

float sample_history_accumulation_limit(vec4 stream_history, bool external_stream) {
    bool involves_moving_light;
    bool same_sublevel_light;
    bool sable_representative_mismatch;
    float relative_light_step;
    bool relative_light_step_valid;
    int direct_history = sample_history_direct_provenance(
        external_stream,
        involves_moving_light,
        same_sublevel_light,
        sable_representative_mismatch,
        relative_light_step,
        relative_light_step_valid
    );
    bool sable_receiver = frag_data_sublevel_token(_frag_data) != 0u;

    if (direct_history == DIRECT_HISTORY_MISMATCH)
        return 0.0f;

#if !defined PH_ENABLE_RESTIR_GI
    // The stable stream of a Sable receiver now contains only samples whose
    // motion-domain token matches that receiver. Its local lighting is rigidly
    // invariant even when ReSTIR selects an external representative for one or
    // more frames. In combined-GI mode this target also carries world-relative
    // bounced light, so that mode keeps the conservative evidence-based limit.
    if (sable_receiver && !external_stream)
        return float(PH_RESTIR_ACCUMULATION_FRAMES);
#endif

    bool moving_receiver = false;
    if (sable_receiver) {
        FragMotion motion;
        frag_motion_load(motion, frag_tex_coord);
        vec3 current_world_pos = frag_data_player_pos(_frag_data) + cameraPosition;
        vec3 previous_world_pos = motion.previous_player_pos + previousCameraPosition;
        vec3 world_motion = current_world_pos - previous_world_pos;
        float normal_alignment = dot(
            frag_data_geo_normal(_frag_data),
            motion.previous_geo_normal
        );
        moving_receiver = dot(world_motion, world_motion) > 1e-6f
            || normal_alignment < 0.9999f;
    }

    bool reactive = moving_receiver
        || involves_moving_light
        || (relative_light_step_valid && relative_light_step > 1e-6f);
    if (!reactive)
        return float(PH_RESTIR_ACCUMULATION_FRAMES);

    float maximum_history = float(PH_RESTIR_ACCUMULATION_FRAMES);
    if (same_sublevel_light)
        return maximum_history;

    float history_limit;
    if (external_stream && sable_receiver && sable_representative_mismatch) {
        // Reservoir identity changes are sampling noise, not a measured
        // emitter/receiver velocity. Match v39's bounded ambiguity behavior
        // until the same external representative persists for another frame.
        history_limit = min(
            maximum_history,
            PH_SABLE_AMBIGUOUS_HISTORY_FRAMES
        );
    } else if (relative_light_step_valid) {
        history_limit = clamp(
            PH_RELATIVE_LIGHT_MAX_TRAIL_BLOCKS
                / max(relative_light_step, 1e-6f),
            PH_RELATIVE_LIGHT_MIN_HISTORY_FRAMES,
            maximum_history
        );

        // The CPU keeps a moving light reactive briefly after it stops. World
        // receivers use that window to drain the old footprint before growing
        // full history again. Receiver-local motion remains authoritative for
        // Sable receivers so co-moving sublevels retain stable accumulation.
        if (!sable_receiver && involves_moving_light
                && relative_light_step <= 1e-6f)
            history_limit = min(
                history_limit,
                PH_RELATIVE_LIGHT_FALLBACK_HISTORY_FRAMES
            );
    } else {
        // Motion from an unrelated Sable domain must not shorten this pixel's
        // external history. The current and reprojected representatives carry
        // the motion-domain evidence used here.
        history_limit = involves_moving_light
            ? PH_RELATIVE_LIGHT_FALLBACK_HISTORY_FRAMES
            : (direct_history == DIRECT_HISTORY_VERIFIED ? 12.0f : 8.0f);
    }

    if (stream_history.a < 0.5f)
        history_limit = min(
            history_limit,
            PH_RELATIVE_LIGHT_FALLBACK_HISTORY_FRAMES
        );

    return min(maximum_history, history_limit);
}

void sample_history_combine_component(
    inout vec4 history,
    vec4 smple,
    float accumulation_limit
) {
#if PH_RESTIR_DENOISER_PASSES != 0
    history.w = min(history.w, accumulation_limit);
    history.rgb = mix(history.rgb, smple.rgb, 1f / (++history.w));
#else
    if (history.a > accumulation_limit) {
        history.rgb *= accumulation_limit / history.a;
        history.a = accumulation_limit;
    }
    if (history.a >= PH_RESTIR_ACCUMULATION_FRAMES - 1f)
        history *= ((PH_RESTIR_ACCUMULATION_FRAMES - 1f) / history.a);

    history.rgb += smple.rgb;
    history.a++;
#endif
}

void sample_history_combine_lighting(inout SampleHistory history, in SampleHistory smple) {
    float stable_limit = sample_history_accumulation_limit(
        history.lighting,
        false
    );
    float external_limit = sample_history_accumulation_limit(
        history.external_lighting,
        true
    );

    sample_history_combine_component(
        history.lighting,
        smple.lighting,
        stable_limit
    );
    sample_history_combine_component(
        history.external_lighting,
        smple.external_lighting,
        external_limit
    );
}

void sample_history_combine_moment(inout SampleHistory history, in SampleHistory smple) {
    float samples = max(
        1.0f,
        min(history.lighting.a, history.external_lighting.a)
    );
    float moment_alpha = 1f / samples;
    vec2 moments = vec2(0f);

    vec3 combined_lighting = smple.lighting.rgb + smple.external_lighting.rgb;
    moments.x = dot(combined_lighting, vec3(0.299, 0.587, 0.114));
    moments.y = moments.x * moments.x;

    history.variance.xy = mix(history.variance.xy, moments, moment_alpha);
    history.variance.w = samples;
}

#if PH_RESTIR_ACCUMULATION_FRAMES < 4
float sample_history_min_variance(float samples) {
    return 1.0f;
}
#else
float sample_history_min_variance(float samples) {
    const float high_variance = 100.0f;

    if (samples > 4.0f) return 0.0001f;
    if (samples > 2.0f) return 0.01f;
    if (frag_is_hand) return high_variance;

    const float padding = 0.13f;
    const vec2 minimum = vec2(padding) * PH_ACTIVE_RENDER_SCALE;
    const vec2 maximum = vec2(1.0f - padding) * PH_ACTIVE_RENDER_SCALE;
    vec2 uv = gl_FragCoord.xy / vec2(viewWidth, viewHeight);

    return clamp(uv, minimum, maximum) != uv
        ? high_variance
        : 0.01f;
}
#endif

void sample_history_compute_variance(inout SampleHistory history, in SampleHistory smple) {
    float samples = max(history.variance.w, 1.0f);
    float sample_variance = max(
        history.variance.y - (history.variance.x * history.variance.x),

        // With few samples, variance estimate is unreliable — use a high floor
        sample_history_min_variance(samples)
    );

    history.variance.z = sample_variance / samples;
}

#endif
