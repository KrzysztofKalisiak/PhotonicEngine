# Photonics GI Temporal Artifacts: v137 Second-Pass Brief

## Purpose

This document records the v137 evidence and a focused second-pass investigation plan for the persistent black/dark formations, camera-dependent GI changes, green flashes, and slow recovery after block edits. It is written for an independent model review. The goal is to identify the smallest defensible fix before changing more rendering features.

The first implementation from this review is now in the working tree. It is
intentionally narrow: it prevents unresolved r7 samples from being committed
as valid black history, stops those samples entering the r8/r9 spatial filters,
and adds an opt-in validity diagnostic. It does not claim to solve every
remaining artifact yet.

## First implementation in the current worktree

The current patch makes these changes:

- r7 accepts a current GI batch when the published voxel tree is ready
  (`ph_world_ready`), rather than waiting for the separate settling-delay bit.
- If r6 has no current transport and no recoverable history, r7 leaves the
  previous history untouched and writes an alpha-zero retry marker instead of
  averaging a zero/alpha-one placeholder into history.
- Direct-only ReSTIR keeps zero direct-light results valid, so a fully
  occluded direct-light frame is not confused with an unresolved GI frame.
- r8 and r9 reject alpha-zero r7 pixels from their spatial neighborhoods.
- `-Dphotonics.restirGiValidityDiagnostic=true` enables a combined-GI,
  full-resolution diagnostic. Red means reprojected history, green means
  current transport, and blue means `ph_world_settled == 0`. The diagnostic
  bypasses denoising while preserving the existing framebuffer layout.
- `-Dphotonics.restirGiValidityChannelsDiagnostic=true` enables a clearer
  combined-GI channel diagnostic. It uses a palette for four independent
  states: history, current direct, current GI batch, and unsettled world.
  The direct/GI bits are captured in r6 and carried to r7 instead of being
  inferred from final radiance.
- The world compiler now logs `layoutReason` as `scene-content-change`,
  `scene-hash-change-without-player-marker`, `streaming-or-rebuild`,
  `section-unload`, or a combined reason. This is provenance only; it does
  not change voxel publication behavior.

The patch does not yet provide per-pixel masks for every r3-r9 rejection
reason, green-frame attachment isolation, or Sable/Veil A/B isolation. Those
remain follow-up work if the validity map shows that the artifact begins after
r7.

## Verification

Launch the combined-GI, full-resolution test build with:

```text
-Dphotonics.restirGiValidityDiagnostic=true
```

Use the same wall sweep and bunker test from v137. In the diagnostic view:

- red means the pixel has accepted reprojected history;
- green means the current frame has transport that r7 can commit;
- blue means the compiler reports `ph_world_settled == 0`;
- black means neither accepted history nor current transport is available.

The colors are written by r7 and are deliberately carried through r8/r9
without denoising. If black areas appear while the tree is ready and the
receiver is unchanged, the remaining failure is later than the r7 validity
decision or is caused by a separate framebuffer/presentation path. If they
appear only with blue, the settling/layout path remains implicated. Capture
the corresponding `layoutReason` entries from the log as well.

For the clearer second diagnostic, launch with:

```text
-Dphotonics.restirGiValidityChannelsDiagnostic=true
```

The stable-state palette is:

| Color | Meaning |
| --- | --- |
| black | no history, no current direct, no current GI |
| red | history only |
| green | current direct proposal/evaluation only |
| yellow | history + current direct |
| blue | current GI batch only |
| magenta | history + current GI |
| cyan | current direct + current GI |
| white | history + current direct + current GI |

When `ph_world_settled == 0`, the same combinations use pastel variants:
gray means no transport, orange/lime/light-yellow represent history/direct
variants, and violet/pink/light-cyan represent GI variants. This makes the
unsettled bit visible without putting it in the lighting alpha channel.

The current-direct bit means that r6 had a usable direct proposal or exact
local direct evaluation. It is deliberately independent of radiance: a
visibility-rejected direct proposal can therefore still be marked green. The
current-GI bit means that r6 had a finite indirect batch and the published
world tree was ready, including zero-radiance GI batches.

