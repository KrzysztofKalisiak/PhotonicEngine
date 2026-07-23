#include "/photonics/tracing.glsl"
#include "/photonics/utility/random.glsl"
#include "/photonics/utility/normal_encoding.glsl"

struct IndirectSample {
    uint packed_visible_normal;
    uint packed_hit_normal;

    vec3 visible_point;
    float trace_distance;

    vec3 color;
    uint rnd_state;
};

const float indirect_sky_distance = 1000.0f;

IndirectSample indirect_sample_empty() {
    return IndirectSample(0u, 0u, vec3(0.0f), 0.0f, vec3(0.0f), 0u);
}

float indirect_normal_factor(FragData frag, vec3 hit_pos) {
    if (any(isnan(hit_pos)) || any(isinf(hit_pos)))
        return 1.0f;

    vec3 to_hit = hit_pos - frag_data_rt_pos(frag);
    float distance_sq = dot(to_hit, to_hit);
    if (distance_sq <= 0.0000001f)
        return 1.0f;

    vec3 direction = to_hit * inversesqrt(distance_sq);
    float geo_normal_occlusion = clamp(
        dot(frag_data_geo_normal(frag), direction),
        0.01f,
        1.0f
    );
    float tex_normal_occlusion = clamp(
        dot(frag_data_tex_normal(frag), direction),
        0.01f,
        1.0f
    );
    float factor = tex_normal_occlusion / geo_normal_occlusion;

    return isnan(factor) || isinf(factor) ? 1.0f : factor;
}

void indirect_sample_set_color(inout IndirectSample smple, vec3 color) {
    smple.color = color;
}

void indirect_sample_set_rnd_state(inout IndirectSample smple, uint rnd_state) {
    smple.rnd_state = rnd_state;
}

vec3 indirect_sample_get_visible_normal(IndirectSample smple) {
    return ph_decode_normal(unpackUnorm2x16(smple.packed_visible_normal));
}

void indirect_sample_set_visible_normal(inout IndirectSample smple, vec3 visible_normal) {
    smple.packed_visible_normal = packUnorm2x16(ph_encode_normal(visible_normal));
}

vec3 indirect_sample_get_visible_point(IndirectSample smple) {
    return smple.visible_point;
}

void indirect_sample_set_visible_point(inout IndirectSample smple, vec3 visible_point) {
    smple.visible_point = visible_point;
}

vec3 indirect_sample_get_hit_normal(IndirectSample smple) {
    return ph_decode_normal(unpackUnorm2x16(smple.packed_hit_normal));
}

void indirect_sample_set_hit_normal(inout IndirectSample smple, vec3 hit_normal) {
    smple.packed_hit_normal = packUnorm2x16(ph_encode_normal(hit_normal));
}

vec3 indirect_sample_get_hit_point(IndirectSample smple) {
    vec3 direction = ph_rand_direction(smple.rnd_state, indirect_sample_get_visible_normal(smple));
    return smple.visible_point + (direction * abs(smple.trace_distance));
}

void indirect_sample_set_hit_position(inout IndirectSample smple, vec3 hit_position) {
    smple.trace_distance = isinf(hit_position.x)
        ? -indirect_sky_distance
        : distance(smple.visible_point, hit_position);
}

bool indirect_sample_hits_sky(IndirectSample smple) {
    return smple.trace_distance < 0.0f;
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

    vec3 hit_normal = indirect_sample_get_hit_normal(smple);

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
    vec3 hit_position = indirect_sample_get_hit_point(smple);
    float old_normal_factor = indirect_normal_factor(src_frag, hit_position);
    float new_normal_factor = indirect_normal_factor(dst_frag, hit_position);
    if (old_normal_factor <= 0.0000001f)
        return 0.0f;

    float normal_shift = new_normal_factor / old_normal_factor;
    float jacobian = indirect_sample_compute_jacobian(
        smple,
        frag_data_rt_pos(dst_frag),
        frag_data_rt_pos(src_frag)
    );
    float shift = normal_shift * jacobian;

    return isnan(shift) || isinf(shift) ? 0.0f : shift;
}
