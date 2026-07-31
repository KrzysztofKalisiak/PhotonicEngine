# Photonics Temporal Lighting Upscaler

## Scope

This branch adds an optional temporal reconstruction path for Photonics
lighting. It is not an FSR implementation and does not upscale the shaderpack's
primary color pipeline.

The expensive Photonics direct-light and GI passes render at the effective
temporal source scale and `photonics.giRenderScale`. The new path reconstructs
their final lighting contribution at the shaderpack's `photonics.renderScale`
immediately before the shaderpack samples that contribution.

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
   `photonics_temporal_source`. It runs at the effective temporal source scale.

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

For each output pixel, the reconstruction pass reads native shaderpack depth,
geometric normal, and texture normal through the existing Photonics world
interface. It independently calls `ph_sable_receiver_motion()` at full
resolution. That classifier probes the current Sable local occupancy/emissive
atlas and returns the authoritative receiver slot, generation token, and
previous rigid transform.

The current-frame estimate uses the four low-resolution texels around the
projected source position. A source texel participates only when:

- it represents the same hand/world class;
- its slot and generation token exactly equal the full-resolution receiver;
- world token zero is unencoded on both the output and source texel;
- its geometric and texture normals agree with the output surface;
- its reconstructed position is close to the output surface plane.

The geometric base tolerance scales from 1/64 block at equal resolution toward
1/32 block as source resolution decreases. The fetched candidate position,
however, came through the RGBA16F `ph_frag_data0` attachment. Its effective
plane tolerance therefore also includes a component- and normal-aware
binary16 rounding bound.

For stored half position component `p_i`, the maximum round-to-nearest error is:

```text
e_i = 2^(floor(log2(max(abs(p_i), 2^-14))) - 11)
```

The `2^-14` floor gives the half-subnormal error bound `2^-25`. For source and
output unit normals `n_s` and `n_o`, reconstruction uses:

```text
E = max(sum_i(abs(n_s_i) * e_i), sum_i(abs(n_o_i) * e_i))
T = min(1/4, T_base + E)
```

This is the worst-case projection of independent component rounding errors
onto either normal used by the plane test. Both normals are normalized first;
degenerate values are rejected. The shader extracts the exact float32 exponent
of each fetched half value rather than relying on an approximate logarithm.

Binary16 half-ULP bounds at power-of-two component magnitudes are:

| Component magnitude | Half-ULP bound |
| ---: | ---: |
| 32 | 1/64 block |
| 64 | 1/32 block |
| 128 | 1/16 block |
| 256 | 1/8 block |

The 1/4-block cap is sufficient throughout a component-wise +/-256-block cube:
for a unit normal, `sum(abs(n_i)) <= sqrt(3)`, so the maximum projected
binary16 error is `sqrt(3) / 8`; adding the maximum 1/32 base margin remains
below 1/4. Outside that range, the cap intentionally rejects uncertain matches
instead of allowing tolerance to grow without bound.

Near the camera, `E` is small and the original sub-voxel separation remains
tight. At greater distances the tolerance necessarily widens, so distinct thin
layers with identical normals can become indistinguishable. Keeping
`ph_frag_data0` at RGBA16F avoids doubling position-buffer bandwidth; RGBA32F
would be required to preserve the same near-field layer discrimination at long
range.

If none of the four bilinear taps represents the output surface, a bounded
3-by-3 nearest-compatible search handles thin geometry and silhouettes.

## Temporal Reprojection

Ordinary world receivers use the existing previous camera matrices and camera
offset. Sable receivers use the previous position and normal returned by the
independent full-resolution Sable classifier. Low-resolution source samples
never determine the output pixel's motion domain.

Four bilinear history taps are considered. Each tap must pass:

- history-age validity;
- receiver-identity signature equality;
- previous geometric-normal agreement;
- previous radial-depth agreement.

If none of those four taps passes, reconstruction searches the nearest
compatible surface in a bounded 3-by-3 footprint around the reprojected
location. The fallback still requires matching identity, normal, and depth and
is marked as lower-support history. This prevents silhouettes and distant thin
geometry from repeatedly restarting at one-frame history.

The history age also carries a compact world-compilation revision tag. A tag
mismatch does not reject an otherwise valid tap: section streaming updates the
global revision even when the reprojected pixel is unaffected. Instead, a
revision mismatch lowers the per-pixel luminance and neighborhood-clamp
thresholds. Pixels whose current lighting changed react immediately, while
unaffected surfaces retain their accumulated history.

The accepted history is rectified against the current compatible source
neighborhood. Reconstruction tracks the number and bilinear support of current
taps, plus whether at least two taps agree on the bright end of the local
radiance range. These signals distinguish coherent lighting changes from a
single low-resolution radiance outlier. Spatial agreement is gated by temporal
source variance rather than added to it: several agreeing taps cannot make a
high-variance estimate authoritative after the source denoiser has spread one
stochastic event across neighboring texels.

Sparse or high-variance incoherent changes reduce both history rectification
and current-frame influence as soon as one valid history sample exists.
Positive outliers receive an additional asymmetric reduction because a
one-frame bright sample is much more visible than the same-sized negative
error. The current estimate is never discarded: persistent changes still
converge, while coherent low-variance multi-tap changes retain the fast path at
every history age.

When geometric history is unavailable, reconstruction starts a new one-frame
history from the current compatible source estimate. A bounded neighborhood at
the reprojected location may act only as a permissive one-sided envelope for a
low-confidence positive burst; it is never accumulated as the current
surface's history. The brightest valid value is selected so this fallback
cannot unnecessarily darken a disocclusion, and coherent current lighting plus
all negative changes remain immediate. Once one valid geometric history sample
exists, confidence gating applies immediately; unstable young history uses the
configured stable-history denominator instead of the large startup weights.

