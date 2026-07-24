//ph_required: uniform vec3 cameraPosition;
//ph_required: uniform int frameCounter;
//ph_required: uniform float viewWidth;
//ph_required: uniform float viewHeight;

//ph_required: uniform vec3 previousCameraPosition;

//ph_required: uniform mat4 gbufferPreviousModelView;
//ph_required: uniform mat4 gbufferPreviousProjection;

#include "/photonics/uniforms.glsl"

vec2 ph_scaled_view_size(float scale) {
    return max(floor(vec2(viewWidth, viewHeight) * scale), vec2(1.0f));
}

vec2 ph_shaderpack_to_render_scale() {
    return ph_scaled_view_size(PH_SHADERPACK_RENDER_SCALE)
        / ph_scaled_view_size(PH_ACTIVE_RENDER_SCALE);
}

vec4 ph_shaderpack_frag_coord() {
    vec4 source_coord = gl_FragCoord;
    source_coord.xy *= ph_shaderpack_to_render_scale();
    return source_coord;
}

ivec2 ph_shaderpack_texel(ivec2 photonics_texel) {
    vec2 source_center = (vec2(photonics_texel) + 0.5f)
        * ph_shaderpack_to_render_scale();
    return ivec2(source_center);
}

// Shader-pack interfaces read full/native-resolution G-buffer data through
// gl_FragCoord. Remap only while compiling that interface; Photonics passes
// continue to address their own scaled attachments with the real coordinate.
#define gl_FragCoord ph_shaderpack_frag_coord()
#include "/photonics/interface/world_interface.glsl"
#undef gl_FragCoord
