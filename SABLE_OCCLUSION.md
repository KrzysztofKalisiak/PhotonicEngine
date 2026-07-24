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
local-bounds classifier still assigns its persistent token. A matching
same-token light is then handled with `visible = false`; it never silently
falls through to the static-world visibility tree.

The v64 motion contract is unchanged. UUID-derived temporal tokens remain
persistent, GPU slots remain frame-local, and the existing current/previous
camera-relative transforms continue to drive history reprojection.

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
   - Maximum table dimension 512 texels.

Full blocks stay on the one-fetch path. Partial blocks use Minecraft's
`BlockState.getShape(...).toAabbs()` data only after DDA reaches that cell.
Air, fluid, and emissive cells keep the existing non-occluding behavior.

Malformed or out-of-cell shapes, shapes with more than 8 boxes, invalid shape
IDs, a missing shape table, and shape
table overflow are represented as conservative full cells. Sublevels larger
than 96 blocks on an axis, 300,000 cells, or the 512-layer aggregate atlas
limit receive no local atlas offset. The complete atlas is additionally capped
at 786,432 cells, which is a 3 MiB `RGBA8` payload. Candidates are UUID-sorted
before planning, and omitted candidates remain in the motion/token snapshot
with an atlas offset of `-1`.

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
3. Resolve the receiver cell and select the nearest normal-facing AABB.
4. Derive the endpoint from that selected AABB, not the whole block cell.
5. Run the endpoint-safe conservative supercover DDA, including the receiver
   cell.
6. Treat full/fallback cells as opaque and intersect sparse AABBs for partial
   cells.

Only the final epsilon-sized endpoint intersection is ignored. Another AABB in
the same multipart receiver cell can therefore block the ray.

If a same-token ray cannot be classified or has no uploaded atlas, it fails
closed. It does not fall back to the static world tree, because Sable plot
sections are deliberately excluded from that tree and would provide a stale
or contradictory answer.

## Visibility Matrix

| Receiver | Emitter | Implemented authority | Dynamic Sable occluders |
| --- | --- | --- | --- |
| World | World | Photonics world tracer | Not applicable |
| Sable A | Sable A | Sable A local DDA and sparse shapes, or fail closed without its slice | Yes, exact within the uploaded shape model |
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
- up to 8 AABB pairs only for a reached partial cell.

World and cross-sublevel rays have no additional dynamic-sublevel loop in this
baseline. The maximum 16 uploaded sublevels and all existing motion/history
limits remain unchanged.

## Diagnostics

An atlas or slice update logs:

- accepted and skipped sublevels;
- receiver, full, exact-shape, fallback, and receiver-only cell counts;
- distinct shape count and per-shape limit;
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

## Remaining Risks

- The topology key relies on Contraption Lights replacing its occupancy array.
  An in-place mutation would not invalidate the cached shape payload.
- Persistent shape rows are not compacted until reset. After 511 distinct rows,
  newly encountered partial shapes become conservative full cells.
- Bounds-only classification can classify an ordinary surface inside an
  omitted sublevel's AABB as Sable. That deliberate false positive produces a
  missing light rather than a fail-open leak.
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
