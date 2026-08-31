# v147 GI Patch Plan

Date: 2026-08-30  
Baseline: `multi-version` at `1695cae9` (`v146f`)  
Purpose: define the next testable patch and the visual behavior it must change
without bundling unrelated estimator or shader-pack changes.

## Decision

The next patch should fix the sample-state contract first. It must distinguish
these states everywhere in the GI/history path:

1. A current ray/batch was evaluated against a published voxel tree and the
   result happened to have zero radiance.
2. A current evaluation was not available or was rejected.
3. A valid historical result is being shown temporarily while a fresh result
   is unavailable.

The current code collapses states 1 and 2 in r7 by requiring positive
luminance. That is the best source-level explanation for black camera-edge
bands and their frame-to-frame flicker. The block-edit blackout is a related
history transition, but it is not the cause of the camera-only artifact.

## Evidence Baseline

The two fresh Luna reviews and the v146 source/log review agree on the
following:

- The v146 recording shows edge formations before the block edit, while the
  wall is otherwise unchanged. They follow the camera and are not physical
  shadows.
- The edit at approximately `10:34:12.845` changes exactly one block. It is
  followed by a short fragmentation/noise burst and near-black output.
- The edit reports `ready=true`, `settled=false`, and `pendingBuilds=1`, but
  there is no pipeline destroy/create reset at that time.
- The actual v146 pipeline resets occur during reload/startup, followed by
  framebuffer clears. They do not coincide with the camera-only formations.
- Recovery begins before the later `settled=true` record, so the two-second
  settling delay is not the fundamental recovery mechanism.

Relevant source locations:

- `r7_accumulation_impl.glsl:44-83`: positive-energy/current-GI gate.
- `restir.glsl:291-315`: history and accumulation validity predicates.
- `r8_variance_prefilter_impl.glsl:93-97` and `r9_denoising_impl.glsl:54-59`:
  unresolved alpha-zero rejection.
- `r3_initial_indirect.fsh:18-77`: current GI trace and batch creation.
- `RestirPipeline.java:307-360`: r3 through r9 ordering and attachments.
- `WorldCompiler.java:386-419`: coarse ready/settled publication signals.

## Next Patch Scope

The v147 candidate should contain the following changes in this order.

### 1. Preserve current GI evaluation state

Add a current-frame GI batch/evaluation state that is independent of radiance
and independent of the final reused reservoir. The state should be written by
r3 after a valid in-world trace against a ready tree, then remain available to
r7 through r4/r5.

The state must be false when r3 skipped the trace because the world was not
ready. It must be true when the trace completed but returned zero radiance.
Do not infer it from `weight`, luminance, or the reservoir selected after
temporal/spatial reuse. Those values answer different questions.

The implementation uses a small standalone current-side framebuffer. A pass
immediately after r3 refreshes it each frame, before r4/r5 can rewrite the
reservoir, and r7 consumes it. It is initialized for out-of-world fragments;
it is intentionally not flipped because it is current-frame state, not
history.

### 2. Fix r7's transport decision

Replace the positive-luminance requirement for current GI with the explicit
current-evaluated state from step 1. A finite zero-radiance result is a valid
current sample and must be allowed to seed/advance history with a positive
sample count.

Keep the existing distinction for a genuinely missing evaluation:

- no current evaluation plus no valid history: emit a retry marker or a
  conservative presentation fallback;
- current evaluation with zero radiance: commit a valid zero result;
- current positive transport: commit normally.

Do not use `indirect_reservoir_has_batch()` on the final r4/r5 reservoir as a
replacement. That reservoir can contain sample-count accounting from rejected
historical paths and does not prove that this frame's current trace completed.

### 3. Make history validity use sample count

Unify all history validity paths around finite values, invalid-sentinel checks,
and a positive accumulation count. A history with zero RGB but a positive
sample count is valid. A zero-count history is unresolved and is not valid.

Apply the same rule to:

- single-texel history loads;
- mixed/bilinear reprojection;
- nearest-texel recovery;
- r8 neighborhood acceptance; and
- r9 neighborhood acceptance.

The current sentinel-only predicate at `restir.glsl:291` must not classify the
v146 alpha-zero retry marker as reusable history.

