# Sable Occlusion

This document defines the visibility contract implemented by the optional
Contraption Lights/Sable bridge and the remaining cross-domain roadmap.

## Implemented Baseline

Photonics owns direct-light sampling, visibility, temporal reuse, denoising,
and final composition. Contraption Lights supplies Sable state and rebuild
notifications; Veil does not make a second visibility decision for a
Photonics reservoir.

The bridge supports exact same-sublevel direct visibility when all of these
conditions hold:

- the receiver is classified into an uploaded Sable sublevel;
- the light carries the same persistent sublevel token;
- the sublevel has an allocated local atlas slice; and
- the source and receiver can be transformed into that local grid.

Receiver identity does not depend on atlas allocation. If a sublevel is
omitted because it is oversized or the aggregate atlas is full, a stricter
local-bounds classifier still assigns its persistent token when exactly one
unavailable bound matches. If multiple unavailable bounds overlap at the
receiver, token `65535` represents "Sable receiver, exact domain unknown" and
all Sable-domain lights fail closed. CPU sublevel tokens are therefore limited
to `1..65534`. These cases never silently fall through to the static-world
visibility tree for a Sable-domain light.

The v64 motion contract is otherwise unchanged. UUID-associated temporal
tokens remain persistent, GPU slots remain frame-local, and the existing
current/previous camera-relative transforms continue to drive history
reprojection.

## Local Geometry Data

`ContraptionLightsSableBridge` builds two textures when Contraption Lights
reports a topology revision:

1. An `RGBA8` local-cell atlas.
   - R: receiver-cell flag.
   - G: shape box count.
   - BA: little-endian 16-bit sparse shape-table row.
   - G = 254: exact full cell.
   - G = 255: conservative full-cell fallback.
2. An `RGBA32F` sparse shape table.
   - Two texels per local AABB: minimum then maximum.
   - At most 8 AABBs per shape.
   - At most 511 persistent shape definitions.
   - Policy table dimension at most 512 texels.

Full blocks stay on the one-fetch path. Partial blocks use Minecraft's
`BlockState.getShape(...).toAabbs()` data only after DDA reaches that cell.
Air and fluid cells remain non-occluding. Emissive blocks now upload their
normal shape; only the currently sampled emitter cell is exempt in the shader,
so another emissive block can occlude the ray.

Malformed or out-of-cell shapes, shapes with more than 8 boxes, invalid shape
IDs, non-positive extents, a missing shape table, and shape-table overflow are
represented as conservative full cells. Sublevels larger
than 96 blocks on an axis, 300,000 cells, or the 512-layer aggregate atlas
limit receive no local atlas offset. The complete atlas is additionally capped
at 786,432 cells, which is a 3 MiB `RGBA8` payload. Candidates are UUID-sorted
before planning, and omitted candidates remain in the motion/token snapshot
with an atlas offset of `-1`.

The render-thread bridge queries `GL_MAX_3D_TEXTURE_SIZE`. Every atlas
dimension must fit that reported value. The shape-table dimension is
`min(512, GL_MAX_3D_TEXTURE_SIZE)`, so its effective row limit can be lower
than 511. If the query fails or the device limit cannot represent a candidate,
fine geometry is omitted and the atlas-less fail-closed identity path applies.

Contraption Lights allocates a new occupancy array when it rebuilds a
sublevel. The bridge uses that array identity as the topology generation.
This detects changes to fences, panes, and other partial blocks even when
Contraption Lights' own coarse occupancy bytes are unchanged.

Each accepted sublevel has an independent CPU cache keyed by its bounds and
topology generation. A dirty sublevel rescans only its own block states and
voxel shapes; unrelated sublevels reuse their cached payload. Byte-identical
rescans skip the GPU upload. Shape IDs remain stable until the atlas is reset,
so a dirty slice can be encoded without renumbering other slices.

When atlas dimensions, accepted ordering, offsets, or sublevel bounds change,
the bridge performs a bounded full upload. When that layout is stable, changed
sublevels use 3D slice uploads only. The shape table still receives a bounded
full upload when a new persistent shape is registered or its texture must be
recreated. Removing a shape does not compact the table during the same atlas
lifetime.

