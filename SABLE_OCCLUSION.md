# Sable Occlusion Plan

This document describes the intended ownership and implementation order for
direct-light visibility involving Sable sublevels.

## Visibility Ownership

Visibility must have one authoritative coordinate domain for each ray:

| Receiver | Emitter | Current authority | Target authority |
| --- | --- | --- | --- |
| World | World | Photonics world tracer | Photonics world tracer |
| Sable A | Sable A | Sable A local DDA | Sable A local DDA |
| Sable A | World | Photonics world tracer | World tracer plus intersected Sable DDAs |
| World | Sable A | Photonics world tracer | World tracer plus intersected Sable DDAs |
| Sable A | Sable B | Photonics world tracer | World tracer plus A/B/intersected Sable DDAs |

The same-sublevel row is deliberately local-only. Running that ray through the
world voxel tree as well gives stale moving geometry a second, contradictory
visibility vote.

## Stage 1: Stable Receiver Identity

Replace occupancy-based fragment ownership inference with an integer identity
written while Sable draws the fragment:

- Keep a persistent 16-bit or 32-bit token per Sable UUID.
- Keep the GPU table slot frame-local and separate from the persistent token.
- Prefer writing the local cell and face together with the token. This removes
  the current floor/probe ambiguity at block boundaries.
- Retain geometric classification only as a compatibility fallback.

The sidecar must be written by every Sable solid draw that contributes to the
Photonics depth input. Batched draws need either a per-vertex identity or
separate draw ranges.

## Stage 2: Dynamic Sublevel Table

Move sublevel metadata from fixed uniform arrays to an SSBO or texture buffer.
Each entry should contain:

- persistent identity token and topology generation;
- current and previous rigid transforms;
- world-space AABB for broad-phase intersection;
- occupancy-atlas offset and dimensions;
- optional emissive-cell range.

This removes the current 16-sublevel truncation. Selection and upload order
must not change temporal identity.

## Stage 3: Cross-Domain Coarse Occlusion

For each final direct-light visibility ray:

1. Trace static world occupancy once.
2. Intersect the finite ray segment with uploaded Sable world-space AABBs.
3. Transform only intersecting intervals into each sublevel's local grid.
4. Run a local DDA over coarse full-block occupancy.
5. Ignore the classified receiver cell and the emitter's emissive cell to
   avoid self-intersection.

Start with full-block occupancy. This supports world-to-Sable,
Sable-to-world, and Sable-to-Sable shadows without coupling Photonics sampling
to Veil. The AABB broad phase is required before enabling this path broadly;
looping over every cell or every sublevel for every candidate is too costly.

Only the final selected ReSTIR sample needs full cross-domain visibility.
Proposal generation may use cheaper visibility or none, provided the estimator
weights remain consistent.

## Stage 4: Fine Occlusion And Transmission

Add sparse shape data only where coarse cells are insufficient:

- voxel-shape masks for fences, panes, trapdoors, and other partial blocks;
- material or transmission data for colored glass;
- optional per-face opacity where the shaderpack requires it.

Keep the coarse atlas as the fast path. Fine data should be sparse and fetched
only after a coarse occupied-cell hit.

## Temporal Rules

- Same-token receiver/emitter lighting is rigid-local and may retain full
  history through the receiver motion transform.
- Cross-domain lighting uses receiver-relative emitter motion and a bounded,
  reactive history window.
- Topology changes invalidate only the changed sublevel generation, not every
  Sable receiver.
- A visibility transition invalidates the affected lighting stream, while a
  transform update alone does not.

## Veil Boundary

Veil may later provide a broad-phase occupancy hint or an additional dynamic
light proposal source. It should not be the authoritative visibility result for
Photonics reservoirs because it does not provide Photonics material, albedo,
or bounced-radiance state. Photonics remains responsible for estimator weights,
final visibility, denoising, and temporal validity.

## Validation

Each stage should be tested independently with:

- a rigid light, fence, and receiver moving together at slow and high speed;
- a world light illuminating a moving Sable wall;
- a Sable light illuminating the world;
- two independently moving sublevels;
- more than 16 loaded sublevels;
- Photon TAA both enabled and disabled;
- GPU timings for final visibility and DDA intersection counts.
