# PhotonicEngine project context — 2026-09-06

## Executive status

This checkout is a fork of Photonics being taken back to Minecraft 1.21.1 and extended toward Create Aeronautics/Sable/Veil compatibility.

- Repository: `KrzysztofKalisiak/PhotonicEngine`
- Branch: `multi-version`
- Pulled baseline: `d43f58f9` (`v149f`), parent `87defba4` (`Fix ReSTIR state routing and publication validity`)
- Current target: Minecraft 1.21.1, Fabric source/runtime
- Linux test pack: Minecraft 1.21.1 on NeoForge, using Sinytra Connector to load the Fabric Photonics jar
- Current worktree: v151 streaming-continuity changes are uncommitted and need a Linux build/test

The project is not yet upstream-equivalent, not yet multi-version, and not yet full Sable dynamic-geometry GI. It is a substantial 1.21.1 experimental renderer integration with partial moving-emitter and same-sublevel direct-visibility support.

## Worktree safety

Before future implementation work, preserve these edits:

```text
modules/core/src/main/java/at/redi2go/photonics/core/iris/extensions/RestirPipeline.java
modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/WorldCompiler.java
modules/shaders/photonics/rendering/restir/passes/r7_gi_final_state.fsh
```

The v149 candidate does three things:

1. Creates dedicated one-attachment framebuffers for `restir_gi_current_state` and `restir_gi_final_state` so shader output location 0 cannot be routed to `GL_NONE` when both logical states share an FBO.
2. Clarifies the output-location contract in the final-state shader.
3. Makes the world diagnostic use the actual `worldPublicationReady` predicate instead of a hard-coded `true` value.

The pulled v149 runtime has now been reviewed against its recording and logs. The v151 shader change below has no runtime evidence yet; do not call it v151-tested until it is built on Linux and run through the controlled test matrix.

## v149 regression found and v151 fix — 2026-09-06

The pulled v149 recording is genuinely broken. At video times 6.3–11.4 s,
15.9–16.6 s, 19.3–20.4 s, 24.5–25.2 s, 29.7–31.1 s, and 43.3–45.2 s,
large black regions or wedges appear. Each interval matches a `section-unload`
or streaming layout revision where `ready=true` but `settled=false` in
`logs/v149/latest.log`. The loaded Connector-mapped jar contains the same
shader hashes as the pulled source, so this is not a stale-package problem.

The immediate cause was in combined-GI r7 accumulation: it required a settled
GI publication before combining current lighting, and then attempted to
validate fallback history against the partially unloaded voxel tree. That left
otherwise valid receiver pixels with zero lighting during every chunk-streaming
burst. v151 now validates stored paths only against a settled tree; while the
tree is transient, it permits surface-matched history only for streaming-only
changes or receivers outside a current, known scene-edit region. Changed
regions and uncertain scene metadata still fail closed.

The same run also reports 30 `#endif without #if` preprocessor errors and very
high direct-reservoir visibility rejection. Those are tracked separately: the
preprocessor errors originate during the native `photon-main (1).zip` pipeline
creation, while the recording uses a native Photonics pack and does not expose
the offending external shader source in this repository. They must be resolved
with a captured preprocessed shader before changing the BSL patch DSL.

## Current bug-fix pass — 2026-09-06

The v148 recordings and log were reviewed with three independent subagent audits. The visible blackouts and cyan/green geometric formations overlap world-layout churn and GI diagnostic output, but the code review also found deterministic attachment-routing bugs that can corrupt the production history itself.

The working tree now adds the following fixes on top of the preserved v149 candidate:

1. Combined block-light + GI attachment order now matches the shader output locations: `restir_external_lighting` is physical slot 6 and `restir_gi_history_epoch` is slot 7. Previously those two outputs crossed.
2. Split direct source-history diagnostics now write to the optional physical slot occupied by `restir_local_lighting` (slot 6), instead of the integer history-epoch slot.
3. `WorldCompiler` rechecks live pending build/unload counts and the latest scene revision on the render thread. A newly queued Sable/Veil or scene update therefore clears `world_settled` immediately instead of waiting for a stale compiler snapshot.
4. Unsettled GI history recovery now requires a valid current voxel tree and actual stored-path validation; the old unvalidated nearest-history fallback was removed.
5. Both r8 denoiser neighborhood loops now reject unresolved r7 pixels, so retry markers cannot contribute stale variance moments.
6. `SingleFramebuffer` rejects empty, duplicate, or unknown named draw-buffer selections, making future output-location mistakes fail at framebuffer setup rather than silently selecting `GL_NONE`.

