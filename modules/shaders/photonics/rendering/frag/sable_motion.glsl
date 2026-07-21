#ifndef PH_SABLE_MOTION_INCLUDE
#define PH_SABLE_MOTION_INCLUDE

#define PH_SABLE_MAX_SUBLEVELS 16
#define PH_SABLE_MAX_EMISSIVE_CELLS 64
const float PH_SABLE_VISIBILITY_BIAS = 0.001f;
const float PH_SABLE_RECEIVER_PROBE = 0.35f;
const float PH_SABLE_RECEIVER_BOUNDS_PAD = 0.4f;
//ph_required: uniform sampler3D ph_sable_occupancy;
//ph_required: uniform int ph_sable_sublevel_count;
//ph_required: uniform int ph_sable_emissive_cell_count;
//ph_required: uniform mat4 ph_sable_current_player_to_grid[16];
//ph_required: uniform mat4 ph_sable_current_player_to_previous_player[16];
//ph_required: uniform mat4 ph_sable_previous_player_to_current_grid[16];
//ph_required: uniform vec4 ph_sable_grid_info[16];
//ph_required: uniform vec4 ph_sable_identity_tokens[4];
//ph_required: uniform vec4 ph_sable_emissive_cells[64];

mat4 ph_sable_player_to_grid_matrix(int slot) {
    return ph_sable_current_player_to_grid[slot];
}

mat4 ph_sable_player_to_previous_player_matrix(int slot) {
    return ph_sable_current_player_to_previous_player[slot];
}

mat4 ph_sable_previous_player_to_current_grid_matrix(int slot) {
    return ph_sable_previous_player_to_current_grid[slot];
}

mat3 ph_sable_normal_to_previous_normal_matrix(int slot) {
    return transpose(inverse(mat3(ph_sable_player_to_previous_player_matrix(slot))));
}

uint ph_sable_identity_token(int slot) {
    vec4 tokens = ph_sable_identity_tokens[slot / 4];
    return uint(tokens[slot % 4] + 0.5f);
}

bool ph_sable_finite_vec3(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

float ph_sable_receiver_relative_light_step(
    int receiver_slot,
    uint receiver_token,
    vec3 current_player_pos,
    vec3 previous_player_pos
) {
    if (receiver_slot < 0 || receiver_slot >= ph_sable_sublevel_count
            || receiver_token == 0u
            || receiver_token != ph_sable_identity_token(receiver_slot))
        return length(current_player_pos - previous_player_pos);

    vec3 current_grid_pos = (
        ph_sable_player_to_grid_matrix(receiver_slot)
            * vec4(current_player_pos, 1.0f)
    ).xyz;
    vec3 previous_grid_pos = (
        ph_sable_previous_player_to_current_grid_matrix(receiver_slot)
            * vec4(previous_player_pos, 1.0f)
    ).xyz;
    if (!ph_sable_finite_vec3(current_grid_pos)
            || !ph_sable_finite_vec3(previous_grid_pos))
        return length(current_player_pos - previous_player_pos);

    return length(current_grid_pos - previous_grid_pos);
}

float ph_sable_cell_flags(ivec3 cell, ivec3 size, int atlas_z) {
    if (atlas_z < 0 || any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, size)))
        return 0.0f;

    return texelFetch(ph_sable_occupancy, ivec3(cell.xy, cell.z + atlas_z), 0).r;
}

bool ph_sable_cell_receiver(ivec3 cell, ivec3 size, int atlas_z) {
    return ph_sable_cell_flags(cell, size, atlas_z) > 0.1f;
}

bool ph_sable_cell_occluder(ivec3 cell, ivec3 size, int atlas_z) {
    return ph_sable_cell_flags(cell, size, atlas_z) > 0.5f;
}

bool ph_sable_matches_emissive_cell(int slot, vec3 grid_pos) {
    for (int i = 0; i < PH_SABLE_MAX_EMISSIVE_CELLS; i++) {
        if (i >= ph_sable_emissive_cell_count)
            break;

        vec4 encoded = ph_sable_emissive_cells[i];
        if (int(encoded.w + 0.5f) != slot + 1)
            continue;

        vec3 nearest = clamp(grid_pos, encoded.xyz, encoded.xyz + vec3(1.0f));
        vec3 distance_to_cell = grid_pos - nearest;
        if (dot(distance_to_cell, distance_to_cell) <= 0.0225f)
            return true;
    }

    return false;
}

