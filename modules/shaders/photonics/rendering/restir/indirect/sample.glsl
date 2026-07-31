#include "/photonics/tracing.glsl"
#include "/photonics/utility/random.glsl"
#include "/photonics/utility/normal_encoding.glsl"

struct IndirectSample {
    vec3 hit_point;
    // Sky samples store a full surface-path signature. Finite samples store a
    // compact 29-bit signature plus the 3-bit voxel-face normal.
    uint packed_hit_normal;
    bool hit_sky;

    vec3 color;
};

const float indirect_sky_distance = 1000.0f;
const uint indirect_path_hash_seed = 2166136261u;
const uint indirect_path_hash_mask = 0x1fffffffu;

IndirectSample indirect_sample_empty() {
    return IndirectSample(vec3(0.0f), 0u, false, vec3(0.0f));
}

void indirect_sample_set_color(inout IndirectSample smple, vec3 color) {
    smple.color = color;
}

uint indirect_path_hash_word(uint path_hash, uint value) {
    return (path_hash ^ value) * 16777619u;
}

uint indirect_path_hash_surface(uint path_hash, RayResult hit) {
    VoxelData voxel_data = ray_result_voxel_data(hit);
    path_hash = indirect_path_hash_word(path_hash, voxel_data.x);
    path_hash = indirect_path_hash_word(path_hash, voxel_data.y);
    path_hash = indirect_path_hash_word(path_hash, voxel_data.z);
    path_hash = indirect_path_hash_word(path_hash, voxel_data.w);

    uint surface_flags = ray_result_skylight(hit)
        | (ray_result_is_transparent(hit) ? 16u : 0u)
        | (ph_encode_voxel_normal(ray_result_normal(hit)) << 5u);
    return indirect_path_hash_word(path_hash, surface_flags);
}

uint indirect_path_hash_compact(uint path_hash) {
    path_hash ^= path_hash >> 16u;
    path_hash *= 0x7feb352du;
    path_hash ^= path_hash >> 15u;
    return path_hash & indirect_path_hash_mask;
}

uint indirect_sample_pack_finite_hit(vec3 hit_normal, uint path_hash) {
    return (indirect_path_hash_compact(path_hash) << 3u)
        | (ph_encode_voxel_normal(hit_normal) & 7u);
}

vec3 indirect_sample_get_hit_normal(IndirectSample smple) {
    VoxelNormal normal = min(smple.packed_hit_normal & 7u, 5u);
    return ph_decode_voxel_normal(normal);
}

void indirect_sample_set_hit_metadata(
        inout IndirectSample smple,
        vec3 hit_normal,
        uint path_hash
) {
    smple.packed_hit_normal = smple.hit_sky
        ? path_hash
        : indirect_sample_pack_finite_hit(hit_normal, path_hash);
}

bool indirect_sample_matches_finite_path(
        IndirectSample smple,
        vec3 hit_normal,
        uint path_hash
) {
    return !smple.hit_sky
        && smple.packed_hit_normal
            == indirect_sample_pack_finite_hit(hit_normal, path_hash);
}

bool indirect_sample_matches_sky_path(
        IndirectSample smple,
        uint path_hash
) {
    return smple.hit_sky && smple.packed_hit_normal == path_hash;
}

vec3 indirect_sample_get_hit_point(IndirectSample smple) {
    return smple.hit_point;
}

void indirect_sample_set_hit_point(
        inout IndirectSample smple,
        vec3 hit_position,
        vec3 visible_point,
        vec3 visible_normal,
        uint rnd_state
) {
    if (any(isinf(hit_position))) {
        uint direction_rnd_state = rnd_state;
        vec3 direction = ph_rand_direction(direction_rnd_state, visible_normal);
        smple.hit_point = visible_point + direction * indirect_sky_distance;
        smple.hit_sky = true;
    } else if (any(isnan(hit_position))) {
        // A zero-length finite sample is rejected by visibility validation.
        smple.hit_point = visible_point;
        smple.hit_sky = false;
    } else {
        smple.hit_point = hit_position;
        smple.hit_sky = false;
    }
}

bool indirect_sample_hits_sky(IndirectSample smple) {
    return smple.hit_sky;
}

float indirect_sample_compute_jacobian(
    IndirectSample smple,
    vec3 dst_pos,
    vec3 src_pos
) {
    vec3 hit_position = indirect_sample_get_hit_point(smple);

    vec3 to_current = dst_pos - hit_position;
    vec3 to_source = src_pos - hit_position;

    float to_current_sq = dot(to_current, to_current);
    float to_source_sq = dot(to_source, to_source);
    if (to_current_sq <= 0.0000001f || to_source_sq <= 0.0000001f)
        return 0.0f;

    // A sky sample's virtual hit normal is exactly opposite the original
    // source-to-sky direction, which is recoverable from its finite endpoint.
    vec3 hit_normal = smple.hit_sky
        ? to_source * inversesqrt(to_source_sq)
        : indirect_sample_get_hit_normal(smple);

    float jacobian = (dot(hit_normal, to_current * inversesqrt(to_current_sq)) / to_current_sq);
    jacobian /= (dot(hit_normal, to_source * inversesqrt(to_source_sq)) / to_source_sq);

    return isinf(jacobian) || isnan(jacobian)
        ? 0.0f
        : clamp(jacobian, 0.0f, 3.0f);
}

float indirect_sample_compute_shift(
    IndirectSample smple,
    FragData dst_frag,
    FragData src_frag
) {
    return indirect_sample_compute_jacobian(
        smple,
        frag_data_rt_pos(dst_frag),
        frag_data_rt_pos(src_frag)
    );
}
