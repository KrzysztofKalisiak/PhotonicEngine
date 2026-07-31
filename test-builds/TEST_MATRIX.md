# Photonics 1.21.1 Experimental Build Matrix

These jars are intentionally separate. Test only one Photonics jar at a time.
Keep the Minecraft instance, Photon settings, mod set, world, route, resolution,
and JVM arguments unchanged unless a test explicitly changes one of them.
Restart Minecraft between JVM-argument changes.

## Builds

| Jar | Branch / commit | Purpose | SHA-256 |
| --- | --- | --- | --- |
| `photonics-v86-mc1.21.1.jar` | `multi-version` / `778cbe31` | Isolates compiler recovery and deterministic thin-cutout transport fixes | `B25C663D2BEF30E88179C41539781CED6E98F271335311A2DC1B2867D0539F80` |
| `photonics-v87-restir-gi-mc1.21.1.jar` | `multi-version` / `956b4ed3` | Completed ReSTIR GI validation and the v86 fixes; use as the functional baseline | `A3A483777D6D8EDEDF4195DFEE045CB4D75E9764B57F2F8B33B487854C7B6D1D` |
| `photonics-v87-performance-mc1.21.1.jar` | `experiment/photonics-performance` / `06c81b9d` | Exact compact SVGF plus an optional adaptive denoiser experiment | `174657A88E2BCF0261D3B620EFAA73C70DE398F28363F1D05AB02EBF5200EAEC` |
| `photonics-v87-temporal-upscaler-mc1.21.1.jar` | `feature/photonics-temporal-upscaler` / `b284e632` | Optional Photonics-only temporal lighting reconstruction | `8A08AB62500E8EE5FC118712DB5A05E9374CDF8F8121C1A152172A2813EA98ED` |
| `photonics-v87-sable-occlusion-mc1.21.1.jar` | `feature/sable-occlusion` / `45607cfe` | Same-sublevel Sable direct occlusion using bounded local voxel shapes | `EDA4E54B210D5E21721657F28C16617D6CAE85C39CFD37C48CC759CA7FDFD127` |
| `photonics-v88-light-list-stability-mc1.21.1.jar` | `multi-version` / `22c9f03e` | Coalesces section-driven light-list changes and makes capped selection deterministic | `232C83C6FEE7DE66BF719C1F7025B229AB30161565D5C835D33654C0F82298C2` |
| `photonics-v88-temporal-upscaler-mc1.21.1.jar` | `fix/v88-temporal-upscaler` / `0bcefa03` | Fixes split-GI sampler compilation and includes v88 light-list stabilization | `A4CE660F33697B9A70D192C142F6733286273CD7B192F5C0C44C6705EBC25625` |
| `photonics-v88-sable-occlusion-mc1.21.1.jar` | `fix/v88-sable-occlusion` / `3b081fb8` | Same-sublevel Sable occlusion plus v88 light-list stabilization and ownership documentation | `3E9C605A892A16E854CF1C868554962A6BCCBA238982DB6C35E802068EA25996` |
| `photonics-v91-upscaler-firefly-stability-mc1.21.1.jar` | `fix/v91-temporal-upscaler-fireflies` / `248c68cd` | Rejects incoherent sparse bright samples without slowing coherent lighting changes | `39AD4C23884AE83F3AB11158F88F199BF8B7DD755C1B66E01C56386B583E565C` |
| `photonics-v92-upscaler-variance-gate-mc1.21.1.jar` | `fix/v92-temporal-upscaler-variance-gate` / `96db8fec` | Makes temporal variance authoritative when denoising spreads a stochastic event across agreeing source taps | `79B68C269937FC44DA526380727F3DDB8C1790C5F3737DA72066D3AE14229A6F` |
| `photonics-v93-upscaler-bootstrap-fireflies-mc1.21.1.jar` | `fix/v93-temporal-upscaler-bootstrap-fireflies` / `167bfa5d` | Bounds low-confidence positive spikes when geometric reprojection history is unavailable and stabilizes young history | `29B22E7FD1A9A951ACC4F0A09C2227FEC98AAE31D3CFD98969A27D13C9F9B739` |
| `photonics-v94-upscaler-radiance-step-limit-mc1.21.1.jar` | `fix/v94-temporal-upscaler-radiance-step-limit` / `c9b81fe7` | Bounds the final positive HDR radiance step so extreme samples cannot bypass temporal weighting or misleading confidence | `53801555AD980620FB6E6FCBA908250DA93F933230532BE3F6AF44996FF265F9` |
| `photonics-v95-upscaler-time-normalized-fireflies-mc1.21.1.jar` | `fix/v95-temporal-upscaler-time-normalized-fireflies` / `c592487e` | Makes the positive HDR growth bound depend on real elapsed time instead of render-frame count | `29E02228F318650D40AAE9E1BFC94E50F2EAFD707349ED286E76FD5B668B9041` |
| `photonics-v96-upscaler-motion-aware-fireflies-mc1.21.1.jar` | `fix/v96-temporal-upscaler-motion-aware-fireflies` / `bdf876f2` | Restricts HDR firefly limiting to stable reprojected receivers so camera motion cannot create half-resolution dark bands | `1438DE6BF0388614DBB7993B024C8313CA276DC8E94C136FE29D10EA13EDD587` |
| `photonics-v97-upscaler-reprojected-fireflies-mc1.21.1.jar` | `fix/v97-temporal-upscaler-reprojected-fireflies` / `128363ab` | Restores confidence-driven firefly rejection during motion and recovers validated history for distant and thin geometry | `7FA999939852DEA70B9E3F8DDC96BD56E330BB91CBF6B023A43BE8F173F4B3CE` |
| `photonics-v98-upscaler-split-screen-diagnostic-mc1.21.1.jar` | `fix/v98-temporal-upscaler-split-screen` / `b172c235` | Debug-only four-quadrant capture of temporal source, pre-limiter resolve, state, and production output | `9EB27AB3D7F2B7868925B10E4DA08555408958153E3C5AC20C9DE82D646046F8` |

