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

        Photonics.LOGGER.info("Photonics feature set: direct-light-v30 generation-aligned adaptive stratified ReSTIR proposals, model-offset-aligned thin cutouts, exposed-face Sable local visibility, geometry-generation history invalidation, zero-lag moving Sable accumulation, bounds-stable Sable receiver motion, strict temporal history validation, SVGF quality floor, moving-light and Iris material bridges; finite-segment OOB visibility and tree-origin tracing, masked passes, texture barriers, temporal reuse, accumulation, denoising, handheld; spatial and combined GI compatibility gates active");

        // The hand needs at least seven denoiser passes to avoid residual noise.
        int requestedDenoiserPasses = properties.getRestirDenoiserPasses();
        this.denoiserPasses = requestedDenoiserPasses != 0 ? Math.max(requestedDenoiserPasses, 7) : 0;

        Photonics.LOGGER.info(
                "Photonics ReSTIR configuration v21: directCandidatesPerPixel={}, directTemporalSampleCap={}, spatialCandidates={}, requestedDenoiserPasses={}, effectiveDenoiserPasses={}, softShadows={}, combinedGi={}",
                properties.getRestirInitialSamples(),
                20 * properties.getRestirInitialSamples(),
                properties.getRestirSpatialReuseSamples(),
                requestedDenoiserPasses,
                denoiserPasses,
                properties.useRestirSoftShadows(),
                properties.useRestirCombinedGi()
        );

        var restirFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_lighting_variance", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_direct_reservoirs0", ITextureFormat.rgb32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isBlockLightEnabled)
                .addAttachment("restir_indirect_reservoirs0", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .addAttachment("restir_indirect_reservoirs1", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .addAttachment("restir_indirect_reservoirs2", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .build(this::registerComponent);

        var directReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_direct_reservoirs0"
        );
        var indirectReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_indirect_reservoirs0",
                "restir_indirect_reservoirs1",
                "restir_indirect_reservoirs2"
        );
        var reusedReservoirFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_direct_reservoirs0",
                "restir_indirect_reservoirs0",
                "restir_indirect_reservoirs1",
                "restir_indirect_reservoirs2"
        );
        var diffuseFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_lighting",
                "restir_direct_reservoirs0",
                "restir_indirect_reservoirs0",
                "restir_indirect_reservoirs1",
                "restir_indirect_reservoirs2"
        );
        var accumulationFramebuffer = restirFramebuffer.withDrawBuffers(
                "restir_lighting",
                "restir_lighting_variance"
        );

        var denoiseFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("denoise_result", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isDenoisingEnabled)
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
                .deferredPass("spatial reuse", "/photonics/rendering/restir/passes/r5_spatial_reuse.fsh", null, this::isSpatialReuseEnabled)
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
        return isRestirEnabled() && properties.getRestirSpatialReuseSamples() > 0;
    }

    public boolean isHandheldLightingEnabled() {
        return properties.isHandheldLightEnabled();
    }

    public boolean isDenoisingEnabled() {
        return isRestirEnabled() && denoiserPasses > 0;
    }

    private String spatialReusePass(String file) {
        return "/photonics/rendering/restir/passes/spatial_reuse/" + file;
    }
}
