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

    vec3 receiver_to_light_direction = normalize(ph_signed_nudge(direction));
    float normal_length_sq = dot(surface_normal, surface_normal);
    vec3 unit_normal = normal_length_sq > 0.000001f
        ? surface_normal * inversesqrt(normal_length_sq)
        : receiver_to_light_direction;
    float normal_side = dot(unit_normal, receiver_to_light_direction) >= 0.0f ? 1.0f : -1.0f;

    const float half_voxel = 0.5f / 16.0f;

    // Move the receiver endpoint toward the light so its own surface is not
    // mistaken for an occluder when the reciprocal ray reaches it.
    float normal_bias_distance = min(half_voxel, light_dist * 0.25f);
    float forward_bias_distance = min(0.001f, light_dist * 0.05f);
    vec3 receiver_endpoint = rt_pos + unit_normal * normal_side * normal_bias_distance
        + receiver_to_light_direction * forward_bias_distance;

    // Air-based lights (handheld and moving/Sable sources) are not represented
    // by an occupied target voxel. Trace reciprocally from the light toward the
    // occupied receiver, as the upstream handheld path does.
    vec3 light_to_receiver = receiver_endpoint - light_rt_pos;
    float trace_dist = length(light_to_receiver);
    if (trace_dist <= 0.0001f) return true;

    vec3 trace_direction = normalize(ph_signed_nudge(light_to_receiver));

    RayIterator ray;
    ray_iter_begin(ray, light_rt_pos, trace_direction);

    // A Sable/transformed receiver may not exist in the static voxel tree, so
    // there is no occupied target node to terminate traversal. Budget enough
    // steps to cross every 1/16-grid boundary along this finite segment. The
    // progress check below still rejects a traversal that stops prematurely.
    float segment_voxel_crossings = dot(abs(light_to_receiver), vec3(16.0f));
    int segment_iteration_budget = int(ceil(segment_voxel_crossings)) + 16;
    ray.iterations = max(max_iterations, segment_iteration_budget);

    vec4 running_tint_color = vec4(0.0f);
    vec3 trace_start = ray.position;
    float endpoint_progress = dot(receiver_endpoint - trace_start, trace_direction);
    bool reached_receiver = endpoint_progress <= 0.0001f;

    while (!reached_receiver) {
        if (!ray_iter_has_next(ray)) {
            // Leaving or never entering the static tree means no represented
            // occluder exists on the remaining segment. Keep iteration-budget
            // exhaustion fail-closed so incomplete traversal cannot leak light.
            if (!ray_iter_is_in_bounds(ray)) reached_receiver = true;
            break;
        }

        RayResult result = ray_iter_next(ray);
        if (!ray_result_is_hit(result)) break;

        vec3 result_pos = ray_result_position(result);
        float result_progress = dot(result_pos - trace_start, trace_direction);

        if (result_progress >= endpoint_progress - 0.01f) {
            reached_receiver = true;
            break;
        }

        // The ray starts inside placed light blocks. Traced emitters must not
        // occlude themselves or adjacent members of an emissive cluster.
        Light hit_light = ray_result_light_data(result);
        if (hit_light.type == LIGHT_TYPE_TRACED) {
            ray_iter_skip_block(ray);
            continue;
        }

        if (ray_result_is_transparent(result)) {
            VoxelData voxel_data = ray_result_voxel_data(result);
            vec4 albedo = voxel_data_albedo(voxel_data);

            float direct_opacity = albedo.a;
            if (voxel_data_is_thin_cutout(voxel_data)) {
                // Preserve plant voxels for GI while reducing the oversized
                // 1/16-grid silhouettes they project from nearby point lights.
                direct_opacity *= direct_opacity;
            }
            light_transmittance *= 1.0f - direct_opacity;

            // Cutout alpha represents unresolved geometric coverage, not a
            // colored transmissive medium like stained glass.
            if (!voxel_data_is_thin_cutout(voxel_data))
                ray_iter_apply_transparency(running_tint_color, albedo);
            ray_iter_skip_block(ray);

            continue;
        }

        return false;
    }

    if (!reached_receiver) {
        float ray_progress = dot(ray.position - trace_start, trace_direction);
        // Missing world data must not turn into unfiltered white light. A miss
        // is visible only if traversal actually advanced beyond the receiver.
        reached_receiver = ray_progress >= endpoint_progress - 0.01f;
    }

    tint_color = running_tint_color.a == 0.0f ? vec3(1.0f) : running_tint_color.rgb;
    return reached_receiver;
#endif
}
