#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir direct_reservoir = direct_reservoir_empty();
    direct_reservoir_load_flipped(direct_reservoir, frag_tex_coord);

    lighting.rgb += direct_reservoir_get_unshadowed_color(
        direct_reservoir,
        frag_rt_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal
    );
    direct_reservoir_encode(direct_reservoir, di_reservoir_0);
#endif
}
