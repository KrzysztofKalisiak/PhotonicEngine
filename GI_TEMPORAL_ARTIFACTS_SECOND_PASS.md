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

## v140 diagnostic run review (archived recordings)

Evidence reviewed:

- `logs/v140/latest.log`
- `logs/v140/debug.log`
- `logs/v140/launcher_log.txt`
- `screenshotd/v140/Screencast From 2026-08-17 12-19-32.mp4`
- `screenshotd/v140/Screencast From 2026-08-17 12-20-39.mp4`
- `screenshotd/v140/Screencast From 2026-08-17 12-21-55.mp4`

### Artifact correlation audit

The current v140 folder does not contain one correlatable run. The current
`latest.log`, `debug.log`, and `launcher_log.txt` start at approximately
`22:50:15` and end at approximately `23:03:04`. The three MP4 files contain
creation timestamps `12:19:32`, `12:20:39`, and `12:21:55`. They therefore
predate the current logs by roughly ten hours. The recordings can be reviewed
visually, but their phases cannot be assigned to the current
`sceneRevision`/`layoutRevision` records.

For the current log, the pipeline reports the normal production configuration:

```text
restirCombinedGi=true
renderScale=1.0
giRenderScale=1.0
temporalUpscalerRequested=false
restirSpatial=0
```

The current log has no combined-GI validity-channel enable message and no
validity-capture timing records. This is consistent with the diagnostic being
disabled. `run_settings` nevertheless contains older `true` entries followed
by `false` entries and a malformed `trueE` token, so it is not authoritative
evidence of the actual JVM arguments. The log is the source of truth for the
current process; the next run should use a fresh settings file or an
explicitly empty argument field.

### Final v140 classification

- **High confidence:** the current process launched Photonics successfully
  with GI enabled, spatial reuse disabled, and the temporal upscaler disabled.
- **High confidence:** the current log is not a clean no-edit run. It records
  48 content transitions from `22:50:56.831` through `23:02:57.643`, reaching
  `sceneRevision=28` and at least `layoutRevision=136`.
- **High confidence:** 33 `#endif without #if` errors occur across three
  pipeline initialization/reload periods. They do not stop the pack from
  loading, but they remain a real shader-compilation risk.
- **Medium confidence:** the current diagnostic is disabled. There is no
  validity enable message or validity timing record, but `run_settings` is
  contaminated by contradictory historical arguments.
- **Low confidence:** the three MP4s describe the current log. Their embedded
  times are `12:19:32`, `12:20:39`, and `12:21:55`, while the current log is
  `22:50:15`-`23:03:04`.

The black GI/direct-light states after edits are compatible with regional
invalidation followed by layout/section publication, but this run cannot
distinguish that from a later framebuffer/history or shaderpack composite
failure. The recordings cannot establish the ordering.

### Exact next test

1. Move the three existing MP4s aside and start a fresh `v141` folder. Clear
   `run_settings` of all Photonics arguments; launch with an empty JVM argument
   field and keep the build unchanged.
2. Record one video whose filename timestamp is generated during that exact
   process. Save the matching `latest.log`, `debug.log`, and launcher log in
   the same folder. Do not reuse files from another session.
3. Enter the fixed test world and wait until `ready=true`, `settled=true`, and
   `pendingBuilds=0` appear in three consecutive world-trace records. Then do
   20 seconds stationary, 20 seconds slow horizontal camera movement, and 20
   seconds slow vertical movement. Do not edit blocks, reload shaders, resize,
   or change worlds.
4. If the stable camera phase is clean, perform exactly one block removal,
   record the timestamp, keep the camera fixed for 10 seconds, then rejoin the
   world and record another 20 seconds after the same settled condition.
5. Report whether blackening begins before or after the edit/rejoin and give
   the matching log timestamp. Only after this correlated baseline should we
   enable the opaque validity overlay to distinguish r6/r7 from r8/r9 and the
   final composite.

### Run configuration caveat

The current log is consistent with a production/no-diagnostic run. The v140
log reports:

```text
restirCombinedGi=true
renderScale=1.0
giRenderScale=1.0
temporalUpscalerRequested=false
restirSpatial=0
```

The current log does not report:

```text
(no combined-GI validity-channel enable line)
```

The repository `run_settings` file contains contradictory historical values,
including both `true` and `false`. Therefore the log, not that file, determines
the current process state. The MP4s also do not match this log's timestamps;
their diagnostic-looking colors may belong to an earlier v140 run and cannot
be used to classify the current run.

### What the recordings actually show

The three recordings show broad cyan, green, magenta, pink, blue, or pastel
regions across ordinary terrain, menus, and the bunker. This is consistent
with the r7 validity palette being written into the lighting attachment and
then processed by the shaderpack. It is not evidence that the scene radiance
itself changed to those colors.

Because the MP4 timestamps do not match the current log, the following are
visual observations only; they must not be used as phase-by-phase evidence
for the current v140 process.

- The `12-19-32` recording starts with pink/magenta faces and cyan edges, then
  moves through mostly cyan/pastel outdoor and wall views. The tint covers
  large areas and changes with the diagnostic state, not with a plausible light
  source.
- The `12-20-39` recording is already cyan in the menu and remains cyan while
  viewing the bunker, the wall, and the outdoor test structure. This confirms
that an earlier diagnostic state was active across scene transitions; it is
not evidence of a single bad voxel or light source in the current run.
- The `12-21-55` recording includes inventory screens, outdoor views, the
  handheld object, and the bunker. Cyan, blue, and purple states appear in all
  of them. The palette is therefore reaching the displayed lighting path, but
  the recording does not isolate the private validity attachments.

The expected v140 palette is bit-combined, not a continuous quality scale:

```text
red   = accepted reprojected history
green = current direct evidence
blue  = current GI batch
pastel variants = the same combinations while ph_world_settled == 0
```

