//HEAD

//TODO: DEPRECATED; REMOVE IN FUTURE RELEASE
#define NO_SHADOW_MAPPING

#include "/photonics/modifiers/indirect_environment_modifier.glsl"

vec3 ph_indirect_sun_color = vec3(0.0f);
vec3 ph_indirect_sky_color = vec3(0.0f);

void ph_load_indirect_environment() {
    ph_indirect_sun_color = indirect_light_color;
    ph_indirect_sky_color = indirect_light_color;
    modify_indirect_environment(
        ph_indirect_sun_color,
        ph_indirect_sky_color
    );
}

vec3 get_sun_direction() {
    return sun_direction;
}

vec3 get_sun_color(vec3 player_pos, vec3 direction) {
    return ph_indirect_sun_color;
}

vec3 get_sky_color(vec3 player_pos, vec3 direction) {
    return ph_indirect_sky_color;
}

bool sample_sun_color(vec3 player_pos, vec3 geo_normal, inout vec3 sun_color) {
    return true;
}