bool ph_sable_light_grid_position(
    int slot,
    vec3 light_player_pos,
    out vec3 light_grid_pos,
    out vec3 emissive_cell_min
) {
    light_grid_pos = (
        ph_sable_player_to_grid_matrix(slot) * vec4(light_player_pos, 1.0f)
    ).xyz;
    if (!ph_sable_finite_vec3(light_grid_pos))
        return false;
    emissive_cell_min = vec3(0.0f);
    ivec3 grid_size = ivec3(ph_sable_grid_info[slot].xyz + 0.5f);
    if (any(lessThan(light_grid_pos, vec3(-0.2f)))
            || any(greaterThan(light_grid_pos, vec3(grid_size) + 0.2f)))
        return false;

    for (int i = 0; i < PH_SABLE_MAX_EMISSIVE_CELLS; i++) {
        if (i >= ph_sable_emissive_cell_count)
            break;

        vec4 encoded = ph_sable_emissive_cells[i];
        if (int(encoded.w + 0.5f) != slot + 1)
            continue;

        vec3 to_center = light_grid_pos - (encoded.xyz + vec3(0.5f));
        if (dot(to_center, to_center) <= 0.0625f) {
            emissive_cell_min = encoded.xyz;
            return true;
        }
    }

    return false;
}

bool ph_sable_light_belongs_to_sublevel(
    int slot,
    uint token,
    vec3 light_player_pos
) {
    if (slot < 0 || slot >= ph_sable_sublevel_count
            || token == 0u
            || token != ph_sable_identity_token(slot))
        return false;

    vec3 light_grid_pos;
    vec3 emissive_cell_min;
    return ph_sable_light_grid_position(
        slot,
        light_player_pos,
        light_grid_pos,
        emissive_cell_min
    );
}

bool ph_sable_cell_line_interval(
    vec3 origin,
    vec3 direction,
    ivec3 cell,
    out float enter_t,
    out float exit_t
) {
    vec3 cell_min = vec3(cell);
    vec3 cell_max = cell_min + vec3(1.0f);
    enter_t = -1e30f;
    exit_t = 1e30f;

    if (abs(direction.x) <= 1e-6f) {
        if (origin.x < cell_min.x || origin.x > cell_max.x) return false;
    } else {
        float first_t = (cell_min.x - origin.x) / direction.x;
        float second_t = (cell_max.x - origin.x) / direction.x;
        enter_t = max(enter_t, min(first_t, second_t));
        exit_t = min(exit_t, max(first_t, second_t));
    }
    if (abs(direction.y) <= 1e-6f) {
        if (origin.y < cell_min.y || origin.y > cell_max.y) return false;
    } else {
        float first_t = (cell_min.y - origin.y) / direction.y;
        float second_t = (cell_max.y - origin.y) / direction.y;
        enter_t = max(enter_t, min(first_t, second_t));
        exit_t = min(exit_t, max(first_t, second_t));
    }
    if (abs(direction.z) <= 1e-6f) {
        if (origin.z < cell_min.z || origin.z > cell_max.z) return false;
    } else {
        float first_t = (cell_min.z - origin.z) / direction.z;
        float second_t = (cell_max.z - origin.z) / direction.z;
        enter_t = max(enter_t, min(first_t, second_t));
        exit_t = min(exit_t, max(first_t, second_t));
    }

    return enter_t <= exit_t + 1e-6f;
}

vec3 ph_sable_dominant_axis(vec3 value) {
    vec3 magnitude = abs(value);
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z)
        return vec3(value.x >= 0.0f ? 1.0f : -1.0f, 0.0f, 0.0f);
    if (magnitude.y >= magnitude.z)
        return vec3(0.0f, value.y >= 0.0f ? 1.0f : -1.0f, 0.0f);
    return vec3(0.0f, 0.0f, value.z >= 0.0f ? 1.0f : -1.0f);
}

bool ph_sable_resolve_receiver_cell(
    vec3 grid_pos,
    vec3 grid_normal,
    ivec3 size,
    int atlas_z,
    out ivec3 receiver_cell,
    out vec3 face_normal
) {
    face_normal = ph_sable_dominant_axis(grid_normal);

    receiver_cell = ivec3(floor(grid_pos - face_normal * PH_SABLE_RECEIVER_PROBE));
    if (ph_sable_cell_receiver(receiver_cell, size, atlas_z)) return true;

    receiver_cell = ivec3(floor(grid_pos + face_normal * PH_SABLE_RECEIVER_PROBE));
    if (ph_sable_cell_receiver(receiver_cell, size, atlas_z)) {
        face_normal = -face_normal;
        return true;
    }

    receiver_cell = ivec3(floor(grid_pos));
    return ph_sable_cell_receiver(receiver_cell, size, atlas_z);
}

