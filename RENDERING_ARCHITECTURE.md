# PhotonicEngine Rendering Architecture

The optional Photonics-only temporal lighting reconstruction path is documented
separately in [TEMPORAL_UPSCALER.md](TEMPORAL_UPSCALER.md).

This guide explains how this repository turns Minecraft state into lighting on
the screen. It is written for an engineer who is comfortable with Python,
probability, and numerical methods, but is new to real-time rendering.

The most important fact is:

> PhotonicEngine is a deferred lighting extension, not a complete renderer.
> Iris and the shader pack determine which surface is visible at each screen
> pixel. Photonics then estimates lighting for that visible surface by tracing
> rays through a separate voxel approximation of the world.

The current branch also contains active Sable/Contraption Lights development.
Sections marked **Current-fork detail** describe that work rather than a stable
upstream contract.

## 1. The 30-second model

For every frame:

```text
Minecraft world state
    |
    +--> Iris/Sodium rasterization ------------------------------+
    |       finds the visible surface at every screen pixel      |
    |       and writes depth, position, normals, albedo, etc.    |
    |                                                            v
    +--> Photonics CPU scene compiler --> sparse voxel tree --> Photonics passes
    |                                          ^                 per visible pixel
    +--> Photonics light compiler --> light list |                    |
    |                                                            direct light
    +--> Sable bridge --> moving lights, transforms, occupancy       + GI
                                                                 + reuse
                                                                 + accumulation
                                                                 + denoising
                                                                       |
                                                                       v
                                                          Photonics lighting textures
                                                                       |
                                                                       v
                                                         shader-pack composition
                                                                       |
                                                                       v
                                                                 screen pixel
```

There are two parallel timelines:

1. **CPU scene maintenance** reacts to chunk and Sable changes, compiles a ray
   tracing representation, and uploads buffers to the GPU.
2. **GPU frame rendering** runs a sequence of full-screen shader passes. Each
   GPU invocation handles one screen pixel, reads the scene buffers, and writes
   intermediate textures.

The CPU work does not rebuild the complete world every frame. It incrementally
updates changed chunk sections. Some compilation is asynchronous, so a recent
block change can reach the ray tracing scene slightly after Minecraft has drawn
the block normally.

## 2. The representations of the scene

The word "scene" can be confusing here because at least three different scene
representations coexist.

| Representation | Producer | Source | Purpose | Important omissions |
|---|---|---|---|---|
| Iris G-buffer | Minecraft, Sodium, Iris, shader pack | Rendered triangles and textures | Find the surface visible at each screen pixel | It only describes what the camera sees, not arbitrary off-screen ray paths |
| Photonics static voxel tree | `SectionManager`, `ChunkCompiler`, `WorldCompiler` | Loaded Minecraft chunk block states and baked block-model quads | Shadow and GI ray queries through the ordinary world | It is an approximation; dynamic entities and moving Sable geometry are not part of this tree |
| Sable motion/occupancy sidecar | `ContraptionLightsSableBridge` | Contraption Lights and Sable state | Classify moving receivers, reproject them, and test coarse same-sublevel visibility | One occupancy cell is one block; it has no full material/albedo model |

### 2.1 Primary visibility comes from Iris

In a conventional path tracer, the renderer fires a primary ray from the camera
to discover what the pixel sees. Photonics does not do that. Minecraft has
already rasterized geometry through Iris. The shader-pack-specific interface
provides:

- whether the pixel belongs to the world;
- player-relative surface position;
- geometric normal;
- texture or normal-mapped normal;
- whether the pixel is the player's hand;
- camera and previous-camera matrices.

The first Photonics pass, [`f0_load_frag.fsh`](modules/shaders/photonics/rendering/frag/f0_load_frag.fsh),
normalizes that information into Photonics-owned textures. This lets every later
pass use one stable format even though shader packs store their G-buffer data
differently.

### 2.2 Secondary visibility comes from Photonics voxels

After a visible point is known, Photonics must answer questions such as:

- Is the segment from this surface to a froglight blocked?
- What surface does a randomly sampled GI ray hit?
- Does transparent glass attenuate or tint the path?

Those are secondary-ray queries. They use the sparse voxel tree in
[`ph_world_voxel_buffer`](modules/shaders/photonics/internal/tracing/common.glsl),
not Minecraft's triangle renderer.

This split explains an important class of bugs: a block can look correct on
screen but cast an incorrect Photonics shadow if its rasterized geometry and its
Photonics voxel proxy disagree.

## 3. Repository map

| Module | Responsibility |
|---|---|
| [`modules/api`](modules/api) | Version-neutral Minecraft, GPU, shader-pack, and configuration contracts |
| [`modules/core`](modules/core) | Scene compilation, light lists, pipeline definitions, and shader patching |
| [`modules/shaders`](modules/shaders) | GLSL code executed by the GPU |
| [`modules/versions/1_21_1/common`](modules/versions/1_21_1/common) | Minecraft 1.21.1, Iris, OpenGL, Sable, and Veil adapters |
| [`modules/versions/1_21_1/fabric`](modules/versions/1_21_1/fabric) | Fabric packaging and loader entry points |
| [`modules/patches`](modules/patches) | Compatibility patches for shader packs without a native Photonics interface |

The architectural split is intentional. Most rendering algorithms live in
`core` and `shaders`; version-specific names, mixins, and OpenGL/Iris details
live under `versions/1_21_1`.

## 4. Startup and pipeline creation

When Iris selects a shader pack, Photonics reads that pack's properties and
creates one extension in
[`PhotonicsExtension.create`](modules/core/src/main/java/at/redi2go/photonics/core/iris/PhotonicsExtension.java):

- `Disabled`: no resources or rendering;
- `OffPipeline`: resource-compatible but no Photonics lighting;
- `BasicPipeline`: present as a mode, but currently has no rendering passes and
  its sampler returns zero;
- `RestirPipeline`: the active direct/GI implementation described below.

