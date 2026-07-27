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

Use the v88 upscaler jar for source scales `0.67` and `0.75`. The v87 jar has
a known split-GI sampler redeclaration and cannot load those configurations.

Then use:

```text
-Dphotonics.giRenderScaleOverride=0.5
-Dphotonics.temporalUpscalerOverride=true
-Dphotonics.temporalUpscalerSourceScaleOverride=0.67
-Dphotonics.temporalUpscalerHistoryFramesOverride=8
```

1. Compare FPS and visual stability at source scales `0.50`, `0.67`, and
   `0.75`; restart between values.
2. Resize the window repeatedly, reload the shaderpack, change dimensions, and
   rejoin the world. Cleared history must prevent a horizon line or stale frame.
3. Test positive and negative world coordinates near 32, 64, 128, 256, and 512
   blocks from the camera.
4. Inspect thin parallel surfaces, fences, leaves, diagonal normals, silhouettes,
   and newly revealed regions for wrong-surface light or ghosting.
5. Repeat with 0, 1, 4, and, if practical, 16 Sable sublevels. Record GPU time
   because full-resolution Sable receiver classification is part of this pass.
6. Move a lit contraption quickly and vertically. Its receiver identity must not
   leak history into the static world or another sublevel.

The branch reconstructs Photonics lighting only. It is not FSR, does not upscale
the shaderpack color/post pipeline, and does not temporally reconstruct
handheld lighting.

## Test D: Sable Occlusion

Use `photonics-v87-sable-occlusion-mc1.21.1.jar`. The branch is active when
the compatible Contraption Lights/Sable bridge is present; no new JVM option
is required.

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

Cross-sublevel direct occlusion, world-to-Sable occlusion, Sable-to-world
occlusion, and Sable geometry in bounced GI are outside the first Sable branch.

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
