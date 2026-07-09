bool trace_light_vis(
    vec3 rt_pos,
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

    vec3 to_fragment = rt_pos - light_rt_pos;
    float fragment_dist = dot(to_fragment, to_fragment);
    if (fragment_dist <= 0.0001f) return true;

    vec3 trace_direction = ph_signed_nudge(to_fragment);

    RayIterator ray;
    ray_iter_begin(ray, light_rt_pos, trace_direction);
    ray.iterations = max(max_iterations, 1);

    vec4 running_tint_color = vec4(0.0f);
    vec3 light_block = floor(light_rt_pos);
    vec3 fragment_block = floor(rt_pos);

    while (ray_iter_has_next(ray)) {
        RayResult result = ray_iter_next(ray);
        if (!ray_result_is_hit(result)) break;

        vec3 result_pos = ray_result_position(result);
        vec3 result_block = floor(result_pos);

        if (all(equal(result_block, light_block))) {
            ray_iter_skip_block(ray);
            continue;
        }

        if (all(equal(result_block, fragment_block)))
            break;

        float result_dist = dot(result_pos - light_rt_pos, result_pos - light_rt_pos);
        if (result_dist - fragment_dist > -0.1f)
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
