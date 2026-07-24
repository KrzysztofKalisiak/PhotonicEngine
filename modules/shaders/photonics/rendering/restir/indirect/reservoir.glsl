#include "/photonics/rendering/restir/indirect/sample.glsl"
#include "/photonics/utility/normal_encoding.glsl"

//TODO Rename restir combined gi
#if defined PH_ENABLE_GI && defined PH_RESTIR_COMBINED_GI
#define PH_ENABLE_RESTIR_GI
#endif

#if defined PH_ENABLE_BLOCKLIGHT
#define INDIRECT_RESERVOIR_0 4
#define INDIRECT_RESERVOIR_1 5
#else
#define INDIRECT_RESERVOIR_0 2
#define INDIRECT_RESERVOIR_1 3
#endif

//ph_required: uniform sampler2D restir_indirect_reservoirs0;
//ph_required: uniform usampler2D restir_indirect_reservoirs1;

//ph_required: uniform sampler2D prev_restir_indirect_reservoirs0;
//ph_required: uniform usampler2D prev_restir_indirect_reservoirs1;

const float max_indirect_temporal_samples = 20.0f;
const float max_indirect_reservoir_samples = 20.0f;
// Stored hit points are full-precision block coordinates. One tracing voxel
// (1/16 block) covers DDA boundary roundoff without accepting a remote face.
const float indirect_endpoint_tolerance = 1.0f / 16.0f;
const float indirect_endpoint_tolerance_sq =
    indirect_endpoint_tolerance * indirect_endpoint_tolerance;

struct IndirectReservoir {
    IndirectSample smple;

    float weight;
    float total_samples;
};

bool indirect_color_is_finite(vec3 color) {
    return !any(isnan(color)) && !any(isinf(color));
}

bool indirect_reservoir_is_finite(IndirectReservoir reservoir) {
    return !isnan(reservoir.weight)
        && !isinf(reservoir.weight)
        && !isnan(reservoir.total_samples)
        && !isinf(reservoir.total_samples)
        && !any(isnan(reservoir.smple.hit_point))
        && !any(isinf(reservoir.smple.hit_point))
        && indirect_color_is_finite(reservoir.smple.color);
}

IndirectReservoir indirect_reservoir_empty() {
    return IndirectReservoir(
        indirect_sample_empty(),
        0.0f,
        0.0f
    );
}

bool indirect_reservoir_has_batch(IndirectReservoir reservoir) {
    return indirect_reservoir_is_finite(reservoir)
        && reservoir.total_samples > 0.0f;
}

bool indirect_reservoir_has_sample(IndirectReservoir reservoir) {
    return indirect_reservoir_has_batch(reservoir)
        && reservoir.weight > 0.0f
        && ph_luminance(reservoir.smple.color) > 0.0f;
}

bool indirect_reservoir_update(
    inout IndirectReservoir reservoir,
    IndirectSample smple,
    float weight,
    float samples
) {
    if (samples <= 0.0f || isnan(samples) || isinf(samples))
        return false;

    reservoir.total_samples += samples;
    if (weight <= 0.0f || isnan(weight) || isinf(weight))
        return false;

    reservoir.weight += weight;
    if (isnan(reservoir.weight) || isinf(reservoir.weight)) {
        reservoir = indirect_reservoir_empty();
        return false;
    }

    float required_rng = weight / reservoir.weight;
    if (ph_rand_next_float(frag_rnd_state) < required_rng) {
        reservoir.smple = smple;
        return true;
    }

    return false;
}

bool indirect_reservoir_merge(
    inout IndirectReservoir result,
    IndirectReservoir other,
    float jacobian,
    inout float sample_weight
) {
    if (!indirect_reservoir_has_sample(other)
            || jacobian <= 0.0f
            || isnan(jacobian)
            || isinf(jacobian))
        return false;

    float other_sample_weight = ph_luminance(other.smple.color);
    if (other_sample_weight <= 0.0f
            || isnan(other_sample_weight)
            || isinf(other_sample_weight))
        return false;

    float other_weight = other_sample_weight * other.weight * other.total_samples * jacobian;
    if (indirect_reservoir_update(result, other.smple, other_weight, other.total_samples)) {
        sample_weight = other_sample_weight;
        return true;
    }

    return false;
}