Properties become GLSL compile-time definitions in
[`IrisDefines.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/IrisDefines.java).
For example, block lighting, combined GI, soft shadows, candidate counts, render
scale, accumulation length, and denoiser passes can compile different code and
can remove unused framebuffer attachments.

[`AbstractPhotonicsExtension.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/AbstractPhotonicsExtension.java)
constructs the persistent CPU/GPU components:

```text
SectionManager
    +--> ChunkCompiler --> WorldCompiler --> BufferWorldAllocator
    |                                      +--> BufferPaletteTexture
    |
    +--> BufferLightList

optional HandheldLightComponent
optional ExternalSubLevelMotion
```

The 1.21.1 Iris mixin creates ordinary Iris `CompositeRenderer` instances for
the Photonics passes. In other words, the passes are normal full-screen deferred
fragment passes scheduled by Iris, with Photonics-selected framebuffers and
buffers. See
[`IrisRenderingPipelineMixin.java`](modules/versions/1_21_1/common/src/main/mixins/at/redi2go/photonics/common/mixins/iris/pipeline/IrisRenderingPipelineMixin.java)
and
[`PhotonicsRenderer.java`](modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/iris/pipeline/renderer/PhotonicsRenderer.java).

## 5. CPU scene construction

### 5.1 Chunk-section tracking

[`SectionManager.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/SectionManager.java)
tracks non-empty 16 x 16 x 16 chunk sections around the camera. Minecraft events
mark sections as added, changed, or unloaded. The manager publishes immutable
section copies to independent bounded work queues.

There are separate consumers because geometry and lights have different update
costs and data structures.

### 5.2 From block model to voxels

[`ChunkCompiler.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/ChunkCompiler.java)
runs two background compiler threads. For every non-air block in a changed
section it asks
[`MinecraftBlockMesher.java`](modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/meshing/MinecraftBlockMesher.java)
for the block model's rendered `BakedQuad` geometry.

[`BlockBakeryImpl.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/bakery/impl/BlockBakeryImpl.java)
then rasterizes those triangles into cells at up to 1/16-block resolution. At
each covered cell it samples the Minecraft texture atlas and records material
data:

```text
block/shader-pack material ID
RGBA albedo
encoded normal
specular data
```

This is voxelization: converting continuous triangle surfaces into occupied
cells on a regular 3D grid. It is analogous to binning geometric observations
into a finite state grid.

Tagged thin cutout blocks, such as plants represented with alpha-tested quads,
have a specialized 4 x 4 coverage sampler per candidate voxel. Their alpha is
treated as unresolved geometric coverage. This avoids tinting light green
merely because it passed through a leaf or flower texture. Narrow opaque models
such as fences are still rasterized from their model quads, but do not
automatically use this tagged alpha-coverage path.

The tradeoff is unavoidable: a 1/16-cell proxy cannot preserve every detail of
the original triangles. Diagonal gaps, very thin geometry, and texture-alpha
boundaries are the most likely places for leaks or extra shadows.

### 5.3 Palette and sparse tree

Repeated voxel material data is deduplicated into a palette. A palette entry
contains six face records, each stored as four integers. The backing SSBO is
created by
[`BufferPaletteTexture.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/allocator/buffer/BufferPaletteTexture.java)
and exposed to GLSL as `ph_palette_texture`.

[`WorldCompiler.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/WorldCompiler.java)
consumes compiled sections in batches. [`TreeManager.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/TreeManager.java)
inserts and removes their block/voxel entries in a sparse hierarchical tree.
Only occupied branches are allocated.

The resulting heap is exposed as the SSBO `ph_world_voxel_buffer` by
[`BufferWorldAllocator.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/allocator/buffer/BufferWorldAllocator.java).
The shader's stack-based traversal is in
[`iterator.glsl`](modules/shaders/photonics/internal/tracing/iterator.glsl).
It skips empty hierarchical cells rather than stepping through every 1/16 cell
between the receiver and the light.

### 5.4 Why the world origin moves

Minecraft world positions are doubles and can become large. Most GPU shader
math uses 32-bit floats, whose spacing becomes too coarse far from zero.

[`WorldOrigin.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/WorldOrigin.java)
selects a section-aligned origin near the camera. Photonics stores the ray scene
relative to that origin and publishes:

- `world_offset`: the current absolute origin;
- `rt_camera_position`: camera position relative to that origin;
- `delta_world_offset`: how the origin changed since the previous frame;
- tree bounds in the same ray-tracing coordinate system.

The coordinate systems used in this project are therefore:

```text
absolute world position (double, CPU)
    - world origin
ray-tracing position, or RT position (float, GPU)

absolute world position
    - camera position
player-relative position (float, Iris/G-buffer)

Sable grid position
    <-> player-relative position through a rigid transform
```

Many motion bugs are coordinate-space bugs: a position is valid but interpreted
in the wrong one of these spaces, or current-camera data is composed with a
previous-frame transform.

## 6. CPU light construction

The light list is compiled independently of voxel geometry by
[`AbstractLightList.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/lights/AbstractLightList.java)
and uploaded by
[`BufferLightList.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/lights/BufferLightList.java).

For ordinary chunk blocks, the compiler:

1. reads the immutable section copies;
2. asks the shader-pack-aware light registry whether each block is emissive;
3. excludes lights fully enclosed by neighboring blocks;
4. combines those lights with optional external moving lights;
5. prioritizes moving/external lights and sorts the remaining lights by an
   approximate camera contribution;
6. truncates the result to `photonics.maxLights`;
7. uploads a mapping from previous light-list indices to current indices.

The mapping matters because a ReSTIR reservoir stores a light index. Sorting a
new frame's list without mapping would silently make history point to a
different emitter.

Each GPU light consumes four `vec4` records in
[`light_list.glsl`](modules/shaders/photonics/light_list.glsl):

```text
0: current position.xyz, block/material ID
1: RGB color, intensity
2: attenuation parameters, falloff, source radius
3: previous position.xyz, signed temporal-domain metadata
```

The temporal-domain token is zero for the ordinary world and nonzero for a
specific moving Sable sublevel. It lets the GPU distinguish "light moved with
my receiver" from "light moved relative to my receiver."

## 7. Sable and Veil bridge

**Current-fork detail.**

