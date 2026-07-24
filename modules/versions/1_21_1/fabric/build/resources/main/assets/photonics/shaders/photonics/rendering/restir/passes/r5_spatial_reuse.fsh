#version 430

#if defined PH_RESTIR_SPLIT_GI
#define PH_RESTIR_DIRECT_PASS
#endif

#include "/photonics/rendering/restir/passes/r5_spatial_reuse_impl.glsl"