For example, cyan means current direct plus current GI, magenta means history
plus current GI, and white means all three state bits. The shaderpack can alter
the exact appearance through albedo, exposure, bloom, and tone mapping. A
cyan or magenta screen is expected while this diagnostic is active; a normal
white-world visual comparison cannot be made from these recordings.

### What the log proves

The v140 pipeline initialized successfully. There is no Photonics shaderpack
load failure, shader exception, or out-of-memory error in the reviewed log.
Both new private passes executed repeatedly:

- 75 `restir GI validity current` timing records, beginning at
  `12:16:58.378` and ending at `12:23:08.614`.
- 75 `restir GI validity final` timing records, beginning at
  `12:16:58.410` and ending at `12:23:08.667`.
- At the approximately 0.892 megapixel diagnostic viewport, current capture
  timing is roughly 0.07-0.15 ms GPU and final capture roughly 0.02-0.04 ms
  GPU. The diagnostic passes are therefore not a plausible explanation for a
  multi-frame visual blackout by themselves.

The world trace was not stable during the test:

- 115 world-tracing records were emitted.
- `layoutRevision` advanced from 0 to 350.
- `sceneRevision` advanced from 0 to 192.
- 79 records were unsettled and 36 were settled.
- 192 scene-content hash records were emitted; 12 had
  `playerChanged=true` and 180 had `playerChanged=false`.
- The most common layout reasons were
  `scene-hash-change-without-player-marker` (46), `section-unload` (25),
  `streaming-or-rebuild` (32), and `scene-content-change` (10).

`playerChanged=false` does not mean that no block changed. The associated
non-air counts and hash transitions show real section changes. It means that
the compiler did not receive the player-change marker for that transition.
The v140 run therefore contains many actual regional invalidations and cannot
serve as the no-edit camera-sweep test requested below.

There is one additional unresolved warning: v140 emits 26 occurrences of
`#endif without #if`, compared with 22 in the v139 log. The pipeline still
renders and the new timing records appear, so this is not a complete load
failure, but it must not be dismissed. The extra occurrences may come from the
new validity program compilation or from another shader reload path. A clean
diagnostic-off run and a per-program preprocessor log are needed before
calling this harmless.

### Expected timeline and correspondence to earlier glitches

The following is the expected behavior for the v140 diagnostic and the
production behavior that should be expected after the diagnostic is disabled.

1. **World join and first compilation**
   - Expected log: `sceneRevision=0`, layout revisions rise, `ready` becomes
     true, and `settled` is initially false before later becoming true.
   - Expected diagnostic: gray or pastel combinations can appear while the
     world is unsettled; normal lighting may take time to converge.
   - Not expected in production: a permanent full-screen black state once the
     tree is ready.
   - Relation to earlier glitches: the old black bunker and black wall can be
     reproduced if a first-frame or uncleared attachment is presented during
     this transition, but the v140 tint alone is not that bug.

2. **Layout-only streaming or section unload**
   - Expected log: `layoutRevision` changes while `sceneRevision` stays fixed;
     `settled=false`, `batchUnloaded` or `pendingBuilds` may be non-zero.
   - Expected diagnostic: the pastel form of the current palette may change;
     current GI can be absent for a receiver whose path or section is not yet
     resident.
   - Not expected in production: unrelated, already-resident walls losing
     accumulated light or developing screen-following black formations.
   - Relation to earlier glitches: this is the strongest match for formations
     appearing on newly exposed left/right wall regions and for vertical-motion
     sensitivity. It is a layout/history contract problem, not a physical
     shadow.

3. **Stable no-edit camera sweep**
   - Expected log: no new scene-content records, stable `sceneRevision`, and
     eventually `settled=true`; camera motion alone may change visibility but
     must not change physical radiance validity for unchanged resident blocks.
   - Expected diagnostic: the mask may change at genuinely different receiver
     pixels, but a fixed wall should not progressively change state while the
     camera only exposes it.
   - Relation to earlier glitches: a changing mask here would implicate
     reprojection, receiver validity, layout publication, or ping-pong state;
     a stable mask with a changing image would move the problem after r7 into
     r8/r9 or the shaderpack composite.

4. **One block placement or removal**
   - Expected log: one regional content transition, `sceneRevision` increments,
     the changed bounds are logged, and a short unsettled interval follows.
   - Expected diagnostic: pixels in or near the changed bounds can lose history
     and show replacement colors; unrelated regions should retain history.
   - Not expected: a full-scene green flash, a black wall far outside the
     changed bounds, or a long-lived blackout after the tree becomes ready.
   - Relation to earlier glitches: the one-second post-edit corruption and
     green frame are consistent with an invalidation/clear/swap boundary, but
     v140's 192 revisions prevent this run from proving which boundary is at
     fault.

5. **No current sample for one receiver**
   - Expected diagnostic: black means no accepted history, no current direct
     evidence, and no current GI batch for that pixel. This can be legitimate
     briefly at an unloaded or newly invalidated receiver.
   - Expected production behavior: retain valid history where possible or use
     a conservative low-confidence fallback; do not turn a whole visible wall
     black just because one frame had no sample.
   - Relation to earlier glitches: this is the direct diagnostic equivalent of
     the pitch-black bunker and black sides of sun-shadowed blocks. It tells us
     what the pixel state was, not why the state was missing.

6. **Shader reload, resize, or world re-entry**
   - Expected log: the pipeline and private attachments are recreated and the
     first few frames can be transitional.
   - Expected production behavior: attachments are cleared, bound, and flipped
     atomically; the final composite must never display an uninitialized
     attachment.
   - Relation to earlier glitches: the green full-screen frame, horizon line,
     and black formations after resize/rejoin remain lifecycle candidates. They
     cannot be explained by GI sampling alone.

### V140 conclusion for the independent review

