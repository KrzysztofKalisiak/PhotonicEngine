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
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasDownloader;

import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_PREV_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.FLIP;

public class RestirPipeline extends AbstractPhotonicsExtension {
    private static final boolean FRAMEBUFFER_ONLY_DIAGNOSTIC = true;

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

        if (FRAMEBUFFER_ONLY_DIAGNOSTIC)
            Photonics.LOGGER.info("Photonics diagnostic: ReSTIR framebuffers/samplers only; render passes disabled");

        int requestedDenoiserPasses = properties.getRestirDenoiserPasses();
        this.denoiserPasses = Math.max(requestedDenoiserPasses, 0);

        var restirFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("restir_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::shouldCreateRestirLighting)
                .addAttachment("restir_lighting_variance", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::shouldCreateRestirLighting)
                .addAttachment("restir_direct_reservoirs0", ITextureFormat.rgb16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::shouldCreateDirectReservoir)
                .addAttachment("restir_indirect_reservoirs0", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::shouldCreateIndirectReservoir)
                .addAttachment("restir_indirect_reservoirs1", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::shouldCreateIndirectReservoir)
                .addAttachment("restir_indirect_reservoirs2", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::shouldCreateIndirectReservoir)
                .build(this::registerComponent);

        var denoiseFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("denoise_result", ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isDenoisingEnabled)
                .build(this::registerComponent);

        var otherFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("other_handheld", ITextureFormat.rgb16f(), CREATE_SAMPLER, this::shouldCreateHandheldLighting)
                .build(this::registerComponent);

        if (!FRAMEBUFFER_ONLY_DIAGNOSTIC)
            Pipelines.fragData(this, irisFactory, properties.getRenderScale());

        irisFactory.newPipeline()
                .debugGroup("restir")
                .withFramebuffer(restirFramebuffer)
                .thenFlip(restirFramebuffer)
                .deferredPass("initial direct", "/photonics/rendering/restir/passes/r1_initial_direct.fsh", null, this::isBlockLightEnabled)
                .deferredPass("validate initial direct", "/photonics/rendering/restir/passes/r2_validate_initial_direct.fsh", null, this::isReservoirValidationEnabled)
                .deferredPass("initial indirect", "/photonics/rendering/restir/passes/r3_initial_indirect.fsh", null, this::isRestirGiEnabled)
                .deferredPass("temporal reuse", "/photonics/rendering/restir/passes/r4_temporal_reuse.fsh", null, this::isTemporalReuseEnabled)
                .deferredPass("spatial reuse", "/photonics/rendering/restir/passes/r5_spatial_reuse.fsh", null, this::isSpatialReuseEnabled)
                .deferredPass("diffuse", "/photonics/rendering/restir/passes/r6_diffuse.fsh", null, this::isRestirEnabled)
                .deferredPass("accumulation", "/photonics/rendering/restir/passes/r7_accumulation.fsh", null, this::isAccumulationEnabled)
                .when(this::isDenoisingEnabled, b0 -> {
                    b0.withFramebuffer(denoiseFramebuffer);
                    b0.debugGroup("svgf");
                    b0.thenRun(() -> atrousIteration = 0);
                    b0.deferredPass("variance prefilter", "/photonics/rendering/restir/passes/r8_variance_prefilter.fsh", null);
                    b0.repeat(denoiserPasses, b1 -> {
                        b1.thenRun(() -> atrousIteration++);
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
        return !FRAMEBUFFER_ONLY_DIAGNOSTIC && properties.isBlockLightEnabled();
    }

    public boolean isRestirGiEnabled() {
        return !FRAMEBUFFER_ONLY_DIAGNOSTIC && properties.isGiEnabled() && properties.useRestirCombinedGi();
    }

    public boolean isRestirEnabled() {
        return isBlockLightEnabled() || isRestirGiEnabled();
    }

    public boolean isReservoirValidationEnabled() {
        return false;
    }

    public boolean isTemporalReuseEnabled() {
        return false;
    }

    public boolean isAccumulationEnabled() {
        return false;
    }

    public boolean isSpatialReuseEnabled() {
        return isRestirEnabled() && properties.getRestirSpatialReuseSamples() > 0;
    }

    public boolean isHandheldLightingEnabled() {
        return !FRAMEBUFFER_ONLY_DIAGNOSTIC && properties.isHandheldLightEnabled();
    }

    public boolean isDenoisingEnabled() {
        return isRestirEnabled() && denoiserPasses > 0;
    }

    private String spatialReusePass(String file) {
        return "/photonics/rendering/restir/passes/spatial_reuse/" + file;
    }

    private boolean shouldCreateRestirLighting() {
        return FRAMEBUFFER_ONLY_DIAGNOSTIC || isRestirEnabled();
    }

    private boolean shouldCreateDirectReservoir() {
        return FRAMEBUFFER_ONLY_DIAGNOSTIC || isBlockLightEnabled();
    }

    private boolean shouldCreateIndirectReservoir() {
        return FRAMEBUFFER_ONLY_DIAGNOSTIC || isRestirGiEnabled();
    }

    private boolean shouldCreateHandheldLighting() {
        return FRAMEBUFFER_ONLY_DIAGNOSTIC || isHandheldLightingEnabled();
    }
}