bool indirect_reservoir_merge_current_batch(
    inout IndirectReservoir result,
    IndirectReservoir current,
    float jacobian,
    inout float sample_weight
) {
    if (indirect_reservoir_has_sample(current))
        return indirect_reservoir_merge(
            result,
            current,
            jacobian,
            sample_weight
        );

    // A fresh zero-radiance proposal still belongs in this frame's sample
    // count. Empty temporal or spatial histories are deliberately not copied.
    if (indirect_reservoir_has_batch(current))
        result.total_samples += current.total_samples;
    return false;
}

void indirect_reservoir_clamp_samples(inout IndirectReservoir reservoir) {
    if (!indirect_reservoir_is_finite(reservoir)) {
        reservoir = indirect_reservoir_empty();
        return;
    }
    if (reservoir.total_samples <= max_indirect_reservoir_samples)
        return;

    reservoir.weight *= max_indirect_reservoir_samples / reservoir.total_samples;
    reservoir.total_samples = max_indirect_reservoir_samples;
}

void indirect_reservoir_reject(inout IndirectReservoir reservoir) {
    reservoir.weight = 0.0f;
    reservoir.smple.color = vec3(0.0f);
}

bool indirect_reservoir_validate_visibility(
    inout IndirectReservoir reservoir,
    vec3 rt_pos
) {
    if (!indirect_reservoir_has_sample(reservoir))
        return false;
    if (ph_world_ready == 0) {
        indirect_reservoir_reject(reservoir);
        return false;
    }

    vec3 hit_point = indirect_sample_get_hit_point(reservoir.smple);
    vec3 to_hit = hit_point - rt_pos;
    float hit_distance_sq = dot(to_hit, to_hit);
    if (hit_distance_sq <= 0.0000001f
            || any(isnan(hit_point))
            || any(isinf(hit_point))) {
        indirect_reservoir_reject(reservoir);
        return false;
    }

    RayIterator ray;
    ray_iter_begin(ray, rt_pos, to_hit * inversesqrt(hit_distance_sq));
    ray.iterations = 128;
    uint path_hash = indirect_path_hash_seed;

    while (ray.iterations > 0) {
        RayResult result = ray_iter_next(ray);
        if (ray.iterations <= 0) {
            indirect_reservoir_reject(reservoir);
            return false;
        }

        if (!ray_result_is_hit(result)) {
            bool valid_sky_path = indirect_sample_hits_sky(reservoir.smple)
                && !ray_iter_is_in_bounds(ray)
                && indirect_sample_matches_sky_path(
                    reservoir.smple,
                    path_hash
                );
            if (!valid_sky_path)
                indirect_reservoir_reject(reservoir);
            return valid_sky_path;
        }

        vec3 position_delta = ray_result_position(result) - hit_point;
        if (dot(position_delta, position_delta)
                <= indirect_endpoint_tolerance_sq) {
            path_hash = indirect_path_hash_surface(path_hash, result);
            bool path_matches = indirect_sample_matches_finite_path(
                reservoir.smple,
                ray_result_normal(result),
                path_hash
            );
            if (!path_matches)
                indirect_reservoir_reject(reservoir);
            return path_matches;
        }

        if (ray_result_is_transparent(result)) {
            path_hash = indirect_path_hash_surface(path_hash, result);
            ray_iter_skip_block(ray);
            continue;
        }

        indirect_reservoir_reject(reservoir);
        return false;
    }

    indirect_reservoir_reject(reservoir);
    return false;
}

