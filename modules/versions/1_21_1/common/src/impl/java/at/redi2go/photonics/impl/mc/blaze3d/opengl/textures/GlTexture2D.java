package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlEnums;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

public class GlTexture2D extends AbstractGlTexture<Vector2ic> implements IGpuTexture2D {
    private final Vector2i size = new Vector2i();

    public GlTexture2D(String label, @TextureUsage int usage, ITextureFormat format, int width, int height, int mipLevels) {
        super(label, usage, format, mipLevels);
        ph$resize(new Vector2i(width, height));
    }

    @Override
    public Vector2ic ph$size(int mipLevel) {
        return new Vector2i(Math.max(1, size.x() >> mipLevel), Math.max(1, size.y() >> mipLevel));
    }

    @Override
    public void ph$resize(Vector2ic newSize) {
        size.set(newSize);
        var glFormat = GlEnums.textureFormat(format);
        int filter = format == ITextureFormat.Values.RGB32UI
                ? GL11.GL_NEAREST
                : GL11.GL_LINEAR;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, handle);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, glFormat.internalFormat(), size.x(), size.y(), 0, glFormat.format(), glFormat.type(), 0L);
        GL30.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
}