vec3 ph_sable_receiver_surface_endpoint(
    vec3 grid_pos,
    vec3 face_normal,
    ivec3 receiver_cell
) {
    vec3 cell_min = vec3(receiver_cell);
    vec3 endpoint = clamp(grid_pos, cell_min, cell_min + vec3(1.0f));

    if (face_normal.x != 0.0f)
        endpoint.x = cell_min.x + (face_normal.x > 0.0f ? 1.0f : 0.0f)
            + face_normal.x * PH_SABLE_VISIBILITY_BIAS;
    else if (face_normal.y != 0.0f)
        endpoint.y = cell_min.y + (face_normal.y > 0.0f ? 1.0f : 0.0f)
            + face_normal.y * PH_SABLE_VISIBILITY_BIAS;
    else
        endpoint.z = cell_min.z + (face_normal.z > 0.0f ? 1.0f : 0.0f)
            + face_normal.z * PH_SABLE_VISIBILITY_BIAS;

    return endpoint;
}

bool ph_sable_exit_receiver_cell(
    vec3 grid_pos,
    vec3 direction,
    ivec3 receiver_cell,
    out vec3 endpoint
) {
    float enter_t;
    float exit_t;
    if (!ph_sable_cell_line_interval(
            grid_pos,
            direction,
            receiver_cell,
            enter_t,
            exit_t
    )) return false;

    endpoint = grid_pos + direction * (exit_t + PH_SABLE_VISIBILITY_BIAS);
    return ph_sable_finite_vec3(endpoint);
}

bool ph_sable_visibility_cell_occludes(
    ivec3 cell,
    ivec3 grid_size,
    int atlas_z,
    ivec3 receiver_cell
) {
    if (all(equal(cell, receiver_cell)))
        return false;
    if (any(lessThan(cell, ivec3(0)))
            || any(greaterThanEqual(cell, grid_size)))
        return false;
    return ph_sable_cell_occluder(cell, grid_size, atlas_z);
}