### 4. Keep camera-edge reprojection bounded

The existing single-tap path already bounds every `texelFetch`, and GLSL
`fract()` is non-negative. The v147 state patch therefore does not claim to
rewrite the reprojection estimator. It makes an all-invalid mixed result stay
`INVALID_HISTORY`, prevents that marker from becoming reusable zero history,
and lets a completed current sample win over the fallback. A later reprojection
patch can replace the nested pairwise mix with explicit valid-tap weight
renormalization if edge artifacts remain after this state fix.

### 5. Tighten scene-edit promotion rules

Keep v146's presentation-continuity idea, but enforce it with the explicit
current-evaluation state:

- a recovered old sample may be displayed temporarily;
- it may not be relabeled as current-scene history without a current
  evaluated sample;
- a current evaluated zero result is sufficient to replace the presentation
  fallback and establish the new epoch;
- receiver bounds alone must not prove that an old radiance path is still
  valid.

This is the minimum edit fix for the next build. Full path-dependency metadata
for radiance history is a later improvement if stale light remains outside the
reported changed bounds.

### 6. Keep diagnostics non-invasive and more precise

Retain the private validity attachments, but keep them non-invasive. The v147
implementation adds the current r3 state to the existing capture pass and
keeps its final-history check aligned with production sentinel/stream rules.
These textures are still private: the current patch does not add a display,
readback, or CPU aggregation path, so the validity flag is not a substitute
for a visible diagnostic recording. A later diagnostic-only patch can expose
the channels if the production test still fails. The intended channel meanings
are:

- current GI evaluation completed;
- current GI positive radiance;
- current direct evaluation;
- history sample-count valid;
- history epoch match;
- recovered presentation history;
- current-tree path valid/blocked/stale;
- world ready and world settled; and
- r7/r8/r9 accepted or rejected.

The diagnostic flag must not replace production lighting, variance, or
external-lighting outputs. Green, cyan, magenta, or other palette colors are
valid only in an explicitly displayed diagnostic view, never in the normal
production frame.

## Explicitly Deferred

Do not combine these changes with v147:

- upstream RNG/state-sequence changes;
- texture-normal or sky-endpoint changes in r3;
- wider upstream GI Jacobian limits;
- fast-history/SVGF attachments;
- Sable/Veil lighting changes; or
- a wholesale replacement with upstream's standalone GI/SVGF pipeline.

Those are separate experiments. Combining them would make a successful or
failed visual result non-diagnostic.

The repeated `#endif without #if` messages must also be traced as a separate
shader-preprocessor issue. They are not accepted as normal output, but they
are not temporally correlated with the v146 camera-edge artifact.

## Expected Visual Changes

| Test moment | Expected after v147 | Not acceptable |
| --- | --- | --- |
| Stable wall before edits | Wall stays spatially stable while the camera is still. | Gradual darkening or formations appearing without a scene change. |
| Slow camera rotation at a wall edge | No black screen-space bands or camera-following dark wedges. | A dark strip appearing exactly where geometry enters the view. |
| Newly exposed shadowed face | It may be noisy briefly, then settle to a stable value. | Alpha-zero blackness that toggles as the camera moves. |
| Fully unlit physical face | A stable very dark or black face is allowed if direct and indirect light are genuinely zero. | Darkness that moves with the viewport or changes while stationary. |
| One block placement/removal | Local, bounded replacement noise for a few frames; current evaluation then establishes valid history. | Whole-wall/full-view blackout, green production frame, or unrelated regions becoming black. |
| Hold still after edit | Output converges and stops accumulating artificial shadow. | Monotonic darkening after `ready=true` and a current batch is available. |
| Streaming with pending builds | Newly exposed areas may have short low-confidence/noisy transitions. | Existing resident geometry losing light globally because another section streams. |
| Rejoin/reload/resize | One normal clear/startup transition may occur. | Repeated in-game resets or an uninitialized attachment reaching the final composite. |
| Diagnostic mode | Palette colors may appear only in the intentionally displayed diagnostic. | Any diagnostic palette leaking into normal production lighting. |

