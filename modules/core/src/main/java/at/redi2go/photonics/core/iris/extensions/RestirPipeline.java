package at.redi2go.photonics.core.iris.extensions;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.AbstractPhotonicsExtension;
import at.redi2go.photonics.core.iris.Pipelines;
import at.redi2go.photonics.core.iris.RestirDiagnostics;
import at.redi2go.photonics.core.iris.TemporalUpscalerDiagnostics;
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
    private final int giDenoiserPasses;

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
                "Photonics GI foundation v99: finite full-position indirect reservoirs, corrected primary-ray origin/origin rebasing/explicit sky hits, Jacobian-weighted world-hit temporal reuse for world and Sable receivers, geometric-normal-consistent indirect energy, explicit empty non-world reservoirs, hand temporal isolation, and selected-reservoir visibility validation"
        );
        Photonics.LOGGER.info(
                "Photonics ReSTIR GI v79: combinedGi={}, splitGi={}, directScale={}, giScale={}, directDenoiserPasses={}, giDenoiserPasses={}, giPipeline=independent-frag-grid+reservoirs+history+svgf, composition=post-estimator-screen-space",
                properties.useRestirCombinedGi(),
                isSplitGiEnabled(),
                properties.getRenderScale(),
                properties.getGiRenderScale(),
                properties.getRestirDenoiserPasses(),
                properties.getRestirGiDenoiserPasses()
        );
        Photonics.LOGGER.info(
                "Photonics split-GI reconstruction v81: receiver-aware four-tap interpolation with unsupported-pixel nearest-compatible fallback"
        );
        Photonics.LOGGER.info(
                "Photonics GI stability v82: world-hit temporal reservoir reuse enabled for rigidly reprojected Sable receivers; hand history remains isolated"
        );
        Photonics.LOGGER.info(
                "Photonics direct startup v100: unbiased logarithmic camera-rank strata for large light lists with exact compact-list prefix proposals"
        );
        if (RestirDiagnostics.isSourceHistoryEnabled()) {
            if (isSourceHistoryDiagnosticEnabled()) {
                Photonics.LOGGER.warn(
                        "Photonics ReSTIR source/history diagnostic v103 enabled via -D{}=true; panels=top-left-current-direct/top-right-accumulated-direct/bottom-left-denoised-direct/bottom-right-final-gi, handheld-and-exact-local-lighting=omitted, auxiliary-lighting-components=not-registered",
                        RestirDiagnostics.SOURCE_HISTORY_PROPERTY
                );
            } else {
                Photonics.LOGGER.warn(
                        "Photonics ReSTIR source/history diagnostic v103 requested via -D{}=true but requires split GI and block lighting; diagnostic disabled for this pipeline",
                        RestirDiagnostics.SOURCE_HISTORY_PROPERTY
                );
            }
        }
        Photonics.LOGGER.info(
                "Photonics ReSTIR GI transport v85+: stochastic tinted-glass traversal and endpoint-first transparent-hit validation"
        );
        Photonics.LOGGER.info(
                "Photonics stability v86: deterministic thin-cutout GI coverage and bounded palette-heap compiler recovery probes"
        );
        Photonics.LOGGER.info(
                "Photonics ReSTIR GI validation v88: triState=valid/blocked-current-receiver/stale, blockedHistoryM=preserved-once-with-zero-energy, staleHistoryM=excluded, maxTemporalCandidates=1, maxSpatialCandidates=1, maxTraversalSteps=128"
        );
        Photonics.LOGGER.info("Photonics feature set: direct-light-v64 expiring Contraption Lights proxy ownership with a conservative nearby unmatched-proxy quarantine, position-matched Sable light alias trail with registry/profile diagnostics and render-thread-owned external merge, full-precision motion-grid Sable receiver visibility and endpoint-safe conservative traversal, dual-space Sable light de-duplication, production composite restored after v57 reservoir diagnostics, corrected indirect hit-normal decoding, full temporal reservoir retention for Sable external lighting, randomized systematic world-light proposals, Sable skylight-transition diagnostics with opt-in getter freeze, post-denoise exact Sable-local direct stream and fail-closed same-domain visibility, camera-relative double-composed Sable reprojection, normal-guided receiver classification, tri-state same-sublevel visibility and conservative supercover voxel traversal, sampling-time receiver-domain partition with exact same-sublevel direct lighting, external-only Sable reservoirs and soft-shadow local-visibility-signature-guided Sable SVGF support, preserved all-zero current proposal batches, elapsed-time moving-light hysteresis and unsnapped render-pose positions, rejected spatial-batch accounting, receiver-domain-complete stable/external history partition and rigid-motion Sable spatial reuse, bounded visibility-rejected reservoirs and representative-scoped external reactivity, split stable/external accumulation histories and stale Sable material recovery, explicit emitter motion-domain identity, motion-domain-stable receiver-relative light-history limits, previous-light position metadata, rotation-correct cross-sublevel motion, guarded ordinary-world all-light and external-only Sable single-neighbor spatial reuse, immutable spatial input, current-receiver visibility validation, bounded spatial history, independent spatial random stream, bounded reservoirs, zero-contribution batch accounting, actual-motion reactive lights, stable-anchor Sable reprojection, surface-plane SVGF, visibility-transition provenance, stable Sable identity, current-visible temporal reuse, Sable plot-section isolation, deterministic diagonal cutouts, generation-aligned adaptive stratified ReSTIR proposals, exposed-face Sable local visibility, duplicate Veil point-light suppression, moving-light and Iris material bridges; finite-segment OOB visibility and tree-origin tracing, masked passes, texture barriers, accumulation, denoising, handheld; Sable-receiver world/cross-sublevel spatial reuse and combined GI compatibility gates active");

        int requestedDenoiserPasses = properties.getRestirDenoiserPasses();
        this.denoiserPasses = requestedDenoiserPasses;
        this.giDenoiserPasses = properties.getRestirGiDenoiserPasses();

        Photonics.LOGGER.info(
                "Photonics ReSTIR configuration v100: directCandidatesPerPixel={}, directTemporalSampleCap={}, directOutputSampleCap=world-128/sable-temporal-cap, spatialCandidates={}, spatialRadiusPixels={}, output=split-direct-and-gi-radiance-plus-exact-sable-local, samplingPolicy=large-list-logarithmic-rank-strata+compact-list-systematic-prefix-tail+distinct-priority-prefix/sable-external-reservoir/exact-all-same-token-lights/fail-closed-tri-state-conservative-local-dda/full-precision-motion-grid-receiver/normal-biased-endpoint/preserved-zero-current-batches, spatialPolicy=receiver-matched-current-frame/external-only-sable/current-rejection-accounting/immutable-input/initial-batch-cap+rejected-fallback-cap+background-finalization, historyPolicy=receiver-domain-complete-split-stable-external/direct-only-full-rigid-local-history/camera-relative-double-compose/normal-guided-receiver/soft-local-signature-reset/visibility-transition-reset/representative-scoped-external-reactivity/explicit-emitter-domain/0.15-block-trail/2-frame-floor, lightListPolicy=position-matched-250ms-alias-trail/250ms-after-loss-minecraft-light-proxy-ownership/125ms+2-frame-unmatched-proxy-quarantine/3-cell-alias-radius/render-thread-owned-merge, motionHoldMs=250, denoiserPolicy=post-denoise-exact-local-hard-shadow+representative-gated-world+soft-local-signature-sable, sableSkyLightDiagnostic=transition-log/optional-freeze-getter, directDenoiserPasses={}, giDenoiserPasses={}, softShadows={}, combinedGi={}, splitGi={}",
                properties.getRestirInitialSamples(),
                20 * properties.getRestirInitialSamples(),
                properties.getRestirSpatialReuseSamples(),
                properties.getRestirSpatialReuseRadius(),
                denoiserPasses,
                giDenoiserPasses,
                properties.useRestirSoftShadows(),
                properties.useRestirCombinedGi(),
                isSplitGiEnabled()
        );

        Pipelines.fragData(this, irisFactory, properties.getRenderScale());
        if (isSplitGiEnabled()) {
            Pipelines.giFragData(this, irisFactory, properties.getGiRenderScale());
            buildSplitDirectPipeline(irisFactory);
            buildSplitGiPipeline(irisFactory);
        } else {
            buildCombinedPipeline(irisFactory);
        }
        buildAuxiliaryLightingPipeline(irisFactory);
        buildTemporalUpscalerPipeline(irisFactory);
    }

    private void buildCombinedPipeline(IrisFactory irisFactory) {
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

        var spatialInputFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_direct_spatial_input", ITextureFormat.rgb32f(), CREATE_SAMPLER, this::isDirectSpatialReuseEnabled)
                .addAttachment("restir_indirect_spatial_input0", ITextureFormat.rgba32f(), CREATE_SAMPLER, this::isIndirectSpatialReuseEnabled)
                .addAttachment("restir_indirect_spatial_input1", ITextureFormat.rgb32ui(), CREATE_SAMPLER, this::isIndirectSpatialReuseEnabled)
                .build(this::registerComponent);

        irisFactory.newPipeline()
                .thenFlip(restirFramebuffer)
                .debugGroup("restir direct")
                .withFramebuffer(directReservoirFramebuffer)
                .deferredPass("initial direct + visibility", "/photonics/rendering/restir/passes/r1_initial_direct.fsh", null, this::isBlockLightEnabled)
                .debugGroup("restir gi initial")
                .withFramebuffer(indirectReservoirFramebuffer)
                .deferredPass("initial indirect", "/photonics/rendering/restir/passes/r3_initial_indirect.fsh", null, this::isRestirGiEnabled)
                .debugGroup("restir temporal")
                .withFramebuffer(reusedReservoirFramebuffer)
                .deferredPass("temporal reuse + tri-state GI classification", "/photonics/rendering/restir/passes/r4_temporal_reuse.fsh", null, this::isRestirEnabled)
                .debugGroup("restir spatial copy")
                .withFramebuffer(spatialInputFramebuffer)
                .deferredPass("copy spatial input", "/photonics/rendering/restir/passes/r5_copy_spatial_input.fsh", null, this::isSpatialReuseEnabled)
                .debugGroup("restir spatial")
                .withFramebuffer(reusedReservoirFramebuffer)
                // The spatial pass also caps temporal reservoirs, so it must run
                // when the configured spatial candidate count is zero.
                .deferredPass("spatial reuse/clamp + tri-state GI classification", "/photonics/rendering/restir/passes/r5_spatial_reuse.fsh", null, this::isRestirEnabled)
                .debugGroup("restir diffuse")
                .withFramebuffer(diffuseFramebuffer)
                .deferredPass("diffuse", "/photonics/rendering/restir/passes/r6_diffuse.fsh", null, this::isRestirEnabled)
                .debugGroup("restir accumulation")
                .withFramebuffer(accumulationFramebuffer)
                .deferredPass("accumulation", "/photonics/rendering/restir/passes/r7_accumulation.fsh", null, this::isRestirEnabled)
                .when(this::isDenoisingEnabled, b0 -> {
                    b0.withFramebuffer(denoiseFramebuffer);
                    b0.debugGroup("svgf variance");
                    b0.thenRun(() -> atrousIteration = denoiserPasses);
                    b0.deferredPass("variance prefilter", "/photonics/rendering/restir/passes/r8_variance_prefilter.fsh", null);
                    b0.debugGroup("svgf atrous");
                    b0.repeat(denoiserPasses, b1 -> {
                        b1.thenRun(() -> atrousIteration--);
                        b1.thenRun(atrousUpdater::updateNow);
                        b1.thenFlip(denoiseFramebuffer);
                        b1.deferredPass("atrous iteration", "/photonics/rendering/restir/passes/r9_denoising.fsh", null);
                    });
                })
                .build(this::registerRenderer);
    }

    private void buildSplitDirectPipeline(IrisFactory irisFactory) {
        if (!isBlockLightEnabled()) return;

        var restirFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_lighting_variance", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_direct_reservoirs0", ITextureFormat.rgb32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_direct_state", ITextureFormat.rg32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_external_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment(
                        "restir_source_lighting",
                        ITextureFormat.rgb16f(),
                        CREATE_SAMPLER,
                        this::isSourceHistoryDiagnosticEnabled
                )
                .build(this::registerComponent);

        var directReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_direct_reservoirs0"
        );
        var diffuseFramebuffer = isSourceHistoryDiagnosticEnabled()
                ? restirFramebuffer.withDrawBuffers(
                        "restir_lighting",
                        "restir_direct_reservoirs0",
                        "restir_direct_state",
                        "restir_external_lighting",
                        "restir_source_lighting"
                )
                : restirFramebuffer.withDrawBuffers(
                        "restir_lighting",
                        "restir_direct_reservoirs0",
                        "restir_direct_state",
                        "restir_external_lighting"
                );
        var accumulationFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_lighting",
                "restir_lighting_variance",
                "restir_external_lighting"
        );
        var denoiseFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("denoise_result", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isDirectDenoisingEnabled)
                .build(this::registerComponent);
        var spatialInputFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_direct_spatial_input", ITextureFormat.rgb32f(), CREATE_SAMPLER, this::isDirectSpatialReuseEnabled)
                .build(this::registerComponent);

        irisFactory.newPipeline()
                .thenFlip(restirFramebuffer)
                .debugGroup("restir direct")
                .withFramebuffer(directReservoirFramebuffer)
                .deferredPass(
                        "initial direct + visibility",
                        "/photonics/rendering/restir/passes/r1_initial_direct.fsh",
                        null
                )
                .debugGroup("restir direct temporal")
                .withFramebuffer(directReservoirFramebuffer)
                .deferredPass(
                        "temporal reuse",
                        "/photonics/rendering/restir/passes/r4_temporal_reuse.fsh",
                        null
                )
                .when(this::isDirectSpatialReuseEnabled, b -> b
                        .debugGroup("restir direct spatial copy")
                        .withFramebuffer(spatialInputFramebuffer)
                        .deferredPass(
                                "copy spatial input",
                                "/photonics/rendering/restir/passes/r5_copy_spatial_input.fsh",
                                null
                        ))
                .debugGroup("restir direct spatial")
                .withFramebuffer(directReservoirFramebuffer)
                .deferredPass(
                        "spatial reuse/clamp",
                        "/photonics/rendering/restir/passes/r5_spatial_reuse.fsh",
                        null
                )
                .debugGroup("restir direct diffuse")
                .withFramebuffer(diffuseFramebuffer)
                .deferredPass(
                        "diffuse",
                        "/photonics/rendering/restir/passes/r6_diffuse.fsh",
                        null
                )
                .debugGroup("restir direct accumulation")
                .withFramebuffer(accumulationFramebuffer)
                .deferredPass(
                        "accumulation",
                        "/photonics/rendering/restir/passes/r7_accumulation.fsh",
                        null
                )
                .when(this::isDirectDenoisingEnabled, b0 -> {
                    b0.withFramebuffer(denoiseFramebuffer);
                    b0.debugGroup("svgf direct variance");
                    b0.thenRun(() -> atrousIteration = denoiserPasses);
                    b0.deferredPass(
                            "variance prefilter",
                            "/photonics/rendering/restir/passes/r8_variance_prefilter.fsh",
                            null
                    );
                    b0.debugGroup("svgf direct atrous");
                    b0.repeat(denoiserPasses, b1 -> {
                        b1.thenRun(() -> atrousIteration--);
                        b1.thenRun(atrousUpdater::updateNow);
                        b1.thenFlip(denoiseFramebuffer);
                        b1.deferredPass(
                                "atrous iteration",
                                "/photonics/rendering/restir/passes/r9_denoising.fsh",
                                null
                        );
                    });
                })
                .build(this::registerRenderer);
    }

    private void buildSplitGiPipeline(IrisFactory irisFactory) {
        if (!isRestirGiEnabled()) return;

        var giFramebuffer = irisFactory.newFramebuffer(properties.getGiRenderScale())
                .addAttachment("restir_gi_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_gi_lighting_variance", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_gi_indirect_reservoirs0", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment("restir_gi_indirect_reservoirs1", ITextureFormat.rgb32ui(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .build(this::registerComponent);
        var giReservoirFramebuffer = giFramebuffer.withDrawBuffers(
                "restir_gi_indirect_reservoirs0",
                "restir_gi_indirect_reservoirs1"
        );
        var giDiffuseFramebuffer = giFramebuffer.withDrawBuffers(
                "restir_gi_lighting",
                "restir_gi_indirect_reservoirs0",
                "restir_gi_indirect_reservoirs1"
        );
        var giAccumulationFramebuffer = giFramebuffer.withDrawBuffers(
                "restir_gi_lighting",
                "restir_gi_lighting_variance"
        );
        var giDenoiseFramebuffer = irisFactory.newFramebuffer(properties.getGiRenderScale())
                .addAttachment("restir_gi_denoise_result", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isGiDenoisingEnabled)
                .build(this::registerComponent);
        var giSpatialInputFramebuffer = irisFactory.newFramebuffer(properties.getGiRenderScale())
                .addAttachment("restir_gi_indirect_spatial_input0", ITextureFormat.rgba32f(), CREATE_SAMPLER, this::isIndirectSpatialReuseEnabled)
                .addAttachment("restir_gi_indirect_spatial_input1", ITextureFormat.rgb32ui(), CREATE_SAMPLER, this::isIndirectSpatialReuseEnabled)
                .build(this::registerComponent);

        irisFactory.newPipeline()
                .thenFlip(giFramebuffer)
                .debugGroup("restir gi initial")
                .withFramebuffer(giReservoirFramebuffer)
                .deferredPass(
                        "initial indirect",
                        "/photonics/rendering/restir/passes/r3_initial_indirect.fsh",
                        null
                )
                .debugGroup("restir gi temporal")
                .withFramebuffer(giReservoirFramebuffer)
                .deferredPass(
                        "temporal reuse + tri-state classification",
                        "/photonics/rendering/restir/passes/r4_temporal_reuse_gi.fsh",
                        null
                )
                .when(this::isIndirectSpatialReuseEnabled, b -> b
                        .debugGroup("restir gi spatial copy")
                        .withFramebuffer(giSpatialInputFramebuffer)
                        .deferredPass(
                                "copy spatial input",
                                "/photonics/rendering/restir/passes/r5_copy_spatial_input_gi.fsh",
                                null
                        ))
                .debugGroup("restir gi spatial")
                .withFramebuffer(giReservoirFramebuffer)
                .deferredPass(
                        "spatial reuse/clamp + tri-state classification",
                        "/photonics/rendering/restir/passes/r5_spatial_reuse_gi.fsh",
                        null
                )
                .debugGroup("restir gi diffuse")
                .withFramebuffer(giDiffuseFramebuffer)
                .deferredPass(
                        "diffuse",
                        "/photonics/rendering/restir/passes/r6_diffuse_gi.fsh",
                        null
                )
                .debugGroup("restir gi accumulation")
                .withFramebuffer(giAccumulationFramebuffer)
                .deferredPass(
                        "accumulation",
                        "/photonics/rendering/restir/passes/r7_accumulation_gi.fsh",
                        null
                )
                .when(this::isGiDenoisingEnabled, b0 -> {
                    b0.withFramebuffer(giDenoiseFramebuffer);
                    b0.debugGroup("svgf gi variance");
                    b0.thenRun(() -> atrousIteration = giDenoiserPasses);
                    b0.deferredPass(
                            "variance prefilter",
                            "/photonics/rendering/restir/passes/r8_variance_prefilter_gi.fsh",
                            null
                    );
                    b0.debugGroup("svgf gi atrous");
                    b0.repeat(giDenoiserPasses, b1 -> {
                        b1.thenRun(() -> atrousIteration--);
                        b1.thenRun(atrousUpdater::updateNow);
                        b1.thenFlip(giDenoiseFramebuffer);
                        b1.deferredPass(
                                "atrous iteration",
                                "/photonics/rendering/restir/passes/r9_denoising_gi.fsh",
                                null
                        );
                    });
                })
                .build(this::registerRenderer);
    }

    private void buildAuxiliaryLightingPipeline(IrisFactory irisFactory) {
        if (isSourceHistoryDiagnosticEnabled()) return;

        var localLightingFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_local_lighting", ITextureFormat.rgb16f(), CREATE_SAMPLER, this::isBlockLightEnabled)
                .build(this::registerComponent);
        var otherFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("other_handheld", ITextureFormat.rgb16f(), CREATE_SAMPLER, this::isHandheldLightingEnabled)
                .build(this::registerComponent);

        irisFactory.newPipeline()
                .debugGroup("restir local")
                .withFramebuffer(localLightingFramebuffer)
                .deferredPass("exact local direct", "/photonics/rendering/restir/passes/r10_local_direct.fsh", null, this::isExactLocalLightingEnabled)
                .debugGroup("restir handheld")
                .withFramebuffer(otherFramebuffer)
                .deferredPass("handheld", "/photonics/rendering/restir/passes/r10_handheld.fsh", null, this::isHandheldLightingEnabled)
                .build(this::registerRenderer);
    }

    private void buildTemporalUpscalerPipeline(IrisFactory irisFactory) {
        if (!properties.isTemporalUpscalerActive()) {
            if (properties.useTemporalUpscaler()) {
                Photonics.LOGGER.info(
                        "Photonics temporal upscaler bypassed: mode={}, reconstructableRestirLighting={}, configuredSourceScale={}, effectiveSourceScale={}, outputScale={}",
                        properties.getLightingMode(),
                        isRestirEnabled(),
                        properties.getTemporalUpscalerSourceScale(),
                        properties.getRenderScale(),
                        properties.getShaderPackRenderScale()
                );
            }
            return;
        }

        var sourceFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment(
                        "photonics_temporal_source",
                        ITextureFormat.rgba16f(),
                        CREATE_SAMPLER
                )
                .build(this::registerComponent);
        var historyFramebuffer = irisFactory.newFramebuffer(properties.getShaderPackRenderScale())
                .addAttachment(
                        "photonics_temporal_lighting",
                        ITextureFormat.rgba16f(),
                        FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER
                )
                .addAttachment(
                        "photonics_temporal_surface",
                        ITextureFormat.rgba16f(),
                        FLIP | CREATE_PREV_SAMPLER
                )
                .addAttachment(
                        "photonics_temporal_diagnostic",
                        ITextureFormat.rgba16f(),
                        FLIP | CREATE_SAMPLER,
                        TemporalUpscalerDiagnostics::isSplitScreenEnabled
                )
                .build(this::registerComponent);

        if (TemporalUpscalerDiagnostics.isSplitScreenEnabled()) {
            Photonics.LOGGER.warn(
                    "Photonics temporal-upscaler split-screen diagnostic enabled via -D{}=true; allocating one double-buffered full-resolution RGBA16F attachment",
                    TemporalUpscalerDiagnostics.SPLIT_SCREEN_PROPERTY
            );
        }

        Photonics.LOGGER.info(
            "Photonics temporal upscaler: enabled=true, configuredSourceScale={}, effectiveSourceScale={}, giScale={}, outputScale={}, historyFrames={}, currentTaps=4+fallback, historyTaps=4, historyBytesPerOutputPixel=32, historyWorldRevisionPolicy=local-reactive, sparseSupportPolicy=history-stable, composition=private-lighting-texture",
                properties.getTemporalUpscalerSourceScale(),
                properties.getRenderScale(),
                properties.getGiRenderScale(),
                properties.getShaderPackRenderScale(),
                properties.getTemporalUpscalerHistoryFrames()
        );

        irisFactory.newPipeline()
                .debugGroup("photonics temporal source")
                .withFramebuffer(sourceFramebuffer)
                .deferredPass(
                        "compose low-resolution lighting",
                        "/photonics/rendering/temporal_upscaler/source.fsh",
                        null
                )
                .debugGroup("photonics temporal reconstruction")
                .thenFlip(historyFramebuffer)
                .withFramebuffer(historyFramebuffer)
                .deferredPass(
                        "reconstruct lighting",
                        "/photonics/rendering/temporal_upscaler/reconstruct.fsh",
                        null
                )
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

    public boolean isSplitGiEnabled() {
        return isRestirGiEnabled()
                && properties.getGiRenderScale() < properties.getRenderScale() - 0.0001f;
    }

    public boolean isSourceHistoryDiagnosticEnabled() {
        return RestirDiagnostics.isSourceHistoryEnabled()
                && isSplitGiEnabled()
                && isBlockLightEnabled();
    }

    public boolean isRestirEnabled() {
        return isBlockLightEnabled() || isRestirGiEnabled();
    }

    public boolean isSpatialReuseEnabled() {
        return properties.getRestirSpatialReuseSamples() > 0
                && (isBlockLightEnabled() || isRestirGiEnabled());
    }

    public boolean isDirectSpatialReuseEnabled() {
        return isBlockLightEnabled() && properties.getRestirSpatialReuseSamples() > 0;
    }

    public boolean isIndirectSpatialReuseEnabled() {
        return isRestirGiEnabled() && properties.getRestirSpatialReuseSamples() > 0;
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

    public boolean isDirectDenoisingEnabled() {
        return isBlockLightEnabled() && denoiserPasses > 0;
    }

    public boolean isGiDenoisingEnabled() {
        return isRestirGiEnabled() && giDenoiserPasses > 0;
    }

    private String spatialReusePass(String file) {
        return "/photonics/rendering/restir/passes/spatial_reuse/" + file;
    }
}
