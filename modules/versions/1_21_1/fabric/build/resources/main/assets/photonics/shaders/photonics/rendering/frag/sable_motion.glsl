#ifndef PH_SABLE_MOTION_INCLUDE
#define PH_SABLE_MOTION_INCLUDE

#define PH_SABLE_MAX_SUBLEVELS 16
#define PH_SABLE_MAX_EMISSIVE_CELLS 64
//ph_required: uniform sampler3D ph_sable_occupancy;
//ph_required: uniform int ph_sable_sublevel_count;
//ph_required: uniform int ph_sable_emissive_cell_count;
//ph_required: uniform mat4 ph_sable_current_world_to_grid[16];
//ph_required: uniform mat4 ph_sable_current_world_to_previous_world[16];
//ph_required: uniform vec4 ph_sable_grid_info[16];
//ph_required: uniform vec4 ph_sable_identity_tokens[4];
//ph_required: uniform vec4 ph_sable_emissive_cells[64];

mat4 ph_sable_world_to_grid_matrix(int slot) {
    return ph_sable_current_world_to_grid[slot];
}

mat4 ph_sable_world_to_previous_world_matrix(int slot) {
    return ph_sable_current_world_to_previous_world[slot];
}

mat3 ph_sable_normal_to_previous_normal_matrix(int slot) {
    return transpose(inverse(mat3(ph_sable_world_to_previous_world_matrix(slot))));
}

uint ph_sable_identity_token(int slot) {
    vec4 tokens = ph_sable_identity_tokens[slot / 4];
    return uint(tokens[slot % 4] + 0.5f);
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

bool ph_sable_matches_occupancy(vec3 grid_pos, ivec3 size, int atlas_z) {
    if (atlas_z < 0)
        return false;

    const float probe = 0.15f;
    if (ph_sable_cell_receiver(ivec3(floor(grid_pos)), size, atlas_z)) return true;
    if (ph_sable_cell_receiver(ivec3(floor(grid_pos + vec3(probe, 0.0f, 0.0f))), size, atlas_z)) return true;
    if (ph_sable_cell_receiver(ivec3(floor(grid_pos - vec3(probe, 0.0f, 0.0f))), size, atlas_z)) return true;
    if (ph_sable_cell_receiver(ivec3(floor(grid_pos + vec3(0.0f, probe, 0.0f))), size, atlas_z)) return true;
    if (ph_sable_cell_receiver(ivec3(floor(grid_pos - vec3(0.0f, probe, 0.0f))), size, atlas_z)) return true;
    if (ph_sable_cell_receiver(ivec3(floor(grid_pos + vec3(0.0f, 0.0f, probe))), size, atlas_z)) return true;
    return ph_sable_cell_receiver(ivec3(floor(grid_pos - vec3(0.0f, 0.0f, probe))), size, atlas_z);
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

bool ph_sable_light_grid_position(int slot, vec3 light_world_pos, out vec3 light_grid_pos) {
    light_grid_pos = (
        ph_sable_world_to_grid_matrix(slot) * vec4(light_world_pos, 1.0f)
    ).xyz;
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
        if (dot(to_center, to_center) <= 0.0625f)
            return true;
    }

    return false;
}

bool ph_sable_grid_segment_visible(int slot, vec3 start_grid, vec3 end_grid) {
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

        if (t_max.x <= next_t + 1e-6f) {
            cell.x += step.x;
            t_max.x += t_delta.x;
        }
        if (t_max.y <= next_t + 1e-6f) {
            cell.y += step.y;
            t_max.y += t_delta.y;
        }
        if (t_max.z <= next_t + 1e-6f) {
            cell.z += step.z;
            t_max.z += t_delta.z;
        }

        if (all(equal(cell, target_cell)))
            return true;
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, grid_size)))
            return true;
        if (ph_sable_cell_occluder(cell, grid_size, atlas_z))
            return false;
    }

    return true;
}

bool ph_sable_same_sublevel_light_visible(
    int receiver_slot,
    uint receiver_token,
    vec3 receiver_world_pos,
    vec3 light_world_pos
) {
    if (receiver_slot < 0 || receiver_slot >= ph_sable_sublevel_count
            || receiver_token == 0u
            || receiver_token != ph_sable_identity_token(receiver_slot))
        return true;

    vec3 light_grid_pos;
    if (!ph_sable_light_grid_position(receiver_slot, light_world_pos, light_grid_pos))
        return true;

    vec3 receiver_grid_pos = (
        ph_sable_world_to_grid_matrix(receiver_slot) * vec4(receiver_world_pos, 1.0f)
    ).xyz;
    return ph_sable_grid_segment_visible(receiver_slot, light_grid_pos, receiver_grid_pos);
}

bool ph_sable_receiver_motion(
    vec3 current_world_pos,
    vec3 current_world_normal,
    out vec3 previous_world_pos,
    out vec3 previous_world_normal,
    out int sublevel_slot,
    out uint sublevel_token
) {
    for (int slot = 0; slot < PH_SABLE_MAX_SUBLEVELS; slot++) {
        if (slot >= ph_sable_sublevel_count)
            break;

        mat4 world_to_grid = ph_sable_world_to_grid_matrix(slot);
        vec3 grid_pos = (world_to_grid * vec4(current_world_pos, 1.0f)).xyz;
        ivec3 grid_size = ivec3(ph_sable_grid_info[slot].xyz + 0.5f);

        if (any(lessThan(grid_pos, vec3(-0.2f))) || any(greaterThan(grid_pos, vec3(grid_size) + 0.2f)))
            continue;

        int atlas_z = int(ph_sable_grid_info[slot].w);
        if (!ph_sable_matches_occupancy(grid_pos, grid_size, atlas_z)
                && !ph_sable_matches_emissive_cell(slot, grid_pos))
            continue;

        previous_world_pos = (
            ph_sable_world_to_previous_world_matrix(slot) * vec4(current_world_pos, 1.0f)
        ).xyz;
        previous_world_normal = normalize(
            ph_sable_normal_to_previous_normal_matrix(slot) * current_world_normal
        );
        sublevel_slot = slot;
        sublevel_token = ph_sable_identity_token(slot);
        return sublevel_token != 0u;
    }

    previous_world_pos = current_world_pos;
    previous_world_normal = current_world_normal;
    sublevel_slot = -1;
    sublevel_token = 0u;
    return false;
}

#endif