## Glitches That May Remain

The following can legitimately remain after the next patch and do not by
themselves mean the state fix failed:

- low-level stochastic GI grain or small brightness variation from the current
  sampler, because upstream RNG and normal changes are deferred;
- a short, localized noisy transition after a real block edit or section
  publication;
- physically correct dark faces in a genuinely unlit enclosure;
- slight convergence delay on a newly visible surface while its first valid
  samples accumulate; and
- unrelated shaderpack/Veil lighting behavior that reproduces with GI disabled.

The following remain hard failures:

- black bands tied to the camera viewport edge;
- dark formations that move when the camera moves but not when geometry changes;
- progressive darkening while the camera is stationary and no scene hash
  changes occur;
- a full-view green/palette flash in production mode;
- a blackout of geometry outside the changed section/path neighborhood; or
- repeated history/pipeline resets without reload, resize, or world change.

## Controlled Test Run

Run the same build in two modes, using a fresh matching log and recording for
each mode.

### A. Production baseline

Use `-Dphotonics.debugSceneHashDiff=true` only if the existing launch method
supports it. Keep the validity palette disabled. In the test world:

1. Wait for `ready=true`, `settled=true`, and `pendingBuilds=0`.
2. Hold the camera still on the wall for 15 seconds.
3. Rotate slowly left/right across the wall edge for 15 seconds.
4. Break one block, hold still for 10 seconds, then place it back.
5. Rejoin once and repeat the settled wall view.

Expected log behavior is one hash diff per intentional edit, no hash diffs in
the no-edit camera phase, no pipeline destroy/create near the edit, and no
permanent retry state after a current batch has been evaluated.

### B. Diagnostic comparison

Repeat the same sequence with:

```text
-Dphotonics.restirGiValidityChannelsDiagnostic=true
-Dphotonics.debugSceneHashDiff=true
```

Do not judge the palette as production lighting. At the first black or noisy
frame, correlate the pixel state in this order:

1. Was current GI evaluation completed with a positive batch count?
2. Was current direct evaluation completed?
3. Was history sample-count valid and in bounds?
4. Did r7 accept a current or recovered sample?
5. Did r8/r9 reject the pixel or its neighbors?
6. Did the final composite display the intended production attachment?

## Decision Rules After Testing

- If camera-edge black bands disappear and only bounded edit noise remains,
  accept v147 and move to the publication-generation fence.
- If `current_evaluated=1` and r7 is valid but the final image is black, inspect
  r8/r9 and the shaderpack composite next.
- If `current_evaluated=0` while `ready=true`, `settled=true`, and
  `pendingBuilds=0`, add an explicit published tree generation/section fence in
  `WorldCompiler` and the shader uniforms.
- If the diagnostic state is stable but production flickers, inspect ping-pong
  attachment binding/clear ordering rather than GI sampling.
- If only stochastic grain remains, evaluate upstream RNG and texture-normal
  changes as isolated follow-up builds.

## Success Criteria

The next patch is successful only if the simple wall test no longer produces
camera-edge black areas, stationary accumulation darkening, or a production
green flash. A brief local noisy transition after one block edit is acceptable
provided it recovers to stable lighting without a full pipeline reset.

## Implementation Status

The v147 source patch is implemented in the working tree:

- `RestirPipeline.java` adds one standalone current-GI-state `RGBA16F`
  framebuffer and captures it immediately after r3 in combined and split GI.
- `r3_gi_current_state.fsh` records evaluated, finite-batch, and positive-
  contribution state without adding a ninth attachment to the combined FBO.
- `r7_accumulation_impl.glsl` commits only a complete current GI evaluation and
  keeps presentation-only history in the prior epoch while that evaluation is
  missing.
- `restir.glsl` and the r7 validity capture use positive accumulation counts,
  invalid-sentinel checks, finite stream values, and independent stable/external
  history validity.

The Java source compiles with `javac --release 21`. Full Gradle configuration
is currently blocked before compilation because Fabric Loom cannot validate the
Mojang version manifest through the local TLS certificate chain; the offline
retry also fails because that manifest is not cached. No runtime visual result
should be attributed to v147 until a matching game build is produced.