## Capture Rules

For each run:

1. Save `latest.log`, `debug.log`, and the launcher log.
2. Record the F3 screen and a fixed 30-60 second camera route.
3. Include at least 20 seconds after world entry before judging steady state.
4. Record whether Photonics was enabled before world entry or after loading.
5. Record average FPS only after shader compilation and world loading settle.

## Test A: v87 GI Baseline

Use `photonics-v87-restir-gi-mc1.21.1.jar` with no new JVM flags.

1. Enter with Photonics already enabled. Direct shadows must appear on the first
   entry, without leaving and rejoining.
2. Leave and rejoin the same world three times. Then toggle Photonics off and
   on once after the world is loaded.
3. At night, test a froglight, a handheld light, colored glass, a fence,
   trapdoor, flower, grass, and leaves. Look for isolated bright points,
   diagonal streaks, black block joints, or shadows that fail to initialize.
4. Repeat under a roof and in an enclosed white-concrete room so sunlight does
   not hide direct-light failures.
5. At day/night transition, inspect GI around vegetation, thin cutouts, tinted
   glass, and newly revealed surfaces while moving and turning quickly.
6. Move a lit Sable contraption vertically and horizontally. This baseline must
   retain the previously fixed receiver-relative stability.

## Test B: Performance Branch

Run the same route after a full restart for each configuration.

Baseline path:

```text
-Dphotonics.performance.compactSvgf=false
-Dphotonics.performance.adaptiveDenoiser=false
-Dphotonics.performance.passTiming=true
```

Exact compact SVGF, which is the branch default:

```text
-Dphotonics.performance.compactSvgf=true
-Dphotonics.performance.adaptiveDenoiser=false
-Dphotonics.performance.passTiming=true
```

Optional adaptive filtering:

```text
-Dphotonics.performance.compactSvgf=true
-Dphotonics.performance.adaptiveDenoiser=true
-Dphotonics.performance.adaptiveVarianceThreshold=0.0025
-Dphotonics.performance.passTiming=true
```

For the final throughput-only run, set
`-Dphotonics.performance.passTiming=false`. Compare median FPS and the logged
direct/GI variance and A-trous GPU timings across at least six stable
five-second windows. Compact SVGF should not visibly change output. Treat
pumping, unresolved noise, or slower disocclusion as an adaptive-filter defect.

## Test C: Temporal Lighting Upscaler

First run the upscaler jar without flags. It is disabled by default and should
match the v87 GI baseline.

Use `photonics-v97-upscaler-reprojected-fireflies-mc1.21.1.jar`. V91-v93
reduced suspicious sample weight, gated confidence, and closed invalid-history
bypasses. V94 bounded the final positive history-relative radiance step, but did
so per render frame. The focused v94 capture ran at roughly 160-250 FPS while
recording at 23.6 FPS. A source burst therefore survived 7-10 render frames
inside one recorded frame, compounding the v94 allowance by roughly 4.8x-9.3x.
V95 uses Iris's real `frameTime` and a fixed log-luminance growth rate. A
persistent real `0 -> 10` change reaches full value in approximately 0.60
seconds at 30, 60, 180, or 250 FPS; negative changes remain immediate.

V95 also exposed an asymmetric-motion regression. A darker source-grid phase
was accepted immediately while the next brighter phase was rate-limited. During
camera movement this produced broad camera-relative strips and half-block dark
regions, especially at source scale `0.50`. Pixels without geometric history
also compared against an unrelated previous value at the same screen position.
V96 starts those pixels from current lighting and applies the positive HDR
bound only to mature, well-supported history moving below the configured
output-pixel-per-second threshold.