These changes are source-reviewed and statically checked, but not yet runtime-validated on the Linux test host. The Windows wrapper cannot currently obtain Gradle 9.5.1 because the corporate TLS trust path rejects the distribution certificate, so the next artifact must be built on Linux.

## What has already been built

### Port/build foundation

- Only a `1_21_1` version module exists under `modules/versions`.
- The module is Loom/Fabric based and uses Java 21 with official Mojang mappings.
- Local catalog currently pins Minecraft 1.21.1, Fabric Loader 0.18.4, Fabric API 0.116.13+1.21.1, Sodium 0.8.12 for 1.21.1, Iris 1.8.8 for 1.21.1, GLSL Transformer 3.0.0-pre3, and JCPP 1.4.14.
- There is no native NeoForge module. NeoForge testing is indirect through Connector.
- The full Gradle build on this Windows machine has previously been blocked before project compilation because Fabric Loom could not validate the Mojang version manifest through the local TLS/network path and the required manifest was not cached. Java source compilation with `javac --release 21` has been used as a partial check.

### Photonics renderer/GI

Photonics extends an Iris/Sodium deferred render path; it is not a complete replacement renderer. The active ReSTIR GI path in the local fork is a custom combined/split pipeline around `r1` through `r10`, with current/final state capture, temporal/spatial reuse, diffuse estimation, accumulation, variance, repeated A-Trous denoising, local direct lighting, and handheld composition.

Implemented safeguards include:

- world section compilation into a sparse voxel tracing tree;
- streamed revision/layout and scene-content tracking;
- regional invalidation and scene-hash diagnostics;
- explicit current-evaluation and final-publication validity bits;
- finite-batch fencing and history-promotion rules;
- path signatures and receiver-domain checks;
- motion-aware history handling;
- optional diagnostic passes and throughput/pass-timing logs.

The renderer still has important incomplete or placeholder areas, including the no-op/basic mode, unsupported voxel/block upload paths, unsupported Iris pipeline builder actions, placeholder build-time telemetry, incomplete multi-face block baking, and empty shader/development-environment TODO implementations. These should be catalogued before calling the port production-ready.

### Sable/Contraption Lights/Veil integration

The local branch has meaningful compatibility work in:

- `modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/compat/ContraptionLightsSableBridge.java`
- `modules/versions/1_21_1/common/src/main/java/at/redi2go/photonics/common/compat/SableSectionExclusion.java`
- Sable client sublevel and render-data mixins
- `modules/core/src/main/java/at/redi2go/photonics/core/rendering/sublevel/ExternalSubLevelMotion.java`
- `LevelRendererMixin` capture hooks

Current behavior:

- Reflectively discovers Sable and Contraption Lights/Veil internals when present, and fails closed when absent.
- Converts moving emissive cell positions from Sable-local coordinates to world coordinates.
- Publishes moving emitters into Photonics' external light list.
- Tracks current/previous transforms, UUID-derived identity tokens, and emitter motion.
- Uploads a coarse R8 occupancy sidecar for Sable sublevels.
- Uses conservative same-domain local DDA visibility for selected direct-light samples.
- Suppresses duplicate Veil point-light brightness when Photonics block lighting is active.
- Filters Sable-owned sections out of the static world voxel representation.

Hard limits and missing data:

- Maximum 16 sublevels and 64 emissive cells in the current sidecar path.
- No stable Sable receiver identity written at the fragment: no persistent sublevel token plus cell/face identity.
- No complete dynamic material payload for albedo, normal, roughness/specular, transmission, or emissive surface data.
- No fine geometry for fences, panes, trapdoors, partial shapes, or colored transmission.
- No authoritative world-to-Sable, Sable-to-world, or Sable-to-Sable cross-domain visibility in the GI path.
- No dynamic Sable geometry in bounced GI; current support is primarily external emitters plus partial same-sublevel direct visibility.
- Reflection targets private/internal layouts and will be version-fragile.

Veil is currently an interoperability boundary, not a Photonics geometry provider. Aeronautics runs on NeoForge and itself has Iris-related visual compatibility concerns, so full Aeronautics support requires a NeoForge Photonics/runtime layer and explicit render-order testing.

## What differs from upstream Photonics

