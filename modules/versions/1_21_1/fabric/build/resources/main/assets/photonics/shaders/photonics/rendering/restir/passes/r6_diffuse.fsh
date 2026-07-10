#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

const int PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT = 128;
const int PH_DIAGNOSTIC_TRACE_ITERATIONS = 96;
const float PH_DIAGNOSTIC_FORCED_LIGHT_RANGE = 16.0f;
const float PH_DIAGNOSTIC_MIN_SURFACE_FACING = 0.15f;
const float PH_DIAGNOSTIC_MIN_LIGHT_SCORE = 0.0001f;

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

#if defined PH_ENABLE_BLOCKLIGHT
vec3 diagnostic_forced_range_light_sample(Light light, vec3 sample_pos, vec3 geo_normal) {
    vec3 to_light = light.position - sample_pos;
    float dist_sq = dot(to_light, to_light);
    float dist = sqrt(max(dist_sq, 0.000001f));

    if (dist > PH_DIAGNOSTIC_FORCED_LIGHT_RANGE)
        return vec3(0.0f);

    vec3 light_dir = to_light / dist;
    float surface_facing = max(dot(normalize(geo_normal), light_dir), PH_DIAGNOSTIC_MIN_SURFACE_FACING);
    float range_weight = 1.0f - clamp(dist / PH_DIAGNOSTIC_FORCED_LIGHT_RANGE, 0.0f, 1.0f);
    float attenuation = range_weight * range_weight / (1.0f + dist_sq * 0.08f);

    return light.color * surface_facing * attenuation * 5.0f;
}

vec3 diagnostic_direct_light_sum(vec3 sample_pos, vec3 geo_normal, vec3 tex_normal) {
    vec3 result = vec3(0.0f);
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;

        if (dot(to_light, to_light) > PH_DIAGNOSTIC_FORCED_LIGHT_RANGE * PH_DIAGNOSTIC_FORCED_LIGHT_RANGE)
            continue;

        vec3 tint_color;
        float light_transmittance;
        if (!trace_light_vis(sample_pos, geo_normal, to_light, light.position, PH_DIAGNOSTIC_TRACE_ITERATIONS, tint_color, light_transmittance))
            continue;

        result += diagnostic_forced_range_light_sample(light, sample_pos, geo_normal) * tint_color * light_transmittance;
    }

    return result;
}

vec3 diagnostic_visibility_mask(vec3 sample_pos, vec3 geo_normal, vec3 tex_normal) {
    int best_light_index = -1;
    float best_score = 0.0f;
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float radius = PH_DIAGNOSTIC_FORCED_LIGHT_RANGE;

        if (dot(to_light, to_light) > radius * radius)
            continue;

        vec3 unshadowed = light_sample_at(
            light,
            sample_pos,
            light.position,
            geo_normal,
            tex_normal
        );
        float score = ph_luminance(unshadowed);

        if (score > best_score) {
            best_score = score;
            best_light_index = i;
        }
    }

    if (best_light_index < 0 || best_score <= PH_DIAGNOSTIC_MIN_LIGHT_SCORE)
        return vec3(1.0f, 0.0f, 1.0f);

    Light light = light_list_get(best_light_index);
    vec3 tint_color;
    float light_transmittance;

    if (trace_light_vis(sample_pos, geo_normal, light.position - sample_pos, light.position, PH_DIAGNOSTIC_TRACE_ITERATIONS, tint_color, light_transmittance))
        return vec3(0.0f, 1.0f, 0.0f);

    return vec3(1.0f, 0.0f, 0.0f);
}

