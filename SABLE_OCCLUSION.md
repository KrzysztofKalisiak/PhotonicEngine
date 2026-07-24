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
- the sublevel fits the local geometry limits; and
- the source and receiver can be transformed into that local grid.

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
   - At most 16 AABBs per shape.
   - At most 1023 distinct shape definitions per atlas rebuild.

Full blocks stay on the one-fetch path. Partial blocks use Minecraft's
`BlockState.getShape(...).toAabbs()` data only after DDA reaches that cell.
Air, fluid, and emissive cells keep the existing non-occluding behavior.

Malformed or out-of-cell shapes, shapes with more than 16 boxes, and shape
table overflow are represented as conservative full cells. Sublevels larger
than 96 blocks on an axis, 300,000 cells, or the 512-layer aggregate atlas
limit receive no local atlas offset.

Contraption Lights allocates a new occupancy array when it rebuilds a
sublevel. The bridge uses that array identity as the topology generation.
This detects changes to fences, panes, and other partial blocks even when
Contraption Lights' own coarse occupancy bytes are unchanged. Byte-identical
Photonics payloads skip the GPU upload.

## Shader Visibility

The direct-light shader first compares the receiver and emitter temporal
tokens. A matching nonzero token selects one authoritative local visibility
test:

1. Transform the CPU-tokened light into the receiver's current local grid.
2. Derive its source cell directly, without the old global 64-emissive-cell
   visibility lookup.
3. Resolve the receiver cell from its local position and surface normal.
4. Run the existing endpoint-safe conservative supercover DDA.
5. Treat full/fallback cells as opaque and intersect sparse AABBs for partial
   cells.

If a same-token ray cannot be classified or has no uploaded atlas, it fails
closed. It does not fall back to the static world tree, because Sable plot
sections are deliberately excluded from that tree and would provide a stale
or contradictory answer.

## Visibility Matrix

| Receiver | Emitter | Implemented authority | Dynamic Sable occluders |
| --- | --- | --- | --- |
| World | World | Photonics world tracer | Not applicable |
| Sable A | Sable A | Sable A local DDA and sparse shapes | Yes, exact within the uploaded shape model |
| Sable A | World | Photonics world tracer | No |
| World | Sable A | Photonics world tracer | No |
| Sable A | Sable B | Photonics world tracer | No |

Cross-domain rows intentionally make no claim that moving Sable geometry is
present. They retain the existing static-world visibility result. This avoids
false shadows from Sable's reserved plot coordinates, but moving Sable
occluders are currently absent from those rays.

## Performance

CPU work occurs on a Contraption Lights topology generation, not on rigid
transform-only frames. It scans each accepted local grid once and deduplicates
shape definitions. The cell atlas grows from one to four bytes per cell.

GPU cost for same-token direct visibility is:

- the existing bounded supercover DDA;
- one `RGBA8` fetch per tested cell;
- up to 16 AABB pairs only for a reached partial cell.

World and cross-sublevel rays have no additional dynamic-sublevel loop in this
baseline. The maximum 16 uploaded sublevels and all existing motion/history
limits remain unchanged.

## Diagnostics

An atlas rebuild logs:

- accepted and skipped sublevels;
- receiver, full, exact-shape, fallback, and receiver-only cell counts;
- distinct shape count and per-shape limit;
- atlas dimensions, payload bytes, and payload hash; and
- the `same-token-local-only` authority marker.

If no candidate fits the atlas limits, a warning states that same-domain
visibility will fail closed. Startup logging also states that cross-domain
visibility remains static-world-only.

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
4. Exceed 16 AABBs with a modded shape. Its cell must remain conservatively
   opaque and increment `conservativeFallbackCells`.
5. Test an oversized sublevel. Its atlas offset should be absent and its
   same-token direct contribution should fail closed without a crash.
6. Test world-to-Sable, Sable-to-world, and Sable-A-to-Sable-B rays. Confirm
   the known limitation: moving Sable geometry does not yet occlude them.
7. Compare stationary and moving GPU timings against the base commit. The
   stationary full-block scene should add only the wider atlas fetch; partial
   shape cost should scale with reached partial cells.

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