The authoritative upstream is [Redi2Go/PhotonicEngine](https://github.com/Redi2Go/PhotonicEngine), current `multi-version` branch. Its version configuration currently includes only Minecraft 1.21.11 in [settings.gradle.kts](https://raw.githubusercontent.com/Redi2Go/PhotonicEngine/multi-version/settings.gradle.kts), with the 1.21.11 dependency catalog in [main_libs.toml](https://raw.githubusercontent.com/Redi2Go/PhotonicEngine/multi-version/modules/versions/1_21_11/main_libs.toml). The upstream binary history includes a published 1.21.11 Photonics build, but the upstream branch does not contain the local Sable/Aeronautics work.

The local branch is not a simple dependency downgrade. It diverges in:

- Minecraft/Iris/Sodium APIs and version module structure;
- `LevelRenderer.renderLevel` mixin signatures and render hooks;
- Blaze3D/OpenGL buffer, texture, sampler, and GPU-resource adapters;
- Iris pipeline/property/renderer package organization;
- mixin configuration and meshing accessors;
- ReSTIR GI pipeline shape and local validity/publication metadata;
- Sable/Veil/Aeronautics compatibility code, which has no upstream equivalent.

Upstream changes that may be useful later, but must be ported one at a time, are:

1. RNG/state-transition cleanup.
2. Texture normals for initial GI and sky endpoints.
3. Normal-ratio/Jacobian bias improvements.
4. Visibility-history ideas after local validity/publication is proven.
5. Fast-history buffer after correctness and reprojection are stable.

Do not replace the local GI pipeline wholesale with upstream `gi0`–`gi3`/SVGF code. The local branch has extra state and Sable-domain contracts that upstream does not know about. Relevant upstream references are [the ReSTIR GI bias changes](https://github.com/Redi2Go/PhotonicEngine/commit/bf4ff6e150ec57fb47035a593c888455b3d6c7e1), [the texture-normal/sky-hit fix](https://github.com/Redi2Go/PhotonicEngine/commit/54e049be12cd1c762c772cb09fd6861ecdda9be5), and the current [upstream GI initial pass](https://raw.githubusercontent.com/Redi2Go/PhotonicEngine/multi-version/modules/shaders/photonics/rendering/restir/indirect/passes/gi0_initial_indirect.fsh).

## Evidence review: v145–v148

### Runtime facts

The v148 Linux run started and shut down normally. The pack reported Minecraft 1.21.1, NeoForge 21.1.235, Connector, Java 21, AMD RX 7900 GRE/Mesa, Iris NeoForge, Sable 2.0.3, Veil 4.2.1, Contraption Lights, and Create Aeronautics. The native Fabric Photonics jar was skipped by NeoForge and the Connector-mapped jar was loaded, confirming an indirect compatibility route rather than native NeoForge support.

At roughly 1559×817, the run reported about 59 FPS and approximately 16.8–16.9 ms frame time. Photonics passes executed; there was no Photonics crash, OOM, or failed GPU allocation.

The world compiler did reach an initial settled state after streaming thousands of sections. However, later camera movement caused repeated section unload/rebuild churn. The last v148 world-tracing event was still unsettled, with 53 pending builds. `ready=true` frequently coexisted with `settled=false`; this is a major test confounder and explains why the run cannot establish a stable final visual verdict.

The log contains real scene transitions: block removal/addition, white-concrete edits, cactus placement, scene revisions 1–8, and many layout-only streaming changes. Pipeline resets were associated with startup/reload, not with the later block edits.

### Visual evidence

The v148 recordings show periods of normal bright geometry interleaved with near-black frames, cyan/green striped formations, and noisy green/teal regions. The run enabled `-Dphotonics.restirGiValidityChannelsDiagnostic=true`, so the palette-like green/cyan imagery is diagnostic output and cannot be treated as production rendering. There is no clean diagnostic-free v148 acceptance recording.

Earlier evidence establishes the recurring symptom more clearly:

- v137: camera-following black formations, progressive stationary wall darkening, a one-frame green whole-scene flash, and severe but temporary corruption after edits.
- v145: a block removal produced an approximately 86% black frame, followed by noisy dark recovery; later camera motion produced isolated formations without an edit.
- v146: camera-edge formations preceded the edit and followed camera movement; the edit caused noise/near-black followed by recovery.
- v147/v148: state-token/current-state fencing was implemented, but the available runs do not prove that the visual regression is fixed.

The most likely issue family is temporal-GI/publication correctness—not hardware failure: invalid or unavailable current data, stale/rejected history, attachment/state publication mismatch, or a global settled gate interacting badly with streamed layout churn. This is a diagnosis, not yet a proven single root cause.

Separate log cleanliness issues include repeated `#endif without #if` preprocessor diagnostics during pipeline creation/reload. They did not prevent startup in these runs, but their source/variant must be traced and eliminated. Simurail resource errors, Realms HTTP failures, model warnings, and generic Sodium messages are unrelated until proven otherwise.

Evidence files: [v145 review](V145_EVIDENCE_REVIEW_2026-08-29.md), [v147/v148 plan](V147_GI_PATCH_PLAN_2026-08-30.md), [temporal artifact review](GI_TEMPORAL_ARTIFACTS_SECOND_PASS.md), [v148 log](logs/v148/latest.log), and [v148 recordings](screenshotd/v148/).

## Prioritized plan

### Phase 0 — establish reproducible baselines

1. Preserve the current worktree patch.
2. Build and record the current candidate on Linux; record jar SHA-256, commit SHA, loader, mod versions, JVM, GPU/driver, shaderpack, flags, and resolution.
3. Run one diagnostic-free production capture and one diagnostic capture; never use a diagnostic palette as the production verdict.
4. Trace the `#endif without #if` errors to the exact transformed shader/variant.
5. Add a short machine-readable run summary so logs can be compared without reconstructing flags by hand.

### Phase 1 — prove GI publication correctness

1. Test the uncommitted dedicated-FBO v149 patch first; it directly addresses the current/final attachment routing risk.
2. Add explicit frame-level publication generation/token logging: source layout revision, scene revision, current-state generation, final-state generation, history generation, pending builds/unloads, and whether the displayed frame came from current, history, or fallback.
3. Test a fixed, fully loaded room with no chunk-boundary crossing; then test one edit; then test camera streaming. Do not combine all three in one capture.
4. Verify three distinct states remain distinct: not evaluated, evaluated zero, and valid nonzero/zero contribution.
5. Only after this passes, reintroduce upstream GI changes one commit/idea per build.

### Phase 2 — make the port genuinely multi-version

1. Keep shared renderer/shader logic version-neutral.
2. Add a separate 1.21.11 adapter module while retaining 1.21.1.
3. Port GPU resource, sampler, buffer, render-hook, meshing, and Iris API adapters deliberately; do not cherry-pick the upstream tree as a single merge.
4. Add a build matrix for both targets and, later, native NeoForge if Aeronautics support is a release requirement.

### Phase 3 — replace the Sable sidecar with explicit dynamic scene data

1. Introduce a versioned compatibility adapter, preferably using supported Sable Companion APIs where available, with reflection isolated behind the adapter.
2. Publish a stable receiver token plus local cell/face identity while Sable renders; retain geometric fallback only as a diagnostic path.
3. Replace fixed 16-sublevel/64-cell caps with a dynamic SSBO/texture-buffer table containing identity, topology generation, transforms, world AABB, atlas offset, and emissive ranges.
4. Add topology-change and visibility-transition invalidation at sublevel granularity.

### Phase 4 — implement cross-domain visibility

Start with full-block conservative geometry:

- world → Sable;
- Sable → world;
- Sable → Sable;
- moving Sable → moving Sable.

Use world static traversal plus AABB broad phase, finite-segment interval conversion into Sable local space, and local DDA. Apply this to the selected ReSTIR sample first. Add fine shapes/transmission only after conservative visibility is stable.

### Phase 5 — dynamic bounced GI/materials

Add Sable-local surface identity, material/albedo/normal/roughness/transmission data, moving-hit reprojection, path signatures, and cross-domain history provenance. This is the point at which “full Sable support” becomes meaningful for indirect lighting; it is not achievable by adding more point lights to the current bridge.

### Phase 6 — upstream quality work

Port RNG, texture normals, Jacobian/normal weighting, visibility history, and fast history as isolated experiments with one build per change. Preserve all local scene/layout/Sable validity contracts while doing so.

## Required acceptance matrix

Every candidate build should include at least:

1. Static world, fixed camera, fully loaded room, 15 seconds.
2. Slow camera rotation without crossing a section boundary, 15 seconds.
3. One block break and one block place, with 10 seconds after each.
4. Camera translation across a known streaming boundary.
5. Stationary Sable emitter/receiver/occluder in one sublevel.
6. Rigidly moving Sable sublevel with same-token history.
7. World↔Sable and Sable↔Sable visibility.
8. Two sublevels, then more than 16 sublevels to verify removal of the current cap.
9. Clean production run plus diagnostic run.

The log contract should expose: pipeline mode, shader flags, layout/scene revisions, compiled/tracked/built/unloaded/pending counts, ready/settled/publication predicates, GI state bits, history sample counts, reuse accept/reject counts, selected visibility domain, pass timings, and exact runtime hashes.

## Immediate next action

The safest next implementation step is to build/test the current candidate on the Linux machine, using the Phase 1 controlled room/edit captures with diagnostics disabled first. If the black/publication artifact is gone, retain the patch and add publication-generation instrumentation next. If not, the new logs should identify whether the remaining failure is current-state production, final-state routing, history promotion, or streaming settlement. Cross-domain Sable geometry should wait until this GI contract is measurable and stable.
