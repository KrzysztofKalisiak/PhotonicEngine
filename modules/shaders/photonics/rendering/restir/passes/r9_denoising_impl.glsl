//ph_required: uniform sampler2D prev_denoise_result;

//ph_required: uniform int atrous_iteration;
//ph_required: uniform sampler2D depthtex0;
//ph_required: uniform float near, far;

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#include "/photonics/rendering/restir/svgf.glsl"

#include "/photonics/utility/color.glsl"

layout(location = 0) out vec4 denoise_out;

bool ph_should_skip_denoise_pass(float variance) {
    return !frag_is_hand
        && atrous_iteration >= PH_RESTIR_DENOISER_PASSES
        && variance < 0.1f;
}

bool ph_should_use_geo_normal_for_denoise(bool is_hand, float variance) {
    return is_hand || variance > 0.05f;
}

vec3 ph_get_normal_for_denoise(FragData frag, float variance) {
    return ph_should_use_geo_normal_for_denoise(
        frag_data_is_hand(frag),
        variance
    )
        ? frag_data_geo_normal(frag)
        : frag_data_tex_normal(frag);
}

ivec2 ph_get_denoise_depth_texel(ivec2 texel) {
    return clamp(
        SVGF_DEPTH_MODIFIER(ph_shaderpack_texel(texel)),
        ivec2(0),
        textureSize(depthtex0, 0) - ivec2(1)
    );
}

void main() {
    denoise_out = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

    // r8 immediately seeds this pass from the current frame's accumulated
    // lighting. A world revision invalidates the temporal reservoirs in r4/r7,
    // but must not bypass the current-frame SVGF chain here; doing so exposes
    // the one-sample GI buffer as raw noise during section streaming.
    denoise_out = texelFetch(prev_denoise_result, frag_tex_coord, 0);
    denoise_out.rgb = ph_restir_sanitize_radiance(denoise_out.rgb);
    denoise_out.a = ph_restir_sanitize_variance(denoise_out.a);
    if (ph_should_skip_denoise_pass(denoise_out.a)) return;

    int step_width = 1 << atrous_iteration;
    float depth = texelFetch(depthtex0, ph_get_denoise_depth_texel(frag_tex_coord), 0).r;
    ivec2 max_texel = textureSize(prev_denoise_result, 0) - ivec2(1);

    // Center fetches
    #define C0 denoise_out.rgb
    #define V0 denoise_out.a

    float L0 = ph_luminance(C0);
    vec3  N0 = ph_should_use_geo_normal_for_denoise(
        frag_is_hand,
        denoise_out.a
    )
        ? frag_geo_normal
        : frag_tex_normal;
    float D0 = svgf_linearize_depth(depth);


    // 2) Bilateral‐style filter with adaptive color weight
    const float phi_depth = 0.5f;
    const float phi_position = 0.05f;
    float phi_luminance = 6.0f * sqrt(max(0.0f, V0)) + 1e-10;

    bool center_has_direct_sample = false;
    int center_direct_light = -1;
    bool center_direct_visible = false;
    float center_local_signature = 0.0f;
#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir center_reservoir = direct_reservoir_empty();
    center_has_direct_sample = direct_reservoir_load(center_reservoir, frag_tex_coord)
        && direct_reservoir_has_sample(center_reservoir);
    vec2 center_state;
    center_direct_visible = direct_history_load(center_state, frag_tex_coord);
    center_local_signature = center_state.y;
    if (center_has_direct_sample) {
        center_direct_light = center_reservoir.smple.light_index;
    }
#endif

    vec3 C_sum = vec3(0.0f);
    float W_sum = 0.0f;
    float V_sum = 0.0f;

    for (int i = 0; i < 9; ++i) {
        ivec2 p = clamp(
            frag_tex_coord + step_width * offset[i],
            ivec2(0),
            max_texel
        );

        FragData sample_frag;
        frag_data_load(sample_frag, p);
        if (!frag_data_is_in_world(sample_frag)
                || frag_data_is_hand(sample_frag) != frag_is_hand
                || frag_data_sublevel_token(sample_frag) != frag_data_sublevel_token(_frag_data))
            continue;

#if defined PH_ENABLE_BLOCKLIGHT
        if (frag_data_sublevel_token(_frag_data) != 0u) {
            vec2 sample_state;
            direct_history_load(sample_state, p);
            if (any(isnan(sample_state)) || any(isinf(sample_state))
                    || abs(sample_state.y - center_local_signature) > 0.5f)
                continue;
        } else if (center_has_direct_sample) {
            DirectReservoir sample_reservoir = direct_reservoir_empty();
            if (direct_reservoir_load(sample_reservoir, p)
                    && direct_reservoir_has_sample(sample_reservoir)
                    && sample_reservoir.smple.light_index == center_direct_light) {
                vec2 sample_state;
                bool sample_visible = direct_history_load(sample_state, p);
                if (sample_visible != center_direct_visible)
                    continue;
            }
        }
#endif

        vec4 sample_data = texelFetch(prev_denoise_result, p, 0);
        sample_data.rgb = ph_restir_sanitize_radiance(sample_data.rgb);
        sample_data.a = ph_restir_sanitize_variance(sample_data.a);
        #define Ci sample_data.rgb
        #define Vi sample_data.a

        float Li = ph_luminance(Ci);
        vec3  Ni = ph_get_normal_for_denoise(sample_frag, denoise_out.a);
        float Di = svgf_linearize_depth(texelFetch(depthtex0, ph_get_denoise_depth_texel(p), 0).x);
        const float k = kernel[i];

        // Color (luminance) weight
        float wC = svgf_luma_edge_stopping_weight(L0, Li, phi_luminance);

        // Normal weight
        float wN = svgf_normal_edge_stopping_weight(N0, Ni);

        // Camera depth changes rapidly across an oblique planar wall and makes
        // coarse a-trous taps form view-dependent rings. World-space distance
        // from both geometric planes is stable under projection jitter.
        float wP;
        if (frag_is_hand) {
            wP = svgf_depth_edge_stopping_weight(D0, Di, phi_depth);
        } else {
            vec3 center_geo_normal = frag_data_geo_normal(_frag_data);
            vec3 sample_geo_normal = frag_data_geo_normal(sample_frag);
            vec3 position_delta = frag_data_player_pos(sample_frag)
                - frag_data_player_pos(_frag_data);
            float plane_distance = max(
                abs(dot(position_delta, center_geo_normal)),
                abs(dot(position_delta, sample_geo_normal))
            );
            wP = exp(-plane_distance / phi_position);
        }

        bool invalid_sample = any(isnan(Ci))
            || any(isinf(Ci))
            || isnan(Vi)
            || isinf(Vi);
        float w = invalid_sample ? 0.0f : wC * wN * wP * k;
        W_sum += w;
        C_sum += Ci.xyz * w;
        V_sum += Vi * w * w;
    }

    W_sum = max(0.0001f, W_sum);
    V_sum = max(0.0001f, V_sum);

    denoise_out.rgb = ph_restir_sanitize_radiance(C_sum / W_sum);
    denoise_out.a = ph_restir_sanitize_variance(
        max(V_sum / (W_sum * W_sum), 0.0f)
    );
}
