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

//ph_required: uniform sampler2D restir_lighting;
//ph_required: uniform sampler2D restir_lighting_variance;

//ph_required: uniform sampler2D prev_restir_lighting;
//ph_required: uniform sampler2D prev_restir_lighting_variance;

struct SampleHistory {
    vec4 lighting;
    vec4 variance;
};

const float INVALID_SAMPLE_COMPONENT = -999.0f;
const SampleHistory INVALID_HISTORY = SampleHistory(vec4(INVALID_SAMPLE_COMPONENT), vec4(INVALID_SAMPLE_COMPONENT));

bool sample_history_is_valid(SampleHistory history) {
    return history.lighting.x != INVALID_SAMPLE_COMPONENT;
}

void sample_history_load(out SampleHistory smple) {
    smple.lighting = texelFetch(restir_lighting, frag_tex_coord, 0),
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

    FragData prev_frag;
    frag_data_load_previous(prev_frag, texel);

    if (!frag_data_is_in_world(prev_frag)) return INVALID_HISTORY;
    if (frag_data_sublevel_token(prev_frag) != sublevel_token) return INVALID_HISTORY;

    if (!frag_is_bad_angle) {
        vec3 projected_player_pos = frag_data_player_pos(prev_frag);
        vec3 d = projected_player_pos - previous_player_pos;

        if (dot(d, d) > distance_factor) return INVALID_HISTORY;
    }

    vec3 n = frag_data_geo_normal(prev_frag);
    if (dot(n, expected_previous_normal) < 0.99f) return INVALID_HISTORY;

    vec4 lighting = texelFetch(prev_restir_lighting, ivec2(texel), 0);
    if (any(isnan(lighting)) || any(isinf(lighting))) return INVALID_HISTORY;

    vec4 variance = texelFetch(prev_restir_lighting_variance, ivec2(texel), 0);
    if (any(isnan(variance)) || any(isinf(variance))) return INVALID_HISTORY;

    return SampleHistory(lighting, variance);
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
        return SampleHistory(vec4(0.0f), vec4(0.0f));

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

    const float block_divsor = 64.0f * PH_RENDER_SCALE;
    float distance_factor = max(dot(previous_player_pos, previous_player_pos) / block_divsor, 0.1f);

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

    const float block_divsor = 64.0f * PH_RENDER_SCALE;
    float distance_factor = max(dot(previous_player_pos, previous_player_pos) / block_divsor, 0.1f);
    if (!frag_is_bad_angle) {
        vec3 projected_player_pos = frag_data_player_pos(prev_frag);
        vec3 d = projected_player_pos - previous_player_pos;
        if (dot(d, d) > distance_factor) return false;
    }

    return dot(frag_data_geo_normal(prev_frag), expected_previous_normal) >= 0.99f;
}

const int DIRECT_HISTORY_UNKNOWN = 0;
const int DIRECT_HISTORY_MISMATCH = 1;
const int DIRECT_HISTORY_VERIFIED = 2;

int sample_history_direct_provenance() {
#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir current_reservoir = direct_reservoir_empty();
    vec2 current_state;
    bool current_visible = direct_history_load(current_state, frag_tex_coord)
        && direct_reservoir_load(current_reservoir, frag_tex_coord);

    DirectReservoir previous_reservoir = direct_reservoir_empty();
    bool previous_visible = false;
    ivec2 previous_texel;
    if (sample_history_reproject_nearest_texel(previous_texel)) {
        vec2 previous_state;
        previous_visible = direct_history_load_previous(previous_state, previous_texel)
            && direct_reservoir_load_previous(previous_reservoir, previous_texel);
    }

    if (!current_visible && !previous_visible)
        return DIRECT_HISTORY_UNKNOWN;
    if (!current_visible || !previous_visible)
        return DIRECT_HISTORY_MISMATCH;

    return current_reservoir.smple.light_index == previous_reservoir.smple.light_index
        ? DIRECT_HISTORY_VERIFIED
        : DIRECT_HISTORY_MISMATCH;
#else
    return DIRECT_HISTORY_UNKNOWN;
#endif
}

