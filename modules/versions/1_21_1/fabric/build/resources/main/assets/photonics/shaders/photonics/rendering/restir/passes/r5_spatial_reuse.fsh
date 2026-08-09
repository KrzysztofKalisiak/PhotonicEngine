#version 430

#if defined PH_RESTIR_SPLIT_GI
#define PH_RESTIR_DIRECT_PASS
#endif

// The estimator diagnostic omits the direct spatial-input attachment so the
// r1 representative reaches r6 unchanged. GI is compiled in a separate pass
// and keeps its configured reuse count.
#if defined PH_RESTIR_DIRECT_ESTIMATOR_DIAGNOSTIC && defined PH_RESTIR_DIRECT_PASS
#undef PH_RESTIR_SPATIAL_REUSE_SAMPLES
#define PH_RESTIR_SPATIAL_REUSE_SAMPLES 0
#endif

#include "/photonics/rendering/restir/passes/r5_spatial_reuse_impl.glsl"
