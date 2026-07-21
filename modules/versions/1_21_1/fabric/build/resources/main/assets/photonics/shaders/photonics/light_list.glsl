#ifndef PH_LIGHT_LIST_INCLUDE
#define PH_LIGHT_LIST_INCLUDE

#include "/photonics/uniforms.glsl"
#include "/photonics/light.glsl"

layout (std140) restrict readonly buffer ph_light_list {
// vec 1: position (xyz) + block_id (w)
// vec 2: color (xyz) + intensity (w)
// vec 3: attenutation (xy) + falloff (z) + block_radius (w)
// vec 4: previous position (xyz) + temporal-validity flag (w)
    vec4 ph_lights_array[];
};

layout (std430) restrict readonly buffer ph_light_mapping {
    int ph_light_mapping_array[];
};

const int ph_light_size = 4; // 4 vec4s per light

Light light_list_get(int index) {
    int real_index = index;
    index*= ph_light_size;

    return new_light_from_vec4(
        ph_lights_array[index + 0],
        ph_lights_array[index + 1],
        ph_lights_array[index + 2],
        real_index,
        LIGHT_TYPE_TRACED
    );
}

vec4 light_list_get_previous_position(int index) {
    int offset = index * ph_light_size;
    vec4 previous = ph_lights_array[offset + 3];
    previous.xyz-= light_list_offset;
    return previous;
}

int light_list_map_index(int old_index) {
    // old_index belongs to the previous list, whose size can exceed the
    // current generation. The mapping buffer is allocated to PH_MAX_LIGHTS.
    if (old_index < 0 || old_index >= PH_MAX_LIGHTS) return -1;

    return ph_light_mapping_array[old_index];
}

#endif
