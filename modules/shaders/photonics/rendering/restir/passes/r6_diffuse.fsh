#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"

layout(location = 0) out vec4 lighting;

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;
}
