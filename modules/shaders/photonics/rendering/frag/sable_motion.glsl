#ifndef PH_SABLE_MOTION_INCLUDE
#define PH_SABLE_MOTION_INCLUDE

#define PH_SABLE_MAX_SUBLEVELS 16
#define PH_SABLE_MAX_EMISSIVE_CELLS 64
#define PH_SABLE_MAX_SHAPE_BOXES 8
#define PH_SABLE_MAX_SHAPE_BOX_TESTS_PER_RAY 64
#define PH_SABLE_FULL_CELL_BOX_COUNT 254
#define PH_SABLE_CONSERVATIVE_CELL_BOX_COUNT 255
#define PH_SABLE_AMBIGUOUS_RECEIVER_TOKEN 65535u
const float PH_SABLE_VISIBILITY_BIAS = 0.001f;
const float PH_SABLE_VISIBILITY_ENDPOINT_GUARD = 0.002f;
const float PH_SABLE_RECEIVER_PROBE = 0.35f;
const float PH_SABLE_RECEIVER_BOUNDS_PAD = 0.4f;
const float PH_SABLE_UNAVAILABLE_RECEIVER_BOUNDS_PAD = 0.05f;
// RGBA8 cell layout: receiver flag, box count, shape-id low, shape-id high.
//ph_required: uniform sampler3D ph_sable_occupancy;
// Sparse shape rows contain min/max AABB pairs as RGBA32F texels.
//ph_required: uniform sampler3D ph_sable_shape_table;
//ph_required: uniform int ph_sable_sublevel_count;
//ph_required: uniform int ph_sable_emissive_cell_count;
//ph_required: uniform int ph_sable_geometry_atlas_ready;
//ph_required: uniform int ph_sable_shape_definition_count;
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

