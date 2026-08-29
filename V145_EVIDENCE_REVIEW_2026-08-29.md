# v145 Evidence Review

Date: 2026-08-29

## Inputs

- Log: `logs/v145/latest.log`
- Recording: `screenshotd/v145/Screencast From 2026-08-29 11-36-59.mp4`
- Build/branch: `v145f`, commit `9e6bea66`
- Runtime flags confirmed by the log: `restirGiValidityChannelsDiagnostic=true` and `debugSceneHashDiff=true`

The recording is approximately 72.93 seconds long. The log starts at 11:36:11 local time and the recording begins during the second Photonics pipeline creation at approximately 11:36:43-44. The video timestamp and log timestamp are therefore aligned to within roughly one second.

## Observed Timeline

| Runtime event | Log evidence | Recording evidence | Interpretation |
| --- | --- | --- | --- |
| Initial world population | Layouts 0 through 43 settle at 11:36:44-51 | Wall is initially pale and stable | Baseline is usable before the edit. |
| First block removal | At 11:37:26.190, one white-concrete block changes to air at world `(-1,8,27)`; `sceneRevision=1`; layout 56 | Near-total blackout at video `26.486s` (`pblack` about 86%), followed by noisy dark recovery | Decisive edit-triggered failure. This is not ordinary right-facing shadowing. |
| First recovery | Layouts 60-61 rebuild and settle at 11:37:27-29 | Noise and dark formations remain briefly, then reduce | Consistent with regional history invalidation plus current GI rebuilding. |
| Camera streaming | Section unload/rebuild cycles continue through layouts 62-81, 11:37:30-48 | Isolated formations appear or are exposed as the camera moves near the wall | Streaming/republication is a second trigger or amplifier; no corresponding block edit is logged. |
| Second block placement | At 11:37:58.938, the same block changes from air to white concrete; `sceneRevision=2`; layout 82 | Noise/reconstruction returns around the later edit window and recovers over the following seconds | The second edit repeats the rebuild behavior. Hash detection works even though `playerChanged=false`. |
| Second recovery | Layout 83 settles at 11:38:01; layout 87 settles at 11:38:05 | Output becomes more stable after the rebuild window | Recovery is delayed, not permanently stuck in this run. |

Only two content edits are logged. The many later visual formations are not evidence of many hidden block edits; they coincide with repeated `section-unload` and `streaming-or-rebuild` transitions.

## What The Diagnostics Establish

- Direct-reservoir diagnostics report `invalid=0` throughout the sampled intervals. This does not prove GI is valid, because those counters are for direct-light reservoirs rather than the full GI path.
- `world_ready=true` remains true during the transitions, while `world_settled` changes to false during rebuilds. Readiness therefore does not mean that stable GI history is available.
- The combined pipeline is active. In combined mode, `giDenoiserPasses=0` does not mean that GI skips denoising; the shared `restirDenoiser=5` chain runs after the combined GI result.
- The validity-channel diagnostic uses private attachments. Its green/debug coloring is not the production lighting output and is not itself the cause of the black frame.
- Startup shader compilation logs repeated `#endif without #if` preprocessor errors. They occur during pipeline creation, not at the block edits. The pipeline still starts and renders, so this is a separate shader-variant correctness issue that should be fixed or explained, but the v145 evidence does not prove that it caused the edit blackout.
- The process shuts down normally. There is no Photonics exception, JVM crash, or out-of-memory event in this run.

## Likely Failure Path

The current v145 design deliberately rejects old GI history for receivers affected by a regional scene change. During the first edit, the changed section is `(-1,0,1)`, and the wall test is in or adjacent to that region. The r7 accumulation pass can then have all of the following at once:

1. Previous GI history rejected because its regional epoch/path is stale.
2. No usable current indirect sample while the changed section is being rebuilt or published.
3. No direct contribution on the shadowed wall to provide a nonzero fallback.
4. Accumulation output initialized to zero and marked for a retry.

That fail-closed path prevents stale light from being reused, but it exposes zero radiance as black for at least a frame. The subsequent salt-and-pepper formations are consistent with partially available current GI and sparse valid neighboring history while the section and denoiser recover. The repeated streaming cycles can recreate the same condition without another edit.

This explains the timing and the persistence better than a theory of repeated invalidation loops: the log has only two scene revisions. It remains a hypothesis until per-pixel GI validity counts confirm whether the affected pixels are failing at history, current transport, or denoiser acceptance.

## Next Test

Run the same controlled scenario twice from a fresh world/session:

1. Baseline with both diagnostic system properties absent.
2. Diagnostic run with `-Dphotonics.restirGiValidityChannelsDiagnostic=true -Dphotonics.debugSceneHashDiff=true`.
3. Let the initial wall settle before editing.
4. Record timestamps for: first view, block removal, first blackout, movement left/right, block placement, and rejoin.
5. Do not add extra blocks or travel far enough to introduce a different test area.

The build should expose or log GI-specific counters for the affected region: current reservoir loaded, usable current transport, history accepted/rejected, scene-change receiver rejection, zero-radiance batch, recovered history, retry, and r7/r8/r9 valid/invalid counts. Existing direct-reservoir counters cannot answer this question.

## Candidate Fix Order

1. Fix the zero-radiance presentation during a regional edit: preserve a bounded previous frame or use a direct-only/stable fallback while the new GI batch is unavailable, while keeping the stale history ineligible for future reuse.
2. Verify that ordinary section streaming does not globally gate or unnecessarily discard valid receiver history. Compare the actual shader uniforms/epochs against the CPU layout transitions.
3. Add the GI counters above and validate the first two items with a fixed camera before changing estimator math.
4. Only after the validity path is understood, A/B the upstream RNG/direction changes and texture-normal input as separate experiments. Do not wholesale import upstream's old four-pass GI/SVGF pipeline into v145.
5. Investigate the startup `#endif` preprocessor diagnostics with source/include provenance and a captured preprocessed shader variant.

## Expected Result

For this simple wall test, moving the camera or waiting for streaming should not create detached black formations on otherwise visible geometry. A block edit may cause a short, bounded noisy transition while GI rebuilds, but it should not produce an 86%-black frame or expose zero radiance where direct sunlight or a stable fallback is available. A dark face that remains spatially attached to the wall and changes smoothly with orientation is expected; the observed edit-synchronous blackout and moving formations are not.