The diagnostic is still composed through the shader pack's lighting hook, so
the final recording can be affected by albedo, exposure, and tone mapping.
Read the dominant tint rather than treating a pale white surface as an exact
RGB value. A later shader-pack-specific overlay can make the colors pixel-
exact, but this mode is sufficient to identify whether a dark region lacks
history, direct transport, GI transport, or only waits for world settling.

`git diff --check` and focused source assertions pass in this worktree. A full
Gradle build was attempted with Java 21, but Fabric Loom could not download the
Minecraft 1.21.1 artifact after three attempts. The compiled jar therefore
still needs to be produced on the Linux machine with its populated Gradle
cache, or after network access to the required Maven/Piston endpoints is
available.

## Evidence files

- `logs/v137/latest.log`
- `logs/v137/debug.log`
- `screenshotd/v137/Screencast From 2026-08-16 19-26-21.mp4`

The recording is approximately 85.06 seconds at 862x526 and 59.94 FPS. The late bunker portion contains the clearest green, black, and unstable GI frames.

## User-reported evidence

### Evidence 1: formations appear while moving left

The first frames show a large wall that is initially close to uniform. Moving left exposes more of the wall, and dark shadow-like formations appear on the newly exposed area. These formations are not attached to a plausible light or blocker and are not stable geometric shadows.

### Evidence 2: formations follow camera exposure

When the camera moves left and exposes another wall region, dark patches appear there. When it moves right, similar dark formations appear on the right side of the newly exposed region. This is screen/history dependent behavior rather than a fixed world-space shadow.

### Evidence 3: darkening while stationary

The camera is held still, but the wall becomes progressively darker. This rules out a pure camera-motion-vector explanation. It is consistent with temporal accumulation repeatedly receiving an invalid, zero, or low-confidence GI contribution and converging toward a dark result.

### Evidence 4: transient green frame

A green-tinted frame appears across the scene for approximately one frame. This is not normal GI noise. It suggests a transient framebuffer, history attachment, or pipeline-state reset being displayed before the replacement data is ready.

### Evidence 5: recovery after a block edit

After a block is destroyed, the image becomes severely corrupted for about a second before recovering. A block edit is expected to invalidate some radiance, but it should not expose a full-screen invalid or uninitialized state for that long.

## Confirmed v137 log facts

### Layout churn occurs without a scene revision

The world tracing diagnostic reports separate counters:

- `layoutRevision` tracks compiler/tree publication and section streaming.
- `sceneRevision` tracks detected section-content hash changes.

The log has long layout-only churn while `sceneRevision` remains zero:

- `19:26:35.554`: layout 62, scene 0, settled true.
- `19:26:35.736`: layout 63, scene 0, settled false, 141 sections unloaded, 48 builds pending.
- `19:26:37.421`: layout 67, scene 0, settled false.
- `19:26:38.939`: layout 71, scene 0, settled false.
- `19:26:40.423`: layout 75, scene 0, settled false.

The same pattern appears after the first content edit:

- `19:27:11.420` through `19:27:17.474`: layouts 89, 93, 100, 103, and 107, all scene revision 1 and unsettled.
- `19:27:19.709`: layout 110, scene revision 1, settled true.

This is strong evidence that moving/exposing the world can coincide with repeated layout publication even when no block content changed.

### Four actual section-content transitions are logged

The v137 log does not show an uncontrolled scene-invalidation loop. It reports four real hash transitions:

1. `19:26:41.124`: section `(5,0,0)`, non-air 8 -> 9, scene revision 1.
2. `19:27:22.925`: section `(-1,0,0)`, non-air 278 -> 279, scene revision 2.
3. `19:27:26.064`: section `(-1,0,0)`, non-air 279 -> 278, scene revision 3.
4. `19:27:28.630`: section `(5,0,-1)`, non-air 1 -> 2, scene revision 4.

Each transition is marked `radianceInvalidation=regional`, `playerChanged=false`, and `initialPopulationComplete=true`. These events can explain local history loss around the edited sections, but they cannot explain every camera-dependent artifact in the run.