The v96 feedback confirmed that the broad moving-camera regions were removed,
but exposed two remaining bypasses. Gating the complete HDR limiter also
disabled its confidence-driven component during motion, allowing uncertain
positive samples through. Distant terrain, vegetation, and silhouettes could
also miss all four bilinear history taps and restart from raw source radiance.
V97 keeps only the confidence-independent hard bound behind the motion gate,
adds a geometry-validated nearest-compatible 3-by-3 history recovery, and uses
the reprojected history neighborhood only as a one-sided bootstrap envelope
when no compatible geometry exists.

V98 does not change the temporal algorithm. Start it with:

```text
-Dphotonics.temporalUpscalerSplitScreen=true
```

The quadrants preserve the original screen UV: top-left/cyan is the current
low-resolution composed source, top-right/blue is the temporal resolve before
the final limiter, bottom-left/yellow is history/limiter state, and
bottom-right/white is the exact production temporal output. Record 20 seconds
stationary and 10 seconds of slow panning for both the moonlit mountain and the
village. Keep the relevant terrain and lights crossing quadrant boundaries.

In the state quadrant, blue means no receiver reprojection, red means
reprojection without geometric history or an envelope, orange means envelope
bootstrap, yellow means 3-by-3 history fallback, and green means bilinear
history. Brighter color means higher source confidence, magenta means limiter
activation, and white means the limiter materially changed the output. A flash
starting in the top-left quadrant is upstream of reconstruction; one starting
only in the top-right is produced during temporal resolve; one visible only in
the bottom-right is produced by the final limiter or final temporal sampling.

For focused v97 acceptance, first repeat both v96 captures without changing
the world, shader settings, resolution, or route:

1. Hold the distant moonlit mountain view still for at least 15 seconds, then
   pan slowly across it. Snow and terrain must not show isolated bright pulses
   or persistent brightness plateaus.
2. Repeat the village route around direct light sources, including foliage,
   roof edges, walls, and dark ground. Inspect the recording frame by frame for
   one- or two-pixel white/yellow bursts.
3. Orbit and turn rapidly in both scenes. V95-style broad horizontal,
   rectangular, or half-screen dark regions must not return.
4. Reveal a genuinely bright surface from behind an edge and place a light
   while moving. It may ramp for a small fraction of a second when evidence is
   sparse, but it must not remain dark, trail in screen space, or flash white.

Then use:

```text
-Dphotonics.giRenderScaleOverride=0.5
-Dphotonics.temporalUpscalerOverride=true
-Dphotonics.temporalUpscalerSourceScaleOverride=0.67
-Dphotonics.temporalUpscalerHistoryFramesOverride=8
```

1. Start at source scale `0.50`. Verify the effective value in the `Photonics
   pipeline for` log line rather than relying on the folder name.
2. Reproduce the v91 distant-light view. After `Photonics world tracing`
   reports `settled=true`, hold the camera completely still for 15 seconds.
   Inspect normal playback and individual frames. Distant surfaces must not
   breathe, and isolated one-frame bright one- or two-pixel points must not
   appear.
3. Compare individual frames around 3.00, 3.17, 3.50, 3.84, 4.86, and 5.87
   seconds with the v93 recording. Check foliage, dark ground, wall faces, and
   distant surfaces rather than only the bright emitters.
4. Reproduce the stationary `128x128` distant crop from v94 for at least 15
   seconds. In v94, strong bursts occur around 0.89, 3.23, 4.80, 5.31, 5.77,
   7.13, 8.75, 9.30, 11.80, and 13.03 seconds. Equivalent wall and ground points
   must no longer jump from roughly `35` to `140-160` for one recorded frame.
5. Repeat the crop once with FPS capped near 60 and once uncapped above 160.
   Flash amplitude and real-light convergence time must not increase with FPS.
   Unchanged flashes in both runs would place the remaining effect downstream
   of `photonics_temporal_lighting`.
6. Reproduce the v95 flat-world source-scale `0.50` scene and orbit its two
   lights continuously at both capped and uncapped FPS. Ground and wall
   brightness must remain attached to world geometry. Broad rectangular,
   horizontal, or half-block dark bands must not appear, and stopping the
   camera must not reveal a delayed bright recovery across the view.
7. Wave or orbit leaves, grass, flowers, fences, and roof edges near a direct
   light. Then reveal a brightly lit surface slowly from behind an edge and
   repeat with a rapid camera turn. A genuinely new bright surface may be
   conservative for one rendered frame, but it must recover immediately
   without a compact white or yellow flash.
