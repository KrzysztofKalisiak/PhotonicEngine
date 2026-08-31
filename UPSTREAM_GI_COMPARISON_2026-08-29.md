# Upstream Photonics GI Comparison

## Scope and references

This comparison is source-only. No upstream code was merged or cherry-picked.

| Tree | Commit | Date | Meaning |
|---|---|---|---|
| Fork | `9e6bea66` | 2026-08-29 | Current `multi-version`, v145f |
| Live upstream | `54e049be` | 2026-08-16 | `Redi2Go/PhotonicEngine` `multi-version` |
| Saved upstream audit | `3c5b91fd` | 2026-08-13 | Snapshot used by the previous audit |
| Older upstream snapshot | `3a9d6a62` | 2026-07-26 | Earlier local `upstream/multi-version` ref |

The live ref was fetched from `https://github.com/Redi2Go/PhotonicEngine.git` into
`audit-upstream-live/multi-version`. The fork and upstream histories have no
usable merge base, so the comparison is by tree and behavior, not by merge
ancestry.

## Executive result

Upstream does contain real GI quality changes. They can plausibly reduce the
camera-dependent dark formations in a simple static scene, especially:

1. corrected random-state progression and a different cosine-direction method;
2. texture-normal use for the first GI ray and sky endpoint;
3. a normal-aware Jacobian used during GI reuse;
4. stricter reuse Jacobian limits; and
5. optional visibility history in the SVGF filter.

These are not a drop-in fix for this fork. Upstream's code uses the older
standalone `gi0`/`gi1`/`gi2`/`gi3` plus `svgf` pipeline. The fork now uses
combined or split `r3` through `r9` passes, regional scene epochs, full path
signatures, Sable receiver domains, tri-state validation, and retry markers.
Replacing the fork with upstream would remove or bypass the protections added
for the black-history and streaming failures.

The most actionable upstream item is the random implementation, followed by a
small, isolated texture-normal experiment. The upstream reuse and SVGF changes
should be translated into the fork's data model rather than copied wholesale.

## 1:1 pipeline mapping

| Upstream | Fork v145 | Main difference |
|---|---|---|
| `rendering/restir/indirect/passes/gi0_initial_indirect.fsh` | `rendering/restir/passes/r3_initial_indirect.fsh` | Initial GI sample and hit metadata |
| `gi1_temporal_reuse.fsh` | `r4_temporal_reuse_impl.glsl` | Temporal reservoir reuse |
| `gi2_spatial_reuse.fsh` plus neighbor pass | `r5_copy_spatial_input_impl.glsl` and `r5_spatial_reuse_impl.glsl` | Spatial candidate selection and receiver checks |
| `gi3_validate_visibility.fsh` | `r6_diffuse_impl.glsl` plus `r4`/`r5` validation | Final GI evaluation and visibility |
| `svgf/history.glsl` and `sv0` | `restir.glsl` plus `r7_accumulation_impl.glsl` | Temporal radiance history |
| `sv1` and `sv2_atrous.fsh` | `r8_variance_prefilter_impl.glsl` and `r9_denoising_impl.glsl` | Variance and bilateral filtering |
| `utility/random.glsl` | Same path | Shared RNG and direction sampling |

## Detailed upstream differences

### A. Random implementation

Upstream commits `545d881e`, `27e1ab65`, and the live tree change
`utility/random.glsl` in three related ways:

- `ph_new_rand_state` uses separate seed and frame multipliers:
  `seed * 26699 + frame * 69193`.
- `ph_rand_next_uint` stores the intermediate `word` as the next state.
- `ph_rand_next_float` returns the integer result without overwriting that
  state with the final scrambled value.
- `ph_rand_direction` uses the upstream `ph_rand_dist` method instead of the
  fork's cosine-weighted local-basis construction.
- `ph_rand_sample_position` floors the light position and returns an offset in
  the live upstream tree. The fork mutates the position in place and has
  separate conditional call sites.

The fork currently has the older seed formula and cosine sampler in
`modules/shaders/photonics/utility/random.glsl`. More importantly, the fork's
`ph_rand_next_float` explicitly assigns `rand_state = x` after calling
`ph_rand_next_uint`. Therefore copying only upstream's `rand_state = word`
line would not reproduce the upstream sequence. The state transition, float
wrapper, and seed formula must be tested as one unit.

This is a credible candidate for directional bias and repeatable left/right
patterns. It is global, however: it changes direct-light jitter, GI rays,
reservoir selection, and diagnostics. Test it on a dedicated build with no
other rendering changes.

