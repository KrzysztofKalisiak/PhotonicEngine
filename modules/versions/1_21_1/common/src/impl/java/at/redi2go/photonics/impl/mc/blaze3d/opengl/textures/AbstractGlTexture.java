package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import org.lwjgl.opengl.GL11;

public abstract class AbstractGlTexture<D> implements IGlTexture {
    protected final int handle = GL11.glGenTextures();
    protected final String label;
    protected final int usage;
    protected final ITextureFormat format;
    protected final int mipLevels;
    protected boolean closed;

    protected AbstractGlTexture(String label, @TextureUsage int usage, ITextureFormat format, int mipLevels) {
        this.label = label == null ? "" : label;
        this.usage = usage;
        this.format = format;
        this.mipLevels = mipLevels;
    }

    @Override
    public int handle() {
        return handle;
    }

    public String ph$label() {
        return label;
    }

    public int ph$usage() {
        return usage;
    }

    public int ph$mipLevels() {
        return mipLevels;
    }

    public ITextureFormat ph$format() {
        return format;
    }

    public boolean ph$isClosed() {
        return closed;
    }

    public void close() {
        if (closed) return;

        closed = true;
        GL11.glDeleteTextures(handle);
    }
}