vec3 diagnostic_ray_classification_mask(vec3 sample_pos, vec3 geo_normal, vec3 tex_normal) {
    int best_light_index = -1;
    float best_score = 0.0f;
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float radius = PH_DIAGNOSTIC_FORCED_LIGHT_RANGE;

        if (dot(to_light, to_light) > radius * radius)
            continue;

        vec3 unshadowed = light_sample_at(
            light,
            sample_pos,
            light.position,
            geo_normal,
            tex_normal
        );
        float score = ph_luminance(unshadowed);

        if (score > best_score) {
            best_score = score;
            best_light_index = i;
        }
    }

    if (best_light_index < 0 || best_score <= PH_DIAGNOSTIC_MIN_LIGHT_SCORE)
        return vec3(1.0f, 0.0f, 1.0f);

    Light light = light_list_get(best_light_index);
    vec3 to_light = light.position - sample_pos;
    float light_dist = dot(to_light, to_light);
    vec3 trace_origin = sample_pos + normalize(to_light) * 0.02f;

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, to_light);
    ray.iterations = PH_DIAGNOSTIC_TRACE_ITERATIONS;

    vec3 start_block = floor(sample_pos);
    vec3 light_block = floor(light.position);

    while (ray_iter_has_next_block(ray, light.position)) {
        RayResult result = ray_iter_next_block(ray, light.position);
        if (!ray_result_is_hit(result))
            break;

        vec3 result_pos = ray_result_position(result);
        vec3 result_block = floor(result_pos);

        if (all(equal(result_block, start_block))) {
            ray_iter_skip_block(ray);
            continue;
        }

        float result_dist = dot(result_pos - sample_pos, result_pos - sample_pos);
        if (all(equal(result_block, light_block)) || result_dist >= light_dist - 0.01f)
            return vec3(1.0f, 1.0f, 0.0f);

        if (ray_result_is_transparent(result))
            return vec3(0.0f, 0.0f, 1.0f);

        return vec3(1.0f, 0.0f, 0.0f);
    }

    return vec3(0.0f, 1.0f, 0.0f);
}

vec3 diagnostic_safe_ray_direction(vec3 direction) {
    vec3 result = direction;

    if (abs(result.x) < 0.0001f)
        result.x = result.x < 0.0f ? -0.0009765625f : 0.0009765625f;

    if (abs(result.y) < 0.0001f)
        result.y = result.y < 0.0f ? -0.0009765625f : 0.0009765625f;

    if (abs(result.z) < 0.0001f)
        result.z = result.z < 0.0f ? -0.0009765625f : 0.0009765625f;

    return normalize(result);
}

vec3 diagnostic_surface_self_hit_mask(vec3 sample_pos, vec3 geo_normal) {
    vec3 normal = normalize(geo_normal);
    vec3 trace_direction = diagnostic_safe_ray_direction(-normal);
    vec3 trace_origin = sample_pos - trace_direction * 0.08f;

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, trace_direction);
    ray.iterations = 24;

    while (ray_iter_has_next(ray)) {
        RayResult result = ray_iter_next(ray);
        if (!ray_result_is_hit(result))
            break;

        float hit_dist = length(ray_result_position(result) - sample_pos);
        if (hit_dist > 1.25f)
            break;

        if (ray_result_is_transparent(result))
            return vec3(0.0f, 0.0f, 1.0f);

        return vec3(1.0f, 0.0f, 0.0f);
    }

    return vec3(0.0f, 1.0f, 0.0f);
}

vec3 diagnostic_cardinal_occluder_mask(vec3 sample_pos, vec3 geo_normal) {
    vec3 normal = normalize(geo_normal);
    vec3 trace_origin = sample_pos + normal * 0.05f;
    vec3 start_block = floor(sample_pos);

    const vec3[8] directions = vec3[8](
        normalize(vec3(1.0f, 0.03125f, 0.0625f)),
        normalize(vec3(-1.0f, 0.03125f, 0.0625f)),
        normalize(vec3(0.0625f, 0.03125f, 1.0f)),
        normalize(vec3(0.0625f, 0.03125f, -1.0f)),
        normalize(vec3(1.0f, -0.03125f, -0.0625f)),
        normalize(vec3(-1.0f, -0.03125f, -0.0625f)),
        normalize(vec3(-0.0625f, -0.03125f, 1.0f)),
        normalize(vec3(-0.0625f, -0.03125f, -1.0f))
    );

    for (int i = 0; i < 8; i++) {
        RayIterator ray;
        ray_iter_begin(ray, trace_origin, directions[i]);
        ray.iterations = PH_DIAGNOSTIC_TRACE_ITERATIONS;

        while (ray_iter_has_next(ray)) {
            RayResult result = ray_iter_next(ray);
            if (!ray_result_is_hit(result))
                break;

            vec3 result_pos = ray_result_position(result);
            if (dot(result_pos - trace_origin, result_pos - trace_origin) > 64.0f)
                break;

            if (all(equal(floor(result_pos), start_block))) {
                ray_iter_skip_block(ray);
                continue;
            }

            if (ray_result_is_transparent(result))
                return vec3(0.0f, 0.0f, 1.0f);

            return vec3(1.0f, 0.0f, 0.0f);
        }
    }

    return vec3(0.0f, 1.0f, 0.0f);
}