The archived build run proves that the r6/r7 boundary capture is wired and
inexpensive. It does not prove whether the private validity state changes when
the old black formations appear, because the private attachments are not
displayed or read back. The recordings mainly prove that the diagnostic
presentation path is active. The timestamp-correlated no-arguments follow-up
below is the current production evidence; changing ReSTIR sample counts or
blaming Veil remains premature until the one-edit test is isolated.

## v140 no-arguments follow-up

This is the later three-part run requested after the archived diagnostic
recordings. The current files are timestamp-correlated and must be analyzed
separately from the earlier `12:xx` recordings:

- `logs/v140/latest.log`
- `logs/v140/debug.log`
- `logs/v140/launcher_log.txt`
- `screenshotd/v140/no arguments/Screencast From 2026-08-17 23-00-09.mp4`
- `screenshotd/v140/no arguments/Screencast From 2026-08-17 23-01-12.mp4`
- `screenshotd/v140/no arguments/Screencast From 2026-08-17 23-02-19.mp4`

The three recordings are approximately 53.67 s, 59.17 s, and 42.67 s. Their
filename times fall inside the `22:50:15`-`23:03:04` log session. The earlier
`12:19`, `12:20`, and `12:21` files belong to the archived diagnostic run and
are not used for this correlation.

### Configuration and log facts

- GI is enabled, `restirSpatial=0`, and the temporal upscaler is disabled.
- The current log contains no combined-GI validity-diagnostic enable line and
  no validity-capture timing records. This is consistent with diagnostic-off
  production lighting; the log is authoritative despite stale `run_settings`.
- The current log records 48 scene-content transitions from
  `22:50:56.831` through `23:02:57.643`, reaching `sceneRevision=28` and at
  least `layoutRevision=136`.
- There are 33 `#endif without #if` messages across three pipeline
  initialization/reload periods. They do not prevent the pack from rendering,
  but they remain a shader-preprocessor risk and must be traced separately.
- No shaderpack-load failure or out-of-memory failure appears in the log.

### Three-part timeline

1. **Part 1: `23-00-09` recording**
   - The first reported block removal is approximately 9 seconds into the
     recording and matches the content change at `23:00:18.655`.
   - `sceneRevision` then advances through 7, 8, 9, 10, 11, 12, 13, 15,
     16, 19, and 20. The log therefore shows repeated regional changes, not
     one isolated edit.
   - Section unloads and layout publication follow from `23:00:46.514` to
     `23:00:56.802`, with the world repeatedly becoming unsettled.
   - The nearly black GI shadows, and apparent loss of direct illumination,
     are not expected after one regional edit. The direct-plus-GI failure
     points to a shared history/output/composite path, although the recording
     alone cannot identify which stage owns it.

2. **Part 2: `23-01-12` recording**
   - A pipeline destruction/recreation and full history clear occur at
     `23:01:10.453`-`23:01:11.535`, immediately before the recording begins.
   - The new session starts at `sceneRevision=0`; initial layout reaches
     `settled=true` at approximately `23:01:17.313`.
   - This partial recovery after rejoin is evidence that lifecycle reset clears
     stale or poisoned state. It is not evidence that the underlying
     invalidation bug is fixed.
   - Section unloads occur from approximately `23:01:31` through `23:01:57`.
     Further content/hash transitions begin around `23:02:05`, so this part
     also stops being a no-edit baseline before it ends.

3. **Part 3: `23-02-19` recording**
   - Content/hash transitions begin around `23:02:20.592` and continue
     through the recording, including several changes in the small test
     structure.
   - The world reaches settled states between some revisions, but later edits
     create new regional invalidations. The later globally dark shadows are
     therefore compatible with repeated invalidation plus history/output
     poisoning, not with ordinary sampling noise alone.

### What is expected versus what occurred

- **Expected after one removal:** one regional content change, one scene
  revision increment, temporary local noise, and recovery after the tree is
  ready and settled.
- **Observed:** repeated scene revisions and layout churn, followed by black
  GI shadows and apparently dark direct lighting. This is a broader failure
  than a GI-only reservoir temporarily having too few samples.
- **Expected after rejoin:** history and attachments clear, a short noisy
  rebuild, then stable lighting.
- **Observed:** partial recovery after rejoin, followed by new darkening once
  more revisions and section activity occurred. This implicates lifecycle or
  regional-history handling, but does not prove the exact buffer.
- **Expected during camera-only movement:** unchanged resident walls may have
  short reprojection noise, but must not progressively darken or turn black.
- **Observed in the broader history:** formations appear as walls are exposed,
  and stationary surfaces can darken. This remains a temporal/layout or final
  presentation artifact, not a physical shadow.

The current run rules out spatial reuse and the upscaler as necessary causes,
because both were disabled. It does not distinguish among current GI
sampling, r7 history acceptance, r8/r9 filtering, ping-pong framebuffer state,
or the shaderpack composite. The simultaneous darkening of direct and GI
output makes the shared output/history/composite branches higher priority than
changing GI sample counts.

### Exact next test: one edit, no confounding changes

Use a fresh `v141` directory for both logs and recordings. Do not reuse the
current v140 log or place the new recordings in the old `no arguments` folder.

1. Remove every Photonics diagnostic argument from `run_settings`, including
   stale contradictory entries. Keep the build and the current settings
   otherwise unchanged; confirm the process log has no validity-diagnostic
   warning.
2. Start the existing small test world. Do not place or remove blocks, resize,
   reload shaders, rejoin, or move the camera until the log has three
   consecutive world-trace records with `ready=true`, `settled=true`, and
   `pendingBuilds=0`.
3. Record 15 seconds with the camera fixed on the test wall and bunker.
4. Record 15 seconds of slow horizontal movement, then 15 seconds of slow
   vertical movement. Do not edit anything during these phases.
5. Remove exactly one known block. Note the recording timestamp at the edit.
6. Keep the camera fixed for 30 seconds. Do not perform another edit. Note the
   first timestamp at which direct light, GI, or both become black.
7. Wait until the log reports a settled state again, then record 15 seconds of
   slow camera movement. Rejoin once, wait for the same settled condition, and
   record 15 seconds without edits.