[`ContraptionLightsSableBridge.java`](modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/compat/ContraptionLightsSableBridge.java)
is an optional compatibility bridge. It currently accesses Contraption Lights
and Sable internals through reflection, so it is more version-fragile than the
core Photonics API.

At the head of `LevelRenderer.renderLevel`,
[`LevelRendererMixin.java`](modules/versions/1_21_1/common/src/main/mixins/at/redi2go/photonics/common/mixins/iris/extension/LevelRendererMixin.java)
captures two related datasets.

### 7.1 Moving emitters

For each Sable sublevel, the bridge reads Contraption Lights' local emissive
block coordinates, transforms their centers into current world coordinates,
retains previous positions, assigns a stable UUID-derived motion token, and
publishes them through
[`ExternalLightList.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/lights/ExternalLightList.java).

Those emitters then join the normal Photonics light list. The light registry,
not Veil's raw luminance value, remains authoritative for color, intensity, and
attenuation.

### 7.2 Receiver motion and local occupancy

The bridge also publishes, for at most 16 sublevels:

- current player-space to local-grid transform;
- current player-space to previous player-space transform;
- previous player-space to current local-grid transform;
- stable identity token;
- grid dimensions and occupancy-atlas offset;
- up to 64 emissive local cells.

These are registered by
[`ExternalSubLevelMotion.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/sublevel/ExternalSubLevelMotion.java).

The occupancy atlas is an `R8` 3D texture with one texel per Sable block cell.
It distinguishes:

- receiver cells: any non-air block or fluid;
- occluder cells: non-emissive, solid, full-collision blocks only.

Consequences of that deliberately coarse policy:

- a full block can occlude same-sublevel direct light;
- a fence, panel, trapdoor, or other non-full block normally does not;
- the atlas cannot supply albedo, normals, or specular material for bounced GI;
- it is useful for motion identity and cheap local visibility, but it is not a
  replacement for the static 1/16-block Photonics voxel tree.

### 7.3 Veil's role

Veil and Photonics remain separate lighting engines. The bridge uses
Contraption Lights/Veil data as an input source, but Veil does not steer the
ReSTIR reservoir or become the authority for Photonics visibility.

When Photonics ReSTIR block lighting is active, the Veil point-light brightness
for these bridged lights is suppressed by
[`ContraptionLightsSubLevelVeilLightingMixin.java`](modules/versions/1_21_1/common/src/main/mixins/at/redi2go/photonics/common/mixins/iris/compat/ContraptionLightsSubLevelVeilLightingMixin.java)
to avoid adding the same light energy twice. The block's emissive appearance and
bloom remain shader-pack/Veil concerns.

For the intended future cross-domain occlusion model, see
[`SABLE_OCCLUSION.md`](SABLE_OCCLUSION.md).

## 8. Exact frame timeline

The following is the useful mental order, even though background compiler
threads can run concurrently.

### 8.1 Frame head on the CPU

```python
def frame_head():
    external_lights = capture_sable_light_positions()
    sable_motion = capture_sable_transforms_and_occupancy()

    section_manager.publish_changed_sections()
    world_compiler.upload_completed_tree_and_palette_work()
    light_list.upload_latest_static_plus_external_lights()
    update_uniforms_and_handheld_lights()
```

The actual dispatch is `extension.onFrameBegin()`, which forwards to registered
rendering components in construction order.

### 8.2 Iris rasterizes the opaque world

Minecraft/Sodium submits triangles. Iris and the shader pack write depth,
positions, normals, albedo, and other pack-specific G-buffer data. This is where
the visible object for every screen pixel is chosen by the depth test.

### 8.3 Photonics runs before Iris' deferred composite stage

The 1.21.1 mixin hooks `beginTranslucents` immediately before Iris invokes its
deferred `CompositeRenderer`. `PhotonicsExtension.onRender()` executes the
registered Photonics pipelines in order.

First, the direct fragment-data pipeline flips its current/previous textures
and runs `f0_load_frag.fsh`. When split GI is active, a second fragment-data
pipeline runs `f0_load_frag_gi.fsh` on the lower-resolution GI grid.

