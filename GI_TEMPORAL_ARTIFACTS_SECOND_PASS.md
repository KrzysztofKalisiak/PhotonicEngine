# Photonics GI Temporal Artifacts: v137-v140 Second-Pass Brief

## Purpose

This document records the v137-v139 evidence and the v140 diagnostic implementation for the persistent black/dark formations, camera-dependent GI changes, green flashes, and slow recovery after block edits. It is written for an independent model review. The goal is to identify the smallest defensible fix before changing more rendering features.

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
  In v140 the current direct/GI bits are written to private diagnostic
  attachments after r6 and after r7; they are no longer packed into the
  production lighting alpha channel.
- The world compiler now logs `layoutReason` as `scene-content-change`,
  `scene-hash-change-without-player-marker`, `streaming-or-rebuild`,
  `section-unload`, or a combined reason. This is provenance only; it does
  not change voxel publication behavior.

The patch does not yet provide an opaque final-composite overlay, per-pixel
masks for every r3-r9 rejection reason, or Sable/Veil A/B isolation. The v140
private captures are enough to compare the state immediately after r6 with
the state after r7 without changing the eight production ReSTIR attachments.
The remaining items are follow-up work if the validity captures show that the
artifact begins after r7.

## v140 diagnostic isolation

The v140 channel diagnostic adds a separate two-attachment, full-resolution
framebuffer only when combined GI is enabled at the normal render scale:

- `restir_gi_validity_current` is written immediately after r6.
  `R=current direct evidence`, `G=current GI batch`, `B=current non-zero
  finite lighting`, and `A=published-tree readiness`.
- `restir_gi_validity_final` is written immediately after r7.
  `R=post-r7 history accepted`, while `G/B/A` copy the current-frame direct,
  GI, and energy evidence from the first attachment.

The capture framebuffer is not flipped and is not part of the production
ReSTIR framebuffer. The current and final passes select one draw buffer each,
so the inactive attachment is not overwritten accidentally. The screen
palette still comes from r7 because the existing shaderpack hook is the only
portable displayed output in this branch; the private attachments make the
post-r6/post-r7 boundary inspectable without using `lighting.a` as a metadata
channel. Alpha in the production lighting attachment remains the real
temporal sample count.

## v139 follow-up findings

Evidence reviewed:

- `logs/v139/latest.log`
- `screenshotd/v139/Screencast From 2026-08-17 00-16-57.mp4`

The v139 run loaded the channel diagnostic correctly. The log reports GI,
combined GI, full render scale, `restirSpatial=0`, and
`restirGiValidityChannelsDiagnostic=true`. The recording is approximately
147.87 seconds at 59.94 FPS.

### Finding 1: the diagnostic colors do not replace the final pixel

The cyan/white/pastel colors are written into `RESTIR_LIGHTING_OUT` by r7 and
are carried through the diagnostic r8/r9 paths. They are then consumed by the
shaderpack lighting hook. This is a lighting-buffer diagnostic, not an opaque
full-screen debug overlay.

Therefore the normal shaderpack image, exposure/tone mapping, and any other
composed lighting path can remain visible beneath or around the diagnostic
color. A dark or noisy region below a stable cyan/white mask is not proof that
the r7 validity state is changing. Conversely, a change in the mask itself is
evidence of a transport/history state transition. The current recording mixes
these two signals, so it cannot yet identify the exact attachment where the
black formations begin.

This also explains why the color test still shows blinking underneath the
colors. The diagnostic is revealing state on top of the production composite;
it is not isolating the production composite from the state map.

### Finding 2: v139 still has substantial layout churn

The log separates physical content revisions from compiler/layout revisions:

- Initial population settles at `layoutRevision=44`, `sceneRevision=0`.
- Later `streaming-or-rebuild` and `section-unload` events repeatedly move the
  layout through revisions 45-135 while `sceneRevision` stays unchanged for
  long intervals. These events report `settled=false`, section unloads, and
  tens of pending builds.
- Six actual content transitions are recorded at approximately 00:17:28,
  00:17:35, 00:18:18, 00:18:25, 00:18:48, and 00:19:00. Each increments
  `sceneRevision` and is marked `radianceInvalidation=regional`.

This means the run contains both classes of event. A block edit legitimately
invalidates regional radiance, but camera movement and section streaming also
publish incomplete layouts without changing the physical scene. The current
patch no longer requires `ph_world_settled` for the basic current GI batch,
but layout churn still affects world residency, recovery, diagnostic state, and
the availability of valid temporal inputs. It remains a credible contributor
to the camera-dependent dark formations.

### Interpretation of the post-edit noise