8. Occlude the same surface and remove its light. The prior screen-space value
   must not leak onto the newly dark surface or leave a lighting trail.
9. Compare the fixed-camera recording directly with v91 and v93. Pay particular
   attention around 1.2, 8.5, and 12.6 seconds, where the v91 capture changed
   brightness in coherent steps. V97 should retain v92's smoothing of those
   source-estimator excursions rather than treating tap agreement as proof of
   stability.
10. Approach the same lights slowly. The rejection must remain stable as a light
   transitions from one compatible source tap to several taps.
11. Place and remove both a nearby light and a tiny distant light while the
    camera is still. A coherent nearby change should react immediately. A
    distant sparse light may ramp briefly, but it must converge within roughly
    one second and must not remain incorrectly dim. Removing or occluding either
    light must remain immediate because the v97 bound is positive-only.
12. Cross the same section boundary slowly and quickly. New compiler revisions
    must not produce a whole-view brightness pulse.
13. Place and remove a full block next to a light while the camera is still.
    The affected pixels must react locally without preserving the obsolete
    shadow or resetting unrelated parts of the view.
14. Repeat at source scales `0.67` and `0.75`; restart between values.
15. Resize the window repeatedly, reload the shaderpack, change dimensions, and
    rejoin the world. Cleared history must prevent a horizon line or stale frame.
16. Test positive and negative world coordinates near 32, 64, 128, 256, and
    512 blocks from the camera.
17. Repeat with 0, 1, 4, and, if practical, 16 Sable sublevels. Record GPU time
    because full-resolution Sable receiver classification is part of this pass.
18. Move a lit contraption quickly and vertically. Its receiver identity must
    not leak history into the static world or another sublevel.

The branch reconstructs Photonics lighting only. It is not FSR, does not upscale
the shaderpack color/post pipeline, and does not temporally reconstruct
handheld lighting.

## Test D: Sable Occlusion

Use `photonics-v89-sable-filtered-occlusion-mc1.21.1.jar`. The branch is active
when the compatible Contraption Lights/Sable bridge is present; no new JVM
option is required.

Required cases:

1. A wall-mounted Sable light with a fence, pane, trapdoor, flower, full block,
   and multi-box shape directly against the light.
2. Static and moving same-sublevel receiver/occluder/light combinations,
   including vertical motion.
3. Add and remove geometry from one sublevel while another remains unchanged.
   Check both the edit-frame hitch and history reset scope.
4. Dense partial geometry and long diagonal rays. Watch for a GPU-time spike or
   a fail-open light leak when the per-ray shape budget is reached.
5. Atlas and shape-table budget exhaustion, overlapping omitted sublevel bounds,
   and world reload/rejoin.
6. Repeat the fence, red stained-glass, and moving-wall cases with the shader
   setting `photonics.restirSoftShadows` first `false`, then `true`. The first
   run still uses exact center-light visibility but now shares ReSTIR
   accumulation/SVGF. The second also samples the emitter area in Sable-local
   space.

Cross-sublevel direct occlusion, world-to-Sable occlusion, Sable-to-world
occlusion, and Sable geometry in bounced GI are outside the first Sable branch.

## Repeatable Performance Route

Do not compare FPS from world-entry build-up or from different camera paths.
For every jar and parameter set:

1. Use the same save, dimension, time, weather, render distance, resolution,
   shader options, and mod set.
2. Wait for `settled=true`, then hold the camera still for another five
   seconds.
3. Record three separate 30-second phases: stationary, rotation without
   crossing a section boundary, and traversal across the same section
   boundaries.
4. Run each phase three times after one warm-up pass. Report median frame time
   and 1% low, not only the F3 instantaneous FPS.
5. Keep screen recording either enabled for every comparison or disabled for
   every comparison. Record its resolution and codec.

## Test E: Light-List Stability

Use `photonics-v88-light-list-stability-mc1.21.1.jar` without the temporal
upscaler or Sable-occlusion branch.

1. Enter the same village route used for the GI recording and wait for the
   `Photonics world tracing` log to report `settled=true`.
2. Hold the camera still for 10 seconds, then cross one 16-block section
   boundary slowly and return across it.
3. Repeat while moving quickly. Include the F3 coordinates and keep the same
   time of day, render distance, and light-count settings.
4. Confirm that the log contains `Photonics light-list publication v88`
   entries. Report their `coalescedBatches`, `remainingSections`, and
   `deferredMs` values alongside any visible pulse.
5. Test once with no Sable contraption, then once with one moving Sable light.
   This distinguishes static section-list churn from external-light updates.

## Reporting

Name each result with the jar and test, for example
`v87-upscaler-067-test-c3`. Include the JVM line, resolution, average FPS, log
set, and recording. A clean visual run without its exact configuration is not
enough to compare branches.