Interpret the result as follows:

- Blackening during the fixed no-edit phase with stable `sceneRevision` points
  to r6/r7 validity, framebuffer history, denoising, or final composite.
- Blackening only after one `sceneRevision` increment and only inside the
  changed bounds points to regional invalidation/replacement history.
- Blackening outside the bounds while `layoutRevision` or section unloads
  change points to layout publication being treated as global radiance loss.
- Simultaneous direct and GI blackening points to shared lighting history,
  ping-pong/clear state, or the final composite rather than GI sampling alone.
- Recovery only after rejoin points to lifecycle reset or stale history; it is
  not an acceptable production recovery mechanism.

Only after this correlated run should the opaque v141 validity overlay be
enabled. That overlay must display current direct evidence, current GI
evidence, accepted history, and final composite state without shaderpack
exposure or bloom. The existing private v140 attachments cannot answer that
question by themselves.

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

The older v137-v139 diagnostic and the v140 diagnostic are different test
paths. Use the older property only when reproducing the earlier palette; use
the v140 channel property when checking the r6/r7 captures.

For the legacy combined-GI, full-resolution palette, launch with:

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

For the reviewed v140 run, this diagnostic was active despite the report that
no custom arguments were entered. Treat the log line
'combined-GI validity channel diagnostic v140 enabled' as the source of truth.
Do not interpret the v140 recordings as a no-argument production comparison
until that line is absent.

For v140's clearer channel diagnostic, launch with:

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

### v140 reviewer checklist

Use the following distinctions when reviewing the three recordings:

- A cyan, magenta, pink, green, or pastel region is expected while the v140
  diagnostic warning is present. It identifies bit combinations after shaderpack
  processing; it is not a production-light color and is not itself a failure.
- A color change during world join, shader reload, or section publication is
  expected while `settled=false`, but it should converge after the published
  tree becomes ready and settled.
- A color change during a clean camera-only sweep is not expected for an
  unchanged, resident wall. If the opaque mask changes, investigate validity,
  reprojection, or framebuffer state before investigating lighting energy.
- A short local change after one block edit is expected inside the changed
  bounds. A full-scene green flash, a distant black wall, or persistent dark
  history after the scene settles is not expected.
- A black diagnostic pixel means that neither accepted history nor current
  direct/GI evidence was recorded for that pixel. It does not identify whether
  r6, r7, layout publication, or the later composite caused the absence.
- The v140 recordings cannot answer the last question because the private
  captures are not displayed directly and the run contains 192 scene-content
  revisions. Treat them as a wiring and provenance check, not as the decisive
  no-edit reproducer.

git diff --check and focused source assertions pass in this worktree. The
user-provided v140 build is confirmed by the log to contain and execute both
new validity passes. A full local Gradle build remains network-blocked by the
Minecraft 1.21.1 artifact download, so the Linux-built jar is the artifact
under test here.

## Evidence files

- `logs/v137/latest.log`
- `logs/v137/debug.log`
- `screenshotd/v137/Screencast From 2026-08-16 19-26-21.mp4`
- `logs/v140/latest.log`
- `logs/v140/debug.log`
- `logs/v140/launcher_log.txt`
- `screenshotd/v140/Screencast From 2026-08-17 12-19-32.mp4`
- `screenshotd/v140/Screencast From 2026-08-17 12-20-39.mp4`
- `screenshotd/v140/Screencast From 2026-08-17 12-21-55.mp4`
- `screenshotd/v140/no arguments/Screencast From 2026-08-17 23-00-09.mp4`
- `screenshotd/v140/no arguments/Screencast From 2026-08-17 23-01-12.mp4`
- `screenshotd/v140/no arguments/Screencast From 2026-08-17 23-02-19.mp4`

The recording is approximately 85.06 seconds at 862x526 and 59.94 FPS. The late bunker portion contains the clearest green, black, and unstable GI frames.
The v140 recordings are approximately 63.85 seconds, 63.60 seconds, and
73.52 seconds at about 1333x725, 1333x725, and 1349x730 respectively. Their
dominant cyan/pink/pastel appearance is expected because the v140 channel
diagnostic was enabled.
The timestamp-correlated no-arguments recordings are approximately 53.67,
59.17, and 42.67 seconds at 1482x676. They are production-lighting evidence,
not validity-palette recordings.

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

The current branch already emits a reason field for each `layoutRevision`.
The intended categories are:

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

## v141 follow-up: radiance collapse after a regional edit

This section records the three v141 recordings and the matching `latest.log`.
The recordings were started at different times, so the video-to-log mapping is
approximate by a few tenths of a second. The state transitions themselves are
unambiguous.

### Evidence files

- `logs/v141/latest.log`
- `logs/v141/debug.log`
- `logs/v141/launcher_log.txt`
- `screenshotd/v141/Screencast From 2026-08-18 07-53-29.mp4`
- `screenshotd/v141/Screencast From 2026-08-18 07-54-58.mp4`
- `screenshotd/v141/Screencast From 2026-08-18 07-56-43.mp4`

The run used full-resolution GI, no temporal upscaler, and
`restirSpatial=0`. Therefore the final collapse is not explained by spatial
reuse or by the upscaler being enabled.

### Independent visual review

The darkest state is not a literal display or framebuffer blackout. The HUD,
selection outlines, sky openings, exterior terrain, and some directly visible
surfaces remain rendered. The failure is a large radiance collapse in the
Photonics lighting result, mainly on interior and shadowed surfaces.

The recordings contain five distinct symptoms:

1. Soft temporal noise during movement. This is expected to a limited degree.
2. Camera-exposure-dependent dark formations on vertical walls. These are not
   stable world-space shadows and are not expected.
3. A green/gray transition around the froglight and bunker. Persistent green
   contamination is not ordinary Monte Carlo noise.
