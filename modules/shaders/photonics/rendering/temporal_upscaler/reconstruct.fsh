#version 430

#define PH_ACTIVE_RENDER_SCALE PH_SHADERPACK_RENDER_SCALE

//ph_required: uniform sampler2D photonics_temporal_source;
//ph_required: uniform sampler2D prev_photonics_temporal_lighting;
//ph_required: uniform sampler2D prev_photonics_temporal_surface;

#include "/photonics/rendering/frag/world_interface.glsl"
#include "/photonics/utility/normal_encoding.glsl"
#include "/photonics/rendering/frag/frag_data.glsl"
#include "/photonics/rendering/frag/sable_motion.glsl"
#include "/photonics/utility/projection.glsl"

layout(location = 0) out vec4 temporal_lighting_out;
layout(location = 1) out vec4 temporal_surface_out;

const float PH_UPSCALE_NORMAL_THRESHOLD = 0.85f;
const float PH_UPSCALE_TEXTURE_NORMAL_THRESHOLD = 0.75f;
const float PH_UPSCALE_HISTORY_NORMAL_THRESHOLD = 0.95f;
const float PH_UPSCALE_MIN_PLANE_DISTANCE = 1.0f / 64.0f;
const float PH_UPSCALE_MAX_PLANE_DISTANCE = 1.0f / 32.0f;
const float PH_UPSCALE_MAX_POSITION_DISTANCE_SQ = 9.0f;

bool ph_temporal_finite_vec3(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

float ph_temporal_plane_tolerance(
    ivec2 source_size,
    ivec2 output_size
) {
    vec2 scale = vec2(source_size) / max(vec2(output_size), vec2(1.0f));
    float source_scale = clamp(min(scale.x, scale.y), 0.0f, 1.0f);
    return mix(
        PH_UPSCALE_MIN_PLANE_DISTANCE,
        PH_UPSCALE_MAX_PLANE_DISTANCE,
        1.0f - source_scale
    );
}

float ph_temporal_identity(int slot, uint token, bool hand) {
    uint slot_value = uint(slot + 1);
    uint value = token ^ (slot_value * 0x9e3779b9u)
        ^ (hand ? 0x85ebca6bu : 0xc2b2ae35u);
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    return float((value & 2047u) + 1u);
}

bool ph_source_matches_receiver_domain(
    FragData source_frag,
    int receiver_slot,
    uint receiver_token
) {
    uint source_token = frag_data_sublevel_token(source_frag);
    int source_slot = frag_data_sublevel_slot(source_frag);
    if (source_token != receiver_token)
        return false;

    if (receiver_token == 0u)
        return receiver_slot < 0 && source_slot < 0;

    return receiver_slot >= 0 && source_slot == receiver_slot;
}

bool ph_source_matches_surface(
    FragData source_frag,
    vec3 player_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    bool hand,
    int receiver_slot,
    uint receiver_token,
    float plane_tolerance,
    out float score
) {
    score = -1e30f;
    if (!frag_data_is_in_world(source_frag)
            || frag_data_is_hand(source_frag) != hand
            || !ph_source_matches_receiver_domain(
                source_frag,
                receiver_slot,
                receiver_token
            ))
        return false;

    vec3 source_pos = frag_data_player_pos(source_frag);
    vec3 source_normal = frag_data_geo_normal(source_frag);
    vec3 source_tex_normal = frag_data_tex_normal(source_frag);
    if (!ph_temporal_finite_vec3(source_pos)
            || !ph_temporal_finite_vec3(source_normal)
            || !ph_temporal_finite_vec3(source_tex_normal))
        return false;

    float normal_alignment = dot(source_normal, geo_normal);
    if (normal_alignment < PH_UPSCALE_NORMAL_THRESHOLD)
        return false;

    float texture_normal_alignment = dot(source_tex_normal, tex_normal);
    if (texture_normal_alignment < PH_UPSCALE_TEXTURE_NORMAL_THRESHOLD)
        return false;

    vec3 position_delta = source_pos - player_pos;
    float position_distance_sq = dot(position_delta, position_delta);
    if (position_distance_sq > PH_UPSCALE_MAX_POSITION_DISTANCE_SQ)
        return false;

    float plane_distance = max(
        abs(dot(position_delta, source_normal)),
        abs(dot(position_delta, geo_normal))
    );
    if (plane_distance > plane_tolerance)
        return false;

    score = normal_alignment * 8.0f
        + texture_normal_alignment * 2.0f
        - plane_distance * 16.0f
        - position_distance_sq * 0.05f;
    return true;
}

float ph_bilinear_weight(vec2 source_position, ivec2 texel) {
    vec2 distance_to_texel = abs(vec2(texel) - source_position);
    vec2 axis_weight = max(vec2(1.0f) - distance_to_texel, vec2(0.0f));
    return axis_weight.x * axis_weight.y;
}

bool ph_find_source_receiver(
    vec2 source_position,
    ivec2 source_size,
    vec3 player_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    bool hand,
    int receiver_slot,
    uint receiver_token,
    float plane_tolerance,
    out ivec2 best_texel
) {
    ivec2 base_texel = ivec2(floor(source_position));
    float best_score = -1e30f;
    bool found = false;

    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 texel = clamp(
                base_texel + ivec2(x, y),
                ivec2(0),
                source_size - ivec2(1)
            );
            FragData candidate;
            frag_data_load(candidate, texel);

            float score;
            if (!ph_source_matches_surface(
                    candidate,
                    player_pos,
                    geo_normal,
                    tex_normal,
                    hand,
                    receiver_slot,
                    receiver_token,
                    plane_tolerance,
                    score
            )) continue;

            vec2 pixel_delta = vec2(texel) - source_position;
            score -= dot(pixel_delta, pixel_delta) * 0.1f;
            if (!found || score > best_score) {
                found = true;
                best_score = score;
                best_texel = texel;
            }
        }
    }

    if (found)
        return true;

    ivec2 nearest_texel = ivec2(floor(source_position + vec2(0.5f)));
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 texel = clamp(
                nearest_texel + ivec2(x, y),
                ivec2(0),
                source_size - ivec2(1)
            );
            FragData candidate;
            frag_data_load(candidate, texel);

            float score;
            if (!ph_source_matches_surface(
                    candidate,
                    player_pos,
                    geo_normal,
                    tex_normal,
                    hand,
                    receiver_slot,
                    receiver_token,
                    plane_tolerance,
                    score
            )) continue;

            vec2 pixel_delta = vec2(texel) - source_position;
            score -= dot(pixel_delta, pixel_delta) * 0.25f;
            if (!found || score > best_score) {
                found = true;
                best_score = score;
                best_texel = texel;
            }
        }
    }

    return found;
}

