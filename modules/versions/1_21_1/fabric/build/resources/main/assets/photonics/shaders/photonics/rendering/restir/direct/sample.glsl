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

DirectSample direct_sample_stratified(
    inout uint rnd_state,
    int candidate_index,
    int priority_offset
) {
    int priority_count = clamp(ph_priority_light_count, 0, light_list_size);
    int priority_samples = direct_priority_sample_count();
    if (candidate_index < priority_samples)
        return DirectSample((priority_offset + candidate_index) % priority_count);

    int suffix_count = light_list_size - priority_count;
    return DirectSample(priority_count + ph_rand_next_int(rnd_state, 0, suffix_count));
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
    int suffix_samples = PH_RESTIR_INITIAL_SAMPLES - priority_samples;
    if (suffix_count <= 0 || suffix_samples <= 0)
        return 0.0f;

    return float(suffix_samples) / (total_samples * float(suffix_count));
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