4. Large interior regions becoming nearly black while sky and UI remain valid.
5. The dark state persisting after the compiler reports that the revised scene
   has settled. This is not a normal one- or two-frame accumulation delay.

### Timeline correlation

| Video | Approximate log interval | Correlated state | Interpretation |
|---|---|---|---|
| `07-53-29` | `07:53:29` to `07:54:41.8` | Mostly settled outdoor test, frequent layout-only streaming changes, edit at `07:54:20.017` | Explains moving wall formations and one regional invalidation, but not a full collapse. |
| `07-54-58` | `07:54:58` to `07:56:11.7` | Edit at `07:54:54.268`; green/dark phase follows a streaming transition around `07:55:20`; edits at `07:55:31.527` and `07:55:41.010`; unload churn increases at `07:55:59` to `07:56:09` | Matches green contamination, noisy bunker lighting, and unstable wall history. |
| `07-56-43` | `07:56:43` to about `07:57:15` | Dark wall formations at roughly `07:56:57` to `07:57:01`; edit at `07:57:01.162`; `sceneRevision=7`, `layoutRevision=129`, `ready=true`, `settled=false`; second edit at `07:57:08.915` advances `sceneRevision=8` | Strongest reproducer of the radiance collapse. The collapse starts at the first real edit and persists after the scene becomes settled at `07:57:03.212`. |

At `07:57:01.211`, the tree reports `ready=true` and `pendingBuilds=0`, but
the published state is still `settled=false`. This means "the tree has a
usable shape and bounds" is not equivalent to "the current indirect proposal
and the previous history belong to the same scene snapshot."

### What the log rules out

- No Photonics history reset occurs at the final collapse. The only resets are
  pipeline-created/destroyed events during startup and the initial attachment
  clears around `07:52:34` to `07:52:44`.
- No shaderpack failure, render-thread exception, out-of-memory error, or GL
  failure occurs near the collapse.
- Startup `#endif without #if` messages and the DH/Iris warnings occur several
  minutes before the visual failure. They remain a separate compatibility risk,
  but they are not a sufficient explanation for this event.
- The final event is not caused by spatial reuse: the v141 run has zero spatial
  reuse samples.

## v141 code-level findings

### Primary candidate: zero indirect radiance is classified as current transport

The current `r7_accumulation_impl.glsl` path computes
`has_current_gi_batch` from:

```glsl
indirect_reservoir_loaded
    && indirect_reservoir_has_batch(current_indirect)
    && ph_world_ready != 0;
```

`indirect_reservoir_has_batch` in
`modules/shaders/photonics/rendering/restir/indirect/reservoir.glsl` only
requires a finite reservoir and `total_samples > 0`. It does not require a
positive final weight or a usable sample. `indirect_reservoir_reject` sets the
weight and sample color to zero while retaining the sample count. The
serialized reservoir can therefore describe "a processed batch with zero
contribution" and still satisfy `has_batch`.

That distinction is valid for an estimator denominator, but it is not valid
for the r7 history decision. In r7, a true `has_current_gi_batch` makes
`has_current_transport` true even when the GI color is zero. The zero current
sample can then enter `sample_history_combine_lighting` instead of taking the
retry path. Repeated frames can progressively darken a previously valid wall,
which matches the stationary darkening and the persistent black interiors more
closely than a sample-count explanation does.

This is the first concrete source-level explanation for the apparent "it
accumulates into black" behavior. It is still a hypothesis until a diagnostic
run shows `current GI batch = true` while current GI radiance/weight is zero.

### Secondary candidate: published-tree readiness is weaker than snapshot validity

`WorldCompiler.onFrameBegin` computes `worldReady` from tree depth and bounds,
then publishes `ph_world_ready` independently of `ph_world_settled`.
Compilation revisions set `ph_world_settled=0` for layout publication, section
streaming, and unloads. Thus `ready=true, settled=false` is an intentional
state, and it appears in the v141 log immediately after the edit.

The current indirect reservoir encoding stores hit point, weight, color, sample
count, and normal, but no explicit layout/tree revision. r4 validates the
stored path geometrically, which is useful, but r7's current-batch test does
not prove that the current reservoir was produced from the tree snapshot now
bound to the shader. A current batch from the previous tree can therefore be
accepted during a publication boundary if its data remains finite.

This likely explains the earlier camera-dependent wall formations: streaming
changes the set of available receiver/reservoir data while the camera exposes
new pixels. It is less likely to be the sole cause of the abrupt final collapse
because that collapse follows a real scene revision.

### Tertiary candidate: unresolved zero output is intentionally preserved as
black through the denoiser chain

The r7/r8/r9 safety changes correctly avoid spatially spreading an unresolved
zero sample. However, when a receiver has neither valid history nor current
transport, r7 outputs a zero-count/zero-radiance retry marker. r8 and r9 then
output zero for that pixel and skip filtering. This prevents contamination of
neighbors, but it also produces a black pixel until a later frame supplies a
valid sample. If the current-batch predicate above incorrectly accepts a
zero-weight batch, the retry marker is not reliable and black can be committed
as history instead.

### Green contamination remains a separate boundary problem

The v141 log does not report the v140 validity diagnostic as enabled. The green
phase therefore cannot be treated as the intentional diagnostic palette. It is
more consistent with a transient or mismatched attachment/composite state,
but the current evidence does not identify whether that state is r6/r7 output,
the denoiser chain, or the shaderpack lighting hook. It needs a raw opaque
attachment capture; increasing GI samples will not diagnose it.

## Revised fix order

1. **Instrument the indirect decision.** Capture, per frame and in aggregate,
   `current_indirect_loaded`, `total_samples`, `weight`,
   `indirect_reservoir_has_sample`, final GI luminance, `ph_world_ready`,
   `ph_world_settled`, `ph_scene_revision`, and whether r7 combined the sample.
   The key counter is `has_batch && !has_sample` and the key pixel mask is
   `has_current_gi_batch && final_gi_luminance <= epsilon`.