### Settling takes several seconds in the diagnostic stream

After the first edit, the compiler reaches a settled state at `19:26:45.675`. After the second and third edits it settles at `19:27:25.027` and `19:27:28.178`. After the fourth edit it settles at `19:27:30.679`, then another unload makes it unsettled at `19:27:32.264` and settled again at `19:27:34.264`.

The current one-to-two second recovery period is therefore observable in the logs. It is not expected that all radiance should disappear during this period, especially outside the regional change bounds.

### No shaderpack failure is present in this run

The v137 log contains the normal Photonics GI passes and no shader compilation exception. It does contain compatibility warnings from the Sable/light-storage integration and unrelated missing model warnings. Those are useful isolation targets, but the v137 evidence does not prove that Sable is the direct cause of the black formations.

## Code-level interpretation

### Leading hypothesis: layout settling is being used as radiance validity

`WorldCompiler.java:354-372` calls `setWorldSettled(false)` whenever `mostRecentCompilationRevision` changes. This compilation revision changes for section publication, streaming, and unload work, not only for physical scene-content edits. The values are then exposed as `ph_world_settled` and `ph_scene_revision` in `WorldCompiler.java:475-489`.

The GI history epoch is intentionally based on physical scene content. `restir.glsl:126-157` also says layout revisions should not invalidate the entire screen and that changed voxel paths should be revalidated locally.

However, `r7_accumulation_impl.glsl:59-71` defines a stable current GI batch as requiring:

```glsl
current_indirect_loaded
    && indirect_reservoir_has_batch(current_indirect)
    && ph_world_settled != 0;
```

That means a layout-only compiler revision can make the current GI batch unusable globally even when `ph_scene_revision` is unchanged.

### Likely visual failure chain

1. Camera movement or section streaming causes a layout publication.
2. `WorldCompiler` publishes `ph_world_settled = 0` for the settling interval.
3. r7 rejects the current GI batch because it is not considered stable.
4. Newly exposed wall pixels have no valid reprojected history, or their history is rejected by surface/path checks.
5. The pixel has neither valid history nor current transport. It enters the retry/recovery path with little or no indirect radiance.
6. Temporal and spatial denoising operate on a mixture of valid history, zero/underconverged pixels, and neighboring pixels with different validity. The resulting dark formations follow screen exposure and can grow while the camera is stationary.

This explains the left/right asymmetry and stationary darkening better than a physical shadow explanation. It remains a hypothesis until the intermediate masks are instrumented.

### Why the block-edit failure is related but not identical

The four block edits correctly advance `sceneRevision` and invalidate a regional area. r7 deliberately avoids committing an empty post-revision frame as a valid current epoch (`r7_accumulation_impl.glsl:124-130`). That is directionally correct, but if a receiver has no valid history and no stable current batch, the output can still remain dark until a usable sample arrives. The regional invalidation should not cause a full-screen blackout or a green attachment to be displayed.

### Green frame

The green flash is a separate symptom until proven otherwise. It is more consistent with a transient framebuffer or history attachment lifecycle problem than with ordinary Monte Carlo variance. Candidate locations include resize/reset, ping-pong swap, shader reload, or a revision path that displays an attachment before it has been cleared and populated. The raw GI, history, denoised, and final outputs must be inspected independently.

## What the evidence does not prove

- It does not prove that every dark patch is caused by Sable or Veil.
- It does not prove a repeated block-hash invalidation loop. v137 shows four real content transitions, not continuous false changes.
- It does not prove that spatial reuse alone is the root cause.
- It does not prove that more samples will fix the problem. More samples may hide it while increasing cost.
- It does not prove that the direct-light reservoir is responsible; the strongest evidence is in GI/temporal presentation.

## Recommended next goals

### Goal 1: add a temporary GI validity diagnostic

Create a split-screen or debug-color output for each pixel showing:

- `ph_world_ready`
- `ph_world_settled`
- `ph_scene_revision`
- previous history epoch match
- reprojected-history accepted
- current GI batch loaded and valid
- scene-change bounds affect receiver
- nearest-history recovery used
- `history_retry_required`
- final history alpha and indirect radiance

The diagnostic must use flat colors or integer labels, not denoised lighting. Capture it while moving left/right, while stationary, and after one block edit. This will identify whether the black formations begin in the current GI pass, history validation, accumulation, or denoising.

### Goal 2: identify why layout revisions happen

Add a reason field to the compiler diagnostic for each `layoutRevision`:

- section build
- section unload
- tree relocation/repacking
- camera/view-distance streaming
- Sable/Veil update
- world join/reload
- explicit block-content change

The current counters prove layout churn exists but do not identify its producer. A no-edit camera sweep should be enough to reproduce this part.

### Goal 3: separate layout validity from scene radiance validity

The likely architectural fix is:

- Keep `sceneRevision` and regional radiance invalidation tied to physical content changes.
- Stop using the global `ph_world_settled` bit as the sole requirement for all current GI transport.
- Allow current GI samples when the voxel query/path is locally valid, even if unrelated sections are being published.
- Use per-path validation, section residency, and receiver-local readiness for the exceptional cases.
- Preserve valid history across layout-only revisions when the receiver and stored path are still valid.

This should be implemented only after Goal 1 has shown the exact failing mask.

### Goal 4: make no-valid-sample behavior visibly safe

When a pixel has no valid current transport and no valid history:

- do not commit zero radiance as a valid history epoch;
- retain the last valid radiance outside the regional invalidation bounds when path validation permits it;
- inside invalid bounds, use a conservative fallback and mark low confidence;
- ensure the denoiser does not spread zero/invalid values into valid neighbors;
- never present an uncleared ping-pong attachment.

### Goal 5: isolate the green reset

Run the same test with raw debug outputs for:

1. current direct/GI transport,
2. reprojected history,
3. accumulated history,
4. SVGF variance/ATrous output,
5. final composed lighting.

If only one attachment turns green, fix its clear/swap/revision path. If all raw attachments turn green simultaneously, inspect framebuffer binding and shader reload/reset ordering.

### Goal 6: test Sable and Veil separately

Repeat the controlled structure with:

- Photonics GI only,
- Photonics plus Veil,
- Photonics plus Sable light storage,
- full mod set.

Keep the camera path, block edits, time, resolution, and render scale identical. This is an isolation test, not a reason to move the root cause to Sable without evidence.

## Controlled test matrix

1. No block edits, stationary camera, 30 seconds.
2. No block edits, slow left/right sweep along the same wall.
3. No block edits, vertical camera movement along the wall.
4. One block placement, wait 10 seconds, then one block removal.
5. Same as 1-4 with GI disabled but direct lighting enabled.
6. Same as 1-4 with spatial reuse disabled.
7. Same as 1-4 with the upscaler disabled.
8. Repeat the smallest reproducer with Veil/Sable isolated.

For every run, record the exact log timestamp, `layoutRevision`, `sceneRevision`, `settled`, `batchUnloaded`, `pendingBuilds`, and the diagnostic masks from Goal 1.

## Questions for the second-pass model

1. Can `ph_world_settled` become zero while the receiver's voxel path is unchanged? If yes, is it currently allowed to produce or commit a valid GI sample?
2. When r7 has no reprojected history and no stable current batch, which exact attachment values are written and how are they treated by later denoising passes?
3. Can a zero/invalid sample be spatially reused or mixed into neighboring valid pixels?
4. Is the history epoch texture cleared and swapped atomically on resize, shader reload, world join, and scene revision?
5. Are `world_offset`, previous camera position, depth, and normal from the same frame snapshot as the history epoch?
6. Which compiler operation creates layouts 63, 67, 71, and 75 in v137 while `sceneRevision=0`?
7. Can the fix preserve history for layout-only revisions without allowing stale history across actual block edits?

The second-pass result should include exact source locations, a minimal patch order, and a statement of which diagnostic result would falsify the leading hypothesis.
