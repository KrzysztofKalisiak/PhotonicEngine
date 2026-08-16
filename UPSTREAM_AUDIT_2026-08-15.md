# Upstream change audit for GPT-5.6 Luna

## Task boundary

This document is an analysis and implementation handoff. No upstream change has been merged, cherry-picked, or implemented.

Repository state when audited:

- Fork: `KrzysztofKalisiak/PhotonicEngine`
- Branch/HEAD: `multi-version` at `b0a921f7` (`v135f`)
- Canonical upstream: `Redi2Go/PhotonicEngine`
- Audited upstream branch/tip: `multi-version` at `3c5b91fd`
- Worktree was clean before this report was added.
- Upstream was fetched into `refs/remotes/audit-upstream/*`; no permanent `upstream` remote was configured.

## Date correction

There were no upstream commits on August 14 or August 15, 2026 at audit time. The latest upstream batch was committed on August 13, 2026 in CDT (`-0500`), ending at 13:50 CDT (20:50 CEST/Poland). This is probably the batch the user meant by “yesterday.”

## Executive recommendation

Do **not** merge upstream `multi-version`, and do **not** cherry-pick the August 13 series wholesale. The histories have no usable merge base and have diverged heavily. The fork is still on the Minecraft 1.21.1 architecture and contains extensive newer custom work (Sable integration, temporal upscaler, streaming/GI stability, ReSTIR diagnostics and fixes). Upstream's large `iris-overhaul` series restructures the core API/property/pipeline architecture and is effectively a different platform direction.

Treat upstream shader commits as a list of ideas to verify against the fork. Most are already superseded or implemented equivalently. Only a few deserve focused visual/performance experiments.

## Classification summary

| Priority | Upstream area | Verdict for this fork |
|---|---|---|
| P0 | Soft shadows disabled fix (`46f16590` / duplicate `d39c1bd5`) | Already addressed equivalently; verify only |
| P1 | ReSTIR GI directional bias (`27e1ab65`) | Potentially useful algorithmic idea; port only after inspecting the fork's newer RNG implementation |
| P1 | Fast history buffer (`0f820e7b`) | Potential performance win; benchmark as a manual adaptation, not a cherry-pick |
| P1 | Shadow weight in SVGF (`06d37a60`) | Potential quality win, but strongly overlaps the fork's visibility-transition/Sable-aware history work; high regression risk |
| P2 | Plane-distance accumulation (`3e381664`) | Already broadly addressed/superseded in the fork; compare constants/weighting only |
| P2 | Texture normal for ReSTIR GI (`725cff34`) | Concept exists throughout the fork; direct upstream code is incompatible with current combined-GI paths |
| P2 | ReSTIR GI tweaks (`9abe0b1a`) | Mixed tuning changes; evaluate individually with captures, never as one patch |
| P3 | Spatial reuse radius default 32 (`a80d3ba8`) | Configuration preference, not a correctness fix; likely irrelevant to fork-specific tuned defaults |
| P3 | `require` to `enable` (`e01e4085`) | Tiny tracing/API semantic change; inspect compatibility but do not import blindly |
| Skip | Atrous formatting cleanup (`be68754c`) | Irrelevant; formatting only |
| Skip/defer | Entire property/Iris overhaul (`201c8eb9` through `3c5b91fd`) | Architecturally conflicting and targeted at upstream's newer platform; not suitable for current setup |

## Detailed assessment: shader and rendering changes

### 1. `0f820e7b` — Fast history buffer

Upstream changes three files and replaces/optimizes SVGF history storage/fetching (`37` insertions, `12` deletions).

Assessment: **possibly useful, high adaptation cost**.

The fork no longer has upstream's `rendering/restir/svgf/history.glsl`; its denoising/history logic is reorganized under the current ReSTIR passes and includes fork-specific split stable/external histories, temporal validation, motion-domain handling, and Sable reactivity. A direct patch cannot apply. Luna should identify the exact storage representation and fetch-count reduction upstream introduced, then determine whether the current fork already obtains the same benefit. Benchmark GPU frame time and VRAM bandwidth before and after any isolated port.

### 2. `9abe0b1a` — ReSTIR GI tweaks

Touches temporal reuse, spatial reuse, indirect sampling, and SVGF history.