bool ph_reconstruct_current(
    vec2 source_position,
    ivec2 source_size,
    vec3 player_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    bool hand,
    int receiver_slot,
    uint receiver_token,
    float plane_tolerance,
    out vec3 radiance,
    out float variance,
    out vec3 neighborhood_min,
    out vec3 neighborhood_max,
    out float support
) {
    ivec2 best_texel;
    if (!ph_find_source_receiver(
            source_position,
            source_size,
            player_pos,
            geo_normal,
            tex_normal,
            hand,
            receiver_slot,
            receiver_token,
            plane_tolerance,
            best_texel
    )) return false;

    ivec2 base_texel = ivec2(floor(source_position));
    radiance = vec3(0.0f);
    variance = 0.0f;
    neighborhood_min = vec3(1e30f);
    neighborhood_max = vec3(-1e30f);
    support = 0.0f;
    float weight_sum = 0.0f;

    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 texel = clamp(
                base_texel + ivec2(x, y),
                ivec2(0),
                source_size - ivec2(1)
            );
            FragData candidate;
            frag_data_load(candidate, texel);

            float unused_score;
            if (!ph_source_matches_surface(
                    candidate,
                    player_pos,
                    geo_normal,
                    tex_normal,
                    hand,
                    receiver_slot,
                    receiver_token,
                    plane_tolerance,
                    unused_score
            ))
                continue;

            float weight = ph_bilinear_weight(source_position, texel);
            if (weight <= 0.0f)
                continue;

            vec4 source = texelFetch(photonics_temporal_source, texel, 0);
            if (!ph_temporal_finite_vec3(source.rgb)
                    || isnan(source.a)
                    || isinf(source.a))
                continue;

            radiance += source.rgb * weight;
            variance += max(source.a, 0.0f) * weight;
            neighborhood_min = min(neighborhood_min, source.rgb);
            neighborhood_max = max(neighborhood_max, source.rgb);
            weight_sum += weight;
        }
    }

    support = clamp(weight_sum, 0.0f, 1.0f);
    if (weight_sum > 0.0001f) {
        radiance /= weight_sum;
        variance /= weight_sum;
        return true;
    }

    vec4 fallback = texelFetch(photonics_temporal_source, best_texel, 0);
    if (!ph_temporal_finite_vec3(fallback.rgb))
        return false;

    radiance = fallback.rgb;
    variance = max(fallback.a, 0.0f);
    neighborhood_min = fallback.rgb;
    neighborhood_max = fallback.rgb;
    support = 0.0f;
    return true;
}

