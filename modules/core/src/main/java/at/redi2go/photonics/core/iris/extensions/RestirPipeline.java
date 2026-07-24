package at.redi2go.photonics.core.iris.extensions;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.AbstractPhotonicsExtension;
import at.redi2go.photonics.core.iris.Pipelines;
import at.redi2go.photonics.core.iris.pipeline.rendering.IrisFactory;
import at.redi2go.photonics.core.iris.pipeline.uniform.IDynamicUniformHolder;
import at.redi2go.photonics.core.rendering.UniformUpdater;
import at.redi2go.photonics.core.rendering.lights.HandheldItemSupplier;
import at.redi2go.photonics.core.rendering.sublevel.ExternalSubLevelMotion;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasDownloader;

import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_PREV_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.FLIP;

public class RestirPipeline extends AbstractPhotonicsExtension {
    private final int denoiserPasses;

    private int atrousIteration = 0;
    private final UniformUpdater atrousUpdater = new UniformUpdater();

    public RestirPipeline(
            PhotonicsProperties properties,
            AtlasDownloader atlasDownloader,
            HandheldItemSupplier handheldItemSupplier,
            IrisFactory irisFactory
    ) {
        super(properties, atlasDownloader, handheldItemSupplier);

        registerComponent(ExternalSubLevelMotion.instance());

        Photonics.LOGGER.info(
                "Photonics feature increment: v65 upstream traversal guard, hand texture-normal evaluation, immature-history edge variance, and variance-guided full SVGF passes; direct-light-v64 proxy ownership retained"
        );
        Photonics.LOGGER.info(
                "Photonics GI foundation v66: finite full-position indirect reservoirs, corrected primary-ray origin/origin rebasing/explicit sky hits, normal/Jacobian-weighted world temporal reuse, hand/Sable temporal isolation, and selected-reservoir visibility validation"
        );
        Photonics.LOGGER.info(
                "Photonics ReSTIR GI v68: combinedGi={}, temporalReservoirReuse=ordinary-world-only, indirectSpatialReuse=false, handSableReservoirHistory=false, indirectStorage=rgba32f+rgb32ui",
                properties.useRestirCombinedGi()
        );
        Photonics.LOGGER.info("Photonics feature set: direct-light-v64 expiring Contraption Lights proxy ownership with a conservative nearby unmatched-proxy quarantine, position-matched Sable light alias trail with registry/profile diagnostics and render-thread-owned external merge, full-precision motion-grid Sable receiver visibility and endpoint-safe conservative traversal, dual-space Sable light de-duplication, production composite restored after v57 reservoir diagnostics, corrected indirect hit-normal decoding, full temporal reservoir retention for Sable external lighting, randomized systematic world-light proposals, Sable skylight-transition diagnostics with opt-in getter freeze, post-denoise exact Sable-local direct stream and fail-closed same-domain visibility, camera-relative double-composed Sable reprojection, normal-guided receiver classification, tri-state same-sublevel visibility and conservative supercover voxel traversal, sampling-time receiver-domain partition with exact same-sublevel direct lighting, external-only Sable reservoirs and soft-shadow local-visibility-signature-guided Sable SVGF support, preserved all-zero current proposal batches, elapsed-time moving-light hysteresis and unsnapped render-pose positions, rejected spatial-batch accounting, receiver-domain-complete stable/external history partition and rigid-motion Sable spatial reuse, bounded visibility-rejected reservoirs and representative-scoped external reactivity, split stable/external accumulation histories and stale Sable material recovery, explicit emitter motion-domain identity, motion-domain-stable receiver-relative light-history limits, previous-light position metadata, rotation-correct cross-sublevel motion, guarded ordinary-world all-light and external-only Sable single-neighbor spatial reuse, immutable spatial input, current-receiver visibility validation, bounded spatial history, independent spatial random stream, bounded reservoirs, zero-contribution batch accounting, actual-motion reactive lights, stable-anchor Sable reprojection, surface-plane SVGF, visibility-transition provenance, stable Sable identity, current-visible temporal reuse, Sable plot-section isolation, deterministic diagonal cutouts, generation-aligned adaptive stratified ReSTIR proposals, exposed-face Sable local visibility, duplicate Veil point-light suppression, moving-light and Iris material bridges; finite-segment OOB visibility and tree-origin tracing, masked passes, texture barriers, accumulation, denoising, handheld; Sable-receiver world/cross-sublevel spatial reuse and combined GI compatibility gates active");

        // The hand needs at least seven denoiser passes to avoid residual noise.
        int requestedDenoiserPasses = properties.getRestirDenoiserPasses();
        this.denoiserPasses = requestedDenoiserPasses != 0 ? Math.max(requestedDenoiserPasses, 7) : 0;

        Photonics.LOGGER.info(
                "Photonics ReSTIR configuration v64: directCandidatesPerPixel={}, directTemporalSampleCap={}, directOutputSampleCap=world-128/sable-temporal-cap, spatialCandidates={}, spatialRadiusPixels={}, output=accumulated-denoised-plus-exact-sable-local, samplingPolicy=randomized-systematic-luminance-sorted-world-suffix+distinct-priority-prefix/sable-external-reservoir/exact-all-same-token-lights/fail-closed-tri-state-conservative-local-dda/full-precision-motion-grid-receiver/normal-biased-endpoint/preserved-zero-current-batches, spatialPolicy=receiver-matched-current-frame/external-only-sable/current-rejection-accounting/immutable-input/initial-batch-cap+rejected-fallback-cap+background-finalization, historyPolicy=receiver-domain-complete-split-stable-external/direct-only-full-rigid-local-history/camera-relative-double-compose/normal-guided-receiver/soft-local-signature-reset/visibility-transition-reset/representative-scoped-external-reactivity/explicit-emitter-domain/0.15-block-trail/2-frame-floor, lightListPolicy=position-matched-250ms-alias-trail/250ms-after-loss-minecraft-light-proxy-ownership/125ms+2-frame-unmatched-proxy-quarantine/3-cell-alias-radius/render-thread-owned-merge, motionHoldMs=250, denoiserPolicy=post-denoise-exact-local-hard-shadow+representative-gated-world+soft-local-signature-sable, sableSkyLightDiagnostic=transition-log/optional-freeze-getter, requestedDenoiserPasses={}, effectiveDenoiserPasses={}, softShadows={}, combinedGi={}",
                properties.getRestirInitialSamples(),
                20 * properties.getRestirInitialSamples(),
                properties.getRestirSpatialReuseSamples(),
                properties.getRestirSpatialReuseRadius(),
                requestedDenoiserPasses,
                denoiserPasses,
                properties.useRestirSoftShadows(),
                properties.useRestirCombinedGi()
        );

        var restirFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_lighting_variance", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_direct_reservoirs0", ITextureFormat.rgb32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isBlockLightEnabled)
                .addAttachment("restir_direct_state", ITextureFormat.rg32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isBlockLightEnabled)
                .addAttachment("restir_indirect_reservoirs0", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .addAttachment("restir_indirect_reservoirs1", ITextureFormat.rgb32ui(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .addAttachment("restir_external_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isBlockLightEnabled)
                .build(this::registerComponent);

        var directReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_direct_reservoirs0"
        );
        var indirectReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_indirect_reservoirs0",
                "restir_indirect_reservoirs1"
        );
        var reusedReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_direct_reservoirs0",
                "restir_indirect_reservoirs0",
                "restir_indirect_reservoirs1"
        );
        var diffuseFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_lighting",
                "restir_direct_reservoirs0",
                "restir_direct_state",
                "restir_indirect_reservoirs0",
                "restir_indirect_reservoirs1",
                "restir_external_lighting"
        );
        var accumulationFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_lighting",
                "restir_lighting_variance",
                "restir_external_lighting"
        );

        var denoiseFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("denoise_result", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isDenoisingEnabled)
                .build(this::registerComponent);

        var localLightingFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_local_lighting", ITextureFormat.rgb16f(), CREATE_SAMPLER, this::isBlockLightEnabled)
                .build(this::registerComponent);

        var spatialInputFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_direct_spatial_input", ITextureFormat.rgb32f(), CREATE_SAMPLER, this::isSpatialReuseEnabled)
                .build(this::registerComponent);

        var otherFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("other_handheld", ITextureFormat.rgb16f(), CREATE_SAMPLER, this::isHandheldLightingEnabled)
                .build(this::registerComponent);

        Pipelines.fragData(this, irisFactory, properties.getRenderScale());

        irisFactory.newPipeline()
                .debugGroup("restir")
                .thenFlip(restirFramebuffer)
                .withFramebuffer(directReservoirFramebuffer)
                .deferredPass("initial direct", "/photonics/rendering/restir/passes/r1_initial_direct.fsh", null, this::isBlockLightEnabled)
                .deferredPass("validate initial direct", "/photonics/rendering/restir/passes/r2_validate_initial_direct.fsh", null, this::isBlockLightEnabled)
                .withFramebuffer(indirectReservoirFramebuffer)
                .deferredPass("initial indirect", "/photonics/rendering/restir/passes/r3_initial_indirect.fsh", null, this::isRestirGiEnabled)
                .withFramebuffer(reusedReservoirFramebuffer)
                .deferredPass("temporal reuse", "/photonics/rendering/restir/passes/r4_temporal_reuse.fsh", null, this::isRestirEnabled)
                .withFramebuffer(spatialInputFramebuffer)
                .deferredPass("copy spatial input", "/photonics/rendering/restir/passes/r5_copy_spatial_input.fsh", null, this::isSpatialReuseEnabled)
                .withFramebuffer(reusedReservoirFramebuffer)
                // The spatial pass also caps temporal reservoirs, so it must run
                // when the configured spatial candidate count is zero.
                .deferredPass("spatial reuse/clamp", "/photonics/rendering/restir/passes/r5_spatial_reuse.fsh", null, this::isRestirEnabled)
                .withFramebuffer(indirectReservoirFramebuffer)
                .deferredPass("validate indirect", "/photonics/rendering/restir/passes/r6_validate_indirect.fsh", null, this::isRestirGiEnabled)
                .withFramebuffer(diffuseFramebuffer)
                .deferredPass("diffuse", "/photonics/rendering/restir/passes/r6_diffuse.fsh", null, this::isRestirEnabled)
                .withFramebuffer(accumulationFramebuffer)
                .deferredPass("accumulation", "/photonics/rendering/restir/passes/r7_accumulation.fsh", null, this::isRestirEnabled)
                .when(this::isDenoisingEnabled, b0 -> {
                    b0.withFramebuffer(denoiseFramebuffer);
                    b0.debugGroup("svgf");
                    b0.thenRun(() -> atrousIteration = denoiserPasses);
                    b0.deferredPass("variance prefilter", "/photonics/rendering/restir/passes/r8_variance_prefilter.fsh", null);
                    b0.repeat(denoiserPasses, b1 -> {
                        b1.thenRun(() -> atrousIteration--);
                        b1.thenRun(atrousUpdater::updateNow);
                        b1.thenFlip(denoiseFramebuffer);
                        b1.deferredPass("atrous iteration", "/photonics/rendering/restir/passes/r9_denoising.fsh", null);
                    });
                })
                .withFramebuffer(localLightingFramebuffer)
                .deferredPass("exact local direct", "/photonics/rendering/restir/passes/r10_local_direct.fsh", null, this::isExactLocalLightingEnabled)
                .debugGroup("other")
                .withFramebuffer(otherFramebuffer)
                .deferredPass("handheld", "/photonics/rendering/restir/passes/r10_handheld.fsh", null, this::isHandheldLightingEnabled)
                .build(this::registerRenderer);
    }

    @Override
    public void registerDynamicUniforms(IDynamicUniformHolder dynamicUniforms) {
        super.registerDynamicUniforms(dynamicUniforms);

        dynamicUniforms.uniform1i(
                "atrous_iteration",
                () -> atrousIteration,
                atrousUpdater.newNotifier()
        );
    }

    public boolean isBlockLightEnabled() {
        return properties.isBlockLightEnabled();
    }

    public boolean isRestirGiEnabled() {
        return properties.isGiEnabled() && properties.useRestirCombinedGi();
    }

    public boolean isRestirEnabled() {
        return isBlockLightEnabled() || isRestirGiEnabled();
    }

    public boolean isSpatialReuseEnabled() {
        return isBlockLightEnabled() && properties.getRestirSpatialReuseSamples() > 0;
    }

    public boolean isHandheldLightingEnabled() {
        return properties.isHandheldLightEnabled();
    }

    public boolean isExactLocalLightingEnabled() {
        return isBlockLightEnabled() && !properties.useRestirSoftShadows();
    }

    public boolean isDenoisingEnabled() {
        return isRestirEnabled() && denoiserPasses > 0;
    }

    private String spatialReusePass(String file) {
        return "/photonics/rendering/restir/passes/spatial_reuse/" + file;
    }
}