2. **Fix the classification boundary.** Keep a zero-contribution reservoir in
   the estimator denominator, but do not call it current radiance transport
   for r7 history accumulation. A receiver with zero usable radiance should
   either retain valid history or remain an explicit retry. This patch should
   be isolated before changing denoiser weights.
3. **Add a tree-snapshot token.** Carry a compact published layout/tree epoch
   with indirect reservoirs, or reject current reservoirs whose producer epoch
   does not match the current published tree. Do not use `sceneRevision` alone:
   layout publication and physical scene content are different domains.
4. **Make the retry fallback visible but non-black.** For a receiver with no
   valid current sample and no valid history, preserve the last valid radiance
   when the path and scene-change bounds allow it; otherwise use an explicitly
   marked low-confidence fallback. Never give an unresolved marker a positive
   history count.
5. **Capture the green path separately.** Add an opaque debug presentation or
   controlled readback for current transport, r7 accumulation, r8 prefilter,
   each r9 iteration, and final lighting-hook input. Do this after step 2 so a
   zero-radiance history bug does not obscure the attachment result.
6. **Fix the shader preprocessor errors independently.** The repeated
   `#endif without #if` messages occur during startup and are not proven to
   cause v141, but a PR should not ship with shader variants rejected during
   pipeline creation.

## Exact v142 test run

Use a fresh world/session and record exact wall-clock timestamps in the video
or type visible chat markers before each action. Keep render scale, GI, Veil,
Sable, DH, and resolution unchanged from v141.

### Run A: no edit baseline

1. Start with the production build and no diagnostic properties.
2. Wait until the log reports `ready=true, settled=true`.
3. Hold the camera still on the white vertical wall for 30 seconds.
4. Sweep left/right for 20 seconds, then move vertically for 20 seconds.
5. Expected result: at most short noise during movement; no monotonic wall
   darkening and no camera-following formations.

### Run B: one controlled edit

1. Return to the same wall/bunker view and wait for settled=true.
2. Place exactly one opaque block, type a chat marker, and do not move the
   camera for 10 seconds.
3. Remove that same block, type a second marker, and wait 10 seconds.
4. Do not perform any other block, time, window, or shader changes.
5. Expected result: a local temporary change near the edited bounds, with
   unaffected wall regions retaining valid lighting. No full-screen green
   frame, no full-wall blackening, and no persistent black after settled=true.

### Run C: classification diagnostic

Repeat Run B with:

```text
-Dphotonics.restirGiValidityChannelsDiagnostic=true
```

The build must log that the v140 combined-GI validity channel diagnostic is
enabled. The channel meaning is red=history accepted, green=current direct,
blue=current GI, with pastel variants while unsettled. This diagnostic is not
the production look. At the failure point, report whether the affected wall
pixels show current GI present, current GI absent, or history accepted with
zero current radiance.

### Run D: no-GI control

Repeat the same edit and camera path with GI disabled but direct lighting left
on. If the black formations disappear, the defect is in the GI transport,
history, or GI denoiser path rather than the base shaderpack composition.

## v141 conclusion

The evidence is now sufficient to stop treating the issue as generic temporal
noise. The final v141 collapse is most strongly associated with a regional
scene revision and an incorrect zero-radiance/current-transport decision. The
earlier moving formations are associated with layout/streaming churn. The next
efficient step is one narrow reservoir-validity instrumentation and test, then
the smallest classification fix if the counter is observed. A broad rewrite of
spatial reuse, Sable occlusion, or GI sample counts is not justified by v141.

## v142 validity-channel diagnostic review

### Run identity

This review covers the three recordings under `screenshotd/v142` and
`logs/v142/latest.log`. The JVM property
`-Dphotonics.restirGiValidityChannelsDiagnostic=true` was applied successfully;
the log confirms it at `18:50:26.388`. The run also had combined GI enabled,
spatial reuse set to zero, the temporal upscaler disabled, and the denoiser
diagnostic path active. The validity passes ran repeatedly, so this was a
useful classification run, but it was not a production-visual run.

### Important diagnostic limitation

The current v140 channel diagnostic does not only populate the private validity
attachment. In `r7_accumulation_impl.glsl`, it replaces the RGB of
`lighting_frag_out` and zeros variance/external-lighting output before later
passes. `r7_validity_final.fsh` then reads that modified lighting attachment.
Therefore the cyan, blue, pastel, and any apparent dark regions in these
recordings are not a direct picture of normal Photonics lighting. The test can
classify validity states, but it cannot be used to judge final brightness,
shadow quality, or whether a normal-production pixel has become black.

### What the recordings actually show

The frame review found the following reliable states:

| Recording | Relative interval | Observation |
| --- | ---: | --- |
| `18-50-39` | roughly 10-60 s | Large cyan regions persist over terrain and structures. This means no accepted history with current direct and current-GI evidence. |
| `18-51-46` | roughly 10-65 s | Cyan dominates bunker walls, floor, and objects. The first frame is a menu and is not evidence. |
| `18-52-56` | roughly 15-35 s and 50-55 s | Clear blue regions appear beside cyan regions. Blue means current GI batch without current-direct evidence and without accepted history. |
| all three | changing viewpoints | Pastel cyan/blue states occur during unsettled publication periods. White and gray are ambiguous because concrete, sky, and froglights have those colors. |

No clip provides a reliable diagnostic-green or diagnostic-yellow region, and
no clip proves a diagnostic-black mask. Black outlines, held items, ordinary
shadowed geometry, cacti, and UI elements must not be counted as validity
colors. The dominant evidence is therefore **missing history**, not proven
missing GI.

### Log correlation

The log confirms that validity changes occurred while the world compiler was
actively publishing and invalidating data:

- Layout-only streaming/unload revisions repeatedly changed `layoutRevision`
  while `sceneRevision` stayed unchanged, including around `18:50:43.984`,
  `18:51:12.796`, `18:52:29.763`, and `18:53:55.055`.