Reference: [27e1ab65](https://github.com/Redi2Go/PhotonicEngine/commit/27e1ab65bf4928d40440e55b973748b896fb3f53)

### B. Initial GI normal and sky endpoint

Upstream `725cff34` changes the first GI sampling normal from `frag_geo_normal`
to `frag_tex_normal`. Live commit `54e049be` also passes the texture normal to
the sky hit-point construction. This changes both the first ray distribution
and the virtual endpoint used when the ray reaches the sky.

The fork's equivalent is `r3_initial_indirect.fsh`. It currently passes
`frag_geo_normal` both to `sample_indirect` and
`indirect_sample_set_hit_point`. The fork also stores a surface-path hash and
uses voxel-face normals for finite endpoints, so only the input normal should
be experimentally changed. The upstream hit-point representation and
camera-relative conversion must not replace the fork's full-position format.

This experiment is relevant to sky-facing and normal-mapped surfaces, but it
does not by itself explain a whole wall turning black after a block edit.

References: [725cff34](https://github.com/Redi2Go/PhotonicEngine/commit/725cff3441a84a937d25a049576a46a8fc8617c3), [54e049be](https://github.com/Redi2Go/PhotonicEngine/commit/54e049be12cd1c762c772cb09fd6861ecdda9be5)

### C. GI reuse and Jacobian

Live upstream commit `bf4ff6e1` adds `indirect_reservoir_reuse` and changes
the Jacobian calculation:

- temporal reuse accepts Jacobians in approximately `1/150..150`;
- spatial reuse accepts Jacobians in approximately `1/50..50`;
- the Jacobian includes a texture-normal versus geometric-normal ratio;
- the initial sample weight is plain radiance luminance;
- upstream limits the source reservoir sample count before merging; and
- upstream tightens the neighbor-selection depth phi from `0.5` to `0.1`.

The fork's equivalent is materially different:

- `r4_temporal_reuse_impl.glsl` validates sublevel and scene epochs, computes a
  shift, restricts it to `0 < shift < 1.2`, and then performs endpoint/path
  classification with a surface signature;
- `r5_spatial_reuse_impl.glsl` first matches the current receiver domain,
  geometric plane, normal, and position, then validates at most one GI
  candidate under the explicit ray budget;
- `indirect/sample.glsl` clamps the Jacobian to `0..3` and uses the stored
  finite endpoint or sky direction; and
- `indirect/reservoir.glsl` rejects non-finite values and separately accounts
  for zero-contribution batches.

The upstream limits are broader than the fork's limits and do not perform the
fork's path-signature validation. Copying `indirect_reservoir_reuse` would
re-admit candidates that the current implementation intentionally classifies
as stale. The useful part to compare is the texture/geometric normal ratio and
the exact Jacobian distribution on grazing surfaces, not the whole function.

Reference: [bf4ff6e1](https://github.com/Redi2Go/PhotonicEngine/commit/bf4ff6e150ec57fb47035a593c888455b3d6c7e1)

### D. Visibility-aware SVGF history

Upstream commit `06d37a60` adds a separate visibility history attachment and
stores visibility in `SampleHistory`. Its atrous filter applies:

```text
wS = mix(1, exp(-abs(centerVisibility - sampleVisibility) / 0.1), ageFactor)
```

The fork does not have `visibility_history` or a `SampleHistory.visibility`
field. It stores direct state separately in `restir_direct_state`, uses binary
direct visibility and local signatures to reject denoiser neighbors, and
applies receiver-domain, normal, and plane checks in `r8`/`r9`.

The upstream idea could reduce bleeding across a moving shadow boundary, but it
is not the first fix for a zero or invalid GI reservoir. A direct port would
also mix the fork's split stable/external history incorrectly. An adaptation
would need a GI visibility/provenance signal produced in `r6`, stored with the
matching `r7` history, and tested in `r8`/`r9` without using production alpha as
metadata.

Reference: [06d37a60](https://github.com/Redi2Go/PhotonicEngine/commit/06d37a602429932eaf65da79a5301a4bccff0290)

### E. Plane-distance accumulation

Upstream `3e381664` adds a geometric-normal and plane-distance rejection during
old SVGF reprojection. Its primary thresholds are normal alignment `0.99` and
plane distance `0.25`.

The fork already has stricter and wider coverage:

- `restir.glsl` defines a base plane tolerance of `1/32` with half-float
  precision compensation;
- temporal and spatial receiver checks use both geometric planes;
- `r8` rejects neighbors beyond `0.075`; and
- `r9` weights both geometric planes with a `0.05` position scale.

This upstream change is already represented or superseded. It is not a safe
reason to replace the fork's current tolerances.

Reference: [3e381664](https://github.com/Redi2Go/PhotonicEngine/commit/3e381664a9ae0b360078fdf8e68b7bfd18ac379e)

### F. Fast history buffer

Upstream `0f820e7b` adds a second short history buffer and clamps the long
history toward it. The fork has no equivalent `fast_history` attachment or
history field. The fork instead has separate stable/external histories and
retry behavior for unresolved r7 pixels.

This could help the observed slow darkening or slow response after a real
visibility transition, but it is a performance/temporal-response experiment,
not evidence that black pixels are valid GI. It requires new framebuffer
attachments and flip ordering, so it should be evaluated only after the
zero/invalid transport path is proven clean.

Reference: [0f820e7b](https://github.com/Redi2Go/PhotonicEngine/commit/0f820e7bd9c2ce9bdd1ce3cdd865985d6ea3c542)

### G. Soft-shadow disable behavior

Upstream `46f16590` avoids light-position jitter unless
`PH_RESTIR_SOFT_SHADOWS` is defined. The fork already gates its equivalent
`ph_rand_sample_position` calls in `direct/reservoir.glsl`, and
`IrisDefines.java` emits `PH_RESTIR_SOFT_SHADOWS` only when the property is
enabled. No source port is indicated.

Reference: [46f16590](https://github.com/Redi2Go/PhotonicEngine/commit/46f165905cd510335ed5312477431bcb09d6e089)

### H. Other upstream changes

- `9abe0b1a` bundles Jacobian rejection, normal-factor and fast-history tuning.
  The fork has different path validation and tighter limits, so this is an
  idea source only.
- `a80d3ba8` changes the default spatial reuse radius to `32`. This is tuning,
  not a correctness fix.
- `e01e4085` changes a tracing declaration from `require` to `enable`. This is
  an interface-compatibility change, not a demonstrated GI fix.
- `be68754c` is formatting only.
- The `201c8eb9` through `3c5b91fd` Iris/property overhaul changes the pipeline,
  property, sampler, renderer, and framebuffer APIs. It is a platform
  migration and conflicts with the fork's 1.21.1/Sable/diagnostic work.
- `316c9e6d` removes the upstream `FRAG_USE_PLAYER_POS` special case. The fork
  still supports that macro in `frag/common.glsl`; this is cleanup, not a
  proven artifact fix.

## Recommended order of experiments

1. Build a baseline from the current tree and capture the exact static-wall
   test with the same JVM flags and no block edits.
2. Port the upstream RNG as one controlled change: seed formula, state
   progression, and float wrapper together. Compare GI and direct-light noise
   separately.
3. Revert to baseline, then test only texture normals in `r3` for the first GI
   ray and sky endpoint. Keep the fork's path hash and full-position storage.
4. Add a diagnostic-only comparison of the upstream normal-shift factor to the
   fork's current Jacobian on grazing surfaces. Do not widen the reuse limits.
5. If black pixels remain with valid current transport, prototype a separate
   GI visibility/provenance attachment for `r6` to `r9`, inspired by upstream
   `06d37a60`.
6. Consider fast history only after the above distinguishes stale valid light
   from invalid/zero transport.

Every experiment must test: no edit, one block place, one block break, camera
translation toward a large wall, slow rotation, rejoin, and a second session.
Record `ph_world_ready`, `ph_world_settled`, scene/layout revisions, current GI
batch state, r7 retry state, and denoiser-pass count for each capture.

## Bottom line

The upstream branch is not a clean replacement for v145. The strongest
actionable differences are the RNG state/direction implementation and the use
of texture normals for initial GI/sky samples. Upstream's shadow-weight and
fast-history ideas may address temporal response, while the fork's existing
regional/path/finite-value protections are specifically aimed at preventing the
black-history failure and should remain in place.

## Why a direct upstream GI replacement is not viable

"Impossible" here means impossible as a direct copy or cherry-pick. A full
port is possible, but it would be a renderer migration rather than a GI bug
fix. The upstream implementation and this fork no longer share the same
pass, resource, or history contracts.

### Hard integration blockers

1. **Different pipeline shape.** Upstream uses standalone `gi0`/`gi1`/`gi2`/
   `gi3` passes followed by a standalone SVGF pass. The fork uses a combined
   r3-r9 pipeline where GI, direct lighting, scene-history policy, and the
   denoiser share the ReSTIR framebuffer. Copying one upstream pass would leave
   its inputs or outputs unbound.

2. **Different framebuffer contracts.** Upstream writes `gi_output` and its
   own indirect reservoir attachments. The fork writes combined `lighting`,
   `external_lighting`, path metadata, scene epochs, direct state, and private
   validity attachments. The Java pipeline also flips and reuses the same
   attachments across r3-r7, then ping-pongs r8/r9. Upstream's draw-buffer
   ordering cannot be substituted without changing `RestirPipeline` and every
   dependent shader.

3. **Different reservoir meaning.** The fork's indirect reservoir contains
   current/reused sample accounting, packed hit metadata, path information,
   finite-value checks, and tri-state validation for valid, blocked, and stale
   paths. Upstream's reservoir merge/reuse functions do not carry the fork's
   scene-epoch or path-validation contract. Directly importing them would either
   fail to compile or silently discard the protections added for stale GI.

4. **Different history model.** Upstream SVGF stores direct plus GI output in
   its own history format, with optional visibility and fast-history buffers.
   The fork's r7 history has split stable/external lighting, accumulation-count
   retry markers, scene epochs, Sable provenance, and r8/r9 validity rejection.
   Replacing it with upstream history would remove the fork's invalidation and
   retry semantics and could reintroduce the old black-history failure.

5. **Different world-publication assumptions.** Upstream traces its own voxel
   tree without the fork's `ph_world_ready`, `ph_world_settled`, layout
   revision, scene revision, and changed-region uniforms. The fork needs to
   distinguish a query against the published tree from a missing query during
   section publication. Upstream code has no direct place for that state.

6. **Sable and receiver-domain behavior.** The fork's r4-r6 path handles Sable
   sublevel tokens, receiver domains, camera-relative positions, exact local
   hard shadows, and external-light history. Upstream GI assumes the older
   world-only receiver contract. A direct replacement would ignore or violate
   those checks, producing cross-domain reuse or incorrect local lighting.

7. **Different shader include and macro environment.** The upstream files use
   names such as `gi_output`, `PH_RESTIR_SPLIT_GI`, and upstream SVGF history
   declarations. The fork's shader preprocessor injects different uniforms,
   pass modes, framebuffer locations, and combined-GI defines. A source copy is
   therefore not a self-contained shader change.

8. **Different platform and pipeline APIs.** The upstream branch also contains
   Iris/property/framebuffer changes that do not match the fork's 1.21.1, Sable,
   and diagnostic integration. Cherry-picking the surrounding Java commits
   would be a broad API migration with unrelated failure modes.

### Costs even if a full port is attempted

- **It destroys diagnostic isolation.** A large pipeline replacement changes
  sampling, history, attachment flips, denoising, and world timing at once. A
  better recording would not prove which change fixed the black edges.
- **It can regress edit correctness.** Upstream does not have the fork's
  scene-revision/path invalidation. Old GI paths may survive a block edit and
  produce stale illumination instead of a visible blackout.
- **It can regress Sable compatibility.** The upstream GI passes do not know the
  fork's external-light and receiver-domain ownership rules. Reusing them would
  require reimplementing those rules around every temporal and spatial merge.
- **It increases resource and bandwidth pressure.** A separate GI output,
  standalone history, visibility history, and optional fast history require
  additional attachments and passes. This changes memory use, GPU cost, and
  synchronization behavior before correctness is established.
- **It changes the visual estimator globally.** The upstream RNG, direction
  sampling, texture-normal input, Jacobian limits, and history response change
  direct and indirect noise everywhere. Those may improve quality, but they are
  not a focused explanation for camera-edge black pixels.
- **It increases maintenance divergence.** Future upstream fixes would need to
  be reconciled with the fork's combined pipeline, scene epochs, Sable support,
  and diagnostics again. A wholesale port would create two competing renderer
  architectures.

### What can be ported safely

The useful upstream behavior should be adapted one item at a time behind the
fork's existing contracts:

1. Port the complete RNG state transition as an isolated experiment.
2. Test texture normals for the first GI ray and sky endpoint while retaining
   the fork's hit-point and path metadata.
3. Compare the upstream normal-aware Jacobian factor without widening the
   fork's current reuse limits or removing path validation.
4. Consider visibility history only after current-batch and history-validity
   failures are fixed, with a fork-compatible GI provenance attachment.
5. Consider fast history only after the renderer no longer produces invalid
   black states and the remaining issue is confirmed to be temporal response.

### If a wholesale port is ever required

It should be treated as a separate experimental renderer mode, not as the next
patch. It would need an adapter for `FragData` and world tracing, a new GI/SVGF
framebuffer and flip schedule, explicit combined/direct/Sable integration,
scene-edit invalidation, diagnostic attachments, and a full no-edit/one-edit/
rejoin A/B test. Until those contracts are reproduced, direct upstream usage
has a higher regression and debugging cost than fixing the identified r7/r8/r9
state semantics in the current pipeline.