Assessment: **idea source only**.

This is a bundle of behavior changes rather than one clear fix. The fork has subsequently rewritten GI compatibility gates, spatial reuse, visibility handling, and temporal retention. Split the commit into individual mathematical changes and test each independently. Do not copy its constants as a group.

### 3. `725cff34` — Use `tex_normal` for ReSTIR GI

Assessment: **largely already addressed; possible edge-case comparison**.

The fork stores and uses texture normals throughout fragment data, direct sampling, combined GI, denoising, and temporal reconstruction. It also intentionally selects geometric normals for hands or bad-angle/high-risk cases. Upstream's unconditional/older-path use of `tex_normal` could regress grazing surfaces—the exact area the fork's v108–v113 work addressed. Compare only the upstream sampling-space calculation; preserve fork normal-selection policy.

### 4. `27e1ab65` — Fix ReSTIR GI directional bias

Upstream simplifies/changes `utility/random.glsl` (`11` insertions, `22` deletions).

Assessment: **best candidate for isolated investigation**.

Directional bias is a real correctness/quality concern. However, the fork has newer random streams, deterministic proposal strata, temporal/spatial stream separation, and Sable-related sampling. Luna should derive the distribution produced by both implementations and run a shader-level histogram or controlled scene comparison. Port only the unbiased sampling math if the fork still exhibits anisotropy; do not replace the whole RNG utility.

### 5. `06d37a60` — Add shadow weight to SVGF

Assessment: **potentially useful but overlaps heavily and is risky**.

Upstream propagates visibility/shadow information into SVGF history and atrous weighting. The fork already contains visibility-transition provenance, current-visible temporal reuse, reactive moving-light handling, and Sable-local visibility signatures. Adding another shadow weight may reduce ghosting at shadow boundaries, but can also reject too much history and reintroduce noise/flicker. Translate the concept into the fork's existing provenance/history fields rather than adding upstream's old data path. Validate moving lights, stationary penumbrae, Sable sublevels, disocclusion, and low sample counts.

### 6. `3e381664` — Use plane distance for accumulation

Assessment: **already addressed/superseded**.

The fork already uses plane-distance tests in ReSTIR sampling, spatial reuse, variance prefiltering, denoising, and temporal upscaling, including precision-aware tolerance and grazing-surface work. The upstream patch targets the old SVGF layout. Luna may compare the formula and tolerance, but should not transplant it.

### 7. `be68754c` — Clean up atrous formatting

Assessment: **irrelevant**. No behavioral value.

### 8. `e01e4085` — Replace `require` with `enable`

Assessment: **low priority compatibility check**.

This changes one tracing declaration token. Determine whether current shader-interface semantics distinguish a required feature from an enabled optional feature. The fork has extensive compatibility gates; changing this may silently permit unsupported paths. Adopt only if current Photon/other supported packs expect `enable` and compilation/runtime fallback tests pass.

### 9. `a80d3ba8` — Default spatial reuse radius to 32

Assessment: **irrelevant as a blind change; tuning experiment only**.

The fork exposes `photonics.restirSpatialReuseRadius` and has adaptive/precision-aware spatial validation. Radius affects quality, reuse acceptance, temporal stability, and cost. Keep current fork defaults unless an A/B matrix demonstrates improvement across near geometry, distant geometry, moving emitters, and Sable receivers.

### 10. `46f16590` and `d39c1bd5` — Fix soft shadows not being disabled

These are duplicate patches on the two parents of the final merge. Upstream avoids random light-position jitter unless `PH_RESTIR_SOFT_SHADOWS` is defined.

Assessment: **already addressed equivalently**.

In the fork, `IrisDefines` emits `PH_RESTIR_SOFT_SHADOWS` only when the property is enabled, and current reservoir visibility code calls `ph_rand_sample_position` only inside `#ifdef PH_RESTIR_SOFT_SHADOWS`. The old upstream `packed_offset` sampling path no longer exists. Luna should add/execute an off-vs-on deterministic validation if absent, but no port is indicated.

## Detailed assessment: Iris/property overhaul

The overhaul begins at `201c8eb9` and is merged by `3c5b91fd`. It includes:

- Annotation-driven typed property parsing and defaults.
- Replacement of the older `PhotonicsProperties`/defines arrangement.
- Pipeline/renderer API restructuring.
- Builder APIs for buffers, samplers, and uniforms.
- Removal or relocation of Iris bridge/mixin classes.
- Per-renderer sampler defines and signature mapping.
- Property parser follow-up fixes.
- New renderer naming (`basic` to `sharp`) and related shader layout changes.

Relevant commits:

- `201c8eb9` Replace properties settings system
- `efeda426` Migrate to new property system
- `a89b93c9` Fix float/int parsing key
- `93117c0b` Fix incorrect keys
- `d1225e34` Don't clear parsed properties
- `3cf21be0` Get rid of IrisManager instance
- `76b72387` Adjust ReSTIR minimums
- `8b5604be` Make extra methods default
- `a6b6a7a0` Fix trace-step define
- `35af691a` Always do signature mapping
- `f6632011` / `60104f99` Receiver-type probing and rename
- `01399bf0` Per-renderer sampler defines
- `f04dcc85` Fall back to property default
- `3c5b91fd` Merge `iris-overhaul` into `multi-version`

Assessment: **conflicting/deferred**.

Why it does not fit the current setup:

1. Current fork work is based on Minecraft 1.21.1 and the established API/layout; upstream is moving toward its newer architecture and 1.21.11-era structure.
2. The overhaul changes roughly 99 files at merge scale (about 2,155 insertions and 1,100 deletions) in precisely the same pipeline, mixin, property, and shader surfaces customized by the fork.
3. Importing it would not be a feature patch; it would be a platform migration that requires re-porting the fork's v86–v135 work.
4. The follow-up parser fixes show the initial replacement was not independently safe until the whole series was present.
5. Most immediate user-facing benefit is maintainability/extensibility, not a demonstrated fix for the fork's current rendering goals.

If multi-version/1.21.11 support becomes an explicit project goal, handle this as a separate migration branch. First inventory every fork extension point and build a compatibility map; do not mix it into rendering-quality changes.

## Suggested Luna implementation plan

1. Preserve `multi-version` and create a dedicated experiment branch/worktree.
2. Confirm baseline build and capture representative scenes/configurations before changes.
3. Verify soft shadows off/on; close as already fixed if no jitter occurs when disabled.
4. Investigate `27e1ab65` mathematically and with a directional sampling test. Port only proven unbiased math.
5. Profile the current history-buffer path, then manually prototype the core optimization from `0f820e7b` without changing history semantics.
6. Prototype the shadow-history weighting concept from `06d37a60` using existing fork provenance fields; compare ghosting versus noise.
7. Treat `9abe0b1a`, `725cff34`, and `3e381664` as formula/constant comparisons only.
8. Skip formatting and default-radius changes unless benchmarks justify them.
9. Do not begin the Iris/property overhaul without explicit approval for a platform migration.

## Required validation matrix for any accepted adaptation

- Clean Gradle build for the fork's supported 1.21.1 target(s).
- Photon with soft shadows both disabled and enabled.
- ReSTIR DI and combined GI enabled/disabled combinations.
- Static and moving block lights.
- Sable same-sublevel, cross-sublevel, and ordinary-world receivers.
- Streaming/chunk transitions and sky exposure changes.
- Grazing-angle surfaces and normal-mapped materials.
- Handheld lighting.
- Temporal upscaling on/off and camera motion.
- GPU timing plus visible noise, ghosting, fireflies, bias, and history rejection.

## Useful Git commands

```powershell
# Show the upstream batch graph
git log --graph --oneline --decorate -45 refs/remotes/audit-upstream/multi-version

# Inspect one candidate
git show 27e1ab65

# Compare current fork to an upstream file without modifying the worktree
git diff HEAD 27e1ab65 -- modules/shaders/photonics/utility/random.glsl

# Inspect the complete property overhaul merge
git show --stat 3c5b91fd
```

## Bottom line

The upstream batch does not contain a safe drop-in update for this fork. Soft-shadow disabling is already handled. Plane-distance and texture-normal concepts are already present in more specialized fork code. Directional-bias sampling, history-buffer performance, and shadow-aware denoising are the only promising ideas worth isolated experiments. The Iris/property overhaul is a separate, conflicting platform migration and should be deferred unless supporting upstream's new architecture becomes the primary goal.
