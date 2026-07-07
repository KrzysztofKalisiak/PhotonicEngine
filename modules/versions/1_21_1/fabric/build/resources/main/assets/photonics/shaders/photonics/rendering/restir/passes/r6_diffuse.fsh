#version 430

#include "/photonics/rendering/restir/restir.glsl"

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);
}
