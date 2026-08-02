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

    // Use at most one proposal per camera-relevant prefix light. Repeated
    // prefix proposals do not improve light coverage and leave too few
    // proposals for the much larger tail, increasing a rare tail hit's energy.
    return min(prefix_count, max(remaining_samples - 1, 0));
}

const int ph_direct_rank_strata_max = 5;

struct DirectProposalLayout {
    int priority_count;
    int priority_samples;
    int ordinary_count;
    int ordinary_samples;
    int stratum_count;
    ivec4 stratum_widths_0_3;
    int stratum_width_4;
    ivec4 stratum_samples_0_3;
    int stratum_samples_4;
};

int direct_rank_boundary(int index) {
    if (index == 0) return 8;
    if (index == 1) return 32;
    if (index == 2) return 128;
    return 512;
}

int direct_layout_stratum_width(DirectProposalLayout proposal_layout, int index) {
    return index < 4
        ? proposal_layout.stratum_widths_0_3[index]
        : proposal_layout.stratum_width_4;
}

void direct_layout_set_stratum_width(
    inout DirectProposalLayout proposal_layout,
    int index,
    int value
) {
    if (index < 4)
        proposal_layout.stratum_widths_0_3[index] = value;
    else
        proposal_layout.stratum_width_4 = value;
}

int direct_layout_stratum_samples(
    DirectProposalLayout proposal_layout,
    int index
) {
    return index < 4
        ? proposal_layout.stratum_samples_0_3[index]
        : proposal_layout.stratum_samples_4;
}

void direct_layout_set_stratum_samples(
    inout DirectProposalLayout proposal_layout,
    int index,
    int value
) {
    if (index < 4)
        proposal_layout.stratum_samples_0_3[index] = value;
    else
        proposal_layout.stratum_samples_4 = value;
}

void direct_layout_add_stratum_sample(
    inout DirectProposalLayout proposal_layout,
    int index
) {
    direct_layout_set_stratum_samples(
        proposal_layout,
        index,
        direct_layout_stratum_samples(proposal_layout, index) + 1
    );
}

DirectProposalLayout direct_build_proposal_layout() {
    DirectProposalLayout proposal_layout;
    proposal_layout.priority_count = clamp(
        ph_priority_light_count,
        0,
        light_list_size
    );
    proposal_layout.priority_samples = direct_priority_sample_count();
    proposal_layout.ordinary_count = light_list_size
        - proposal_layout.priority_count;
    proposal_layout.ordinary_samples = max(
        PH_RESTIR_INITIAL_SAMPLES - proposal_layout.priority_samples,
        0
    );
    proposal_layout.stratum_count = 0;
    proposal_layout.stratum_widths_0_3 = ivec4(0);
    proposal_layout.stratum_width_4 = 0;
    proposal_layout.stratum_samples_0_3 = ivec4(0);
    proposal_layout.stratum_samples_4 = 0;

    // The exact-prefix sampler is lower variance for compact lists. Once the
    // ordinary list exceeds the last rank boundary, disjoint logarithmic
    // strata reclaim prefix proposals and bound every selected light's hit
    // multiplier without adding rays or removing full-list support.
    if (proposal_layout.ordinary_count <= direct_rank_boundary(3)
            || proposal_layout.ordinary_samples <= 0)
        return proposal_layout;

    int natural_strata = 1;
    for (int i = 0; i < ph_direct_rank_strata_max - 1; i++) {
        if (proposal_layout.ordinary_count > direct_rank_boundary(i))
            natural_strata++;
    }
    proposal_layout.stratum_count = min(
        natural_strata,
        proposal_layout.ordinary_samples
    );

    int start = 0;
    for (int i = 0; i < ph_direct_rank_strata_max; i++) {
        if (i >= proposal_layout.stratum_count) break;

        int end = i == proposal_layout.stratum_count - 1
            ? proposal_layout.ordinary_count
            : min(direct_rank_boundary(i), proposal_layout.ordinary_count);
        direct_layout_set_stratum_width(proposal_layout, i, end - start);
        direct_layout_set_stratum_samples(proposal_layout, i, 1);
        start = end;
    }

    int remaining = proposal_layout.ordinary_samples
        - proposal_layout.stratum_count;

    // Keep useful coverage in the upper ranks before minimax allocation. For
    // the observed 3999-light ordinary list this establishes [2,1,2,3,1].
    if (remaining > 0 && proposal_layout.stratum_count > 0) {
        direct_layout_add_stratum_sample(proposal_layout, 0);
        remaining--;
    }
    if (remaining > 0 && proposal_layout.stratum_count > 2) {
        direct_layout_add_stratum_sample(proposal_layout, 2);
        remaining--;
    }
    if (remaining > 0 && proposal_layout.stratum_count > 3) {
        direct_layout_add_stratum_sample(proposal_layout, 3);
        remaining--;
    }
    if (remaining > 0 && proposal_layout.stratum_count > 3) {
        direct_layout_add_stratum_sample(proposal_layout, 3);
        remaining--;
    }

    // Allocate every remaining proposal to the band with the largest current
    // lights-per-proposal ratio. This keeps the worst expansion bounded while
    // retaining the upper-rank anchors above.
    for (int allocation = 0;
            allocation < PH_RESTIR_INITIAL_SAMPLES;
            allocation++) {
        if (remaining <= 0) break;

        int best_stratum = 0;
        int best_width = direct_layout_stratum_width(proposal_layout, 0);
        int best_samples = direct_layout_stratum_samples(proposal_layout, 0);
        for (int i = 1; i < ph_direct_rank_strata_max; i++) {
            if (i >= proposal_layout.stratum_count) break;

            int width = direct_layout_stratum_width(proposal_layout, i);
            int samples = direct_layout_stratum_samples(proposal_layout, i);
            if (width * best_samples > best_width * samples) {
                best_stratum = i;
                best_width = width;
                best_samples = samples;
            }
        }

        direct_layout_add_stratum_sample(proposal_layout, best_stratum);
        remaining--;
    }

    return proposal_layout;
}