bool ph_sable_ambiguous_receiver_rejects_light(
    uint receiver_token,
    int light_temporal_domain
) {
    return receiver_token == PH_SABLE_AMBIGUOUS_RECEIVER_TOKEN
        && light_temporal_domain > 0;
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

bool ph_sable_recover_current_grid_position(
    int receiver_slot,
    uint receiver_token,
    vec3 previous_player_pos,
    out vec3 current_grid_pos
) {
    current_grid_pos = vec3(0.0f);
    if (receiver_slot < 0 || receiver_slot >= ph_sable_sublevel_count
            || receiver_token == 0u
            || receiver_token != ph_sable_identity_token(receiver_slot))
        return false;

    current_grid_pos = (
        ph_sable_previous_player_to_current_grid_matrix(receiver_slot)
            * vec4(previous_player_pos, 1.0f)
    ).xyz;
    return ph_sable_finite_vec3(current_grid_pos);
}

vec4 ph_sable_cell_data(ivec3 cell, ivec3 size, int atlas_z) {
    if (ph_sable_geometry_atlas_ready == 0
            || atlas_z < 0
            || any(lessThan(cell, ivec3(0)))
            || any(greaterThanEqual(cell, size)))
        return vec4(0.0f);

    return texelFetch(
        ph_sable_occupancy,
        ivec3(cell.xy, cell.z + atlas_z),
        0
    );
}

bool ph_sable_cell_receiver(ivec3 cell, ivec3 size, int atlas_z) {
    return ph_sable_cell_data(cell, size, atlas_z).r > 0.5f;
}

int ph_sable_cell_box_count(vec4 cell_data) {
    return int(cell_data.g * 255.0f + 0.5f);
}

int ph_sable_cell_shape_id(vec4 cell_data) {
    int low = int(cell_data.b * 255.0f + 0.5f);
    int high = int(cell_data.a * 255.0f + 0.5f);
    return low | (high << 8);
}

bool ph_sable_slot_has_geometry(int slot) {
    return ph_sable_geometry_atlas_ready != 0
        && slot >= 0
        && slot < ph_sable_sublevel_count
        && int(ph_sable_grid_info[slot].w) >= 0;
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

    // The temporal-domain token was assigned from this sublevel's CPU light
    // record. Recovering the source cell directly avoids the old global
    // 64-emitter lookup limit while retaining bounds validation.
    emissive_cell_min = vec3(clamp(
        ivec3(floor(light_grid_pos)),
        ivec3(0),
        max(grid_size - ivec3(1), ivec3(0))
    ));
    return true;
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

bool ph_sable_aabb_line_interval(
    vec3 origin,
    vec3 direction,
    vec3 box_min,
    vec3 box_max,
    out float enter_t,
    out float exit_t
) {
    enter_t = -1e30f;
    exit_t = 1e30f;

    if (abs(direction.x) <= 1e-6f) {
        if (origin.x < box_min.x || origin.x > box_max.x) return false;
    } else {
        float first_t = (box_min.x - origin.x) / direction.x;
        float second_t = (box_max.x - origin.x) / direction.x;
        enter_t = max(enter_t, min(first_t, second_t));
        exit_t = min(exit_t, max(first_t, second_t));
    }
    if (abs(direction.y) <= 1e-6f) {
        if (origin.y < box_min.y || origin.y > box_max.y) return false;
    } else {
        float first_t = (box_min.y - origin.y) / direction.y;
        float second_t = (box_max.y - origin.y) / direction.y;
        enter_t = max(enter_t, min(first_t, second_t));
        exit_t = min(exit_t, max(first_t, second_t));
    }
    if (abs(direction.z) <= 1e-6f) {
        if (origin.z < box_min.z || origin.z > box_max.z) return false;
    } else {
        float first_t = (box_min.z - origin.z) / direction.z;
        float second_t = (box_max.z - origin.z) / direction.z;
        enter_t = max(enter_t, min(first_t, second_t));
        exit_t = min(exit_t, max(first_t, second_t));
    }

    return enter_t <= exit_t + 1e-6f;
}

bool ph_sable_shape_id_valid(int shape_id) {
    if (shape_id <= 0 || shape_id > ph_sable_shape_definition_count)
        return false;

    ivec3 table_size = textureSize(ph_sable_shape_table, 0);
    return table_size.x >= PH_SABLE_MAX_SHAPE_BOXES * 2
        && table_size.y > shape_id
        && table_size.z >= 1;
}

bool ph_sable_shape_box(
    int shape_id,
    int box_index,
    ivec3 cell,
    out vec3 box_min,
    out vec3 box_max
) {
    box_min = vec3(0.0f);
    box_max = vec3(0.0f);
    if (shape_id <= 0
            || box_index < 0
            || box_index >= PH_SABLE_MAX_SHAPE_BOXES)
        return false;

    box_min = texelFetch(
        ph_sable_shape_table,
        ivec3(box_index * 2, shape_id, 0),
        0
    ).xyz + vec3(cell);
    box_max = texelFetch(
        ph_sable_shape_table,
        ivec3(box_index * 2 + 1, shape_id, 0),
        0
    ).xyz + vec3(cell);
    return ph_sable_finite_vec3(box_min)
        && ph_sable_finite_vec3(box_max)
        && all(greaterThan(box_max - box_min, vec3(0.0f)))
        && all(greaterThanEqual(box_min, vec3(cell) - vec3(1e-5f)))
        && all(lessThanEqual(box_max, vec3(cell) + vec3(1.00001f)));
}

bool ph_sable_segment_intersects_box(
    vec3 segment_origin,
    vec3 segment_direction,
    vec3 box_min,
    vec3 box_max,
    float endpoint_limit
) {
    float enter_t;
    float exit_t;
    return ph_sable_aabb_line_interval(
            segment_origin,
            segment_direction,
            box_min,
            box_max,
            enter_t,
            exit_t
        )
        && exit_t >= max(enter_t, 0.0f) - 1e-6f
        && enter_t < endpoint_limit;
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

bool ph_sable_select_receiver_box(
    vec3 grid_pos,
    vec3 face_normal,
    ivec3 receiver_cell,
    ivec3 grid_size,
    int atlas_z,
    out vec3 receiver_box_min,
    out vec3 receiver_box_max
) {
    receiver_box_min = vec3(receiver_cell);
    receiver_box_max = receiver_box_min + vec3(1.0f);
    vec4 cell_data = ph_sable_cell_data(receiver_cell, grid_size, atlas_z);
    int box_count = ph_sable_cell_box_count(cell_data);
    if (box_count <= 0
            || box_count == PH_SABLE_FULL_CELL_BOX_COUNT
            || box_count == PH_SABLE_CONSERVATIVE_CELL_BOX_COUNT)
        return true;
    if (box_count > PH_SABLE_MAX_SHAPE_BOXES)
        return false;

    int shape_id = ph_sable_cell_shape_id(cell_data);
    if (!ph_sable_shape_id_valid(shape_id))
        return false;

    bool found = false;
    float best_distance_sq = 1e30f;
    for (int box_index = 0; box_index < PH_SABLE_MAX_SHAPE_BOXES; box_index++) {
        if (box_index >= box_count)
            break;

        vec3 box_min;
        vec3 box_max;
        if (!ph_sable_shape_box(
                shape_id,
                box_index,
                receiver_cell,
                box_min,
                box_max
        )) return false;

        vec3 nearest_face = clamp(grid_pos, box_min, box_max);
        if (face_normal.x > 0.0f)
            nearest_face.x = box_max.x;
        else if (face_normal.x < 0.0f)
            nearest_face.x = box_min.x;
        else if (face_normal.y > 0.0f)
            nearest_face.y = box_max.y;
        else if (face_normal.y < 0.0f)
            nearest_face.y = box_min.y;
        else if (face_normal.z > 0.0f)
            nearest_face.z = box_max.z;
        else
            nearest_face.z = box_min.z;

        vec3 to_face = nearest_face - grid_pos;
        float distance_sq = dot(to_face, to_face);
        if (!found || distance_sq < best_distance_sq) {
            found = true;
            best_distance_sq = distance_sq;
            receiver_box_min = box_min;
            receiver_box_max = box_max;
        }
    }
    return found;
}

vec3 ph_sable_receiver_surface_endpoint(
    vec3 grid_pos,
    vec3 face_normal,
    vec3 receiver_box_min,
    vec3 receiver_box_max
) {
    vec3 endpoint = clamp(grid_pos, receiver_box_min, receiver_box_max);

    if (face_normal.x != 0.0f)
        endpoint.x = (face_normal.x > 0.0f ? receiver_box_max.x : receiver_box_min.x)
            + face_normal.x * PH_SABLE_VISIBILITY_BIAS;
    else if (face_normal.y != 0.0f)
        endpoint.y = (face_normal.y > 0.0f ? receiver_box_max.y : receiver_box_min.y)
            + face_normal.y * PH_SABLE_VISIBILITY_BIAS;
    else
        endpoint.z = (face_normal.z > 0.0f ? receiver_box_max.z : receiver_box_min.z)
            + face_normal.z * PH_SABLE_VISIBILITY_BIAS;

    return endpoint;
}

bool ph_sable_visibility_cell_occludes(
    ivec3 cell,
    ivec3 grid_size,
    int atlas_z,
    vec3 segment_origin,
    vec3 segment_direction,
    float endpoint_limit,
    ivec3 emitter_cell,
    inout int remaining_shape_box_tests
) {
    if (any(lessThan(cell, ivec3(0)))
            || any(greaterThanEqual(cell, grid_size)))
        return false;
    if (all(equal(cell, emitter_cell)))
        return false;

    vec4 cell_data = ph_sable_cell_data(cell, grid_size, atlas_z);
    int box_count = ph_sable_cell_box_count(cell_data);
    if (box_count <= 0)
        return false;
    if (!ph_sable_segment_intersects_box(
            segment_origin,
            segment_direction,
            vec3(cell),
            vec3(cell) + vec3(1.0f),
            endpoint_limit
    )) return false;
    if (box_count == PH_SABLE_FULL_CELL_BOX_COUNT
            || box_count == PH_SABLE_CONSERVATIVE_CELL_BOX_COUNT)
        return true;
    if (box_count > PH_SABLE_MAX_SHAPE_BOXES)
        return true;
    if (box_count > remaining_shape_box_tests)
        return true;
    remaining_shape_box_tests -= box_count;

    int shape_id = ph_sable_cell_shape_id(cell_data);
    if (!ph_sable_shape_id_valid(shape_id))
        return true;

    for (int box_index = 0; box_index < PH_SABLE_MAX_SHAPE_BOXES; box_index++) {
        if (box_index >= box_count)
            break;

        vec3 box_min;
        vec3 box_max;
        if (!ph_sable_shape_box(
                shape_id,
                box_index,
                cell,
                box_min,
                box_max
        )) return true;
        if (ph_sable_segment_intersects_box(
                segment_origin,
                segment_direction,
                box_min,
                box_max,
                endpoint_limit
        ))
            return true;
    }

    return false;
}

bool ph_sable_grid_segment_visible(
    int slot,
    vec3 start_grid,
    vec3 end_grid,
    ivec3 receiver_cell,
    ivec3 emitter_cell
) {
    ivec3 grid_size = ivec3(ph_sable_grid_info[slot].xyz + 0.5f);
    int atlas_z = int(ph_sable_grid_info[slot].w);
    if (!ph_sable_slot_has_geometry(slot))
        return false;

    ivec3 cell = ivec3(floor(start_grid));
    ivec3 target_cell = ivec3(floor(end_grid));
    vec3 ray = end_grid - start_grid;
    float ray_extent = max(abs(ray.x), max(abs(ray.y), abs(ray.z)));
    float endpoint_guard_t = min(
        0.25f,
        PH_SABLE_VISIBILITY_ENDPOINT_GUARD / max(ray_extent, 1e-6f)
    );
    float endpoint_limit = 1.0f - endpoint_guard_t;
    int remaining_shape_box_tests = PH_SABLE_MAX_SHAPE_BOX_TESTS_PER_RAY;
    // The endpoint can be pushed into the cell immediately outside a
    // wall-mounted emitter. Test that start cell explicitly; only the
    // CPU-tokened emitter cell is exempt from its own source geometry.
    if (ph_sable_visibility_cell_occludes(
            cell,
            grid_size,
            atlas_z,
            start_grid,
            ray,
            endpoint_limit,
            emitter_cell,
            remaining_shape_box_tests
    )) return false;
    if (all(equal(cell, target_cell)))
        return true;

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
        // The endpoint is already biased onto the receiver's exposed side.
        // Do not let conservative edge/corner coverage reinterpret the final
        // surface touch as an adjacent receiver block occluding itself.
        if (next_t >= endpoint_limit)
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
                x_cell, grid_size, atlas_z,
                start_grid, ray, endpoint_limit,
                emitter_cell,
                remaining_shape_box_tests)) return false;
        if (cross_y && ph_sable_visibility_cell_occludes(
                y_cell, grid_size, atlas_z,
                start_grid, ray, endpoint_limit,
                emitter_cell,
                remaining_shape_box_tests)) return false;
        if (cross_z && ph_sable_visibility_cell_occludes(
                z_cell, grid_size, atlas_z,
                start_grid, ray, endpoint_limit,
                emitter_cell,
                remaining_shape_box_tests)) return false;
        if (cross_x && cross_y && ph_sable_visibility_cell_occludes(
                x_cell + ivec3(0, step.y, 0),
                grid_size,
                atlas_z,
                start_grid,
                ray,
                endpoint_limit,
                emitter_cell,
                remaining_shape_box_tests
        )) return false;
        if (cross_x && cross_z && ph_sable_visibility_cell_occludes(
                x_cell + ivec3(0, 0, step.z),
                grid_size,
                atlas_z,
                start_grid,
                ray,
                endpoint_limit,
                emitter_cell,
                remaining_shape_box_tests
        )) return false;
        if (cross_y && cross_z && ph_sable_visibility_cell_occludes(
                y_cell + ivec3(0, 0, step.z),
                grid_size,
                atlas_z,
                start_grid,
                ray,
                endpoint_limit,
                emitter_cell,
                remaining_shape_box_tests
        )) return false;
        if (cross_x && cross_y && cross_z
                && ph_sable_visibility_cell_occludes(
                    x_cell + ivec3(0, step.y, step.z),
                    grid_size,
                    atlas_z,
                    start_grid,
                    ray,
                    endpoint_limit,
                    emitter_cell,
                    remaining_shape_box_tests
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

bool ph_sable_same_sublevel_light_visibility_at_grid(
    int receiver_slot,
    uint receiver_token,
    vec3 receiver_grid_pos,
    vec3 receiver_world_normal,
    int light_temporal_domain,
    vec3 light_player_pos,
    out bool visible
) {
    visible = true;
    if (ph_sable_ambiguous_receiver_rejects_light(
            receiver_token,
            light_temporal_domain
    )) {
        visible = false;
        return true;
    }
    if (receiver_token == PH_SABLE_AMBIGUOUS_RECEIVER_TOKEN)
        return false;
    if (receiver_slot < 0 || receiver_slot >= ph_sable_sublevel_count
            || receiver_token == 0u
            || receiver_token != ph_sable_identity_token(receiver_slot))
        return false;
    if (light_temporal_domain <= 0
            || uint(light_temporal_domain) != receiver_token)
        return false;
    // A matching token selects this local visibility authority. Every
    // subsequent validation failure is handled conservatively, never routed
    // back through the static world tree.
    visible = false;
    if (!ph_sable_slot_has_geometry(receiver_slot))
        return true;

    vec3 light_grid_pos;
    vec3 emissive_cell_min;
    if (!ph_sable_light_grid_position(
            receiver_slot,
            light_player_pos,
            light_grid_pos,
            emissive_cell_min
    ))
        return true;

    if (!ph_sable_finite_vec3(receiver_grid_pos))
        return true;

    mat4 player_to_grid = ph_sable_player_to_grid_matrix(receiver_slot);
    vec3 receiver_grid_normal = transpose(inverse(mat3(player_to_grid)))
        * receiver_world_normal;
    float receiver_grid_normal_length_sq = dot(receiver_grid_normal, receiver_grid_normal);
    if (!ph_sable_finite_vec3(receiver_grid_normal)
            || receiver_grid_normal_length_sq <= 1e-8f)
        return true;
    receiver_grid_normal *= inversesqrt(receiver_grid_normal_length_sq);

    vec3 source_center = emissive_cell_min + vec3(0.5f);
    vec3 receiver_to_source = source_center - receiver_grid_pos;
    float receiver_to_source_length_sq = dot(receiver_to_source, receiver_to_source);
    if (!ph_sable_finite_vec3(receiver_to_source)
            || receiver_to_source_length_sq <= 1e-8f) {
        visible = true;
        return true;
    }
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
        return true;

    vec3 receiver_box_min;
    vec3 receiver_box_max;
    if (!ph_sable_select_receiver_box(
            receiver_grid_pos,
            face_normal,
            receiver_cell,
            grid_size,
            atlas_z,
            receiver_box_min,
            receiver_box_max
    )) return true;

    vec3 axis_mask = abs(face_normal);
    bool coplanar_source = abs(dot(
        emissive_cell_min - vec3(receiver_cell),
        axis_mask
    )) < 0.5f;

    vec3 receiver_endpoint = ph_sable_receiver_surface_endpoint(
        receiver_grid_pos,
        face_normal,
        receiver_box_min,
        receiver_box_max
    );
    vec3 source_endpoint;
    if (coplanar_source) {
        // A wall-mounted source must begin on the exposed face. Starting from
        // its center sends tangential rays through neighboring wall cells.
        source_endpoint = source_center
            + face_normal * (0.5f + PH_SABLE_VISIBILITY_BIAS);
    } else {
        // Snap to the classified exposed face before constructing the
        // collinear source endpoint. A ray-direction bias has vanishing
        // normal clearance at grazing angles and is unstable at block joints.
        vec3 source_to_receiver = receiver_endpoint - source_center;
        float source_ray_scale = max(
            abs(source_to_receiver.x),
            max(abs(source_to_receiver.y), abs(source_to_receiver.z))
        );
        if (!ph_sable_finite_vec3(receiver_endpoint) || source_ray_scale <= 1e-8f)
            return true;

        vec3 source_cell_ray = source_to_receiver / source_ray_scale;
        source_endpoint = source_center
            + source_cell_ray * (0.5f - PH_SABLE_VISIBILITY_BIAS);
    }

    if (!ph_sable_finite_vec3(source_endpoint)
            || !ph_sable_finite_vec3(receiver_endpoint))
        return true;
    visible = ph_sable_grid_segment_visible(
        receiver_slot,
        source_endpoint,
        receiver_endpoint,
        receiver_cell,
        ivec3(emissive_cell_min)
    );
    return true;
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
    if (ph_sable_ambiguous_receiver_rejects_light(
            receiver_token,
            light_temporal_domain
    )) {
        visible = false;
        return true;
    }
    if (receiver_token == PH_SABLE_AMBIGUOUS_RECEIVER_TOKEN)
        return false;
    if (receiver_slot < 0 || receiver_slot >= ph_sable_sublevel_count
            || receiver_token == 0u
            || receiver_token != ph_sable_identity_token(receiver_slot))
        return false;

    vec3 receiver_grid_pos = (
        ph_sable_player_to_grid_matrix(receiver_slot)
            * vec4(receiver_player_pos, 1.0f)
    ).xyz;
    return ph_sable_same_sublevel_light_visibility_at_grid(
        receiver_slot,
        receiver_token,
        receiver_grid_pos,
        receiver_world_normal,
        light_temporal_domain,
        light_player_pos,
        visible
    );
}

bool ph_sable_receiver_motion(
    vec3 current_player_pos,
    vec3 current_world_normal,
    out vec3 previous_player_pos,
    out vec3 previous_world_normal,
    out int sublevel_slot,
    out uint sublevel_token
) {
    int fallback_slot = -1;
    int fallback_matches = 0;
    uint fallback_token = 0u;
    vec3 fallback_previous_player_pos = current_player_pos;
    vec3 fallback_previous_world_normal = current_world_normal;

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

        vec3 candidate_previous_player_pos = (
            ph_sable_player_to_previous_player_matrix(slot)
                * vec4(current_player_pos, 1.0f)
        ).xyz;
        vec3 candidate_previous_world_normal = normalize(
            ph_sable_normal_to_previous_normal_matrix(slot) * current_world_normal
        );
        uint candidate_token = ph_sable_identity_token(slot);
        if (candidate_token == 0u)
            continue;

        if (!ph_sable_slot_has_geometry(slot)) {
            // Fine occupancy can be omitted by the bounded atlas planner.
            // A unique bounds match keeps its identity. Multiple unavailable
            // bounds become the explicit unknown-Sable token below.
            if (any(lessThan(
                    grid_pos,
                    vec3(-PH_SABLE_UNAVAILABLE_RECEIVER_BOUNDS_PAD)
            )) || any(greaterThan(
                    grid_pos,
                    vec3(grid_size)
                        + vec3(PH_SABLE_UNAVAILABLE_RECEIVER_BOUNDS_PAD)
            ))) continue;
            fallback_matches++;
            if (fallback_matches == 1) {
                fallback_slot = slot;
                fallback_token = candidate_token;
                fallback_previous_player_pos = candidate_previous_player_pos;
                fallback_previous_world_normal = candidate_previous_world_normal;
            }
            continue;
        }

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

        previous_player_pos = candidate_previous_player_pos;
        previous_world_normal = candidate_previous_world_normal;
        sublevel_slot = slot;
        sublevel_token = candidate_token;
        return true;
    }

    if (fallback_matches > 1) {
        // Multiple atlas-less bounds contain this surface and no exact
        // atlas-backed receiver matched. Preserve "Sable but unknown" as a
        // first-class domain so every Sable light is rejected fail-closed.
        previous_player_pos = current_player_pos;
        previous_world_normal = current_world_normal;
        sublevel_slot = -1;
        sublevel_token = PH_SABLE_AMBIGUOUS_RECEIVER_TOKEN;
        return true;
    }

    if (fallback_matches == 1 && fallback_slot >= 0 && fallback_token != 0u) {
        previous_player_pos = fallback_previous_player_pos;
        previous_world_normal = fallback_previous_world_normal;
        sublevel_slot = fallback_slot;
        sublevel_token = fallback_token;
        return true;
    }

    previous_player_pos = current_player_pos;
    previous_world_normal = current_world_normal;
    sublevel_slot = -1;
    sublevel_token = 0u;
    return false;
}

#endif