Then [`RestirPipeline.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/extensions/RestirPipeline.java)
runs:

| Order | Pass | Main output | Purpose |
|---:|---|---|---|
| 0 | Flip history | current/previous attachments swap roles | Preserve last frame without copying it |
| 1 | `r1_initial_direct` | direct reservoir | Propose direct-light candidates, retain one weighted representative, and validate its visibility |
| 2 | `r3_initial_indirect` | two indirect reservoir textures | Trace one full-rate initial GI path |
| 3 | `r4_temporal_reuse` | direct and indirect reservoirs | Reproject compatible previous-frame reservoirs; classify each historical GI candidate before merging it |
| 4 | `r5_copy_spatial_input` | immutable reservoir copies | Prevent reads from observing writes from the same spatial pass |
| 5 | `r5_spatial_reuse` | direct and indirect reservoirs | Validate and merge at most one shifted GI neighbor, then clamp history |
| 6 | `r6_diffuse` | raw lighting, direct state, reservoirs | Evaluate selected direct/GI samples at the current receiver |
| 7 | `r7_accumulation` | lighting, external lighting, variance | Reproject and average radiance history |
| 8 | `r8_variance_prefilter` | denoise buffer | Estimate/filter variance and prepare SVGF input |
| 9 | repeated `r9_denoising` | ping-pong denoise buffers | Edge-aware a-trous spatial filtering |
| 10 | `r10_local_direct` | exact local lighting | Evaluate every same-token Sable light with coarse local DDA when hard shadows are selected |
| 11 | `r10_handheld` | handheld lighting | Evaluate main/off-hand sources |

All of these are screen-space passes. A pass over a 1920 x 1080 target launches
roughly 2.07 million fragment-shader invocations. Each invocation reads the data
for one visible pixel and writes one texel to one or more framebuffer
attachments.

The pipeline invokes initial direct visibility from `r1_initial_direct`.
Indirect history validation is candidate-scoped: `r4_temporal_reuse` validates
its one historical temporal candidate before merging it, and `r5_spatial_reuse`
validates at most one shifted neighbor before merging it. A blocked but
otherwise valid path contributes its represented effective sample count once
with zero weight. Stale or malformed history contributes neither weight nor
sample count. The fresh current-frame proposal, including a genuine
zero-radiance batch, keeps its existing estimator semantics. The standalone
`r2_validate_initial_direct` and
`r6_validate_indirect` shader sources remain for upstream comparison, but are
not scheduled.

V77 restores full-rate initial GI. The v75 one-pixel and v76 tiled history
interleaves produced no measurable stage-time reduction on the tested GPU, so
keeping their extra history fetches would only complicate the baseline. V77
instead exposes JVM overrides for render scale, initial direct candidates, and
denoiser iterations. Lower render scale physically reduces framebuffer size and
shader invocations, making it a valid performance experiment rather than a
divergent fragment-shader early return.

The v77 launch-time overrides are:

```text
-Dphotonics.renderScaleOverride=0.75
-Dphotonics.restirInitialSamplesOverride=16
-Dphotonics.restirDenoiserPassesOverride=5
```

Each property is independent and omitted properties continue to use the shader
pack setting. Valid ranges are `0.25..1.0`, `8..32`, and `0..12`,
respectively. Pipeline creation logs the effective values, and
`Photonics frame throughput v78` reports exact rendered frames and FPS for each
approximately five-second window.

V78 separates the shader pack's native render scale from Photonics' effective
render scale. Shader-pack G-buffer interfaces expect `gl_FragCoord` in their
native pixel grid, while ReSTIR may now run on a smaller independent grid. The
fragment-data pass remaps coordinates by the ratio of the exact truncated
shader-pack and Photonics framebuffer dimensions, and SVGF applies the same
conversion before reading shader-pack depth. Photonics history, reservoirs,
accumulation, and denoising remain at the smaller effective resolution.
Framebuffer textures clamp at their edges so filtering the reduced result
cannot wrap lighting from the opposite side of the screen.

V79 can split direct lighting and GI onto independent grids. The split is
enabled when combined ReSTIR GI is active and `giRenderScale` is lower than
`renderScale`. Direct reservoirs, direct history, direct accumulation, and
direct SVGF remain at `renderScale`; GI gets independent fragment data,
indirect reservoirs, temporal/spatial reuse, accumulation, and SVGF at
`giRenderScale`.

This is a real reduction in shader invocations. It does not skip branches
inside a full-size pass, and it does not upscale a low-resolution reservoir
into a different receiver. Each GI reservoir belongs to one low-resolution
receiver throughout its lifetime. Only the finished GI radiance is composed
onto the direct grid, using position, normal, hand state, and Sable identity to
avoid interpolation across unrelated surfaces.

The additional v79 launch-time overrides are:

```text
-Dphotonics.giRenderScaleOverride=0.50
-Dphotonics.restirGiDenoiserPassesOverride=3
```

The GI scale accepts `0.25..1.0` and is capped at the effective direct render
scale. GI denoiser passes accept `0..12` and are capped at the effective direct
denoiser count. Their shader-property equivalents are
`photonics.giRenderScale` and `photonics.restirGiDenoiserPasses`.

### 8.4 The shader pack composes the result

Photonics exposes functions such as `sample_photonics_direct(tex_coord)` and
`sample_photonics_handheld(tex_coord)` from
[`samplers.glsl`](modules/shaders/photonics/samplers.glsl).

A shader pack with native support calls those functions itself. For a supported
non-native pack, [`ShaderPatcher.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/patching/ShaderPatcher.java)
injects the interface and pack-specific composition code. For example, the BSL
patch adds Photonics direct, handheld, and optional indirect terms and then
multiplies them by the surface albedo in its deferred pass.

The exact final equation is therefore partly shader-pack-owned. Photonics
produces lighting textures; the pack decides where they enter its material,
emissive, bloom, exposure, tonemapping, and post-processing pipeline.

## 9. GPU data contract

The most useful textures and buffers are:

| Name | Produced by | Meaning |
|---|---|---|
| `ph_frag_data0` | fragment-data pass | visible player-relative position plus RT-position correction data |
| `ph_frag_data1` | fragment-data pass | packed RT direction, geometric normal, texture normal, world/hand flags, Sable slot/token |
| `ph_frag_motion` | fragment-data pass | previous player-relative position and normal for a classified Sable receiver |
| `ph_gi_frag_data0..1`, `ph_gi_frag_motion` | split-GI fragment-data pass | the same receiver contract sampled on the independent GI grid |
| `ph_world_voxel_buffer` | world compiler | sparse tree nodes, leaves, block metadata, and embedded static-light records |
| `ph_palette_texture` | palette compiler | per-face voxel material records |
| `ph_light_list` | light compiler | current/previous emitter state, material ID, and temporal domain |
| `ph_light_mapping` | light compiler | previous light index to current light index |
| `ph_sable_occupancy` | Sable bridge | block-resolution local receiver/occluder flags |
| `restir_direct_reservoirs0` | ReSTIR passes | chosen light index, normalized reservoir weight, effective sample count |
| `restir_direct_state` | diffuse pass | final visibility/confidence or local-history signature |
| `restir_indirect_reservoirs0..1` | GI passes | path color, first-hit point/normal or finite-path surface signature, sky identity, weight/count |
| `restir_lighting` | diffuse then accumulation | stable direct/GI lighting history |
| `restir_external_lighting` | diffuse then accumulation | lighting whose emitter motion differs from the receiver domain |
| `restir_lighting_variance` | accumulation | temporal moments and variance for denoising |
| `denoise_result` | SVGF passes | final filtered stochastic lighting |
| `restir_gi_lighting`, `restir_gi_lighting_variance` | split-GI diffuse/accumulation | independent low-resolution GI radiance history and moments |
| `restir_gi_indirect_reservoirs0..1` | split-GI ReSTIR passes | indirect reservoirs owned by GI-grid receivers |
| `restir_gi_denoise_result` | split-GI SVGF passes | filtered GI radiance sampled during final composition |
| `restir_local_lighting` | exact local pass | same-sublevel Sable hard-shadow lighting |
| `other_handheld` | handheld pass | handheld-light contribution |