Some noise immediately after a block edit is expected in this test because the
edit deliberately invalidates a region and the diagnostic bypasses the normal
r8/r9 denoising path. The first replacement GI samples are therefore exposed
more directly than in production.

The following are not expected behavior:

- a stable wall gradually becoming darker while the camera is stationary;
- dark formations following newly exposed screen regions;
- a full-scene green flash or a long-lived black patch after a local edit;
- unrelated regions losing valid accumulated light during a regional edit.

The v139 evidence does not prove that all of these are produced by r7. The
v139 diagnostic was still composed through the normal lighting hook, so its
final recording could not identify the first failing attachment. v140 adds
private snapshots at the r6/r7 boundary, but it does not yet display those
snapshots as an opaque overlay.

## Ordered next steps after v140

1. **Add an opaque diagnostic presentation path.** Keep the current channel
   mask, but add a temporary final-composite/debug overlay that writes the mask
   directly to the displayed framebuffer. It must suppress shaderpack lighting,
   exposure, bloom, and tone mapping for the test. This gives an unambiguous
   answer about whether the validity state itself blinks.
2. **Extend the raw attachment capture around one edit.** v140 now records
   r6 current direct/GI evidence in `restir_gi_validity_current` and the
   post-r7 history decision in `restir_gi_validity_final`. Add equivalent
   snapshots or a readback path for r8 variance input, r9 denoised output, and
   the final composite. The first attachment that changes to black/green is
   the owner of the bug.
3. **Run a no-edit camera sweep with the opaque overlay.** Hold the scene
   fixed, sweep left/right and vertically, and correlate every color change
   with `layoutReason`, `sceneRevision`, `settled`, pending builds, and section
   unloads. This isolates layout churn from actual radiance invalidation.
4. **Separate layout readiness from radiance validity.** Preserve history and
   current samples across layout-only publication when the receiver/path is
   still resident and valid. Use receiver-local/path-local checks for missing
   sections instead of treating global `ph_world_settled=0` as a screen-wide
   loss of GI validity.
5. **Make regional edit recovery explicit.** For the changed bounds, reject
   stale paths and keep a low-confidence retry state. Outside those bounds,
   retain valid history. Do not clear or publish a shared attachment in a way
   that exposes an uncleared ping-pong buffer to the final composite.
6. **Only after steps 1-5, isolate Sable/Veil.** Repeat the smallest test with
   Photonics only, Photonics plus Veil, and the full mod set. Sable/Veil should
   be treated as a confirmed cause only if the same raw attachment fails only
   in the combined configuration.
7. **Then tune denoising and performance.** More samples, spatial reuse, or
   stronger denoising can hide the symptom but cannot repair a wrong validity or
   framebuffer state. Do not use them as the first fix.

### Expected falsification results

- If the opaque mask remains stable while the normal image blinks, the bug is
  after r7 and likely in the shaderpack composite, temporal presentation, or a
  separate lighting path.
- If the opaque mask changes during layout-only revisions with no content
  change, the layout/readiness contract is still invalidating or replacing
  current GI state too aggressively.
- If only the changed region changes after an edit and unrelated pixels remain
  stable, regional invalidation is behaving correctly and the remaining issue
  is local replacement-sample quality.