bool direct_layout_uses_rank_strata(DirectProposalLayout proposal_layout) {
    return proposal_layout.stratum_count > 0;
}

float direct_rank_stratum_phase(
    int index,
    vec4 phases_0_3,
    float phase_4
) {
    return index < 4 ? phases_0_3[index] : phase_4;
}

DirectSample direct_sample_stratified(
    int candidate_index,
    int priority_offset,
    float camera_phase,
    float tail_phase,
    DirectProposalLayout proposal_layout,
    vec4 rank_phases_0_3,
    float rank_phase_4,
    out float proposal_probability
) {
    proposal_probability = 0.0f;
    float total_samples = float(PH_RESTIR_INITIAL_SAMPLES);
    int priority_count = proposal_layout.priority_count;
    int priority_samples = proposal_layout.priority_samples;
    if (candidate_index < priority_samples) {
        proposal_probability = float(priority_samples)
            / (total_samples * float(priority_count));
        return DirectSample((priority_offset + candidate_index) % priority_count);
    }

    int ordinary_candidate = candidate_index - priority_samples;
    if (direct_layout_uses_rank_strata(proposal_layout)) {
        int sample_start = 0;
        int light_start = 0;
        for (int i = 0; i < ph_direct_rank_strata_max; i++) {
            if (i >= proposal_layout.stratum_count) break;

            int stratum_samples = direct_layout_stratum_samples(
                proposal_layout,
                i
            );
            int stratum_width = direct_layout_stratum_width(
                proposal_layout,
                i
            );
            if (ordinary_candidate < sample_start + stratum_samples) {
                int local_candidate = ordinary_candidate - sample_start;
                float systematic_position = (
                    float(local_candidate) + clamp(
                        direct_rank_stratum_phase(
                            i,
                            rank_phases_0_3,
                            rank_phase_4
                        ),
                        0.0f,
                        0.99999994f
                    )
                ) * float(stratum_width) / float(stratum_samples);
                int local_index = min(
                    int(systematic_position),
                    stratum_width - 1
                );
                proposal_probability = float(stratum_samples)
                    / (total_samples * float(stratum_width));
                return DirectSample(
                    priority_count + light_start + local_index
                );
            }

            sample_start += stratum_samples;
            light_start += stratum_width;
        }

        return direct_sample_empty();
    }

    int suffix_count = proposal_layout.ordinary_count;
    int camera_count = direct_camera_prefix_count();
    int camera_samples = direct_camera_sample_count();
    if (ordinary_candidate < camera_samples) {
        float systematic_position = (
            float(ordinary_candidate)
                + clamp(camera_phase, 0.0f, 0.99999994f)
        ) * float(camera_count) / float(camera_samples);
        int camera_index = min(int(systematic_position), camera_count - 1);
        proposal_probability = float(camera_samples)
            / (total_samples * float(camera_count));
        return DirectSample(priority_count + camera_index);
    }

    int tail_count = suffix_count - camera_count;
    int tail_samples = PH_RESTIR_INITIAL_SAMPLES
        - priority_samples
        - camera_samples;
    if (tail_count <= 0 || tail_samples <= 0)
        return direct_sample_empty();

    int tail_candidate = ordinary_candidate - camera_samples;
    float systematic_position = (
        float(tail_candidate) + clamp(tail_phase, 0.0f, 0.99999994f)
    ) * float(tail_count) / float(tail_samples);
    int tail_index = min(int(systematic_position), tail_count - 1);
    proposal_probability = float(tail_samples)
        / (total_samples * float(tail_count));

    return DirectSample(priority_count + camera_count + tail_index);
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