Attachments with `FLIP` have two physical textures. At frame start,
[`FlippableFramebuffer.java`](modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/iris/pipeline/framebuffer/FlippableFramebuffer.java)
swaps them:

```text
last frame's write texture -> this frame's prev_* sampler
last frame's read texture  -> this frame's write target
```

This is ping-pong buffering. It avoids an expensive full-screen copy and avoids
undefined behavior from reading and writing the same texture simultaneously.

## 10. Direct lighting and ReSTIR

Suppose pixel `x` can receive light from `N` emitters. A brute-force direct
lighting pass would evaluate every emitter and trace `N` shadow rays:

```text
L_direct(x) = sum_j visibility(x, j) * contribution(x, j)
```

With hundreds or thousands of lights, doing that for every pixel is too
expensive. ReSTIR evaluates a much smaller candidate set and stores one
representative plus enough aggregate statistics to estimate the sum.

### 10.1 Candidate target and proposal

For candidate light `j`, the current code first evaluates an unshadowed RGB
contribution. At a high level it contains:

```text
emitter color * intensity
* distance attenuation and falloff
* receiver normal/material response
```

Shader-pack modifier hooks can change the exact attenuation/material formula.
The scalar resampling target is approximately:

```text
p_hat(j, x) = luminance(unshadowed_contribution(j, x))
```

If the proposal selects `j` with probability `q(j)`, its resampling weight is:

```text
w_j = p_hat(j, x) / q(j)
```

The current proposal is stratified. A priority prefix guarantees candidates
for moving/external lights, while a randomized systematic sweep covers the
remaining camera-contribution-sorted suffix. This reduces duplicate proposals
and coherent pulses when history is unavailable.

### 10.2 Reservoir update

A direct reservoir stores only:

```text
selected light y
sum/normalized weight W
effective candidate count M
```

As candidates arrive, candidate `j` replaces `y` with probability:

```text
w_j / sum_so_far(w)
```

After all candidates, the normalization used by this implementation is the
equivalent of:

```text
W = sum(w_j) / (M * p_hat(y, x))
estimate = evaluated_RGB(y, x) * W
```

The code is in
[`direct/sample.glsl`](modules/shaders/photonics/rendering/restir/direct/sample.glsl)
and
[`direct/reservoir.glsl`](modules/shaders/photonics/rendering/restir/direct/reservoir.glsl).

For a quant analogy, this is sequential importance resampling compressed into
one retained particle. The reservoir is not a cache of all lights. `M` and the
weight summarize the population represented by that one particle.

Practical clamps, visibility rejection, domain partitioning, and finite history
make this a real-time estimator, not a claim of exact textbook unbiasedness.

### 10.3 Visibility and shadows

The initial target is unshadowed. Once a representative is selected, Photonics
traces a finite segment between light and receiver using
[`trace_light_vis`](modules/shaders/photonics/internal/tracing/simple.glsl).

The path can:

- hit an opaque voxel and return blocked;
- pass through transparent voxels while reducing transmittance;
- acquire a tint from transmissive material such as stained glass;
- treat thin cutout alpha as neutral fractional coverage;
- reach the receiver and return visible.

Only the representative usually gets this expensive test. That is why shadow
noise can exist even when unshadowed brightness looks stable.

### 10.4 Hard and soft shadows

With hard shadows, the visibility ray targets the emitter center. A geometric
edge therefore produces a sharp binary transition before denoising.

With `PH_RESTIR_SOFT_SHADOWS`, the final visibility endpoint is randomly jittered
over a disk of radius 1/16 block around the emitter. Across pixels and frames,
some samples see around an occluder and some do not. Temporal accumulation and
SVGF turn that binary Monte Carlo noise into a penumbra.

```text
larger apparent source / more endpoint spread -> wider soft shadow
more history and filtering                  -> less noise, slower reaction
less history                                -> faster reaction, more noise
```

In the current Sable path, hard-shadow same-sublevel lighting is handled by the
exact local pass. Soft same-sublevel samples instead remain in the accumulated
and denoised path.

## 11. Temporal reuse, spatial reuse, and accumulation

These are three different operations. Treating them as one concept makes the
renderer much harder to debug.

### 11.1 Temporal reservoir reuse

[`r4_temporal_reuse.fsh`](modules/shaders/photonics/rendering/restir/passes/r4_temporal_reuse.fsh)
asks: "Which pixel in the previous frame represented this same physical
surface?"

For an ordinary world surface, projection matrices and camera motion provide
the answer. For a Sable receiver,
[`frag_motion.glsl`](modules/shaders/photonics/rendering/frag/frag_motion.glsl)
uses the sublevel's rigid current-to-previous transform.

History is accepted only when checks such as these pass:

- projected coordinate is on screen;
- both pixels represent world geometry;
- Sable identity tokens match;
- positions are sufficiently close;
- geometric normals align strongly;
- the previous light index maps into the current list;
- the selected light remains valid and visible at the current receiver.

The previous and fresh reservoirs are then merged using their effective sample
counts and current target values. This improves candidate selection before any
radiance averaging happens.

For GI, the historical temporal candidate is traced from the current receiver
before it enters the reservoir merge. Finite paths also compare a compact
signature of every transparent pass-through and the first-hit voxel
face/material. The result has three states:

| Validation state | Weight/energy | Effective sample count `M` |
|---|---:|---:|
| valid path | merged normally | added once |
| opaque blocker before stored endpoint | zero | added once |
| stale signature, endpoint, world, or traversal | zero | not added |

This distinction matters when the fresh proposal also has zero radiance:
blocked history plus the fresh batch remains a finite zero-weight reservoir
with both represented counts, whereas stale history leaves only the fresh
batch. If blocked history is the only batch, finalization preserves its finite
`M` and emits zero radiance.

### 11.2 Spatial reservoir reuse

[`r5_spatial_reuse.fsh`](modules/shaders/photonics/rendering/restir/passes/r5_spatial_reuse.fsh)
samples random nearby screen pixels. A neighbor is reusable only when it appears
to describe the same kind of surface:

- same world/Sable receiver domain and identity;
- aligned geometric normal;
- small 3D receiver distance;
- small distance from the same surface plane;
- not hand or unstable grazing-angle data.

For direct light, the neighbor's selected light is re-evaluated for the current
receiver before merging. For GI, cheap receiver and Jacobian checks run first,
then the shifted path is visibility- and surface-signature-validated before it
can contribute. Valid, blocked, and stale spatial candidates follow the same
weight/`M` table as temporal history. The 1.21.1 property layer and shader both
cap this to one spatial validation ray per receiver per frame. Spatial reuse is
valuable because nearby pixels often found different good samples. It can also
create flicker or leaks if receiver matching is too permissive. The immutable
copy pass prevents pass-order-dependent feedback.

### 11.3 Radiance accumulation

After the reservoir has produced a current RGB estimate, `r7_accumulation`
performs a capped moving average:

```text
H_t = (n * H_reprojected + L_t) / (n + 1)
```

where `n` is bounded by an adaptive history limit.

The current fork splits direct lighting into two streams:

- **stable stream**: emitter and receiver share a stable motion domain;
- **external stream**: the emitter moves relative to the receiver domain.

External history is shortened according to relative emitter/receiver motion.
This is the core trail/noise tradeoff:

```text
long history + moving light -> smooth but leaves a trail
short history + moving light -> correct position but more grain/flicker
```

For a light and receiver on the same rigid Sable sublevel, relative motion is
approximately zero, so long history can remain valid even while the entire
contraption moves through world space.

## 12. Motion vectors in this fork

A motion vector does not predict light. It maps a current visible receiver back
to where that same receiver appeared in the previous frame.

For an ordinary block:

```text
current player-space position
    -> previous camera/view/projection matrices
    -> previous screen coordinate
```

For a Sable block:

```text
current player-space position
    -> current-to-previous sublevel rigid transform
    -> previous player-space position
    -> previous projection
    -> previous screen coordinate
```

The Sable token is carried with the fragment and checked in the previous frame.
This prevents a pixel vacated by sublevel A from borrowing history from world
geometry or sublevel B.

Motion vectors solve receiver correspondence. They do not by themselves solve:

- a moving light illuminating a stationary world receiver;
- one moving sublevel illuminating another independently moving sublevel;
- a GI ray whose bounce hit point was stored in world coordinates on moving
  geometry.

Those cases also need emitter or hit-point motion-domain information.

## 13. Global illumination

Direct lighting traces a known segment from receiver to emitter. Global
illumination asks where incoming radiance may have come from after one or more
bounces.

[`indirect_lighting.glsl`](modules/shaders/photonics/rendering/indirect_lighting.glsl)
starts from the visible receiver and samples a cosine-weighted hemisphere
direction, with special handling for sun/sky sampling. It traverses the voxel
tree and, at each hit:

1. loads voxel albedo, transparency, normal, skylight, and optional emissive
   data;
2. adds incoming sun, sky, or eligible block-light radiance;
3. multiplies path throughput by surface albedo;
4. samples the next bounce direction;
5. stops at the configured bounce or traversal limit.

Transparent glass intersections use stochastic alpha coverage: a path either
accepts the surface or passes through it, with probability determined by
opacity. A passed glass surface still tints the path. Tagged thin cutouts use
v86's deterministic neutral coverage transmittance instead, because persistent
binary plant events produced bright one-pixel GI outliers. First-segment
validation records both kinds of pass-through in the path signature and uses
the same block-skip traversal as initial sampling.

The indirect reservoir stores more state than the direct one because it must be
able to shift and validate a path: first hit point, sky-hit identity, RGB
result, weight, and sample count. Sky samples use the metadata field for a full
transparent-path signature; their direction is recovered from the stored
finite sky endpoint and source receiver. Finite samples use that field for a
voxel-face normal and a compact hash of transparent pass-through surfaces plus
first-hit material, transparency, and skylight data. The receiver position and
normals come from the matching fragment-data texture; RNG state is not
persisted.

The signature protects the receiver-to-first-hit segment. Later bounce
segments and time-varying sky/light radiance are not serialized, so reused
multi-bounce samples remain an approximation rather than a fully reconnected
path. Finite endpoint validation uses a one-tracing-voxel (1/16 block) linear
tolerance: enough for DDA boundary roundoff while substantially smaller than a
distinct block surface.

The rendering equation behind this is conceptually:

```text
L_o(x, wo) = L_e(x, wo)
            + integral_over_hemisphere(
                  BRDF(x, wi, wo) * L_i(x, wi) * cos(theta) dwi
              )
```

Monte Carlo sampling replaces the integral with random weighted paths. ReSTIR,
history, and denoising are variance-reduction mechanisms around that estimate.

In this branch, the `r3` combined-GI path runs only when GI and
`restirCombinedGi` are both enabled. Otherwise, a native shader pack can retain
its own indirect-lighting path instead of using the combined ReSTIR reservoir.

### Current Sable GI limitation

The Sable sidecar provides occupancy but not complete material, albedo, normal,
or outgoing-radiance data. Indirect reservoirs also store hit points in the
Photonics RT/world space rather than a persistent Sable-local hit identity.

Therefore same-sublevel direct lighting can be made stable with local
transforms, but full bounced GI involving moving Sable surfaces needs a richer
moving-geometry/material representation and local-space hit reprojection.
Veil visibility alone cannot reconstruct that integral.

## 14. SVGF denoising

Even a correct Monte Carlo estimator is noisy with few samples. The current
pipeline uses an SVGF-style process:

1. temporal accumulation estimates first and second luminance moments;
2. variance is derived from those moments;
3. `r8_variance_prefilter` stabilizes high-variance, low-history pixels;
4. repeated `r9_denoising` passes apply an edge-aware a-trous filter.

"A-trous" means the filter kernel develops holes as its step size grows. It can
cover a large screen radius in a few fixed-size passes. The weights use fragment
position, surface plane, normal, color, and variance so the filter does not
freely blur light across object boundaries.

Typical failure modes:

- filters too weak: grain or a moving "smoke" texture remains;
- filters too strong: shadows lose shape and light crosses edges;
- invalid reprojection: history disappears and noise repeatedly rebuilds;
- overly permissive reprojection: old shadows ghost or trail;
- unstable surface identity: the whole receiver pulses despite a static local
  lighting configuration.

## 15. Historical v57 diagnostic output

**Historical fork detail.** The v57 diagnostic version of
[`rendering/restir/samplers.glsl`](modules/shaders/photonics/rendering/restir/samplers.glsl)
defined `PH_RESTIR_STREAM_SPLIT_DIAGNOSTIC` and intentionally returned
different data in four vertical screen quarters:

| Horizontal range | Returned direct-light value |
|---|---|
| 0% to 25% | zero Photonics direct light |
| 25% to 50% | current-frame ReSTIR estimate before accumulation |
| 50% to 75% | accumulated stable plus external estimate before SVGF output selection |
| 75% to 100% | current exact same-sublevel Sable-local lighting attachment (active in hard-shadow mode) |

Those quarters were diagnostics, not the intended final composition. That mode
is no longer active; current `sample_photonics_direct` composes the direct,
external, exact-local, and optional split-GI results normally. Historical
visual comparisons must take
the current quarter into account.

## 16. A Python-like model of one pixel

This pseudocode omits GPU packing and compatibility checks, but preserves the
algorithmic order:

```python
def shade_pixel(pixel, frame):
    # Produced from the shader pack's G-buffer, not by ray tracing.
    receiver = load_visible_surface(pixel)
    if not receiver.is_world:
        return 0.0

    current = Reservoir.empty()
    for light, q in propose_lights(receiver, n=INITIAL_SAMPLES):
        if light.motion_token == receiver.motion_token != 0:
            continue  # evaluated exactly in the local path

        unshadowed = evaluate_attenuation_and_material(receiver, light)
        target = luminance(unshadowed)
        current.update(light, weight=target / q, represented_samples=1)

    current.validate_selected_visibility(receiver, static_voxel_tree)

    previous_pixel = reproject(receiver)
    if compatible(receiver, previous_fragment(previous_pixel)):
        previous = load_previous_reservoir(previous_pixel)
        previous.remap_light_index(current_light_mapping)
        previous.reevaluate_and_validate(receiver)
        current.merge(previous)

    for neighbor in compatible_random_neighbors(receiver):
        candidate = load_immutable_current_reservoir(neighbor)
        candidate.reevaluate_and_validate(receiver)
        current.merge(candidate)

    stochastic_light = current.evaluate_selected(receiver)
    indirect_light = evaluate_selected_gi_reservoir(receiver)

    stable, external = partition_by_relative_motion(
        receiver, current.selected_light, stochastic_light + indirect_light
    )
    accumulated = accumulate_reprojected(stable, external)
    filtered = svgf(accumulated)

    # This separate exact pass is the current hard-shadow Sable-local path.
    local = sum(
        evaluate_with_sable_local_dda(receiver, light)
        for light in same_sublevel_lights(receiver)
    )
    handheld = evaluate_handheld(receiver)

    return shader_pack_compose(filtered, local, handheld)
```

On the GPU this runs for many pixels in parallel. There is no Python loop over
screen pixels; the graphics driver schedules fragment invocations across GPU
cores.

## 17. Debugging by symptom

Use the earliest stage capable of producing the symptom.

| Symptom | First places to inspect |
|---|---|
| Block looks correct but casts no Photonics shadow | `BlockBakeryImpl`, compiled section diagnostics, tree upload, `trace_light_vis` |
| Fence/flower has diagonal, missing, or oversized shadow | thin-cutout voxel coverage, palette alpha, ray boundary traversal |
| Emitter glows but adds no direct light | light registry, `AbstractLightList`, `ph_light_list`, shader-pack sampler composition |
| Every light is absent | properties/defines, shader compilation, light-list size, `sample_photonics_direct` integration |
| Light has correct position but wrong color | light registry, shader-pack block ID, light modifier, transparent tint path |
| Colored glass works intermittently | representative selection, visibility result, palette alpha/material classification, history reset |
| Static scene is grainy | candidate count, reservoir target/proposal, accumulation validity, denoiser inputs |
| Old shadow trails a moving emitter | previous light position, temporal-domain token, external-stream history limit |
| Correct moving shadow flickers | too little valid history, receiver reprojection, moving-light candidate coverage, denoiser variance |
| Whole moving contraption changes brightness together | Sable receiver classification, current/previous transforms, identity token, stable/external partition |
| Sable fence does not block same-sublevel light | expected with current block-resolution full-block-only occupancy |
| One Sable sublevel borrows another's history | token assignment/checks, slot mapping, previous fragment metadata |
| Sable light and receiver move together but lose accumulation | receiver-relative motion calculation or emitter temporal-domain metadata |
| Sable geometry edit causes a brief reset | occupancy-atlas revision/rebuild and the resulting receiver/history validity change |
| Black, magenta, green, or non-finite regions | framebuffer format/binding, shader compilation, NaN/Inf reservoir diagnostics, explicit diagnostic shader output |
| Horizontal line after resize | framebuffer resize, previous/current target sizes, history invalidation, shader-pack or Distant Horizons composition |
| Shader pack fails to load | `ShaderPatcher`, generated `.ph-patched-shaders`, interface functions, sampler declarations, GLSL compile log |
| Sable source is much too bright | duplicate Veil point light plus Photonics contribution, emissive/bloom composition |

Useful ownership boundaries:

```text
wrong visible surface or material before Photonics -> Minecraft/Iris/shader pack
wrong off-screen blocking geometry                -> Photonics voxel compiler/tree
wrong candidate emitter                           -> light registry/list/ReSTIR proposal
wrong moving correspondence                       -> Sable transforms/tokens/motion
correct raw estimate, unstable over time           -> reuse/accumulation/SVGF
correct Photonics texture, wrong final appearance  -> shader-pack composition/exposure/bloom
```

## 18. Suggested code-reading order

Read in this order rather than alphabetically:

1. [`PhotonicsProperties.java`](modules/api/src/main/java/at/redi2go/photonics/api/shaders/PhotonicsProperties.java)
   to learn the public switches.
2. [`PhotonicsExtension.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/PhotonicsExtension.java)
   and [`AbstractPhotonicsExtension.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/AbstractPhotonicsExtension.java)
   to see component construction.
3. [`SectionManager.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/SectionManager.java),
   [`ChunkCompiler.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/ChunkCompiler.java),
   and [`BlockBakeryImpl.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/bakery/impl/BlockBakeryImpl.java)
   for world ingestion and voxelization.
4. [`WorldCompiler.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/WorldCompiler.java),
   [`TreeManager.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/TreeManager.java),
   and [`iterator.glsl`](modules/shaders/photonics/internal/tracing/iterator.glsl)
   for the CPU-to-GPU ray scene.
5. [`AbstractLightList.java`](modules/core/src/main/java/at/redi2go/photonics/core/rendering/lights/AbstractLightList.java)
   and [`light_list.glsl`](modules/shaders/photonics/light_list.glsl) for emitters.
6. [`IrisRenderingPipelineMixin.java`](modules/versions/1_21_1/common/src/main/mixins/at/redi2go/photonics/common/mixins/iris/pipeline/IrisRenderingPipelineMixin.java)
   and [`RestirPipeline.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/extensions/RestirPipeline.java)
   for exact frame scheduling.
7. [`f0_load_frag.fsh`](modules/shaders/photonics/rendering/frag/f0_load_frag.fsh)
   and [`frag_data.glsl`](modules/shaders/photonics/rendering/frag/frag_data.glsl)
   for per-pixel input.
8. [`r1_initial_direct.fsh`](modules/shaders/photonics/rendering/restir/passes/r1_initial_direct.fsh),
   [`direct/sample.glsl`](modules/shaders/photonics/rendering/restir/direct/sample.glsl),
   and [`direct/reservoir.glsl`](modules/shaders/photonics/rendering/restir/direct/reservoir.glsl)
   for ReSTIR direct lighting.
9. [`r4_temporal_reuse.fsh`](modules/shaders/photonics/rendering/restir/passes/r4_temporal_reuse.fsh),
   [`r5_spatial_reuse.fsh`](modules/shaders/photonics/rendering/restir/passes/r5_spatial_reuse.fsh),
   and [`restir.glsl`](modules/shaders/photonics/rendering/restir/restir.glsl)
   for reuse and accumulation.
10. [`indirect_lighting.glsl`](modules/shaders/photonics/rendering/indirect_lighting.glsl)
    for GI.
11. [`ContraptionLightsSableBridge.java`](modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/compat/ContraptionLightsSableBridge.java),
    [`sable_motion.glsl`](modules/shaders/photonics/rendering/frag/sable_motion.glsl),
    and [`SABLE_OCCLUSION.md`](SABLE_OCCLUSION.md) for moving sublevels.
12. [`ShaderPatcher.java`](modules/core/src/main/java/at/redi2go/photonics/core/iris/patching/ShaderPatcher.java)
    and the active shader pack's `shader_interface.glsl` to understand final
    integration.

[`DOCUMENTATION.md`](DOCUMENTATION.md) is the companion API reference. This file
explains flow and ownership; `DOCUMENTATION.md` explains the shader-facing types
and functions.

## 19. Glossary

**Albedo**

The fraction and color of incoming diffuse light reflected by a surface. It is
the material color before lighting, exposure, and tonemapping.

**Attachment**

A texture attached to a framebuffer as one output target. One shader pass can
write several attachments at different `layout(location=...)` indices.

**BRDF**

Bidirectional reflectance distribution function. It describes how incoming
light from one direction is reflected toward the viewer.

**DDA**

Digital differential analyzer. A grid traversal algorithm that visits voxel
cells crossed by a ray or segment.

**Deferred rendering**

First rasterize visible surfaces into G-buffer textures, then compute lighting
in full-screen passes. Photonics is inserted into this later lighting phase.

**Fragment**

A candidate rasterized pixel produced from a triangle. A fragment shader runs
for it; after depth/stencil tests its outputs become framebuffer texels.

**Framebuffer / FBO**

A set of textures selected as render outputs. It does not itself hold pixels;
its attachments do.

**G-buffer**

Screen-sized textures containing geometry/material attributes for visible
surfaces, commonly depth, position, normal, and albedo.

**GI**

Global illumination: light arriving after bouncing from other surfaces, plus
environment lighting paths, rather than only direct emitter-to-receiver light.

**GLSL**

The OpenGL Shading Language used by files under `modules/shaders`.

**Motion vector / reprojection**

A mapping from a current surface sample to the previous screen location of the
same physical surface.

**Normal**

A surface direction. The geometric normal describes actual coarse geometry;
the texture normal can add visual detail through a normal map. Visibility bias
usually uses the geometric normal; material response can use the texture normal.

**Primary ray / secondary ray**

A primary ray determines what the camera sees. Photonics delegates that job to
Iris rasterization. Secondary rays query shadows, reflections, or GI from the
already-visible point.

**Proposal distribution**

The probability distribution `q` used to select Monte Carlo candidates. Good
proposals spend more samples on candidates likely to matter while retaining the
proper probability correction.

**Reservoir**

A compact weighted sample containing one representative and aggregate weight
and sample-count statistics for all candidates it represents.

**SSBO**

Shader storage buffer object. A GPU buffer that shaders can index as structured
arrays, used here for the world tree, palette, lights, and index mapping.

**Temporal accumulation**

Averaging compatible estimates across frames after reprojection.

**Temporal reuse**

Reusing and reweighting the previous frame's reservoir candidates. This occurs
before radiance accumulation and is not the same operation.

**Voxel**

A cell in a 3D grid. Photonics voxelizes static block-model surfaces at up to
1/16-block resolution; the current Sable occupancy sidecar is one cell per
block.

**SVGF**

Spatiotemporal Variance-Guided Filtering, a family of denoisers that combines
history, luminance moments, variance, and edge-aware spatial filtering.