- If an entire attachment becomes green or black at once, inspect framebuffer
  clear, ping-pong swap, resize, and shader-reload ordering before changing the
  ReSTIR estimator.

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
-Dphotonics.restirSpatialReuseSamples=0
```

The second property is for the controlled test, not a requirement of the
diagnostic. It removes spatial candidates so a color transition can be
correlated with current transport and temporal history without a neighboring
reservoir introducing another source of evidence.

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

When the on-screen r7 diagnostic sees `ph_world_settled == 0`, the same
combinations use pastel variants: gray means no transport,
orange/lime/light-yellow represent history/direct variants, and
violet/pink/light-cyan represent GI variants. This makes the settling bit
visible without putting it in the lighting alpha channel.

The private v140 capture uses a different readiness bit deliberately:
`restir_gi_validity_current.a` records `ph_world_ready`, because that is the
condition used for current GI-batch eligibility. Its channels are:

| Attachment | R | G | B | A |
| --- | --- | --- | --- | --- |
| `restir_gi_validity_current` | current direct evidence | current GI batch | finite/non-zero current lighting | published-tree ready |
| `restir_gi_validity_final` | post-r7 history accepted | copied direct evidence | copied GI evidence | copied current-energy evidence |

These attachments are private and are not currently shown directly in the
recording. Therefore a changing pastel color proves a change in the existing
r7 presentation state, while a dark or noisy surface underneath a stable tint
does not by itself prove that the private r6/r7 capture changed.

The current-direct bit means that r6 had a usable direct proposal or exact
local direct evaluation. It is deliberately independent of radiance: a
visibility-rejected direct proposal can therefore still be marked green. The
current-GI bit means that r6 had a finite indirect batch and the published
world tree was ready, including zero-radiance GI batches.

The displayed diagnostic is still composed through the shader pack's lighting
hook, so the final recording can be affected by albedo, exposure, and tone
mapping. Read the dominant tint rather than treating a pale white surface as
an exact RGB value. A later shader-pack-specific overlay or readback is still
needed to make the private captures pixel-exact; v140 is sufficient to verify
that the new snapshots exist without modifying production lighting alpha.

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

### Leading hypothesis: layout settling is still coupled to presentation

`WorldCompiler.java:354-372` calls `setWorldSettled(false)` whenever `mostRecentCompilationRevision` changes. This compilation revision changes for section publication, streaming, and unload work, not only for physical scene-content edits. The values are then exposed as `ph_world_settled` and `ph_scene_revision` in `WorldCompiler.java:475-489`.

The GI history epoch is intentionally based on physical scene content. `restir.glsl:126-157` also says layout revisions should not invalidate the entire screen and that changed voxel paths should be revalidated locally.

The pre-v140 code path used `ph_world_settled` as the global current-GI gate.
The current `r7_accumulation_impl.glsl` path instead uses
`ph_world_ready != 0` for `has_current_gi_batch`, so a layout-only settling
interval no longer automatically rejects every current GI batch. The settled
bit still participates in nearest-history recovery and in the displayed
diagnostic palette. This distinction is important: a `sceneRevision=0` run
can still show pastel/settling state without proving that the current GI
reservoir was rejected.

The old v137 condition was:

```glsl
current_indirect_loaded
    && indirect_reservoir_has_batch(current_indirect)
    && ph_world_settled != 0;
```

That historical condition explains why a layout-only compiler revision could
make the current GI batch unusable globally even when `ph_scene_revision` was
unchanged. It is no longer the current-batch condition, but it remains a
useful explanation for older recordings.

### Likely visual failure chain

1. Camera movement or section streaming causes a layout publication.
2. `WorldCompiler` publishes `ph_world_settled = 0` for the settling interval.
3. The current GI batch is now eligible when `ph_world_ready` is true, but a
   newly exposed wall pixel may still lack a geometrically valid history or a
   locally available reservoir.
4. If the pixel has neither valid history nor current transport, r7 leaves the
   prior history untouched and marks a retry state rather than intentionally
   committing a black sample. A separate later pass can still be reading an
   uncleared, stale, or partially populated attachment; v140 is intended to
   distinguish that from an r7 validity decision.
5. Temporal and spatial denoising can then operate on a mixture of valid
   history, replacement samples, and neighboring pixels with different
   validity. The resulting dark formations follow screen exposure and can
   grow while the camera is stationary.

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

### Goal 1: temporary GI validity diagnostic (v140 partially implemented)

v140 now has a first boundary capture. When the channel diagnostic is enabled
for combined full-resolution GI, it allocates the private
`restir_gi_validity_current` and `restir_gi_validity_final` attachments,
writes the first immediately after r6, and writes the second immediately after
r7. It also removes the old production-alpha bit packing. This is useful for
the next implementation step, but it is not yet a complete per-pixel readback
or opaque presentation path.

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

The remaining diagnostic must use flat colors or integer labels, not denoised
lighting. Capture it while moving left/right, while stationary, and after one
block edit. This will identify whether the black formations begin in the
current GI pass, history validation, accumulation, or denoising. The next
implementation should expose the private v140 attachments through a temporary
opaque presentation or controlled GPU readback before adding more estimator
heuristics.

### Goal 2: identify why layout revisions happen (reason logging present)

Add a reason field to the compiler diagnostic for each `layoutRevision`:

- section build
- section unload
- tree relocation/repacking
- camera/view-distance streaming
- Sable/Veil update
- world join/reload
- explicit block-content change

The current branch logs `layoutReason` values, so the remaining task is to
correlate those reasons with the private validity captures. A no-edit camera
sweep should be enough to reproduce this part.

### Goal 3: separate layout validity from scene radiance validity

The likely architectural fix is:

- Keep `sceneRevision` and regional radiance invalidation tied to physical content changes.
- Stop using the global `ph_world_settled` bit as the sole requirement for all current GI transport.
- Allow current GI samples when the voxel query/path is locally valid, even if unrelated sections are being published.
- Use per-path validation, section residency, and receiver-local readiness for the exceptional cases.
- Preserve valid history across layout-only revisions when the receiver and stored path are still valid.

The v140 capture is the first boundary check, not the complete diagnostic
requested here. The remaining per-pixel masks and opaque presentation should
be implemented before changing more estimator behavior.

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
