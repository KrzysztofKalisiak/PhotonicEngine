#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

const int PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT = 128;
const int PH_DIAGNOSTIC_TRACE_ITERATIONS = 96;
const float PH_DIAGNOSTIC_RADIUS_PADDING = 0.0f;
const float PH_DIAGNOSTIC_MIN_LIGHT_SCORE = 0.0001f;

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

#if defined PH_ENABLE_BLOCKLIGHT
vec3 diagnostic_direct_light_sum(vec3 sample_pos, vec3 geo_normal, vec3 tex_normal) {
    vec3 result = vec3(0.0f);
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float radius = max(light.block_radius + PH_DIAGNOSTIC_RADIUS_PADDING, 1.0f);

        if (dot(to_light, to_light) > radius * radius)
            continue;

        vec3 tint_color;
        float light_transmittance;
        if (!trace_light_vis(sample_pos, to_light, light.position, PH_DIAGNOSTIC_TRACE_ITERATIONS, tint_color, light_transmittance))
            continue;

        result += light_sample_at(
            light,
            sample_pos,
            light.position,
            geo_normal,
            tex_normal
        ) * tint_color * light_transmittance;
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
        float radius = max(light.block_radius + PH_DIAGNOSTIC_RADIUS_PADDING, 1.0f);

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
        return vec3(0.0f);

    Light light = light_list_get(best_light_index);
    vec3 tint_color;
    float light_transmittance;

    if (trace_light_vis(sample_pos, light.position - sample_pos, light.position, PH_DIAGNOSTIC_TRACE_ITERATIONS, tint_color, light_transmittance))
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
        float radius = max(light.block_radius + PH_DIAGNOSTIC_RADIUS_PADDING, 1.0f);

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
        return vec3(0.0f);

    Light light = light_list_get(best_light_index);
    vec3 to_light = light.position - sample_pos;
    float light_dist = dot(to_light, to_light);
    vec3 trace_origin = sample_pos + normalize(to_light) * 0.02f;

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, to_light);
    ray.iterations = PH_DIAGNOSTIC_TRACE_ITERATIONS;

    vec3 start_block = floor(sample_pos);
    vec3 light_block = floor(light.position);

    while (ray_iter_has_next(ray)) {
        RayResult result = ray_iter_next(ray);
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

vec3 diagnostic_surface_self_hit_mask(vec3 sample_pos, vec3 geo_normal) {
    vec3 normal = normalize(geo_normal);
    vec3 trace_origin = sample_pos + normal * 0.08f;

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, -normal);
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
