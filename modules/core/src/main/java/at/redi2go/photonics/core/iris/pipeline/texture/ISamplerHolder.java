package at.redi2go.photonics.core.iris.pipeline.texture;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture;

import java.util.function.Supplier;
import java.util.function.IntSupplier;

public interface ISamplerHolder {
    void addSampler(String name, Supplier<IGpuTexture.WithSampler<?>> textureAndSampler);

    void addDefaultSampler(String name, Supplier<IGpuTexture<?>> texture);

    void addExternalSampler3D(String name, IntSupplier textureHandle);
}