## Shader Visibility

The direct-light shader first compares the receiver and emitter temporal
tokens. A matching nonzero token selects one authoritative local visibility
test:

1. Transform the CPU-tokened light into the receiver's current local grid.
2. Derive its source cell directly, without the old global 64-emissive-cell
   visibility lookup.
3. Test the DDA start cell explicitly. Only the CPU-tokened emitter cell is
   self-exempt, so a wall-mounted source cannot skip an occluder immediately
   outside its exposed face.
4. Resolve the receiver cell and select the nearest normal-facing AABB.
5. Derive the endpoint from that selected AABB, not the whole block cell.
6. Run the endpoint-safe conservative supercover DDA, including the receiver
   cell.
7. Treat full/fallback cells as opaque and intersect sparse AABBs for partial
   cells.

Only the final epsilon-sized endpoint intersection is ignored. Another AABB in
the same multipart receiver cell can therefore block the ray.

Each ray may perform at most 64 partial-shape AABB intersection tests. Coarse
cell rejection runs before consuming this budget, and exact full/fallback
cells remain on their constant-cost path. If another partial cell would exceed
the budget, visibility fails closed. The value allows eight maximally complex
8-box cells while bounding the expensive inner loop.

If a same-token ray cannot be classified or has no uploaded atlas, it fails
closed. It does not fall back to the static world tree, because Sable plot
sections are deliberately excluded from that tree and would provide a stale
or contradictory answer.

## Visibility Matrix

| Receiver | Emitter | Implemented authority | Dynamic Sable occluders |
| --- | --- | --- | --- |
| World | World | Photonics world tracer | Not applicable |
| Sable A | Sable A | Sable A local DDA and sparse shapes, or fail closed without its slice | Yes, exact within the uploaded shape model |
| Unknown overlapping Sable bounds | Any Sable domain | Explicit unknown-domain rejection | Fail closed |
| Sable A | World | Photonics world tracer | No |
| World | Sable A | Photonics world tracer | No |
| Sable A | Sable B | Photonics world tracer | No |

Cross-domain rows intentionally make no claim that moving Sable geometry is
present. They retain the existing static-world visibility result. This avoids
false shadows from Sable's reserved plot coordinates, but moving Sable
occluders are currently absent from those rays.

## Performance

CPU shape work occurs on a Contraption Lights topology generation, not on
rigid transform-only frames. Only dirty sublevels are rescanned. The atlas,
all cached local payloads, the combined upload payload, and direct upload
buffers are each bounded by the 786,432-cell policy. A layout rebuild can
temporarily hold cached slices, one combined 3 MiB payload, and one 3 MiB
direct upload buffer.

GPU cost for same-token direct visibility is:

- the existing bounded supercover DDA;
- one `RGBA8` fetch per tested cell;
- a coarse cell AABB rejection before shape-table work; and
- up to 8 AABB pairs for one reached partial cell and 64 total partial AABB
  intersection tests per ray.

World and cross-sublevel rays have no additional dynamic-sublevel loop in this
baseline. The maximum 16 uploaded sublevels and all existing motion/history
limits remain unchanged.

There is deliberately no deferred per-frame topology queue in this patch.
Keeping the previous slice after a dirty topology revision could expose stale
occluders and fail open; dropping/repacking slices across several frames would
also cause repeated full layout changes. Instead, hard memory/upload sizes are
bounded and the bridge warns, at powers of two, when a topology update exceeds
any diagnostic target:

- 4 ms CPU shape scanning;
- 8 ms total CPU update/command submission; or
- 1 MiB uploaded in one update.

These are profiling targets, not correctness cutoffs. `updateMs` measures CPU
work and command submission, not asynchronous GPU completion.

## Diagnostics

An atlas or slice update logs:

- accepted and skipped sublevels;
- receiver, full, exact-shape, fallback, and receiver-only cell counts;
- distinct shape count and per-shape limit;
- `GL_MAX_3D_TEXTURE_SIZE` and hardware-limit skips;
- per-frame and cumulative cache hits, rescans, scanned cells, upload counts,
  bytes, and CPU timing;
- full versus slice upload mode and atlas dimensions/budgets; and
- the `same-token-local-only` authority marker.

Over-budget warnings are emitted only when the skip summary changes. They
state that omitted receivers retain bounds-classified identity and matching
same-domain visibility fails closed. Startup logging also states that
cross-domain visibility remains static-world-only.

## Validation Matrix

Run these cases after a centralized serial 1.21.1 build:

1. Move one rigid Sable structure containing a froglight, fence, trapdoor,
   full block, and receiving wall. Shadows should remain attached to the
   structure during translation and rotation.
2. Repeat with a pane, stair, slab, flower, and several connected fences.
   Partial silhouettes should replace full-cell shadows.
3. Add and remove a partial block while stationary and moving. The atlas
   rebuild log should increment once per topology update and transform-only
   frames should not rebuild it.
4. Exceed 8 AABBs with a modded shape. Its cell must remain conservatively
   opaque and increment `localConservativeCells`.
5. Test an oversized sublevel and a set whose aggregate atlas exceeds 786,432
   cells. Their atlas offsets should be absent, receiver motion should retain
   a nonzero token, and matching direct contributions should fail closed.
6. Test world-to-Sable, Sable-to-world, and Sable-A-to-Sable-B rays. Confirm
   the known limitation: moving Sable geometry does not yet occlude them.
7. Compare stationary and moving GPU timings against the base commit. The
   stationary full-block scene should add only the wider atlas fetch; partial
   shape cost should scale with reached partial cells.
8. Put two disjoint AABBs in one receiver cell and aim a ray through the first
   toward the second. The earlier AABB must occlude while the selected
   receiver surface must not self-occlude.
9. Place a wall-mounted Sable light with an occupied cell immediately outside
   its exposed face. That start cell must block the ray while the emitter's own
   cell remains exempt. Put a second emissive block on the ray and confirm that
   it still occludes.
10. Overlap two atlas-less sublevel bounds. Their overlapping receiver pixels
    must encode token `65535` and reject lights from either Sable domain.
11. Trace through more than 64 intersecting partial-shape boxes. The result
    must fail closed rather than continue unbounded.
12. Dirty all accepted sublevels in one frame and inspect the scan/update/upload
    diagnostics. Record frame time and GPU timing externally if a diagnostic
    target is exceeded.

## Remaining Risks

- The topology key relies on Contraption Lights replacing its occupancy array.
  An in-place mutation would not invalidate the cached shape payload.
- Persistent shape rows are not compacted until reset. After 511 distinct rows,
  newly encountered partial shapes become conservative full cells.
- Bounds-only classification can classify an ordinary surface inside an
  omitted sublevel's AABB as Sable. That deliberate false positive produces a
  missing light rather than a fail-open leak.
- Ambiguous atlas-less receivers have no authoritative rigid transform, so
  their motion output remains in current player space and their Sable direct
  contribution is suppressed until classification becomes unique.
- Runtime warnings do not include asynchronous GPU completion time; the
  validation workload requires an external GPU profiler or frame-time capture.
- Cross-domain moving occlusion remains unsupported and uses the static world
  result. No Veil shadow result is treated as authoritative Photonics
  visibility.

## Cross-Domain Roadmap

Cross-domain dynamic occlusion needs a separate bounded path:

1. Upload each sublevel's world-space AABB with its current transform.
2. Broad-phase the final selected direct ray against those AABBs.
3. Transform only intersecting ray intervals into the corresponding local
   grids.
4. Trace static world occupancy once and each intersected Sable interval once.
5. Define explicit source/receiver endpoint ownership for two independently
   moving sublevels.

Only the final selected ReSTIR sample should pay this full visibility cost.
Veil occupancy can be a broad-phase hint, but it is not sufficient as the
authoritative Photonics result because it lacks Photonics material,
transmission, and bounced-radiance state.