void indirect_reservoir_finalize_weight(
    inout IndirectReservoir reservoir,
    float sample_weight
) {
    if (sample_weight <= 0.0f
            || isnan(sample_weight)
            || isinf(sample_weight)
            || reservoir.total_samples <= 0.0f
            || !indirect_reservoir_is_finite(reservoir)) {
        if (reservoir.total_samples > 0.0f
                && reservoir.weight == 0.0f
                && !isnan(reservoir.total_samples)
                && !isinf(reservoir.total_samples)) {
            reservoir.smple = indirect_sample_empty();
            return;
        }

        reservoir = indirect_reservoir_empty();
        return;
    }

    float final_weight = (1.0f / sample_weight)
        * (reservoir.weight / reservoir.total_samples);
    if (isnan(final_weight) || isinf(final_weight)) {
        reservoir = indirect_reservoir_empty();
        return;
    }

    reservoir.weight = final_weight;
}

vec3 indirect_reservoir_get_final_color(inout IndirectReservoir reservoir) {
    if (!indirect_reservoir_has_sample(reservoir))
        return vec3(0.0f);

    vec3 color = reservoir.smple.color * reservoir.weight;
    return indirect_color_is_finite(color) ? color : vec3(0.0f);
}

void indirect_reservoir_encode(
    IndirectReservoir reservoir,
    out vec4 data0,
    out uvec3 data1
) {
    IndirectReservoir safe_reservoir = reservoir;
    if (!indirect_reservoir_is_finite(safe_reservoir))
        safe_reservoir = indirect_reservoir_empty();

    data0.xyz = safe_reservoir.smple.hit_point;
    data0.w = max(safe_reservoir.weight, 0.0f);
    if (safe_reservoir.smple.hit_sky)
        data0.w = -data0.w;

    const float max_half_float = 65504.0f;
    vec3 packed_color = clamp(
        safe_reservoir.smple.color,
        vec3(0.0f),
        vec3(max_half_float)
    );
    data1.x = packHalf2x16(packed_color.rg);
    data1.y = packHalf2x16(vec2(
        packed_color.b,
        clamp(safe_reservoir.total_samples, 0.0f, max_half_float)
    ));
    data1.z = safe_reservoir.smple.packed_hit_normal;
}

void indirect_reservoir_decode(
    out IndirectReservoir reservoir,
    vec4 data0,
    uvec3 data1
) {
    reservoir.smple.hit_point = data0.xyz;
    reservoir.smple.hit_sky = data0.w < 0.0f;
    reservoir.weight = abs(data0.w);

    vec2 unpacked_value = unpackHalf2x16(data1.x);
    reservoir.smple.color.rg = unpacked_value;

    unpacked_value = unpackHalf2x16(data1.y);
    reservoir.smple.color.b = unpacked_value.x;
    reservoir.total_samples = unpacked_value.y;

    reservoir.smple.packed_hit_normal = data1.z;
}

bool indirect_reservoir_load(out IndirectReservoir reservoir, ivec2 tex_coord) {
    indirect_reservoir_decode(
        reservoir,
        texelFetch(restir_indirect_reservoirs0, tex_coord, 0),
        texelFetch(restir_indirect_reservoirs1, tex_coord, 0).rgb
    );

    if (!indirect_reservoir_is_finite(reservoir)) {
        reservoir = indirect_reservoir_empty();
        return false;
    }

    return true;
}

bool indirect_reservoir_load_previous(out IndirectReservoir reservoir, ivec2 tex_coord) {
    indirect_reservoir_decode(
        reservoir,
        texelFetch(prev_restir_indirect_reservoirs0, tex_coord, 0),
        texelFetch(prev_restir_indirect_reservoirs1, tex_coord, 0).rgb
    );

    reservoir.smple.hit_point -= delta_world_offset;

    if (!indirect_reservoir_is_finite(reservoir)) {
        reservoir = indirect_reservoir_empty();
        return false;
    }

    return true;
}
