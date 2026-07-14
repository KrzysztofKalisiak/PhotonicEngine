//ph_required: uniform sampler2D ph_frag_data0;
//ph_required: uniform sampler2D ph_frag_data1;

//ph_required: uniform sampler2D prev_ph_frag_data0;
//ph_required: uniform sampler2D prev_ph_frag_data1;

#include "/photonics/utility/normal_encoding.glsl"

struct FragData {
    vec4 data0;
    uvec4 data1;
};

const uint frag_is_in_world_bit = 1u << 0;
const uint frag_bad_angle_bit = 1u << 1;
const uint frag_is_hand_bit = 1u << 2;
const uint frag_sublevel_slot_shift = 3u;
const uint frag_sublevel_slot_mask = 0x1fu << frag_sublevel_slot_shift;
const uint frag_sublevel_token_shift = 8u;
const uint frag_sublevel_token_mask = 0xffffu << frag_sublevel_token_shift;

void frag_data_load(out FragData frag, ivec2 texel) {
    frag.data0 = texelFetch(ph_frag_data0, texel, 0);
    frag.data1 = floatBitsToUint(texelFetch(ph_frag_data1, texel, 0));
}

void frag_data_load_previous(out FragData frag, ivec2 texel) {
    frag.data0 = texelFetch(prev_ph_frag_data0, texel, 0);
    frag.data1 = floatBitsToUint(texelFetch(prev_ph_frag_data1, texel, 0));
}

vec3 frag_data_player_pos(FragData frag) {
    return frag.data0.xyz;
}

vec3 frag_data_rt_pos(FragData frag) {
    vec3 dir = ph_decode_normal(unpackSnorm2x16(frag.data1.x));
    float mag = frag.data0.w;

    return (frag.data0.xyz + rt_camera_position) + (dir * mag);
}

vec3 frag_data_geo_normal(FragData frag) {
    return ph_decode_normal(unpackSnorm2x16(frag.data1.y));
}

vec3 frag_data_tex_normal(FragData frag) {
    return ph_decode_normal(unpackSnorm2x16(frag.data1.z));
}

bool frag_data_is_in_world(FragData frag) {
    return (frag.data1.w & frag_is_in_world_bit) != 0;
}

bool frag_data_is_bad_angle(FragData frag) {
    return (frag.data1.w & frag_bad_angle_bit) != 0;
}

bool frag_data_is_hand(FragData frag) {
    return (frag.data1.w & frag_is_hand_bit) != 0;
}

int frag_data_sublevel_slot(FragData frag) {
    uint encoded = (frag.data1.w & frag_sublevel_slot_mask) >> frag_sublevel_slot_shift;
    return int(encoded) - 1;
}

uint frag_data_sublevel_token(FragData frag) {
    return (frag.data1.w & frag_sublevel_token_mask) >> frag_sublevel_token_shift;
}

void frag_data_encode_sublevel(inout uvec4 data1, int slot, uint token) {
    data1.w |= (uint(slot + 1) << frag_sublevel_slot_shift) & frag_sublevel_slot_mask;
    data1.w |= (token << frag_sublevel_token_shift) & frag_sublevel_token_mask;
}
