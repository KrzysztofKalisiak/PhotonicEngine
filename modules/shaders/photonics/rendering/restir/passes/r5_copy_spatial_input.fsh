#version 430

//ph_required: uniform sampler2D restir_direct_reservoirs0;

layout(location = 0) out vec3 spatial_reservoir_out;

void main() {
    spatial_reservoir_out = texelFetch(
        restir_direct_reservoirs0,
        ivec2(gl_FragCoord.xy),
        0
    ).rgb;
}
