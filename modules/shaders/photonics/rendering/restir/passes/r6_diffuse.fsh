#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

const int PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT = 256;

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

#if defined PH_ENABLE_BLOCKLIGHT
vec3 diagnostic_direct_light_sum(vec3 sample_pos, vec3 geo_normal, vec3 tex_normal) {
    vec3 result = vec3(0.0f);
    int light_count = min(light_list_size, PH_DIAGNOSTIC_DIRECT_LIGHT_LIMIT);

    for (int i = 0; i < light_count; i++) {
        Light light = light_list_get(i);
        vec3 to_light = light.position - sample_pos;
        float radius = max(light.block_radius + 1.0f, 1.0f);

        if (dot(to_light, to_light) > radius * radius)
            continue;

        result += light_sample_at(
            light,
            sample_pos,
            light.position,
            geo_normal,
            tex_normal
        );
    }

    return result;
}
#endif

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir direct_reservoir = direct_reservoir_empty();

    lighting.rgb += diagnostic_direct_light_sum(
        frag_rt_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal
    );
    direct_reservoir_encode(direct_reservoir, di_reservoir_0);
#endif
}
