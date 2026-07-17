#version 430

//ph_required: uniform float near, far;

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#include "/photonics/rendering/restir/svgf.glsl"

layout(location = 0) out vec4 denoise_out;

bool ph_matches_denoise_receiver(ivec2 texel) {
    FragData frag;
    frag_data_load(frag, texel);

    return frag_data_is_in_world(frag)
        && frag_data_is_hand(frag) == frag_is_hand
        && frag_data_sublevel_token(frag) == frag_data_sublevel_token(_frag_data);
}

void main() {
    setup_frag_data(0);

    ivec2 max_texel = textureSize(restir_lighting, 0) - ivec2(1);

    // Firefly rejection
    vec4 center = texelFetch(restir_lighting, frag_tex_coord, 0);
    denoise_out = vec4(center.rgb, 10.0f);
    if (!frag_is_in_world) return;

    vec3 maxNeighbour = vec3(0.0f);
    bool hasNeighbour = false;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) continue;

            ivec2 pos = clamp(frag_tex_coord + ivec2(x, y), ivec2(0), max_texel);
            if (!ph_matches_denoise_receiver(pos)) continue;

            vec3 color = texelFetch(restir_lighting, pos, 0).rgb;
            maxNeighbour = max(maxNeighbour, color);
            hasNeighbour = true;
        }
    }

    if (hasNeighbour)
        denoise_out.rgb = min(center.rgb, maxNeighbour);

    if (frag_is_hand) return;

    denoise_out.a = 0.0f;
    float weight_sum = 0.0f;

    for (int i = 0; i < 9; i++) {
        ivec2 p = clamp(frag_tex_coord + offset[i], ivec2(0), max_texel);
        if (!ph_matches_denoise_receiver(p)) continue;

        float variance = texelFetch(restir_lighting_variance, p, 0).z;
        float kernel_weight = kernel[i];

        denoise_out.a += variance * kernel_weight;
        weight_sum += kernel_weight;
    }

    if (weight_sum > 0.0f)
        denoise_out.a /= weight_sum;
}
