#include "/photonics/light_list.glsl"
#include "/photonics/tracing.glsl"

#include "/photonics/utility/random.glsl"

struct DirectSample {
    int light_index; // The index of the sampled light, will be -1 when empty
};

DirectSample direct_sample_empty() {
    return DirectSample(-1);
}

int direct_priority_sample_count() {
    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    if (priority_count <= 0)
        return 0;

    // If every selected light is dynamic, the entire candidate budget belongs
    // to this stratum. Otherwise reserve at most half the budget for distinct
    // dynamic lights, leaving the ordinary-light suffix at least half.
    if (priority_count == light_list_size)
        return PH_RESTIR_INITIAL_SAMPLES;

    return min(
        PH_RESTIR_INITIAL_SAMPLES,
        min(priority_count, max(4, PH_RESTIR_INITIAL_SAMPLES / 2))
    );
}

const int ph_direct_camera_prefix_max = 8;

int direct_camera_prefix_count() {
    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    int suffix_count = light_list_size - priority_count;
    if (suffix_count <= 0)
        return 0;

    int remaining_samples = PH_RESTIR_INITIAL_SAMPLES
        - direct_priority_sample_count();
    if (remaining_samples <= 0)
        return 0;

    int prefix_count = min(ph_direct_camera_prefix_max, suffix_count);

    // With only one remaining proposal, retain the original full-suffix
    // distribution. Otherwise one proposal is always left for the tail.
    if (suffix_count > prefix_count && remaining_samples <= 1)
        return 0;

    return prefix_count;
}

int direct_camera_sample_count() {
    int prefix_count = direct_camera_prefix_count();
    if (prefix_count <= 0)
        return 0;

    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    int suffix_count = light_list_size - priority_count;
    int remaining_samples = PH_RESTIR_INITIAL_SAMPLES
        - direct_priority_sample_count();
    int tail_count = suffix_count - prefix_count;

    // If the prefix is the entire suffix, keep every remaining proposal in
    // that stratum. Systematic sampling may revisit a light, but its proposal
    // probability remains exact and no candidate slot is wasted.
    if (tail_count <= 0)
        return remaining_samples;

    return min(prefix_count, max(remaining_samples - 1, 0));
}

DirectSample direct_sample_stratified(
    int candidate_index,
    int priority_offset,
    float camera_phase,
    float tail_phase
) {
    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    int priority_samples = direct_priority_sample_count();
    if (candidate_index < priority_samples)
        return DirectSample((priority_offset + candidate_index) % priority_count);

    int suffix_count = light_list_size - priority_count;
    int camera_count = direct_camera_prefix_count();
    int camera_samples = direct_camera_sample_count();
    int suffix_candidate = candidate_index - priority_samples;
    if (suffix_candidate < camera_samples) {
        float systematic_position = (
            float(suffix_candidate)
                + clamp(camera_phase, 0.0f, 0.99999994f)
        ) * float(camera_count) / float(camera_samples);
        int camera_index = min(int(systematic_position), camera_count - 1);
        return DirectSample(priority_count + camera_index);
    }

    int tail_count = suffix_count - camera_count;
    int tail_samples = PH_RESTIR_INITIAL_SAMPLES
        - priority_samples
        - camera_samples;
    if (tail_count <= 0 || tail_samples <= 0)
        return direct_sample_empty();

    // The ordinary-light suffix is sorted by approximate camera contribution.
    // Its strongest prefix is sampled separately above so newly exposed
    // receivers immediately see nearby lights. The remaining proposals still
    // sweep the full tail, preserving nonzero probability for every light.
    int tail_candidate = suffix_candidate - camera_samples;
    float systematic_position = (
        float(tail_candidate) + clamp(tail_phase, 0.0f, 0.99999994f)
    ) * float(tail_count) / float(tail_samples);
    int tail_index = min(int(systematic_position), tail_count - 1);

    return DirectSample(priority_count + camera_count + tail_index);
}

float direct_sample_probability(DirectSample smple) {
    if (smple.light_index < 0 || smple.light_index >= light_list_size)
        return 0.0f;

    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    int priority_samples = direct_priority_sample_count();
    float total_samples = float(PH_RESTIR_INITIAL_SAMPLES);

    // These are disjoint strata. Using their exact aggregate proposal density
    // keeps canonical RIS unbiased while guaranteeing useful dynamic samples.
    if (smple.light_index < priority_count) {
        if (priority_samples <= 0)
            return 0.0f;

        return float(priority_samples) / (total_samples * float(priority_count));
    }

    int suffix_count = light_list_size - priority_count;
    int camera_count = direct_camera_prefix_count();
    int camera_samples = direct_camera_sample_count();
    if (smple.light_index < priority_count + camera_count) {
        if (camera_samples <= 0)
            return 0.0f;

        return float(camera_samples) / (total_samples * float(camera_count));
    }

    int tail_count = suffix_count - camera_count;
    int tail_samples = PH_RESTIR_INITIAL_SAMPLES
        - priority_samples
        - camera_samples;
    if (tail_count <= 0 || tail_samples <= 0)
        return 0.0f;

    return float(tail_samples) / (total_samples * float(tail_count));
}

bool direct_sample_is_empty(DirectSample smple) {
    return smple.light_index == -1;
}

float direct_sample_weight(vec3 color) {
    float weight = ph_luminance(color);
    // Match the old uniform sampler's numerical cutoff in physical-light space.
    float min_weight = 0.0001f / float(max(light_list_size, 1));

    return weight < min_weight ? 0.0f : weight;
}

Light direct_sample_get_light(DirectSample smple) {
    if (direct_sample_is_empty(smple))
        return new_invalid_light();

    return light_list_get(int(smple.light_index));
}

int direct_sample_get_temporal_domain(DirectSample smple) {
    if (direct_sample_is_empty(smple))
        return 0;

    return light_list_get_temporal_domain(smple.light_index);
}

bool direct_sample_matches_receiver_domain(
    DirectSample smple,
    uint receiver_token
) {
    return receiver_token != 0u
        && direct_sample_get_temporal_domain(smple) == int(receiver_token);
}

// History motion is receiver-relative. Only lights in the receiver's motion
// domain are stable: world lights are stable for world receivers, while a
// Sable receiver keeps only lights attached to its own rigid sublevel here.
bool direct_sample_uses_external_history(
    DirectSample smple,
    uint receiver_token
) {
    int light_domain = direct_sample_get_temporal_domain(smple);
    return uint(max(light_domain, 0)) != receiver_token;
}

vec3 direct_sample_get_color(
    DirectSample smple,
    Light light,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (!light_is_valid(light))
        return vec3(0.0f);

    return light_sample_at(
        light,
        sample_pos,
        light.position,
        geo_normal,
        tex_normal
    );
}

float direct_sample_get_weight(
    DirectSample smple,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (direct_sample_is_empty(smple))
        return 0.0f;

    Light light = direct_sample_get_light(smple);
    vec3 color = direct_sample_get_color(smple, light, sample_pos, geo_normal, tex_normal);

    return direct_sample_weight(color);
}

bool direct_sample_reproject(inout DirectSample smple) {
    if (smple.light_index < 0) return false;

    smple.light_index = light_list_map_index(smple.light_index);
    if (smple.light_index < 0 || smple.light_index >= light_list_size) {
        smple.light_index = -1;
        return false;
    }

    return true;
}
