#version 430

#define PH_RESTIR_SOURCE_HISTORY_COMPOSE_PASS

#include "/photonics/rendering/restir/samplers.glsl"

layout(location = 0) out vec3 diagnostic_out;

void main() {
    vec2 source_size = vec2(textureSize(restir_local_lighting, 0));
    vec2 tex_coord = gl_FragCoord.xy / source_size;
    vec3 result = ph_sample_restir_source_history_diagnostic(tex_coord);

    if (any(isnan(result)) || any(isinf(result)))
        result = vec3(0.0f);

    diagnostic_out = clamp(result, vec3(0.0f), vec3(65504.0f));
}