The final resolved radiance also has a one-sided, history-relative HDR growth
bound. Reducing a suspicious sample's temporal weight is insufficient when its
radiance is orders of magnitude above history: even a small contribution can
become a white point after shaderpack exposure and bloom. Large positive steps
therefore cannot bypass the bound even when source variance reports high
confidence. Small coherent changes remain immediate. A persistent large change
advances at a fixed log-luminance rate based on Iris's real frame time and
converges rapidly; negative changes are never restricted. The rate is
frame-rate independent. This matters on fast GPUs, where a stochastic source
burst may survive several render frames but still occupy only one video or
display frame.

The confidence-independent part of that bound is asymmetric, so it is enabled
only for mature, supported history whose reprojected receiver is effectively
stationary. The motion gate uses output pixels per second, not pixels per frame,
so its behavior is consistent across frame rates. Camera or receiver motion
releases the hard bound before low-resolution source-grid phase changes can be
converted into dark bands. The confidence-driven outlier bound remains active
during motion because it already requires an incoherent, uncertain positive
sample; this closes the moving-camera firefly path without restoring the broad
v95 dark regions.

A reactive weight increases current-frame influence for:

- large current/history luminance changes;
- material or visibility disocclusion indicated by history clamping;
- large screen-space motion.

Sparse current or history bilinear support alone is not reactive. It is common
at silhouettes and thin geometry, and forcing current-frame weight there
exposes the low-resolution source-grid phase as visible shimmer. Surface,
receiver, luminance, and clamp validation still reject genuinely stale taps.
High source variance lowers current confidence even when the source taps agree,
rather than increasing its temporal weight. This reduces history rectification
for both positive and negative stochastic changes. Positive changes retain the
additional asymmetric reduction because short bright excursions are especially
visible after shaderpack exposure and bloom.

Stable pixels converge to the configured history length. Reactive pixels return
toward one-frame history instead of carrying stale lighting.

Texture normals validate current reconstruction but are not stored in the
compact history attachment. History remains validated by geometric normal,
radial depth, and receiver identity. A texture-normal-only change can therefore
retain history briefly until luminance change and neighborhood clamping make
the pixel reactive.

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
photonics.temporalUpscalerSourceScale = 0.67
photonics.temporalUpscalerHistoryFrames = 8
```

Equivalent test overrides:

```text
-Dphotonics.temporalUpscalerOverride=true
-Dphotonics.temporalUpscalerSourceScaleOverride=0.67
-Dphotonics.temporalUpscalerHistoryFramesOverride=8
```

The upscaler activates only for RESTIR with block lighting or combined RESTIR
GI, and only when the effective source scale is lower than the shaderpack's
`photonics.renderScale`. At equal scales it is bypassed, even when requested,
because it would add a full-resolution pass without reducing ray work.

Source-scale precedence is:

1. `-Dphotonics.renderScaleOverride` is the existing generic override and has
   highest priority. The feature does not clamp it to output scale; a value that
   is not below output scale simply causes temporal reconstruction to bypass.
2. Without that generic override, an eligible enabled temporal upscaler uses
   `photonics.temporalUpscalerSourceScaleOverride` when present, otherwise the
   shaderpack's `photonics.temporalUpscalerSourceScale`.
3. The temporal source scale is clamped to 0.25 through 1.0 and cannot exceed
   the shaderpack output scale.
4. While the upscaler is disabled or ineligible for the selected lighting
   configuration, temporal source-scale settings have no effect. Effective
   render scale remains exactly the generic override, when present, or the
   shaderpack's `photonics.renderScale`.

The temporal source-scale default is 0.67. It is inert while the feature is
disabled, so a shaderpack-only configuration needs only to enable the feature
when the default is acceptable. The output scale always remains the
shaderpack's `photonics.renderScale`.

`photonics.temporalUpscalerHistoryFrames` accepts 2 through 32 frames. Eight is
the default and the recommended starting point. A longer history is steadier
but reacts more slowly to lighting changes.

Example JVM configuration for a 1440p performance test:

```text
-Dphotonics.giRenderScaleOverride=0.5
-Dphotonics.temporalUpscalerOverride=true
-Dphotonics.temporalUpscalerSourceScaleOverride=0.67
-Dphotonics.temporalUpscalerHistoryFramesOverride=8
```

## Diagnostics

Pipeline creation logs:

- requested and active state;
- configured/effective source, GI, and output scales;
- history-frame limit;
- current and history tap counts;
- history memory cost per output pixel.
- local-reactive world-revision and history-stable sparse-support policies.

`photonics_temporal_lighting.a` is the per-pixel effective history age. In a
RenderDoc capture, stable surfaces should approach the configured frame limit;
reactive edges and newly revealed surfaces should stay near one.

## Expected Performance

The feature pays for:

- one low-resolution source-composition pass;
- one full-resolution reconstruction pass;
- two double-buffered RGBA16F history attachments, or 32 bytes per output pixel
  including both framebuffer sides.

It saves work only through a lower effective temporal source scale. Relative
direct-pass pixel counts are approximately:

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
- Full-resolution Sable domain classification probes the local atlas for every
  reconstructed world pixel while Sable sublevels are active. This is required
  for domain correctness but adds work to the reconstruction pass.
- Receiver identity uses a compact eleven-bit validation signature in addition to
  depth and normal tests. A hash collision is possible, although all validation
  conditions must collide at the same reprojected pixel for history to pass.
- Exact coplanar surfaces with identical geometric and texture normals have no
  material identifier in the compact fragment data. They remain
  indistinguishable if they occupy the same plane and receiver domain.
- The pass uses temporal reconstruction rather than proprietary FSR features
  such as optical flow, exposure control, frame generation, or vendor-specific
  sharpening.
