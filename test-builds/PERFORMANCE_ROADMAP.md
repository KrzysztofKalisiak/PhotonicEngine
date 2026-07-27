# Photonics 1.21.1 Performance Roadmap

This file separates implemented experiments from researched follow-up work.
The estimates come from the captured approximately 11.1 ms Photonics GPU
workload and are not additive guarantees.

## Implemented Test Branch

`photonics-v87-performance-mc1.21.1.jar` contains:

1. Exact compact SVGF neighborhood evaluation, enabled by default.
2. Optional adaptive coarse A-trous filtering, disabled by default.
3. Lower-overhead sampled timing diagnostics and a complete timing-off mode.

Use the A/B configurations in `TEST_MATRIX.md`.

## Next Contained Experiments

These should remain separate commits or branches until visual and GPU-timing
tests pass.

| Rank | Experiment | Estimated benefit | Main validation risk |
| ---: | --- | ---: | --- |
| 1 | Produce one `RGBA16F` stable-plus-external lighting source for the variance pass instead of reading two `RGBA32F` histories per neighbor | 0.25-0.65 ms GPU | HDR rounding/clipping, stained glass, bright Sable lights |
| 2 | Pack the direct reservoir into `RG32UI` and direct state into `R32UI`; retain an exact 16-bit light index and float weight bits | 0.2-0.7 ms GPU | 4,000-light IDs, sentinels, sample caps, Sable signatures |
| 3 | Make temporal reuse write the immutable spatial-input layout and remove direct/GI spatial copy passes | 0.18-0.25 ms GPU | Split/combined modes and zero-neighbor behavior |
| 4 | Default timing and reservoir readback diagnostics off outside diagnostic builds | 0-0.2 ms average; potentially better p99 | None for rendering; logs lose detailed timings |
| 5 | Cache framebuffer attachments, completeness results, attachment selections, and draw-buffer arrays until create/resize/flip | 0.05-0.3 ms CPU | Refresh correctness after resize and framebuffer flips |
| 6 | Hoist direct proposal counts/probabilities outside the candidate loop and skip empty heap upload encoders | 0-0.15 ms GPU plus small CPU savings | Compiler may already optimize most shader work |

A realistic combined target for the first five items is approximately
0.8-1.8 ms, subject to GPU architecture and scene content.

## Architectural Experiments

These can move the frame budget more substantially, but they change core
pipeline structure and need independent correctness branches.

| Rank | Experiment | Estimated benefit | Main risk |
| ---: | --- | ---: | --- |
| 1 | Compute-tiled SVGF with shared 8x8 tiles and tile inactivity | 0.6-1.5 ms GPU beyond compact SVGF | A-trous halos, shared-memory pressure, barriers |
| 2 | Store selected visibility/tint/transmittance and fuse final direct shading with reuse or accumulation | 0.5-1.2 ms GPU | Soft shadows, transparency, and estimator correctness |
| 3 | Specialized finite-segment visibility traversal with resumable transparent hits | 0.3-2.0 ms, scene dependent | Foliage/glass correctness and traversal stack behavior |
| 4 | Compact average-based histories and omit external Sable history when impossible | 0.4-1.2 ms GPU | Mode transitions, HDR range, moving-light trails |
| 5 | Compact or conditionally omit the full-resolution `RGBA32F` motion MRT | 0.15-0.6 ms GPU | Previously fragile Sable reprojection |
| 6 | Checkerboard GI with reactive fill, or a light-importance table reducing direct candidates from 32 to 4-8 | 0.4-1.0 ms GPU | Disocclusion noise and estimator bias |

Reaching a stable 60 FPS from a measured approximately 20 ms total frame is
unlikely from contained optimizations alone. Compute-tiled SVGF or visibility
and pass fusion is the most plausible next architectural step after the
current jars are visually validated.

## Upstream Audit

The public upstream head audited on 2026-07-24 was `98495d57`. Its only new
skylight visibility fix is already covered more strictly by local v87
tri-state GI validation, so it should not be cherry-picked.

The local deterministic thin-cutout transmittance intentionally differs from
upstream. Removing it would reintroduce the vegetation/village bright-point
failure that v86 addresses.

Primary references used in the audit:

- ReSTIR DI: <https://cs.dartmouth.edu/~wjarosz/publications/bitterli20spatiotemporal.html>
- SVGF: <https://research.nvidia.com/labs/rtr/publication/schied2017spatiotemporal/>
- AMD RDNA performance guidance: <https://gpuopen.com/learn/rdna-performance-guide/>
- OpenGL timer queries: <https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt>
- OpenGL internal-format queries: <https://registry.khronos.org/OpenGL/extensions/ARB/ARB_internalformat_query2.txt>