vec3 diagnostic_nearest_light_position_mask(vec3 sample_pos) {
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);
    int best_light_index = -1;
    float best_dist_sq = 340282346638528859811704183484516925440.0f;
    float best_radius = 1.0f;

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float dist_sq = dot(to_light, to_light);

        if (dist_sq < best_dist_sq) {
            best_dist_sq = dist_sq;
            best_radius = max(light.block_radius, 1.0f);
            best_light_index = i;
        }
    }

    if (best_light_index < 0)
        return vec3(1.0f, 0.0f, 1.0f);

    float dist = sqrt(best_dist_sq);

    if (dist < 0.75f)
        return vec3(1.0f, 0.0f, 0.0f);

    if (dist < 1.5f)
        return vec3(1.0f, 1.0f, 0.0f);

    if (dist <= best_radius)
        return vec3(0.0f, 1.0f, 0.0f);

    return vec3(0.0f, 0.0f, 1.0f);
}

vec3 diagnostic_nearest_light_visibility_mask(vec3 sample_pos) {
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);
    int best_light_index = -1;
    float best_dist_sq = 340282346638528859811704183484516925440.0f;
    float best_radius = 1.0f;

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float dist_sq = dot(to_light, to_light);

        if (dist_sq < best_dist_sq) {
            best_dist_sq = dist_sq;
            best_radius = max(light.block_radius, 1.0f);
            best_light_index = i;
        }
    }

    if (best_light_index < 0)
        return vec3(1.0f, 0.0f, 1.0f);

    Light light = light_list_get(best_light_index);
    float dist = sqrt(best_dist_sq);

    if (dist > best_radius)
        return vec3(0.0f, 0.0f, 1.0f);

    if (dist < 1.5f)
        return vec3(1.0f, 1.0f, 0.0f);

    vec3 tint_color;
    float light_transmittance;

    if (trace_light_vis(sample_pos, vec3(0.0f, 1.0f, 0.0f), light.position - sample_pos, light.position, PH_DIAGNOSTIC_TRACE_ITERATIONS, tint_color, light_transmittance))
        return vec3(0.0f, 1.0f, 0.0f);

    return vec3(1.0f, 0.0f, 0.0f);
}

vec3 diagnostic_nearest_light_first_hit_mask(vec3 sample_pos) {
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);
    int best_light_index = -1;
    float best_dist_sq = 340282346638528859811704183484516925440.0f;
    float best_radius = 1.0f;

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float dist_sq = dot(to_light, to_light);

        if (dist_sq < best_dist_sq) {
            best_dist_sq = dist_sq;
            best_radius = max(light.block_radius, 1.0f);
            best_light_index = i;
        }
    }

    if (best_light_index < 0)
        return vec3(1.0f, 0.0f, 1.0f);

    Light light = light_list_get(best_light_index);
    float light_dist = best_dist_sq;

    if (sqrt(light_dist) > best_radius)
        return vec3(0.0f, 0.0f, 1.0f);

    if (light_dist <= 2.25f)
        return vec3(1.0f, 1.0f, 0.0f);

    vec3 to_light = light.position - sample_pos;
    vec3 trace_origin = sample_pos + normalize(to_light) * 0.02f;

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, to_light);
    ray.iterations = PH_DIAGNOSTIC_TRACE_ITERATIONS;

    vec3 start_block = floor(sample_pos);
    vec3 light_block = floor(light.position);

    while (ray_iter_has_next_block(ray, light.position)) {
        RayResult result = ray_iter_next_block(ray, light.position);
        if (!ray_result_is_hit(result))
            break;

        vec3 result_pos = ray_result_position(result);
        vec3 result_block = floor(result_pos);

        if (all(equal(result_block, start_block))) {
            ray_iter_skip_block(ray);
            continue;
        }

        float result_dist = dot(result_pos - sample_pos, result_pos - sample_pos);
        if (all(equal(result_block, light_block)) || result_dist >= light_dist - 0.01f)
            return vec3(1.0f, 1.0f, 0.0f);

        if (ray_result_is_transparent(result))
            return vec3(0.0f, 1.0f, 1.0f);

        return vec3(1.0f, 0.0f, 0.0f);
    }

    return vec3(0.0f, 1.0f, 0.0f);
}
#endif

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir direct_reservoir = direct_reservoir_empty();

    lighting.rgb += diagnostic_direct_light_sum(
        frag_rt_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal
    );
    direct_reservoir_encode(direct_reservoir, di_reservoir_0);
#endif
}
