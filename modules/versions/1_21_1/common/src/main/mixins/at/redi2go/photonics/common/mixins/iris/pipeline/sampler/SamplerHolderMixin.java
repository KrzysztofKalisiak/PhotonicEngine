package at.redi2go.photonics.common.mixins.iris.pipeline.sampler;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.core.iris.pipeline.texture.ISamplerHolder;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Supplier;
import java.util.function.IntSupplier;

@Mixin(SamplerHolder.class)
public interface SamplerHolderMixin extends SamplerHolder, ISamplerHolder {
    @Override
    default void addSampler(
            String name,
            Supplier<IGpuTexture.WithSampler<?>> textureAndSampler
    ) {
        var value = textureAndSampler.get();
        addDynamicSampler(
                IrisUtil.getTextureType(value.texture()),
                () -> IrisUtil.getTextureHandle(value.texture()),
                IrisUtil.getGlSampler(value.sampler()),
                name
        );
    }

    @Override
    default void addDefaultSampler(String name, Supplier<IGpuTexture<?>> texture) {
        addDynamicSampler(
                IrisUtil.getTextureType(texture.get()),
                () -> IrisUtil.getTextureHandle(texture.get()),
                null,
                name
        );
    }

    @Override
    default void addExternalSampler3D(String name, IntSupplier textureHandle) {
        addDynamicSampler(
                net.irisshaders.iris.gl.texture.TextureType.TEXTURE_3D,
                textureHandle,
                null,
                name
        );
    }
}