bool ph_sable_grid_segment_visible(
    int slot,
    vec3 start_grid,
    vec3 end_grid,
    ivec3 receiver_cell
) {
    ivec3 grid_size = ivec3(ph_sable_grid_info[slot].xyz + 0.5f);
    int atlas_z = int(ph_sable_grid_info[slot].w);
    if (atlas_z < 0)
        return true;

    ivec3 cell = ivec3(floor(start_grid));
    ivec3 target_cell = ivec3(floor(end_grid));
    if (all(equal(cell, target_cell)))
        return true;

    vec3 ray = end_grid - start_grid;
    ivec3 step = ivec3(sign(ray));
    vec3 t_delta = vec3(1e30f);
    vec3 t_max = vec3(1e30f);

    if (abs(ray.x) > 1e-6f) {
        t_delta.x = abs(1.0f / ray.x);
        float boundary = float(cell.x + (step.x > 0 ? 1 : 0));
        t_max.x = (boundary - start_grid.x) / ray.x;
    }
    if (abs(ray.y) > 1e-6f) {
        t_delta.y = abs(1.0f / ray.y);
        float boundary = float(cell.y + (step.y > 0 ? 1 : 0));
        t_max.y = (boundary - start_grid.y) / ray.y;
    }
    if (abs(ray.z) > 1e-6f) {
        t_delta.z = abs(1.0f / ray.z);
        float boundary = float(cell.z + (step.z > 0 ? 1 : 0));
        t_max.z = (boundary - start_grid.z) / ray.z;
    }

    for (int iteration = 0; iteration < 288; iteration++) {
        float next_t = min(t_max.x, min(t_max.y, t_max.z));
        if (next_t > 1.0f)
            return true;

        bool cross_x = t_max.x <= next_t + 1e-6f;
        bool cross_y = t_max.y <= next_t + 1e-6f;
        bool cross_z = t_max.z <= next_t + 1e-6f;
        ivec3 base_cell = cell;
        ivec3 x_cell = base_cell + ivec3(step.x, 0, 0);
        ivec3 y_cell = base_cell + ivec3(0, step.y, 0);
        ivec3 z_cell = base_cell + ivec3(0, 0, step.z);

        // A standard DDA advances all tied axes at once and can jump through
        // the gap between blocks that meet on an edge or corner. Test every
        // touched side cell before the diagonal cell so shared voxel borders
        // remain conservatively closed.
        if (cross_x && ph_sable_visibility_cell_occludes(
                x_cell, grid_size, atlas_z, receiver_cell)) return false;
        if (cross_y && ph_sable_visibility_cell_occludes(
                y_cell, grid_size, atlas_z, receiver_cell)) return false;
        if (cross_z && ph_sable_visibility_cell_occludes(
                z_cell, grid_size, atlas_z, receiver_cell)) return false;
        if (cross_x && cross_y && ph_sable_visibility_cell_occludes(
                x_cell + ivec3(0, step.y, 0),
                grid_size,
                atlas_z,
                receiver_cell
        )) return false;
        if (cross_x && cross_z && ph_sable_visibility_cell_occludes(
                x_cell + ivec3(0, 0, step.z),
                grid_size,
                atlas_z,
                receiver_cell
        )) return false;
        if (cross_y && cross_z && ph_sable_visibility_cell_occludes(
                y_cell + ivec3(0, 0, step.z),
                grid_size,
                atlas_z,
                receiver_cell
        )) return false;
        if (cross_x && cross_y && cross_z
                && ph_sable_visibility_cell_occludes(
                    x_cell + ivec3(0, step.y, step.z),
                    grid_size,
                    atlas_z,
                    receiver_cell
                )) return false;

        if (cross_x) {
            cell.x += step.x;
            t_max.x += t_delta.x;
        }
        if (cross_y) {
            cell.y += step.y;
            t_max.y += t_delta.y;
        }
        if (cross_z) {
            cell.z += step.z;
            t_max.z += t_delta.z;
        }

        if (all(equal(cell, receiver_cell)))
            return true;
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, grid_size)))
            return true;
        if (all(equal(cell, target_cell)))
            return true;
    }

    return false;
}

bool ph_sable_same_sublevel_light_visibility(
    int receiver_slot,
    uint receiver_token,
    vec3 receiver_player_pos,
    vec3 receiver_world_normal,
    int light_temporal_domain,
    vec3 light_player_pos,
    out bool visible
) {
    visible = true;
    if (receiver_slot < 0 || receiver_slot >= ph_sable_sublevel_count
            || receiver_token == 0u
            || receiver_token != ph_sable_identity_token(receiver_slot))
        return false;
    if (light_temporal_domain <= 0
            || uint(light_temporal_domain) != receiver_token)
        return false;

    vec3 light_grid_pos;
    vec3 emissive_cell_min;
    if (!ph_sable_light_grid_position(
            receiver_slot,
            light_player_pos,
            light_grid_pos,
            emissive_cell_min
    ))
        return false;

    mat4 player_to_grid = ph_sable_player_to_grid_matrix(receiver_slot);
    vec3 receiver_grid_pos = (
        player_to_grid * vec4(receiver_player_pos, 1.0f)
    ).xyz;
    if (!ph_sable_finite_vec3(receiver_grid_pos))
        return false;

    vec3 receiver_grid_normal = transpose(inverse(mat3(player_to_grid)))
        * receiver_world_normal;
    float receiver_grid_normal_length_sq = dot(receiver_grid_normal, receiver_grid_normal);
    if (!ph_sable_finite_vec3(receiver_grid_normal)
            || receiver_grid_normal_length_sq <= 1e-8f)
        return false;
    receiver_grid_normal *= inversesqrt(receiver_grid_normal_length_sq);

    vec3 source_center = emissive_cell_min + vec3(0.5f);
    vec3 receiver_to_source = source_center - receiver_grid_pos;
    float receiver_to_source_length_sq = dot(receiver_to_source, receiver_to_source);
    if (!ph_sable_finite_vec3(receiver_to_source)
            || receiver_to_source_length_sq <= 1e-8f)
        return true;
    receiver_to_source *= inversesqrt(receiver_to_source_length_sq);

    ivec3 grid_size = ivec3(ph_sable_grid_info[receiver_slot].xyz + 0.5f);
    int atlas_z = int(ph_sable_grid_info[receiver_slot].w);
    ivec3 receiver_cell;
    vec3 face_normal;
    if (!ph_sable_resolve_receiver_cell(
            receiver_grid_pos,
            receiver_grid_normal,
            grid_size,
            atlas_z,
            receiver_cell,
            face_normal
    ))
        return false;

    vec3 axis_mask = abs(face_normal);
    bool coplanar_source = abs(dot(
        emissive_cell_min - vec3(receiver_cell),
        axis_mask
    )) < 0.5f;

    vec3 receiver_endpoint;
    vec3 source_endpoint;
    if (coplanar_source) {
        // A wall-mounted source must begin on the exposed face. Starting from
        // its center sends tangential rays through neighboring wall cells.
        receiver_endpoint = ph_sable_receiver_surface_endpoint(
            receiver_grid_pos,
            face_normal,
            receiver_cell
        );
        source_endpoint = source_center
            + face_normal * (0.5f + PH_SABLE_VISIBILITY_BIAS);
    } else {
        // Preserve the collinear v28 segment for non-coplanar layouts.
        if (!ph_sable_exit_receiver_cell(
                receiver_grid_pos,
                receiver_to_source,
                receiver_cell,
                receiver_endpoint
        )) return false;

        vec3 source_to_receiver = receiver_endpoint - source_center;
        float source_ray_scale = max(
            abs(source_to_receiver.x),
            max(abs(source_to_receiver.y), abs(source_to_receiver.z))
        );
        if (!ph_sable_finite_vec3(receiver_endpoint) || source_ray_scale <= 1e-8f)
            return false;

        vec3 source_cell_ray = source_to_receiver / source_ray_scale;
        source_endpoint = source_center
            + source_cell_ray * (0.5f - PH_SABLE_VISIBILITY_BIAS);
    }

    if (!ph_sable_finite_vec3(source_endpoint)
            || !ph_sable_finite_vec3(receiver_endpoint))
        return false;
    visible = ph_sable_grid_segment_visible(
        receiver_slot,
        source_endpoint,
        receiver_endpoint,
        receiver_cell
    );
    return true;
}

