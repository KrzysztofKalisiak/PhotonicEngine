# Photonics Temporal Lighting Upscaler

## Scope

This branch adds an optional temporal reconstruction path for Photonics
lighting. It is not an FSR implementation and does not upscale the shaderpack's
primary color pipeline.

The expensive Photonics direct-light and GI passes continue to render at
`photonics.renderScale` and `photonics.giRenderScale`. The new path reconstructs
their final lighting contribution at the shaderpack render scale immediately
before the shaderpack samples that contribution.

The feature is disabled by default.

## Render Graph

The integration is at the end of `RestirPipeline`, after:

1. direct and GI reservoir generation;
2. temporal and spatial reservoir reuse;
3. lighting evaluation and accumulation;
4. SVGF denoising;
5. exact Sable-local direct lighting.

The upscaler adds two passes:

1. **Low-resolution source composition**

   This pass combines the final denoised direct radiance, split or combined GI,
   external Sable lighting, and exact Sable-local lighting into
   `photonics_temporal_source`. It runs at `photonics.renderScale`.

2. **Full-resolution temporal reconstruction**

   This pass writes private Photonics attachments at
   `PH_SHADERPACK_RENDER_SCALE`:

   - `photonics_temporal_lighting`: resolved RGB and history age;
   - `photonics_temporal_surface`: encoded geometric normal, radial
     camera-space depth, and a compact receiver-identity signature.

The shaderpack-facing `sample_photonics_direct()` function samples
`photonics_temporal_lighting` only while the feature is active. No Iris
`colortex`, depth target, shaderpack draw buffer, or final color attachment is
replaced or resized.

Handheld lighting remains on its existing non-temporal path. This avoids
ghosting first-person animation and keeps the change focused on direct and GI
world lighting.

## Reconstruction

For each output pixel, the reconstruction pass reads native shaderpack depth
and normals through the existing Photonics world interface.

The current-frame estimate uses the four low-resolution texels around the
projected source position. A source texel participates only when:

- it represents the same hand/world class;
- its geometric normal agrees with the output surface;
- its reconstructed position is close to the output surface plane;
- it belongs to the selected world or Sable receiver domain.

If none of the four bilinear taps represents the output surface, a bounded
3-by-3 nearest-compatible search handles thin geometry and silhouettes.

## Temporal Reprojection

Ordinary world receivers use the existing previous camera matrices and camera
offset. Sable receivers use the slot and generation token selected from the
low-resolution fragment data, then apply the existing rigid
current-player-to-previous-player transform. This avoids a full Sable occupancy
search for every full-resolution pixel.

Four bilinear history taps are considered. Each tap must pass:

- history-age validity;
- receiver-identity signature equality;
- previous geometric-normal agreement;
- previous radial-depth agreement.

The accepted history is clamped to the current compatible source neighborhood.
A reactive weight increases current-frame influence for:

- large current/history luminance changes;
- material or visibility disocclusion indicated by history clamping;
- high source variance;
- weak current geometric support;
- large screen-space motion.

Stable pixels converge to the configured history length. Reactive pixels return
toward one-frame history instead of carrying stale lighting.

## History Reset

No persistent CPU-side history object is required.

- On initial allocation, both framebuffer sides are cleared and history age is
  zero.
- On viewport resize, `SingleFramebuffer.recalculateSizes()` resizes and clears
  both sides, invalidating all history.
- On shaderpack, dimension, world-pipeline, or Photonics pipeline replacement,
  the existing pipeline lifecycle closes the attachments and creates cleared
  replacements.
- While `ph_world_ready` is false, the reconstruction writes invalid
  zero-history pixels.

The existing framebuffer reset diagnostics list the two temporal attachment
names and whether the reason was initial allocation or viewport resize.

## Configuration

Shaderpack properties:

```properties
photonics.temporalUpscaler = true
photonics.temporalUpscalerHistoryFrames = 8
```

Equivalent test overrides:

```text
-Dphotonics.temporalUpscalerOverride=true
-Dphotonics.temporalUpscalerHistoryFramesOverride=8
```

The upscaler activates only for RESTIR with block lighting or combined RESTIR
GI, and only when `photonics.renderScale` is lower than the shaderpack render
scale. At equal scales it is bypassed, even when requested, because it would add
a full-resolution pass without reducing ray work.

`photonics.temporalUpscalerHistoryFrames` accepts 2 through 32 frames. Eight is
the default and the recommended starting point. A longer history is steadier
but reacts more slowly to lighting changes.

Example JVM configuration for a 1440p performance test:

```text
-Dphotonics.renderScaleOverride=0.67
-Dphotonics.giRenderScaleOverride=0.5
-Dphotonics.temporalUpscalerOverride=true
-Dphotonics.temporalUpscalerHistoryFramesOverride=8
```

## Diagnostics

Pipeline creation logs:

- requested and active state;
- source, GI, and output scales;
- history-frame limit;
- current and history tap counts;
- history memory cost per output pixel.

`photonics_temporal_lighting.a` is the per-pixel effective history age. In a
RenderDoc capture, stable surfaces should approach the configured frame limit;
reactive edges and newly revealed surfaces should stay near one.

## Expected Performance

The feature pays for:

- one low-resolution source-composition pass;
- one full-resolution reconstruction pass;
- two double-buffered RGBA16F history attachments, or 32 bytes per output pixel
  including both framebuffer sides.

It saves work only through a lower `photonics.renderScale`. Relative direct-pass
pixel counts are approximately:

| Render scale | Direct-pass pixels |
| --- | ---: |
| 1.00 | 100% |
| 0.75 | 56% |
| 0.67 | 45% |
| 0.50 | 25% |

Actual frame-time improvement depends on whether Photonics ray traversal is the
GPU bottleneck. The reconstruction is texture-bandwidth-heavy, so reductions
below 0.5 are unlikely to scale linearly and will lose thin lighting detail.

## Limitations

- This reconstructs lighting, not shaderpack color, transparency, particles,
  post-processing, or UI.
- It cannot recover geometric lighting detail that never appeared in any
  low-resolution source sample.
- Non-Sable moving entities have no dedicated object motion vectors. Their
  history is normally rejected by depth or reactive tests, but fast motion can
  still reveal short-lived ghosting.
- Receiver identity uses a compact ten-bit validation signature in addition to
  depth and normal tests. A hash collision is possible, although all validation
  conditions must collide at the same reprojected pixel for history to pass.
- The pass uses temporal reconstruction rather than proprietary FSR features
  such as optical flow, exposure control, frame generation, or vendor-specific
  sharpening.