bool ph_reproject_receiver(
    vec3 current_player_pos,
    vec3 current_geo_normal,
    bool hand,
    int receiver_slot,
    uint receiver_token,
    vec3 classified_previous_player_pos,
    vec3 classified_previous_geo_normal,
    out vec2 previous_uv,
    out vec3 previous_player_pos,
    out vec3 previous_geo_normal,
    out float expected_identity
) {
    expected_identity = ph_temporal_identity(
        receiver_slot,
        receiver_token,
        hand
    );
    if (hand)
        return false;

    if (receiver_token != 0u) {
        if (receiver_slot < 0
                || receiver_slot >= ph_sable_sublevel_count
                || receiver_token != ph_sable_identity_token(receiver_slot))
            return false;

        previous_player_pos = classified_previous_player_pos;
        previous_geo_normal = classified_previous_geo_normal;
        previous_uv = ph_project_previous_player_pos(
            previous_player_pos,
            get_taa_jitter()
        ).xy;
    } else {
        if (receiver_slot >= 0)
            return false;

        previous_uv = ph_reproject_player_pos(
            current_player_pos,
            false,
            get_taa_jitter(),
            previous_player_pos
        ).xy;
        previous_geo_normal = current_geo_normal;
    }

    return ph_temporal_finite_vec3(previous_player_pos)
        && ph_temporal_finite_vec3(previous_geo_normal)
        && !any(isnan(previous_uv))
        && !any(isinf(previous_uv))
        && all(greaterThanEqual(previous_uv, vec2(0.0f)))
        && all(lessThan(previous_uv, vec2(1.0f)));
}

bool ph_history_tap(
    ivec2 texel,
    ivec2 history_size,
    vec3 expected_previous_pos,
    vec3 expected_previous_normal,
    float expected_identity,
    out vec3 radiance,
    out float age
) {
    if (any(lessThan(texel, ivec2(0)))
            || any(greaterThanEqual(texel, history_size)))
        return false;

    vec4 history = texelFetch(
        prev_photonics_temporal_lighting,
        texel,
        0
    );
    vec4 surface = texelFetch(
        prev_photonics_temporal_surface,
        texel,
        0
    );
    if (history.a < 0.5f
            || !ph_temporal_finite_vec3(history.rgb)
            || any(isnan(surface))
            || any(isinf(surface))
            || abs(surface.a - expected_identity) > 0.25f)
        return false;

    vec3 previous_normal = ph_decode_normal(surface.rg);
    if (dot(previous_normal, expected_previous_normal)
            < PH_UPSCALE_HISTORY_NORMAL_THRESHOLD)
        return false;

    float expected_depth = length(expected_previous_pos);
    float depth_tolerance = max(0.15f, expected_depth * 0.0015f);
    if (abs(surface.b - expected_depth) > depth_tolerance)
        return false;

    radiance = history.rgb;
    age = history.a;
    return true;
}

bool ph_reconstruct_history(
    vec2 previous_uv,
    vec3 expected_previous_pos,
    vec3 expected_previous_normal,
    float expected_identity,
    out vec3 radiance,
    out float age,
    out float support
) {
    ivec2 history_size = textureSize(
        prev_photonics_temporal_lighting,
        0
    );
    vec2 history_position = previous_uv * vec2(history_size) - 0.5f;
    ivec2 base_texel = ivec2(floor(history_position));

    radiance = vec3(0.0f);
    age = 0.0f;
    float weight_sum = 0.0f;
    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 texel = base_texel + ivec2(x, y);
            vec3 tap_radiance;
            float tap_age;
            if (!ph_history_tap(
                    texel,
                    history_size,
                    expected_previous_pos,
                    expected_previous_normal,
                    expected_identity,
                    tap_radiance,
                    tap_age
            )) continue;

            float weight = ph_bilinear_weight(history_position, texel);
            radiance += tap_radiance * weight;
            age += tap_age * weight;
            weight_sum += weight;
        }
    }

    if (weight_sum <= 0.0001f)
        return false;

    radiance /= weight_sum;
    age /= weight_sum;
    support = clamp(weight_sum, 0.0f, 1.0f);
    return true;
}

float ph_luminance(vec3 color) {
    return dot(color, vec3(0.2126f, 0.7152f, 0.0722f));
}

