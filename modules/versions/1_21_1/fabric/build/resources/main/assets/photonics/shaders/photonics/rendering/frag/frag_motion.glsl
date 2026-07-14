#ifndef PH_FRAG_MOTION_INCLUDE
#define PH_FRAG_MOTION_INCLUDE

//ph_required: uniform sampler2D ph_frag_motion;

struct FragMotion {
    vec3 previous_player_pos;
    vec3 previous_geo_normal;
};

void frag_motion_load(out FragMotion motion, ivec2 texel) {
    vec4 encoded = texelFetch(ph_frag_motion, texel, 0);
    motion.previous_player_pos = encoded.xyz;
    motion.previous_geo_normal = ph_decode_normal(unpackSnorm2x16(floatBitsToUint(encoded.w)));
}

vec3 ph_reproject_frag_data(
    FragData current_frag,
    ivec2 current_texel,
    bool hand,
    vec2 jitter,
    out vec3 previous_player_pos,
    out vec3 expected_previous_normal,
    out uint sublevel_token
) {
    sublevel_token = frag_data_sublevel_token(current_frag);
    if (sublevel_token != 0u) {
        FragMotion motion;
        frag_motion_load(motion, current_texel);
        previous_player_pos = motion.previous_player_pos;
        expected_previous_normal = motion.previous_geo_normal;
        return ph_project_previous_player_pos(previous_player_pos, jitter);
    }

    expected_previous_normal = frag_data_geo_normal(current_frag);
    return ph_reproject_player_pos(
        frag_data_player_pos(current_frag),
        hand,
        jitter,
        previous_player_pos
    );
}

#endif
