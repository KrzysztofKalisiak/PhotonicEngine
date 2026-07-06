package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlEnums;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class GlTexture3D extends AbstractGlTexture<Vector3ic> implements IGpuTexture3D {
    private final Vector3i size = new Vector3i();

    public GlTexture3D(String label, @TextureUsage int usage, ITextureFormat format, int width, int height, int depth, int mipLevels) {
        super(label, usage, format, mipLevels);
        ph$resize(new Vector3i(width, height, depth));
    }

    @Override
    public Vector3ic ph$size(int mipLevel) {
        return new Vector3i(Math.max(1, size.x() >> mipLevel), Math.max(1, size.y() >> mipLevel), Math.max(1, size.z() >> mipLevel));
    }

    @Override
    public void ph$resize(Vector3ic newSize) {
        size.set(newSize);
        var glFormat = GlEnums.textureFormat(format);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, handle);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, glFormat.internalFormat(), size.x(), size.y(), size.z(), 0, glFormat.format(), glFormat.type(), 0L);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }
}