void main() {
    temporal_lighting_out = vec4(0.0f);
    temporal_surface_out = vec4(0.0f);

    if (ph_world_ready == 0 || !is_in_world())
        return;

    vec3 player_pos = load_player_position();
    vec3 geo_normal;
    vec3 tex_normal;
    load_fragment_data(geo_normal, tex_normal);
    if (!ph_temporal_finite_vec3(player_pos)
            || !ph_temporal_finite_vec3(geo_normal)
            || !ph_temporal_finite_vec3(tex_normal))
        return;

    bool hand = is_hand_at();
    vec3 classified_previous_player_pos = player_pos;
    vec3 classified_previous_geo_normal = geo_normal;
    int receiver_slot = -1;
    uint receiver_token = 0u;
    if (!hand) {
        bool classified_sable = ph_sable_receiver_motion(
            player_pos,
            geo_normal,
            classified_previous_player_pos,
            classified_previous_geo_normal,
            receiver_slot,
            receiver_token
        );
        if (!classified_sable) {
            classified_previous_player_pos = player_pos;
            classified_previous_geo_normal = geo_normal;
            receiver_slot = -1;
            receiver_token = 0u;
        }
    }

    ivec2 output_size = textureSize(
        prev_photonics_temporal_lighting,
        0
    );
    ivec2 source_size = textureSize(photonics_temporal_source, 0);
    vec2 tex_coord = gl_FragCoord.xy / vec2(output_size);
    vec2 source_position = tex_coord * vec2(source_size) - 0.5f;
    float plane_tolerance = ph_temporal_plane_tolerance(
        source_size,
        output_size
    );

    vec3 current_radiance;
    float current_variance;
    vec3 neighborhood_min;
    vec3 neighborhood_max;
    float source_support;
    if (!ph_reconstruct_current(
            source_position,
            source_size,
            player_pos,
            geo_normal,
            tex_normal,
            hand,
            receiver_slot,
            receiver_token,
            plane_tolerance,
            current_radiance,
            current_variance,
            neighborhood_min,
            neighborhood_max,
            source_support
    )) return;

    float identity = ph_temporal_identity(
        receiver_slot,
        receiver_token,
        hand
    );
    temporal_surface_out = vec4(
        ph_encode_normal(geo_normal),
        length(player_pos),
        identity
    );

    vec2 previous_uv;
    vec3 previous_player_pos;
    vec3 previous_geo_normal;
    float expected_identity;
    bool can_reproject = ph_reproject_receiver(
        player_pos,
        geo_normal,
        hand,
        receiver_slot,
        receiver_token,
        classified_previous_player_pos,
        classified_previous_geo_normal,
        previous_uv,
        previous_player_pos,
        previous_geo_normal,
        expected_identity
    );

    vec3 history_radiance;
    float history_age;
    float history_support;
    bool has_history = can_reproject && ph_reconstruct_history(
        previous_uv,
        previous_player_pos,
        previous_geo_normal,
        expected_identity,
        history_radiance,
        history_age,
        history_support
    );

    if (!has_history) {
        temporal_lighting_out = vec4(current_radiance, 1.0f);
        return;
    }

    float current_luma = ph_luminance(current_radiance);
    float history_luma = ph_luminance(history_radiance);
    float relative_sigma = sqrt(max(current_variance, 0.0f))
        / max(current_luma, 0.1f);
    float neighborhood_expansion = 0.01f * (1.0f + current_luma)
        + min(sqrt(max(current_variance, 0.0f)), 2.0f);
    vec3 history_clamped = clamp(
        history_radiance,
        neighborhood_min - vec3(neighborhood_expansion),
        neighborhood_max + vec3(neighborhood_expansion)
    );

    float relative_luma_delta = abs(current_luma - history_luma)
        / max(max(current_luma, history_luma), 0.1f);
    float clamp_delta = length(history_clamped - history_radiance)
        / max(length(current_radiance), 0.1f);
    float motion_pixels = length(
        (previous_uv - tex_coord) * vec2(output_size)
    );

    float reactive = smoothstep(0.12f, 0.80f, relative_luma_delta);
    reactive = max(reactive, smoothstep(0.02f, 0.25f, clamp_delta));
    reactive = max(
        reactive,
        0.25f * smoothstep(0.15f, 1.50f, relative_sigma)
    );
    reactive = max(
        reactive,
        0.35f * (1.0f - smoothstep(0.10f, 0.60f, source_support))
    );
    reactive = max(
        reactive,
        0.50f * (1.0f - smoothstep(0.10f, 0.75f, history_support))
    );
    reactive = max(
        reactive,
        0.35f * smoothstep(8.0f, 48.0f, motion_pixels)
    );
    reactive = clamp(reactive, 0.0f, 1.0f);

    float max_history = float(PH_TEMPORAL_UPSCALER_HISTORY_FRAMES);
    float base_current_weight = 1.0f
        / min(history_age + 1.0f, max_history);
    float current_weight = max(base_current_weight, reactive);
    vec3 resolved = mix(history_clamped, current_radiance, current_weight);
    float resolved_age = mix(
        min(history_age + 1.0f, max_history),
        1.0f,
        reactive
    );

    if (!ph_temporal_finite_vec3(resolved)) {
        resolved = current_radiance;
        resolved_age = 1.0f;
    }
    temporal_lighting_out = vec4(
        clamp(resolved, vec3(0.0f), vec3(65504.0f)),
        clamp(resolved_age, 1.0f, max_history)
    );
}
