package at.redi2go.photonics.impl.mc.blaze3d.opengl;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.heap.DefaultGpuBufferHeap;
import at.redi2go.photonics.api.gpu.buffers.heap.IGpuBufferHeap;
import at.redi2go.photonics.api.gpu.systems.ICommandEncoder;
import at.redi2go.photonics.api.gpu.systems.IGpuDevice;
import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import at.redi2go.photonics.api.gpu.textures.IGpuSampler;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlBuffer;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.systems.GlCommandEncoder;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.GlSampler;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.GlTexture2D;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.GlTexture3D;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.function.Supplier;

public enum GlGpuDevice implements IGpuDevice {
    INSTANCE;

    @Override
    public ICommandEncoder ph$createCommandEncoder() {
        return GlCommandEncoder.INSTANCE;
    }

    @Override
    public IGpuSampler ph$createSampler(
            IAddressMode addressModeU,
            IAddressMode addressModeV,
            IFilterMode minFilter,
            IFilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
        return new GlSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public IGpuTexture2D ph$createTexture2D(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            int width,
            int height,
            int mipLevels
    ) {
        return new GlTexture2D(label == null ? null : label.get(), usage, textureFormat, width, height, mipLevels);
    }

    @Override
    public IGpuTexture3D ph$createTexture3D(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            int width,
            int height,
            int depth,
            int mipLevels
    ) {
        return new GlTexture3D(label == null ? null : label.get(), usage, textureFormat, width, height, depth, mipLevels);
    }

    @Override
    public IGpuBuffer ph$createBuffer(@Nullable Supplier<String> label, long byteSize, @BufferUsage int usage) {
        return new GlBuffer(byteSize, usage);
    }

    @Override
    public IGpuBufferHeap ph$createBufferHeap(@Nullable Supplier<String> label, long byteSize, @BufferUsage int usage) {
        return new DefaultGpuBufferHeap(this, label, byteSize, usage);
    }
}
