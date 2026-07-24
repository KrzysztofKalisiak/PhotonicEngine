package at.redi2go.photonics.core.iris;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.core.iris.pipeline.rendering.IrisFactory;

import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_PREV_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.FLIP;

public class Pipelines {
    private Pipelines() {

    }

    public static void fragData(AbstractPhotonicsExtension ext, IrisFactory irisFactory, float renderScale) {
        fragData(
                ext,
                irisFactory,
                renderScale,
                "frag data",
                "ph_frag_data0",
                "ph_frag_data1",
                "ph_frag_motion",
                "/photonics/rendering/frag/f0_load_frag.fsh"
        );
    }

    public static void giFragData(AbstractPhotonicsExtension ext, IrisFactory irisFactory, float renderScale) {
        fragData(
                ext,
                irisFactory,
                renderScale,
                "frag data gi",
                "ph_gi_frag_data0",
                "ph_gi_frag_data1",
                "ph_gi_frag_motion",
                "/photonics/rendering/frag/f0_load_frag_gi.fsh"
        );
    }

    private static void fragData(
            AbstractPhotonicsExtension ext,
            IrisFactory irisFactory,
            float renderScale,
            String debugGroup,
            String data0Name,
            String data1Name,
            String motionName,
            String fragmentShader
    ) {
        var framebuffer = irisFactory.newFramebuffer(renderScale)
                .addAttachment(data0Name, ITextureFormat.rgba16f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment(data1Name, ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER)
                .addAttachment(motionName, ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER)
                .build(ext::registerComponent);

        irisFactory.newPipeline()
                .debugGroup(debugGroup)
                .withFramebuffer(framebuffer)
                .thenFlip(framebuffer)
                .deferredPass(
                        debugGroup,
                        fragmentShader,
                        null
                )
                .build(ext::registerRenderer);
    }
}