float sample_history_accumulation_limit(SampleHistory history, SampleHistory smple) {
    if (frag_data_sublevel_token(_frag_data) == 0u)
        return float(PH_RESTIR_ACCUMULATION_FRAMES);

    FragMotion motion;
    frag_motion_load(motion, frag_tex_coord);
    vec3 current_world_pos = frag_data_player_pos(_frag_data) + cameraPosition;
    vec3 previous_world_pos = motion.previous_player_pos + previousCameraPosition;
    vec3 world_motion = current_world_pos - previous_world_pos;
    float normal_alignment = dot(
        frag_data_geo_normal(_frag_data),
        motion.previous_geo_normal
    );

    if (dot(world_motion, world_motion) <= 1e-6f && normal_alignment >= 0.9999f)
        return float(PH_RESTIR_ACCUMULATION_FRAMES);

    int direct_history = sample_history_direct_provenance();
    if (direct_history == DIRECT_HISTORY_MISMATCH)
        return 0.0f;

    // Geometry edits change the token and arrive here with no retained samples.
    // For rigid motion, preserve a longer history only when both frames have
    // a final-visible reservoir for the same remapped light. Any new shadow
    // edge, light remap, or failed visibility check resets immediately.
    if (history.lighting.a < 0.5f)
        return min(float(PH_RESTIR_ACCUMULATION_FRAMES), 4.0f);

    vec3 previous_color = max(history.lighting.rgb, vec3(0.0f));
    vec3 current_color = max(smple.lighting.rgb, vec3(0.0f));
    vec3 color_delta = abs(previous_color - current_color);
    float color_scale = max(
        max(
            max(previous_color.x, max(previous_color.y, previous_color.z)),
            max(current_color.x, max(current_color.y, current_color.z))
        ),
        0.02f
    );
    float relative_change = max(color_delta.x, max(color_delta.y, color_delta.z)) / color_scale;
    float relative_change_limit = direct_history == DIRECT_HISTORY_VERIFIED
        ? 0.75f
        : 0.25f;
    if (relative_change > relative_change_limit)
        return 0.0f;

    float verified_history_limit = direct_history == DIRECT_HISTORY_VERIFIED
        ? 8.0f
        : 4.0f;
    return min(float(PH_RESTIR_ACCUMULATION_FRAMES), verified_history_limit);
}

void sample_history_combine_lighting(inout SampleHistory history, in SampleHistory smple) {
    float accumulation_limit = sample_history_accumulation_limit(history, smple);
#if PH_RESTIR_DENOISER_PASSES != 0
    history.lighting.w = min(history.lighting.w, accumulation_limit);
    history.lighting.rgb = mix(history.lighting.rgb, smple.lighting.rgb, 1f / (++history.lighting.w));
#else
    if (history.lighting.a > accumulation_limit) {
        history.lighting.rgb *= accumulation_limit / history.lighting.a;
        history.lighting.a = accumulation_limit;
    }
    if (history.lighting.a >= PH_RESTIR_ACCUMULATION_FRAMES - 1f)
        history.lighting *= ((PH_RESTIR_ACCUMULATION_FRAMES - 1f) / history.lighting.a);

    history.lighting.rgb+= smple.lighting.rgb;
    history.lighting.a++;
#endif
}

void sample_history_combine_moment(inout SampleHistory history, in SampleHistory smple) {
    float moment_alpha = 1f / history.lighting.a;
    vec2 moments = vec2(0f);

    moments.x = dot(smple.lighting.rgb, vec3(0.299, 0.587, 0.114));
    moments.y = moments.x * moments.x;

    history.variance.xy = mix(history.variance.xy, moments, moment_alpha);
    history.variance.w = 1f;
}

void sample_history_compute_variance(inout SampleHistory history, in SampleHistory smple) {
#if PH_RESTIR_ACCUMULATION_FRAMES < 4
    #define PH_MIN_VARIANCE 1f
#else
    #define PH_MIN_VARIANCE (samples < 4f) ? 0.1f : 0.01f
#endif

    float samples = history.lighting.a;
    float sample_variance = max(
        history.variance.y - (history.variance.x * history.variance.x),

        // With few samples, variance estimate is unreliable — use a high floor
        PH_MIN_VARIANCE
    );

    history.variance.z = sample_variance / samples;
}

#endif
