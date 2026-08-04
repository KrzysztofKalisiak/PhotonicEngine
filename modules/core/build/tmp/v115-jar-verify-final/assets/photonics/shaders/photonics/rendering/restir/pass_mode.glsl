#ifndef PH_RESTIR_PASS_MODE_INCLUDE
#define PH_RESTIR_PASS_MODE_INCLUDE

#if defined PH_RESTIR_DIRECT_PASS
// A split direct pass must not compile the combined-GI branches that the
// shader-pack feature defines would otherwise enable.
#ifdef PH_ENABLE_RESTIR_GI
#undef PH_ENABLE_RESTIR_GI
#endif
#ifdef PH_RESTIR_COMBINED_GI
#undef PH_RESTIR_COMBINED_GI
#endif
#endif

#if defined PH_RESTIR_GI_PASS
#ifdef PH_ENABLE_BLOCKLIGHT
#undef PH_ENABLE_BLOCKLIGHT
#endif
#ifndef PH_ENABLE_RESTIR_GI
#define PH_ENABLE_RESTIR_GI
#endif

#ifdef PH_RESTIR_DENOISER_PASSES
#undef PH_RESTIR_DENOISER_PASSES
#endif
#define PH_RESTIR_DENOISER_PASSES PH_RESTIR_GI_DENOISER_PASSES
#define PH_ACTIVE_RENDER_SCALE PH_GI_RENDER_SCALE

// GI owns an independent receiver grid and independent temporal resources.
// Alias the shared estimator code to those textures before its includes are
// preprocessed.
#define ph_frag_data0 ph_gi_frag_data0
#define ph_frag_data1 ph_gi_frag_data1
#define prev_ph_frag_data0 prev_ph_gi_frag_data0
#define prev_ph_frag_data1 prev_ph_gi_frag_data1
#define ph_frag_motion ph_gi_frag_motion

#define restir_lighting restir_gi_lighting
#define restir_lighting_variance restir_gi_lighting_variance
#define prev_restir_lighting prev_restir_gi_lighting
#define prev_restir_lighting_variance prev_restir_gi_lighting_variance

#define restir_indirect_reservoirs0 restir_gi_indirect_reservoirs0
#define restir_indirect_reservoirs1 restir_gi_indirect_reservoirs1
#define prev_restir_indirect_reservoirs0 prev_restir_gi_indirect_reservoirs0
#define prev_restir_indirect_reservoirs1 prev_restir_gi_indirect_reservoirs1

#define restir_indirect_spatial_input0 restir_gi_indirect_spatial_input0
#define restir_indirect_spatial_input1 restir_gi_indirect_spatial_input1

#define denoise_result restir_gi_denoise_result
#define prev_denoise_result prev_restir_gi_denoise_result

// Required-uniform comments are parsed after Iris' macro preprocessor, so the
// GI names must be declared explicitly in addition to the aliases above.
//ph_required: uniform sampler2D ph_gi_frag_data0;
//ph_required: uniform sampler2D ph_gi_frag_data1;
//ph_required: uniform sampler2D prev_ph_gi_frag_data0;
//ph_required: uniform sampler2D prev_ph_gi_frag_data1;
//ph_required: uniform sampler2D ph_gi_frag_motion;

//ph_required: uniform sampler2D restir_gi_lighting;
//ph_required: uniform sampler2D restir_gi_lighting_variance;
//ph_required: uniform sampler2D prev_restir_gi_lighting;
//ph_required: uniform sampler2D prev_restir_gi_lighting_variance;

//ph_required: uniform sampler2D restir_gi_indirect_reservoirs0;
//ph_required: uniform usampler2D restir_gi_indirect_reservoirs1;
//ph_required: uniform sampler2D prev_restir_gi_indirect_reservoirs0;
//ph_required: uniform usampler2D prev_restir_gi_indirect_reservoirs1;

//ph_required: uniform sampler2D restir_gi_indirect_spatial_input0;
//ph_required: uniform usampler2D restir_gi_indirect_spatial_input1;

//ph_required: uniform sampler2D restir_gi_denoise_result;
//ph_required: uniform sampler2D prev_restir_gi_denoise_result;
#endif

#ifndef PH_ACTIVE_RENDER_SCALE
#define PH_ACTIVE_RENDER_SCALE PH_RENDER_SCALE
#endif

#endif
