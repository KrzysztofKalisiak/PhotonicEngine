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

    vec3 trace_direction = normalize(ph_signed_nudge(direction));
    vec3 unit_direction = trace_direction;
    float normal_length_sq = dot(surface_normal, surface_normal);
    vec3 unit_normal = normal_length_sq > 0.000001f
        ? surface_normal * inversesqrt(normal_length_sq)
        : unit_direction;
    float normal_side = dot(unit_normal, unit_direction) >= 0.0f ? 1.0f : -1.0f;

    // The world representation is voxelized at 1/16-block resolution. Move half
    // a voxel off the receiver without allowing the bias to pass a nearby light.
    float normal_bias_distance = min(0.5f / 16.0f, light_dist * 0.25f);
    float forward_bias_distance = min(0.001f, light_dist * 0.05f);
    vec3 trace_origin = rt_pos + unit_normal * normal_side * normal_bias_distance
        + unit_direction * forward_bias_distance;

    RayIterator ray;
    ray_iter_begin(ray, trace_origin, trace_direction);
    ray.iterations = max(max_iterations, 1);

    vec4 running_tint_color = vec4(0.0f);
    vec3 light_block = floor(light_rt_pos);
    bool reached_light = false;

    while (ray_iter_has_next_block(ray, light_rt_pos)) {
        RayResult result = ray_iter_next_block(ray, light_rt_pos);
        if (!ray_result_is_hit(result)) break;

        vec3 result_pos = ray_result_position(result);
        vec3 result_block = floor(result_pos);
        float result_dist = length(result_pos - rt_pos);

        if (all(equal(result_block, light_block)) || result_dist >= light_dist - 0.01f) {
            reached_light = true;
            break;
        }

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

    if (!reached_light) {
        float target_progress = dot(light_rt_pos - trace_origin, unit_direction);
        float ray_progress = dot(ray.position - trace_origin, unit_direction);
        reached_light = ray_progress >= target_progress - 0.01f;
    }

    tint_color = running_tint_color.a == 0.0f ? vec3(1.0f) : running_tint_color.rgb;
    return reached_light;
#endif
}