- These revisions repeatedly produced `ready=true, settled=false`. Pastel
  channel colors are expected in that state, but a pixel that remains without
  history after the world settles is not expected.
- Real scene-content hash changes are also present: section non-air changes at
  `18:50:45.737`, `18:51:00.229`, `18:51:56.549`, `18:52:02.835`,
  `18:52:05.187`, `18:52:10.789`, and again from `18:53:08.150` through
  `18:53:47.451`. Some are marked as player changes and some are not.
- No Photonics shader reload or pipeline failure occurs during the recordings;
  the reloads precede them. The Veil and Distant Horizons messages do not
  provide a direct failure correlation.

### Hypothesis audit

| Hypothesis | Result from v142 | Interpretation |
| --- | --- | --- |
| Spatial reuse causes the result | Not supported | Spatial reuse was set to zero. It is not the source of this run's cyan/blue states. |
| Layout streaming/rebuild exposes invalid regions | Strongly supported | Streaming changes the published tree and marks the world unsettled even when scene content is unchanged. |
| A nonzero scene revision means the player edited a block | Rejected | The run contains real content changes, but also hash changes without the player marker. A scene revision is not an edit audit trail. |
| `worldReady` guarantees a valid stable snapshot | Rejected | `ready` and `settled` are independent. The log shows `ready=true, settled=false`. |
| Zero-weight or zero-radiance GI is treated as valid | Strong source-level bug candidate | `indirect_reservoir_has_batch` checks finite values and `total_samples > 0`, while rejected reservoirs can retain sample count after weight/color are zeroed. r7 can therefore classify a non-usable batch as current GI and skip retry. |
| Temporal history rejection/reprojection is involved | Plausible contributor | Scene epochs, camera reprojection, path validation, and changed bounds can reject history. The current diagnostic does not identify which rejection happened. |
| Veil or Distant Horizons directly causes the black output | Unproven | They may cause asynchronous section changes, but v142 has no direct Photonics error or causal log event. |
| Shader reload causes the later failures | Not supported | Reloads happen before the recordings. |
| The diagnostic output itself is contaminating the visual result | Confirmed | The diagnostic writes into the production lighting attachment and bypasses normal denoiser behavior. |

### Conclusions

1. The run confirms substantial history loss and GI-only classifications. That
   is valuable evidence for the temporal path, but it does not prove that GI
   radiance is absent or that the production image is black.
2. Layout churn and real section-content changes overlap the observations. The
   test does not isolate camera motion from compiler publication.
3. The strongest code-level defect remains the distinction between a batch that
   has a positive sample and a batch that merely has a positive sample count.
   A rejected/zero-radiance batch must not count as current transport for r7
   history decisions.
4. Increasing samples, re-enabling spatial reuse, or changing Veil occlusion
   is not justified yet. The diagnostic needs to be made non-invasive first.

### Required next patch

The next implementation should be narrow and diagnostic-only:

1. Keep `lighting_frag_out`, variance, and external lighting untouched when the
   validity diagnostic is enabled. Present the palette through a dedicated
   diagnostic output or attachment instead.
2. Add separate validity bits for `has_batch`, `has_sample` (positive weight
   and positive finite radiance), accepted path, blocked path, stale path,
   history epoch match, `worldReady`, and `worldSettled`.
3. Change r7's `has_current_transport` decision to use usable GI sample state,
   not `total_samples > 0`. Preserve a zero-contribution sample for estimator
   accounting, but do not use it to commit or darken temporal lighting.
4. Add a published layout/tree epoch to the indirect reservoir or reject a
   reservoir produced from an older tree snapshot. `sceneRevision` alone is
   insufficient because layout publication and physical scene content are
   separate domains.
5. Keep `-Dphotonics.debugSceneHashDiff=true` for the next controlled run. It
   is implemented in `ChunkCompiler` and will identify the exact scene block
   hash/state transition instead of only reporting a revision number.

### Controlled rerun after the patch

Run the same scene in four short phases, with a visible chat marker before each
phase and no other block/time/window/shader changes:

1. Production, diagnostic off: wait for `ready=true, settled=true`, hold on a
   white vertical wall for 30 seconds, then pan and move vertically. No
   monotonic darkening or camera-following formations should remain.
2. Production, diagnostic off: place one block, wait 10 seconds, remove the
   same block, wait 10 seconds. Only the edited region may temporarily change;
   unaffected wall regions must not become black.
3. Diagnostic on, plus `-Dphotonics.debugSceneHashDiff=true`: repeat phase 2.
   At the failure moment, report the channel color and whether the log shows
   `has_batch` without `has_sample`, history mismatch, blocked/stale path, or
   unsettled layout.
4. GI disabled with direct lighting still enabled: repeat the same camera path.
   If the formations disappear, the remaining defect is in GI transport/history
   rather than the base shaderpack composite.

## v143 scene-hash diagnostic review

### Run identity

The three recordings under `screenshotd/v143` were run with both
`-Dphotonics.debugSceneHashDiff=true` and
`-Dphotonics.restirGiValidityChannelsDiagnostic=true`. The log confirms both
properties. Spatial reuse was zero and the temporal upscaler was disabled.

### Hash instrumentation result

The scene-hash diagnostic worked. Every reported transition had exactly one
changed block and an old/new state. The important transitions were:

| Time | World position | Transition |
| --- | --- | --- |
| 20:59:46.293 | `(-22, 3, 53)` | air -> white concrete |
| 20:59:54.440 | `(-23, 3, 53)` | air -> white concrete |
| 20:59:54.540 | `(-23, 2, 53)` | air -> white concrete |
| 20:59:55.192 | `(-23, 2, 53)` | white concrete -> air |
| 20:59:56.341 | `(-22, 2, 53)` | air -> white concrete |
| 21:00:10.447 | `(-22, 1, 53)` | white concrete -> air |
| 21:00:10.898 | `(-22, 2, 53)` | white concrete -> air |
| 21:00:47.715 | `(-2, 2, 0)` | air -> white concrete |
| 21:00:58.003 | `(-3, 1, 1)` | air -> red stained glass |
| 21:01:01.054 | `(-3, 1, 1)` | red stained glass -> air |
| 21:01:04.306 | `(-2, 2, 0)` | white concrete -> air |
| 21:01:31.552 | `(-2, 11, 26)` | air -> white concrete |
| 21:01:39.806 | `(-2, 11, 26)` | white concrete -> air |
| 21:01:55.762 | `(73, 2, 136)` | air -> cactus |

This rules out the hypothesis that the scene revisions were only camera noise.
Several changes have `playerChanged=false`, but that only means the Photonics
player-edit marker was absent. It does not mean that the hash transition was
imaginary or that the camera caused it.

The render thread also coalesced revisions: scene revisions advanced from 2 to
5 while several compiler events arrived between render-thread publications.
That is possible with the current asynchronous compiler, but it makes history
invalidation order-dependent and should be treated as a separate stability
risk.

### Recording correlation

- Recording 1 starts during initial streaming, then shows the first concrete
  placement at approximately `+24 s` and rapid concrete edits around `+32-34 s`.
  Cyan, blue, and magenta diagnostic regions change around those edits. The
  later removals occur around `+48 s`.
- Recording 2 starts from the menu. The red stained-glass placement/removal is
  around `+19-25 s`; the later white-concrete placement/removal is around
  `+52 s` and `+61 s`. The surrounding diagnostic colors change after those
  transitions.
- Recording 3 contains no new block transition in the log during the clip.
  It still shows large magenta/cyan regions because earlier scene revisions,
  history invalidation, and layout churn remain active.

The diagnostic palette means cyan = current direct plus current GI with no
accepted history, blue = current GI without current direct or history, and
magenta = accepted history plus GI without current direct. Pastel variants mean
the world is unsettled. The recordings are therefore dominated by history
replacement/rejection states, not ordinary Photonics colors.

### Updated hypothesis ranking

1. **Diagnostic contamination remains critical.** The validity shader still
   writes palette RGB into `lighting_frag_out`, zeros external-lighting output,
   and bypasses normal denoising. v143 cannot prove that a production surface
   became black.
2. **Streaming churn is confirmed.** Layouts repeatedly enter `settled=false`
   during section unload/rebuild, including a long sequence around layouts
   63-107. `ready=true` while `settled=false` and compiled/tracked sections
   differ. This can expose temporary missing-current-GI regions.
3. **The zero-radiance batch classification remains a high-confidence source
   bug candidate.** `indirect_reservoir_has_batch` uses finite values and
   `total_samples > 0`, while rejected reservoirs can retain sample count after
   their weight and color are cleared. r7 can then suppress retry/recovery for
   a batch that contributes no usable GI.
4. **History invalidation is a real contributor.** Each content revision changes
   the GI epoch and uses regional scene-change bounds. This explains transient
   changes after edits, but not every persistent wall formation by itself.
5. **Unmarked-change provenance is unresolved.** The hash diff proves real
   transitions but does not identify whether the source was Sable, Veil,
   asynchronous world state, natural block behavior, or a delayed compiler
   snapshot.
6. Spatial reuse, temporal upscaling, shader reload during gameplay, and a
   direct Photonics pipeline exception are not supported by this run.

### Next step

Do not use another palette recording to judge final black lighting yet. First
make the diagnostic non-invasive and add independent bits for batch presence,
positive weight/radiance, accepted/blocked/stale path, history epoch match,
scene-bound rejection, `worldReady`, and `worldSettled`. Then repeat the same
test with one intentional placement/removal and a no-edit camera phase.

Expected hash behavior after that change:

- no scene-hash diff during the no-edit phase;
- one diff for each intentional placement/removal;
- no persistent invalid state outside the reported scene-change bounds after
  `ready=true, settled=true`;
- any remaining unmarked diff must be investigated as an actual block-state
  source, not dismissed as a revision counter artifact.

## v144 diagnostic isolation and usable-GI gate

The v144 source patch makes the validity-channel diagnostic non-invasive. The
`-Dphotonics.restirGiValidityChannelsDiagnostic=true` flag no longer replaces
the production r7 lighting, variance, or external-lighting outputs, and the
normal SVGF passes remain enabled. The private validity attachments now use:

- current `R`: direct-light evidence;
- current `G`: a finite GI batch with positive sample count;
- current `B`: a usable GI sample with positive weight and positive finite
  radiance;
- current `A`: bit flags for world readiness, settled layout, matching history
  epoch, scene-change bounds, reservoir load, batch/sample presence, and
  positive current energy;
- final `R/G/B`: post-r7 history acceptance, current direct evidence, and
  usable GI sample;
- final `A`: the current flags plus finite-history and accepted-history bits.

The production r7 gate now uses the usable-sample state rather than
`total_samples > 0`. A rejected or zero-radiance reservoir can still retain
its proposal count for estimator accounting, but it cannot be committed as a
black current GI sample or suppress retry/recovery.

### v144 test interpretation

Run the ordinary scene test with the validity-channel property and
`-Dphotonics.debugSceneHashDiff=true` still enabled. The screen should now show
normal lighting, including denoising; green/magenta palette frames are no
longer expected. Compare the result against the same run with both diagnostic
properties disabled:

- matching visuals means the previous palette/no-denoiser path was contaminating
  the evidence;
- remaining black formations in both runs are production GI/history defects;
- a private current `G=1, B=0` state identifies the old zero-radiance batch case;
- a private `B=0` with `ready=1, settled=1`, no scene-change bit, and no valid
  history identifies a real missing current GI sample rather than streaming
  delay;
- persistent darkening after `ready=1, settled=1` and no hash diff points to
  history/reprojection or denoiser handling, not a block edit.
