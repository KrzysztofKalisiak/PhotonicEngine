bool trace_light_vis(
    vec3 rt_pos,
    vec3 surface_normal,
    vec3 direction,
    vec3 light_rt_pos,
    int max_iterations,
    out vec3 tint_color,
    out float light_transmittance
) {
#ifdef PH_DISABLE_RESTIR_VISIBILITY
    tint_color = vec3(1.0f);
    light_transmittance = 1.0f;
    return true;
#else
    tint_color = vec3(1.0f);
    light_transmittance = 1.0f;

    vec3 to_light = light_rt_pos - rt_pos;
    float light_dist = length(to_light);
    if (light_dist <= 0.0001f) return true;

    const float start_bias_distance = 0.25f;
    const float near_surface_skip_distance = 0.45f;

    vec3 trace_direction = ph_signed_nudge(direction);
    vec3 unit_direction = normalize(trace_direction);
    vec3 trace_origin = rt_pos + unit_direction * min(start_bias_distance, light_dist * 0.45f);

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, trace_direction);
    ray.iterations = max(max_iterations, 1);

    vec4 running_tint_color = vec4(0.0f);
    vec3 start_block = floor(rt_pos);
    vec3 origin_block = floor(trace_origin);
    vec3 light_block = floor(light_rt_pos);

    while (ray_iter_has_next_block(ray, light_rt_pos)) {
        RayResult result = ray_iter_next_block(ray, light_rt_pos);
        if (!ray_result_is_hit(result)) break;

        vec3 result_pos = ray_result_position(result);
        vec3 result_block = floor(result_pos);
        float result_dist = length(result_pos - rt_pos);

        if (result_dist < near_surface_skip_distance) {
            ray_iter_skip_block(ray);
            continue;
        }

        if (all(equal(result_block, start_block)) || all(equal(result_block, origin_block))) {
            ray_iter_skip_block(ray);
            continue;
        }

        if (all(equal(result_block, light_block)) || result_dist >= light_dist - 0.2f)
            break;

        if (ray_result_is_transparent(result)) {
            VoxelData voxel_data = ray_result_voxel_data(result);
            vec4 albedo = voxel_data_albedo(voxel_data);

            light_transmittance *= 1.0f - albedo.a;
            ray_iter_apply_transparency(running_tint_color, albedo);
            ray_iter_skip_block(ray);

            continue;
        }

        return false;
    }

    tint_color = running_tint_color.a == 0.0f ? vec3(1.0f) : running_tint_color.rgb;
    return true;
#endif
}