bool ph_sable_receiver_motion(
    vec3 current_player_pos,
    vec3 current_world_normal,
    out vec3 previous_player_pos,
    out vec3 previous_world_normal,
    out int sublevel_slot,
    out uint sublevel_token
) {
    for (int slot = 0; slot < PH_SABLE_MAX_SUBLEVELS; slot++) {
        if (slot >= ph_sable_sublevel_count)
            break;

        mat4 player_to_grid = ph_sable_player_to_grid_matrix(slot);
        vec3 grid_pos = (player_to_grid * vec4(current_player_pos, 1.0f)).xyz;
        ivec3 grid_size = ivec3(ph_sable_grid_info[slot].xyz + 0.5f);

        if (any(lessThan(grid_pos, vec3(-PH_SABLE_RECEIVER_BOUNDS_PAD)))
                || any(greaterThan(
                    grid_pos,
                    vec3(grid_size) + PH_SABLE_RECEIVER_BOUNDS_PAD
                )))
            continue;

        int atlas_z = int(ph_sable_grid_info[slot].w);
        vec3 grid_normal = transpose(inverse(mat3(player_to_grid)))
            * current_world_normal;
        float grid_normal_length_sq = dot(grid_normal, grid_normal);
        if (!ph_sable_finite_vec3(grid_normal)
                || grid_normal_length_sq <= 1e-8f)
            continue;
        grid_normal *= inversesqrt(grid_normal_length_sq);

        ivec3 receiver_cell;
        vec3 receiver_face_normal;
        bool receiver_match = ph_sable_resolve_receiver_cell(
            grid_pos,
            grid_normal,
            grid_size,
            atlas_z,
            receiver_cell,
            receiver_face_normal
        );
        if (!receiver_match && !ph_sable_matches_emissive_cell(slot, grid_pos))
            continue;

        previous_player_pos = (
            ph_sable_player_to_previous_player_matrix(slot) * vec4(current_player_pos, 1.0f)
        ).xyz;
        previous_world_normal = normalize(
            ph_sable_normal_to_previous_normal_matrix(slot) * current_world_normal
        );
        sublevel_slot = slot;
        sublevel_token = ph_sable_identity_token(slot);
        return sublevel_token != 0u;
    }

    previous_player_pos = current_player_pos;
    previous_world_normal = current_world_normal;
    sublevel_slot = -1;
    sublevel_token = 0u;
    return false;
}

#endif
