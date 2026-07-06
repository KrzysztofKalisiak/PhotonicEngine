package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import at.redi2go.photonics.api.gpu.textures.IGpuSampler;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlEnums;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL33;

import java.util.OptionalDouble;

public class GlSampler implements IGpuSampler {
    private final int id = GL33.glGenSamplers();
    private final IAddressMode addressModeU;
    private final IAddressMode addressModeV;
    private final IFilterMode minFilter;
    private final IFilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;

    public GlSampler(IAddressMode addressModeU, IAddressMode addressModeV, IFilterMode minFilter, IFilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;

        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_WRAP_S, GlEnums.addressMode(addressModeU));
        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_WRAP_T, GlEnums.addressMode(addressModeV));
        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_MIN_FILTER, GlEnums.filterMode(minFilter));
        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_MAG_FILTER, GlEnums.filterMode(magFilter));
        maxLod.ifPresent(value -> GL33.glSamplerParameterf(id, GL12.GL_TEXTURE_MAX_LOD, (float) value));
    }

    public int id() {
        return id;
    }

    @Override
    public IAddressMode ph$addressModeU() {
        return addressModeU;
    }

    @Override
    public IAddressMode ph$addressModeV() {
        return addressModeV;
    }

    @Override
    public IFilterMode ph$minFilter() {
        return minFilter;
    }

    @Override
    public IFilterMode ph$magFilter() {
        return magFilter;
    }

    @Override
    public int ph$maxAnisotropy() {
        return maxAnisotropy;
    }

    @Override
    public OptionalDouble ph$maxLod() {
        return maxLod;
    }

    @Override
    public void close() {
        GL33.glDeleteSamplers(id);
    }
}
